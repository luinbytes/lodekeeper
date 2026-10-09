package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One owner for requested animal stock and urgent food, including pending native evidence. */
final class AnimalHarvestAction implements NativeRun {
    private static final double RANGE_SQUARED = 32.0 * 32.0;
    private static final long TARGET_NANOS = 30_000_000_000L;
    private static final Map<UUID, Long> FAILED_UNTIL = new LinkedHashMap<>();
    private static WeakReference<Object> failedWorld = new WeakReference<>(null);
    private enum Phase { IDLE, PREPARING_HAND, APPROACH, STOPPING, EFFECT, WAITING, PICKUP, DRAIN, DONE }
    private record Capability(AnimalHarvestSource source, ItemId cooked) { }
    private static final List<Capability> CAPABILITIES = capabilities();
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final MovementController movement;
    private final WorldProtection worldProtection;
    private final PlacementProvenance provenance;
    private Phase phase = Phase.IDLE;
    private PlanStep request;
    private boolean urgent, recoveryMode, effectSent, ambiguous, selectionAbandoned, drainRequested;
    private boolean paused, attackPending, effectAcknowledged, returningHand, navigationAbandoned;
    private boolean movementHandedOff, airObserver;
    private SlotTransfer handTransfer;
    private int borrowedFrom = -1;
    private float pendingAttackHealth;
    private long pauseStarted, started, effectSequence;
    private int attacks, failedRoutes, waitTicks, originalSlot = -1, selectedSlot = -1, shearDamage;
    private Animal target;
    private UUID targetId;
    private ItemEntity drop;
    private UUID dropId;
    private int pinnedDropCount;
    private Object owningPlayer, actionWorld;
    private OwnedStationLedger.Session session;
    private long jobToken, offerGeneration;
    private double originX, originY, originZ;
    private Map<ItemId, Integer> protection = Map.of();
    private Map<ItemId, PlacementProvenance.ServerInventoryReceipt> before = Map.of();
    private Map<UUID, Integer> oldDrops = Map.of();
    private Outcome terminal;
    private Outcome pendingTerminal;
    private String status = "animal acquisition idle";

    AnimalHarvestAction(Minecraft client, LodekeeperConfig config, PlayerActions actions,
                        MovementController movement, WorldProtection worldProtection, PlacementProvenance provenance) {
        this.client = client; this.config = config; this.actions = actions; this.movement = movement;
        this.worldProtection = worldProtection; this.provenance = provenance;
    }

    private static List<Capability> capabilities() {
        List<Capability> entries = new ArrayList<>();
        for (String[] spec : List.of(new String[]{"COW", "beef", "cooked_beef"},
                new String[]{"COW", "leather", ""}, new String[]{"PIG", "porkchop", "cooked_porkchop"},
                new String[]{"SHEEP", "mutton", "cooked_mutton"})) {
            ItemId output = ItemId.parse("minecraft:" + spec[1]);
            entries.add(new Capability(new AnimalHarvestSource("animal:kill:" + spec[1], output,
                    new NativeWork.AnimalHarvest(NativeWork.AnimalKind.valueOf(spec[0]), NativeWork.HarvestMethod.KILL),
                    List.of()), spec[2].isEmpty() ? null : ItemId.parse("minecraft:" + spec[2])));
        }
        for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
            entries.add(new Capability(new AnimalHarvestSource("animal:shear:" + color,
                    ItemId.parse("minecraft:" + color + "_wool"),
                    new NativeWork.AnimalHarvest(NativeWork.AnimalKind.SHEEP, NativeWork.HarvestMethod.SHEAR),
                    List.of(new ToolRequirement(Ingredient.of(ItemId.parse("minecraft:shears")), 2, "ordinary animal shears", 1))), null));
        }
        return List.copyOf(entries);
    }

    static List<AnimalHarvestSource> supportedSources() {
        return GameApi.supportsAnimalHarvest() ? CAPABILITIES.stream().map(Capability::source).toList() : List.of();
    }

    static boolean ordinaryCommodity(ItemId item) {
        return GameApi.supportsAnimalHarvest() && CAPABILITIES.stream().anyMatch(entry ->
                entry.source().output().equals(item) || item.equals(entry.cooked()));
    }

    static boolean ordinary(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemStack actual = stack.copy(), standard = new ItemStack(stack.getItem());
        if (stack.isDamageableItem()) { actual.setDamageValue(0); standard.setDamageValue(0); }
        return ItemStack.isSameItemSameComponents(actual, standard);
    }

    void updateProtection(Map<ItemId, Integer> floors) { protection = Map.copyOf(floors); }
    boolean active() { return phase != Phase.IDLE && phase != Phase.DONE; }
    boolean urgent() { return urgent; }
    long jobToken() { return jobToken; }
    long offerGeneration() { return offerGeneration; }
    boolean quotaClockActive() { return active() && !paused && phase != Phase.DRAIN; }
    String status() { return status; }
    record Observation(long jobToken, UUID target, int sentEffects, boolean active, boolean pendingEvidence, boolean airObserver) { }
    Observation observation() { return new Observation(jobToken, targetId, shearing() ? (effectSent ? 1 : 0) : attacks,
            active(), attackPending || shearing() && effectSent && !effectAcknowledged || handTransfer != null, airObserver); }

    boolean airObserver() { return airObserver; }
    boolean handOffMovementForAir() {
        if (!active() || !currentSession() || navigationAbandoned) return false;
        drainRequested = true; phase = Phase.DRAIN;
        if (airObserver) return true;
        movement.checkAirRecoveryOwnership();
        if (!movementHandedOff) movement.stop();
        if (!movement.finishCancellation()) { status = "finishing animal cancellation before air escape"; return false; }
        movementHandedOff = airObserver = true;
        status = "observing retained animal evidence during air escape";
        return true;
    }
    void finishAirObservation() { airObserver = false; }

    boolean ready(boolean recovery) {
        if (active() || unsafeContextReason(recovery) != null) return false;
        return nearest(null, true) != null;
    }
    boolean ready() { return ready(false); }

    boolean begin(boolean recovery, long job, long generation, BooleanSupplier reserveAttempt) {
        Animal animal = nearest(null, true);
        if (animal == null) return false;
        ItemId output = GameCatalog.id(rawMeat(animal));
        AnimalHarvestSource source = CAPABILITIES.stream().map(Capability::source)
                .filter(entry -> entry.output().equals(output)).findFirst().orElseThrow();
        return begin(PlanStep.nativeAction(source.sourceId(), output, 1, 1, List.of(), source.work()),
                true, recovery, job, generation, reserveAttempt);
    }

    boolean begin(PlanStep step, boolean urgent, boolean recovery, long job, long generation,
                  BooleanSupplier reserveAttempt) {
        if (active() || step.kind() != PlanKind.NATIVE || !(step.nativeWork() instanceof NativeWork.AnimalHarvest)
                || (urgent ? CAPABILITIES.stream().map(Capability::source) : supportedSources().stream())
                    .noneMatch(source -> source.sourceId().equals(step.sourceId())
                    && source.output().equals(step.output()) && source.work().equals(step.nativeWork()))) return false;
        if (unsafeContextReason(recovery) != null || !provenance.confirmedInventoryReady()) {
            status = "waiting for coherent ordinary server stock"; return false;
        }
        Animal animal = nearest(step, urgent);
        if (animal == null) { status = "no eligible unprotected animal within 32 blocks"; return false; }
        var capturedSession = provenance.session().orElse(null);
        if (capturedSession == null || !reserveAttempt.getAsBoolean()) { status = "animal job quota exhausted"; return false; }
        this.request = step; this.urgent = urgent; recoveryMode = recovery;
        session = capturedSession; jobToken = job; offerGeneration = generation;
        target = animal; targetId = animal.getUUID(); owningPlayer = client.player; actionWorld = currentWorld();
        originX = client.player.getX(); originY = client.player.getY(); originZ = client.player.getZ();
        started = System.nanoTime(); paused = false; pauseStarted = 0;
        attacks = failedRoutes = waitTicks = 0; attackPending = effectAcknowledged = returningHand = false;
        handTransfer = null; borrowedFrom = -1; navigationAbandoned = movementHandedOff = airObserver = false;
        effectSent = ambiguous = drainRequested = false;
        before = Map.of(); oldDrops = Map.of(); drop = null; dropId = null;
        terminal = pendingTerminal = null;
        originalSlot = client.player.getInventory().getSelectedSlot();
        selectedSlot = originalSlot; selectionAbandoned = false;
        int selected = chooseEffectSlot();
        if (selected < 0 && shearing() && prepareStoredShears()) return true;
        if (selected < 0) {
            pendingTerminal = new Outcome.Blocked("no safe empty hand or reserved ordinary shears available");
            phase = Phase.DRAIN; return true;
        }
        if (selected != originalSlot && !actions.selectSlot(selected)) {
            pendingTerminal = new Outcome.Blocked("could not select the native action hand");
            phase = Phase.DRAIN; return true;
        }
        selectedSlot = selected;
        phase = Phase.APPROACH; status = "approaching " + step.sourceId();
        startApproach();
        return true;
    }

    @Override public Outcome tick() {
        if (!active()) return terminal == null ? new Outcome.Drained() : terminal;
        if (!currentSession()) { abandonSession(); return terminal; }
        if (paused) { paused = false; started += System.nanoTime() - pauseStarted; }
        if (client.player.getInventory().getSelectedSlot() != selectedSlot) selectionAbandoned = true;
        if (phase == Phase.DRAIN) return tickDrain();
        String unsafe = unsafeContextReason(recoveryMode);
        if (unsafe != null) { block(unsafe); return tickDrain(); }
        if (client.player.getInventory().getSelectedSlot() != selectedSlot) {
            selectionAbandoned = true; block("manual hotbar selection has priority"); return tickDrain();
        }
        if (System.nanoTime() - started >= TARGET_NANOS || !withinOrigin(client.player.getX(), client.player.getY(), client.player.getZ())) {
            coolDown(); block("animal target exceeded its 30 second or 32 block limit"); return tickDrain();
        }
        if (attackPending && (target.getHealth() < pendingAttackHealth || nativeEffectObserved())) {
            attackPending = false; failedRoutes = 0; movement.recordConfirmedWorldAction();
        }
        try {
            switch (phase) {
                case PREPARING_HAND -> {
                    if (handTransfer != null) {
                        if (!handTransfer.tick()) break;
                        handTransfer = null;
                    }
                    if (!provenance.confirmedInventoryReady()) {
                        status = "observing coherent ordinary server stock after shears preparation"; break;
                    }
                    if (chooseEffectSlot() != selectedSlot) block("prepared shears no longer satisfy their ordinary reservation");
                    else startApproach();
                }
                case APPROACH -> {
                    if (nativeEffectObserved() || canHit()) { movement.stop(); phase = Phase.STOPPING; }
                    else {
                        if (!eligible(target, request, urgent)) { block("tracked animal became unsafe"); break; }
                        try { if (movement.tick()) { failedRoutes++; movement.stop(); phase = Phase.STOPPING; } }
                        catch (MovementController.NavigationFailure failure) {
                            if (failure.kind != MovementController.NavigationFailure.Kind.PROCESS_ENDED) throw failure;
                            failedRoutes++; movement.stop(); phase = Phase.STOPPING;
                        }
                    }
                }
                case STOPPING -> {
                    movement.stop();
                    if (!movement.finishCancellation()) break;
                    if (nativeEffectObserved()) { phase = Phase.WAITING; waitTicks = 0; }
                    else if (canHit()) phase = Phase.EFFECT;
                    else if (failedRoutes >= 4) { coolDown(); block("animal remained outside safe interaction reach"); }
                    else startApproach();
                }
                case EFFECT -> tickEffect();
                case WAITING -> tickWaiting();
                case PICKUP -> tickPickup();
                default -> { }
            }
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) {
                abandonNavigationOwnership();
                return active() ? new Outcome.Blocked("navigation ownership lost while an inventory receipt remains pending") : terminal;
            }
            coolDown(); block(failure.getMessage());
        } catch (RuntimeException failure) { block(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
        return phase == Phase.DRAIN ? tickDrain() : new Outcome.Pending(status);
    }

    private void tickEffect() {
        if (attackPending) { status = "observing the pending native attack without resending"; return; }
        if (nativeEffectObserved()) { phase = Phase.WAITING; waitTicks = 0; return; }
        if (!eligible(target, request, urgent)) { block("tracked animal changed or became protected before interaction"); return; }
        if (!canHit()) { startApproach(); return; }
        if (shearing() && effectSent) { phase = Phase.WAITING; return; }
        if (!shearing() && attacks >= 32) { block("32 native attacks made insufficient progress"); return; }
        int hand = chooseEffectSlot();
        if (hand != selectedSlot || hand < 0) { block("selected native action hand no longer satisfies its reservation"); return; }
        if (!shearing() && (!(client.player.getAttackStrengthScale(0) >= 1) || !GameApi.animalAttackWindow(target))) return;
        if (!effectSent && !captureBefore()) { status = "waiting for coherent ordinary pre-interaction stock"; return; }
        if (!worldProtection.mayInteractEntity(target)) { block("animal interaction permission was revoked"); return; }
        actions.look(target.getBoundingBox().getCenter());
        if (shearing()) {
            shearDamage = client.player.getInventory().getItem(selectedSlot).getDamageValue();
            effectSent = true;
            GameApi.shearAnimal(client, (Sheep) target);
            phase = Phase.WAITING;
        } else {
            pendingAttackHealth = target.getHealth(); attackPending = true; effectSent = true; attacks++;
            client.gameMode.attack(client.player, target);
            GameApi.swing(client.player, InteractionHand.MAIN_HAND);
        }
        status = "awaiting native animal evidence";
    }

    private boolean captureBefore() {
        Map<ItemId, PlacementProvenance.ServerInventoryReceipt> stock = new HashMap<>();
        for (ItemId item : acceptedItems()) {
            var receipt = provenance.confirmedOrdinaryInventoryReceipt(item).orElse(null);
            if (receipt == null) return false;
            stock.put(item, receipt);
        }
        before = Map.copyOf(stock); effectSequence = provenance.serverReceiptSequence();
        Map<UUID, Integer> drops = new HashMap<>();
        for (ItemEntity item : nearbyDrops()) {
            if (drops.size() >= 128) { block("too many matching drops to capture safely"); return false; }
            drops.put(item.getUUID(), item.getItem().getCount());
        }
        oldDrops = Map.copyOf(drops);
        return true;
    }

    private void tickWaiting() {
        if (!nativeEffectObserved()) { status = "observing pending animal interaction without resending"; return; }
        ObservedStock gained = gain();
        if (gained != null) { ambiguous = true; yieldStock(gained, "coherent stock gained before a pinned pickup"); return; }
        List<ItemEntity> candidates = nearbyDrops().stream().filter(item ->
                item.getItem().getCount() > oldDrops.getOrDefault(item.getUUID(), 0))
                .sorted(Comparator.comparingDouble(client.player::distanceToSqr)).toList();
        if (!candidates.isEmpty()) {
            // A freshly tossed item can occupy an unsupported feet cell above its eventual landing.
            var settled = candidates.stream().filter(ItemEntity::onGround).findFirst();
            if (settled.isEmpty()) { status = "waiting for an ordinary matching drop to land before owned pickup"; return; }
            drop = settled.get(); dropId = drop.getUUID(); pinnedDropCount = drop.getItem().getCount();
            ambiguous = oldDrops.containsKey(dropId);
            movement.startOwnedPickup(drop, session, MovementController.RouteEffects.MOVEMENT_ONLY); phase = Phase.PICKUP;
            status = "collecting pinned ordinary " + GameCatalog.id(drop.getItem().getItem());
            return;
        }
        if (++waitTicks >= 20 && !shearing()) {
            coolDown(); yieldStock(stock(request.output()), "animal yielded no observed requested drop");
        }
    }

    private void tickPickup() {
        if (drop == null || !dropId.equals(drop.getUUID()) || !accepts(drop.getItem()) && drop.isAlive()) {
            block("pinned drop identity or components changed"); return;
        }
        ObservedStock gained = gain();
        if (gained != null) {
            // A global gain and a changed drop do not establish its collector or attributable quantity.
            yieldStock(gained, "coherent ordinary stock gain without attributable pickup quantity");
            return;
        }
        if (!drop.isAlive()) { yieldStock(stock(request.output()), "pinned drop vanished without matching inventory gain"); return; }
        if (movement.tick()) yieldStock(stock(request.output()), "pickup ended without matching server gain");
    }

    private ObservedStock stock(ItemId item) {
        var receipt = provenance.confirmedOrdinaryInventoryReceipt(item).orElse(null);
        return receipt == null ? null : new ObservedStock(session, jobToken, offerGeneration, item,
                receipt.count(), receipt.increaseSequence());
    }
    private ObservedStock gain() {
        for (ItemId item : acceptedItems()) {
            ObservedStock stock = stock(item);
            var initial = before.get(item);
            if (stock != null && initial != null && stock.count() > initial.count()
                    && stock.increaseSequence() > Math.max(initial.increaseSequence(), effectSequence)) return stock;
        }
        return null;
    }
    private void yieldStock(ObservedStock stock, String reason) {
        pendingTerminal = stock == null ? new Outcome.Blocked("ordinary stock observation unavailable after " + reason)
                : new Outcome.Yielded(stock, reason);
        phase = Phase.DRAIN; status = reason;
    }
    private void block(String reason) { pendingTerminal = new Outcome.Blocked(reason); phase = Phase.DRAIN; status = reason; }

    private Outcome tickDrain() {
        try {
            if (!airObserver) movement.checkAirRecoveryOwnership();
            if (!navigationAbandoned && !movementHandedOff) movement.stop();
            if (!navigationAbandoned && !movementHandedOff && !movement.finishCancellation()) return System.nanoTime() - started >= TARGET_NANOS
                    ? new Outcome.Blocked("animal movement cancellation remains pending after 30 seconds")
                    : new Outcome.Pending("animal movement cancellation remains pending");
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) {
                abandonNavigationOwnership();
                return active() ? new Outcome.Blocked("navigation ownership lost while an inventory receipt remains pending") : terminal;
            }
            return new Outcome.Blocked("animal cancellation needs attention: " + failure.getMessage());
        }
        if (attackPending) {
            if (target != null && (target.getHealth() < pendingAttackHealth || nativeEffectObserved())) attackPending = false;
            else return System.nanoTime() - started >= TARGET_NANOS
                    ? new Outcome.Blocked("native attack acknowledgment remains uncertain; resume to observe without resending")
                    : new Outcome.Pending("observing pending native attack during drain");
        }
        if (shearing() && effectSent && !nativeEffectObserved())
            return System.nanoTime() - started >= TARGET_NANOS
                    ? new Outcome.Blocked("shearing state and wear acknowledgment remains uncertain; resume to observe without resending")
                    : new Outcome.Pending("observing pending shearing state and wear during drain");
        if (airObserver) return new Outcome.Pending("retaining animal evidence until air movement releases ownership");
        Outcome handDrain = drainHandTransfer();
        if (handDrain != null) return handDrain;
        if (!restoreSelection()) return new Outcome.Pending("animal hand restoration awaits a safe player screen");
        if (effectSent && provenance.confirmedOrdinaryInventoryReceipt(request.output()).isEmpty())
            return System.nanoTime() - started >= TARGET_NANOS
                    ? new Outcome.Blocked("animal drain lacks coherent same-session ordinary stock after 30 seconds")
                    : new Outcome.Pending("animal drain awaits coherent same-session ordinary stock");
        if (pendingTerminal instanceof Outcome.Yielded yielded) {
            ObservedStock fresh = stock(yielded.stock().item());
            if (fresh == null) return new Outcome.Pending("native output needs a fresh coherent stock observation");
            pendingTerminal = new Outcome.Yielded(fresh, yielded.reason());
        }
        terminal = pendingTerminal == null ? new Outcome.Drained() : pendingTerminal;
        if (drainRequested) {
            ObservedStock observed = stock(request.output());
            terminal = effectSent && observed != null ? new Outcome.Yielded(observed, "native action safely drained") : new Outcome.Drained();
        }
        phase = Phase.DONE;
        return terminal;
    }

    @Override public void requestDrain(DrainReason reason) {
        if (!active()) return;
        if (currentSession() && client.player.getInventory().getSelectedSlot() != selectedSlot) selectionAbandoned = true;
        drainRequested = true; phase = Phase.DRAIN; status = "draining animal action for " + reason;
        if (currentSession()) {
            try { if (!navigationAbandoned && !movementHandedOff) movement.stop(); }
            catch (MovementController.NavigationFailure failure) {
                if (failure.kind != MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
                abandonNavigationOwnership();
            }
        } else abandonSession();
    }
    @Override public void pause() {
        if (!active()) return;
        if (currentSession() && client.player.getInventory().getSelectedSlot() != selectedSlot) selectionAbandoned = true;
        if (!paused) { paused = true; pauseStarted = System.nanoTime(); }
        try { if (!navigationAbandoned && !movementHandedOff) movement.suspend(); }
        catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
            abandonNavigationOwnership();
        }
    }
    void stop() { requestDrain(DrainReason.PREEMPT); }
    @Override public void abandonSession() {
        selectionAbandoned = true; handTransfer = null; borrowedFrom = -1; airObserver = false;
        phase = Phase.DONE; terminal = new Outcome.Drained();
        owningPlayer = actionWorld = null; status = "animal evidence abandoned with the old native session";
    }
    void abandonNavigationOwnership() {
        selectionAbandoned = navigationAbandoned = true; airObserver = false;
        pendingTerminal = new Outcome.Blocked("animal action lost navigation ownership");
        if (handTransfer != null || attackPending || shearing() && effectSent && !nativeEffectObserved()) phase = Phase.DRAIN;
        else { borrowedFrom = -1; phase = Phase.DONE; terminal = pendingTerminal; }
        status = "animal action lost navigation ownership";
    }
    @Override public boolean safeToRelease() { return !active(); }

    private boolean currentSession() {
        return owningPlayer == client.player && actionWorld == currentWorld()
                && provenance.session().filter(session::equals).isPresent();
    }
    private boolean shearing() { return request != null && ((NativeWork.AnimalHarvest) request.nativeWork()).method() == NativeWork.HarvestMethod.SHEAR; }
    private boolean nativeEffectObserved() {
        if (effectAcknowledged) return true;
        if (!effectSent || target == null) return false;
        if (!shearing()) {
            effectAcknowledged = target.getHealth() <= 0;
            return effectAcknowledged;
        }
        if (!((Sheep) target).isSheared()) return false;
        ItemStack shear = provenance.confirmedOrdinaryStack(selectedSlot).orElse(null);
        effectAcknowledged = shear != null && shear.is(Items.SHEARS) && shear.getDamageValue() == shearDamage + 1;
        return effectAcknowledged;
    }
    private boolean canHit() {
        return target != null && GameApi.defenseWithinReach(client.player, target) && client.player.hasLineOfSight(target);
    }
    private void startApproach() {
        if (target == null || !eligible(target, request, urgent)) { block("animal is no longer safe to approach"); return; }
        movement.startFollowing(target, entity -> entity == target && targetId.equals(target.getUUID())
                && eligible(target, request, urgent) && withinOrigin(target.getX(), target.getY(), target.getZ()),
                MovementController.RouteEffects.MOVEMENT_ONLY);
        phase = Phase.APPROACH; status = "pursuing " + request.sourceId();
    }
    private boolean eligible(Animal animal, PlanStep step, boolean food) {
        if (animal == null || kind(animal) == null || !animal.isAlive() || !Float.isFinite(animal.getHealth())
                || animal.getHealth() <= 0 || animal.isBaby() || animal.hasCustomName() || animal.isLeashed()
                || animal.isPassenger() || !animal.getPassengers().isEmpty() || animal.isOnFire() || animal.isInLava()
                || animal instanceof TamableAnimal tameable && tameable.isTame()
                || !worldProtection.mayInteractEntity(animal)) return false;
        if (food) return safeFood(new ItemStack(rawMeat(animal)));
        NativeWork.AnimalHarvest work = (NativeWork.AnimalHarvest) step.nativeWork();
        return work.animal() == kind(animal) && (work.method() != NativeWork.HarvestMethod.SHEAR
                || animal instanceof Sheep sheep && !sheep.isSheared()
                && GameApi.sheepWool(sheep).equals(step.output()));
    }
    private Animal nearest(PlanStep step, boolean food) {
        if (client.player == null || client.level == null) return null;
        pruneFailed();
        var player = client.player;
        return client.level.getEntities(EntityTypeTest.forClass(Animal.class), player.getBoundingBox().inflate(32), animal ->
                eligible(animal, step, food) && distanceSquared(animal.getX(), animal.getY(), animal.getZ(),
                        player.getX(), player.getY(), player.getZ()) <= RANGE_SQUARED
                        && !FAILED_UNTIL.containsKey(animal.getUUID())).stream()
                .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }
    private static NativeWork.AnimalKind kind(Animal animal) {
        if (animal.getClass() == Cow.class) return NativeWork.AnimalKind.COW;
        if (animal.getClass() == Pig.class) return NativeWork.AnimalKind.PIG;
        if (animal.getClass() == Sheep.class) return NativeWork.AnimalKind.SHEEP;
        return null;
    }
    private static Item rawMeat(Animal animal) {
        return animal.getClass() == Cow.class ? Items.BEEF : animal.getClass() == Pig.class ? Items.PORKCHOP : Items.MUTTON;
    }
    private List<ItemId> acceptedItems() {
        if (!urgent) return List.of(request.output());
        Capability entry = CAPABILITIES.stream().filter(candidate -> candidate.source().output().equals(request.output())).findFirst().orElseThrow();
        return List.of(request.output(), entry.cooked());
    }
    private boolean accepts(ItemStack stack) {
        return ordinary(stack) && acceptedItems().contains(GameCatalog.id(stack.getItem())) && (!urgent || safeFood(stack));
    }
    private boolean safeFood(ItemStack stack) {
        var food = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        var consumable = stack.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
        return food != null && consumable != null && food.nutrition() > 0
                && consumable.onConsumeEffects().stream().allMatch(net.minecraft.world.item.consume_effects.PlaySoundConsumeEffect.class::isInstance);
    }
    private List<ItemEntity> nearbyDrops() {
        return client.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), target.getBoundingBox().inflate(8), item ->
                item.isAlive() && accepts(item.getItem()) && withinOrigin(item.getX(), item.getY(), item.getZ()));
    }
    private boolean prepareStoredShears() {
        int destination = -1;
        for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getItem(slot).isEmpty()) { destination = slot; break; }
        if (destination < 0 || !provenance.confirmedInventoryReady()) return false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack shear = provenance.confirmedOrdinaryStack(slot).orElse(null);
            if (!reservedShears(shear)) continue;
            if (destination != originalSlot && !actions.selectSlot(destination)) return false;
            selectedSlot = destination; borrowedFrom = slot;
            handTransfer = new SlotTransfer(client, client.player.inventoryMenu, slot, 36 + destination, 1);
            phase = Phase.PREPARING_HAND; status = "preparing reserved ordinary shears with server receipts";
            return true;
        }
        return false;
    }
    private boolean reservedShears(ItemStack stack) {
        SelectedToolRequirement tool = request.requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
        return tool != null && tool.item().equals(ItemId.parse("minecraft:shears")) && stack != null
                && stack.is(Items.SHEARS) && stack.getCount() == 1
                && stack.getMaxDamage() - stack.getDamageValue() >= tool.minimumDurability();
    }
    private Outcome drainHandTransfer() {
        if (handTransfer == null && borrowedFrom < 0) return null;
        if (GameApi.screen(client) != null || client.player.containerMenu != client.player.inventoryMenu || manualInput())
            return new Outcome.Pending("ordinary shears transfer awaits its owned player screen");
        try {
            if (handTransfer != null) {
                if (returningHand) {
                    if (!handTransfer.tick()) return new Outcome.Pending("observing ordinary shears restoration receipt");
                } else handTransfer.recover();
                handTransfer = null;
            }
            if (!provenance.confirmedInventoryReady()) return new Outcome.Pending("observing coherent ordinary shears after transfer");
            if (borrowedFrom < 0) return null;
            if (returningHand || !client.player.getInventory().getItem(borrowedFrom).isEmpty()) { borrowedFrom = -1; return null; }
            if (selectionAbandoned || client.player.getInventory().getSelectedSlot() != selectedSlot) {
                selectionAbandoned = true; borrowedFrom = -1; return null;
            }
            ItemStack shear = provenance.confirmedOrdinaryStack(selectedSlot).orElse(null);
            if (shear == null || !shear.is(Items.SHEARS) || shear.getCount() != 1) {
                selectionAbandoned = true; borrowedFrom = -1; return null;
            }
            handTransfer = new SlotTransfer(client, client.player.inventoryMenu, 36 + selectedSlot, borrowedFrom, 1);
            returningHand = true;
            return new Outcome.Pending("restoring borrowed ordinary shears with server receipts");
        } catch (RuntimeException failure) {
            return new Outcome.Blocked("ordinary shears transfer retains its pending receipt: " + failure.getMessage());
        }
    }
    private int chooseEffectSlot() {
        if (shearing()) {
            SelectedToolRequirement tool = request.requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                    .map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
            if (tool == null || !tool.item().equals(ItemId.parse("minecraft:shears"))) return -1;
            for (int offset = 0; offset < 9; offset++) {
                int slot = (selectedSlot + offset) % 9;
                ItemStack stack = provenance.confirmedOrdinaryStack(slot).orElse(null);
                if (stack != null && stack.is(Items.SHEARS) && stack.getCount() == 1
                        && stack.getMaxDamage() - stack.getDamageValue() >= tool.minimumDurability()) return slot;
            }
            return -1;
        }
        if (urgent) return chooseWeaponSlot(selectedSlot);
        for (int offset = 0; offset < 9; offset++) {
            int slot = (selectedSlot + offset) % 9;
            if (client.player.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }
    private boolean restoreSelection() {
        if (selectionAbandoned || originalSlot == selectedSlot || originalSlot < 0) return true;
        if (!currentSession() || client.player.getInventory().getSelectedSlot() != selectedSlot || manualInput()) {
            selectionAbandoned = true; return true;
        }
        if (GameApi.screen(client) != null || client.player.containerMenu != client.player.inventoryMenu) return false;
        return actions.selectSlot(originalSlot);
    }
    private void pruneFailed() {
        if (failedWorld.get() != currentWorld()) { FAILED_UNTIL.clear(); failedWorld = new WeakReference<>(currentWorld()); }
        long now = System.nanoTime(); FAILED_UNTIL.entrySet().removeIf(entry -> now - entry.getValue() >= 0);
    }
    private void coolDown() {
        if (targetId == null) return;
        pruneFailed(); FAILED_UNTIL.put(targetId, System.nanoTime() + 120_000_000_000L);
        while (FAILED_UNTIL.size() > 128) FAILED_UNTIL.remove(FAILED_UNTIL.keySet().iterator().next());
    }
    private boolean withinOrigin(double x, double y, double z) { return distanceSquared(x, y, z, originX, originY, originZ) <= RANGE_SQUARED; }
    private static double distanceSquared(double x, double y, double z, double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz; return dx * dx + dy * dy + dz * dz;
    }
    private int chooseWeaponSlot(int selected) {
        var inventory = client.player.getInventory();
        int best = -1;
        double damage = Double.NEGATIVE_INFINITY;
        boolean sweepCollateral = target != null
                && GameApi.defenseHasSweepCollateral(client.level, client.player, target);
        for (int offset = 0; offset < 9; offset++) {
            int slot = (selected + offset) % 9;
            ItemStack stack = inventory.getItem(slot);
            if (!safeAttackStack(stack) || sweepCollateral && GameApi.isSword(stack)) continue;
            double candidate = GameApi.defenseAttackDamage(client.player, stack);
            if (Double.isFinite(candidate) && candidate > damage) { best = slot; damage = candidate; }
        }
        return best;
    }

    private boolean safeAttackStack(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (stack.isEnchanted() || GameApi.hasCustomName(stack)
                || protection.getOrDefault(GameCatalog.id(stack.getItem()), 0) > 0) return false;
        ItemStack normalized = stack.copy(), ordinary = new ItemStack(stack.getItem());
        if (stack.isDamageableItem()) {
            int wear = GameApi.defenseAttackWear(stack);
            int reserve = GameApi.isSword(stack) || GameApi.isAxe(stack) ? 2 : 16;
            if (wear < 0 || stack.getMaxDamage() - stack.getDamageValue() - wear < reserve) return false;
            normalized.setDamageValue(0); ordinary.setDamageValue(0);
        }
        return ItemStack.isSameItemSameComponents(normalized, ordinary);
    }

    private String unsafeContextReason(boolean recovery) {
        if (client.player == null || client.level == null || client.gameMode == null) return "world unavailable";
        var player = client.player;
        float health = player.getHealth();
        float healthFloor = recovery ? Math.min(3.0f, config.pauseBelowHealth) : config.pauseBelowHealth;
        if (!player.isAlive() || !Float.isFinite(health) || health <= 0.0f || health <= healthFloor) {
            return "player health safeguard";
        }
        if (player.isOnFire() || player.isInLava()) return "player is in a fire or lava hazard";
        if (player.isCreative() || player.isSpectator()) return "animal acquisition requires survival play";
        if (player.isPassenger() || player.isUsingItem()) return "player is riding or using an item";
        if (GameApi.screen(client) != null || player.containerMenu != player.inventoryMenu
                || handTransfer == null && !player.containerMenu.getCarried().isEmpty()) return "inventory screen or cursor is not safe";
        if (manualInput()) return "manual player input has priority";
        return null;
    }

    private Object currentWorld() {
        return client.level;
    }

    private boolean manualInput() {
        var options = client.options;
        return options.keyAttack.isDown() || options.keyUse.isDown()
                || options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown() || options.keyShift.isDown() || options.keySprint.isDown();
    }


}
