package dev.lodekeeper.fabric;

import dev.lodekeeper.core.*;
import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import java.util.*;
import java.util.function.BooleanSupplier;


final class CropHarvestAction implements NativeRun {
    private static final int RADIUS = 16, MAX_SEARCH = 16_384, SEARCH_PER_TICK = 512;
    private static final long CYCLE_NANOS = 30_000_000_000L, SEARCH_NANOS = 5_000_000_000L;
    private enum Phase { SEARCH, APPROACH, STOPPING, HARVEST_WAIT, COLLECT, PICKUP,
        SWAP_WAIT, PLANT, PLANT_WAIT, RESTORE_SWAP_WAIT, DRAIN, DONE }
    static final class JobBudget {
        private int cycles;
        private long activeNanos;
        boolean reserve() { if (cycles >= 32 || exhausted()) return false; cycles++; return true; }
        void observe(long nanos) { activeNanos = Math.min(600_000_000_000L, activeNanos + Math.max(0, nanos)); }
        boolean exhausted() { return activeNanos >= 600_000_000_000L; }
    }
    record Admission(PlanStep step, GameCatalog catalog, long catalogGeneration, long jobToken,
                     Object phaseIdentity, OwnedKernelRuntime owner, OwnedKernelRuntime.Session nativeSession,
                     OwnedStationLedger.Session provenanceSession, BooleanSupplier newEffects,
                     BooleanSupplier repairEffects, BooleanSupplier ownedAuthority, BooleanSupplier cleanupEffects,
                     long protectionEpoch, long nativePolicyGeneration, AirRecoveryAction airRecovery, JobBudget budget) {
        Admission {
            Objects.requireNonNull(step); Objects.requireNonNull(catalog); Objects.requireNonNull(phaseIdentity);
            Objects.requireNonNull(owner); Objects.requireNonNull(nativeSession); Objects.requireNonNull(provenanceSession);
            Objects.requireNonNull(newEffects); Objects.requireNonNull(repairEffects);
            Objects.requireNonNull(ownedAuthority); Objects.requireNonNull(cleanupEffects);
            Objects.requireNonNull(airRecovery); Objects.requireNonNull(budget);
            if (jobToken <= 0 || catalogGeneration < 0 || protectionEpoch < 0 || nativePolicyGeneration < 0)
                throw new IllegalArgumentException("Invalid crop admission");
        }
    }
    record Observation(long jobToken, BlockPos unrepairedPosition, boolean harvestSent, boolean plantingSent,
                       boolean pendingEvidence, boolean plantingReserved, String status, long breakAfterSequence,
                       long harvestReceiptSequence, long plantAfterSequence, long replantReceiptSequence, int cycles, long activeNanos) { }
    private static final List<CropHarvestSource> SOURCES = sources();
    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final MovementController movement;
    private final WorldProtection protection;
    private final PlacementProvenance provenance;
    private Admission admission;
    private Phase phase = Phase.DONE;
    private Object player, world, network, connection, menu, input;
    private enum InputAvailability { ORIGINAL, OWNED_MOVEMENT, OWNED_AIR, RESTORING, UNAVAILABLE, FOREIGN }
    private InputAvailability inputAvailability = InputAvailability.UNAVAILABLE;
    private MovementController.InputLease movementInputLease;
    private BlockPos origin, target;
    private BlockState matureState, farmlandState;
    private NativeWork.CropKind crop;
    private ItemId plantingItem;
    private Set<ItemId> stockItems;
    private Map<ItemId, Integer> floors = Map.of();
    private PlacementProvenance.JointStock before, beforePlant, beforeSwap;
    private List<ItemStack> expectedSwap;
    private final Map<UUID, Integer> oldDrops = new HashMap<>();
    private ItemEntity pickup;
    private UUID pickupId;
    private int pickupCount, searchIndex, routeFailures, waitTicks, originalSlot, selectedSlot, swapSource = -1;
    private long started, lastTick, pauseStarted, breakSequence, plantSequence, swapSequence;
    private long harvestReceipt, replantReceipt, manualRepairReceipt;
    private boolean harvestSent, plantSent, swapSent, restoreSwapSent, swapConfirmed, plantRejected, plantAcknowledged, restoreSwapConfirmed;
    private boolean reservedPlanting, drainRequested, paused, rightsLost, navigationLost, timedOut;
    private boolean repairAllowedAtDrain, airObserver, movementHandedOff, dropOverflow;
    private Outcome terminal;
    private String status = "crop acquisition idle", blocker;

    CropHarvestAction(MinecraftClient client, LodekeeperConfig config, PlayerActions actions,
                      MovementController movement, WorldProtection protection, PlacementProvenance provenance) {
        this.client = client; this.config = config; this.actions = actions; this.movement = movement;
        this.protection = protection; this.provenance = provenance;
    }
    private static List<CropHarvestSource> sources() {
        List<CropHarvestSource> result = new ArrayList<>();
        for (String[] spec : List.of(new String[]{"WHEAT", "wheat", "wheat_seeds"},
                new String[]{"CARROT", "carrot"}, new String[]{"POTATO", "potato"},
                new String[]{"BEETROOT", "beetroot", "beetroot_seeds"})) {
            NativeWork.CropHarvest work = new NativeWork.CropHarvest(NativeWork.CropKind.valueOf(spec[0]));
            for (int index = 1; index < spec.length; index++) result.add(new CropHarvestSource(
                    "crop:mature:" + spec[index], ItemId.parse("minecraft:" + spec[index]), work));
        }
        return List.copyOf(result);
    }
    static List<CropHarvestSource> supportedSources() { return GameApi.supportsCropHarvest() ? SOURCES : List.of(); }
    static boolean ordinaryCommodity(ItemId item) {
        return supportedSources().stream().anyMatch(source -> source.output().equals(item));
    }
    void updateProtection(Map<ItemId, Integer> floors) { this.floors = Map.copyOf(floors); }
    boolean active() { return phase != Phase.DONE; }
    boolean originalSessionCurrent() { return currentSession(); }
    long jobToken() { return admission == null ? 0 : admission.jobToken(); }
    long offerGeneration() { return admission == null ? -1 : admission.catalogGeneration(); }
    String status() { return status; }
    Observation observation() {
        return new Observation(jobToken(), unrepaired() ? target : null, harvestSent, plantSent,
                pendingEvidence(), reservedPlanting, status, breakSequence, harvestReceipt, plantSequence, replantReceipt,
                admission == null ? 0 : admission.budget().cycles, admission == null ? 0 : admission.budget().activeNanos);
    }
    boolean begin(Admission next) {
        if (active() || !(next.step().nativeWork() instanceof NativeWork.CropHarvest work)
                || supportedSources().stream().noneMatch(source -> source.sourceId().equals(next.step().sourceId())
                    && source.output().equals(next.step().output()) && source.work().equals(work))
                || unsafe() != null || client.player.input == null || !provenance.confirmedInventoryReady() || !next.newEffects().getAsBoolean()
                || !next.owner().isCurrent(next.nativeSession()) || next.nativeSession().world() != client.world
                || !next.ownedAuthority().getAsBoolean() || protection.capture().epoch() != next.protectionEpoch()
                || next.owner().policyGeneration() != next.nativePolicyGeneration()
                || !next.catalog().ready() || next.catalog().generation() != next.catalogGeneration()
                || provenance.session().filter(next.provenanceSession()::equals).isEmpty()) {
            status = "crop source, original native ownership or coherent admission unavailable"; return false;
        }
        int empty = -1;
        for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getStack(slot).isEmpty()) { empty = slot; break; }
        if (empty < 0) { status = "crop harvest needs an empty hotbar slot"; return false; }
        if (!next.budget().reserve()) { status = "crop job exhausted 32 cycles or ten active minutes"; return false; }
        admission = next; crop = work.crop(); plantingItem = ItemId.parse("minecraft:" + switch (crop) {
            case WHEAT -> "wheat_seeds"; case CARROT -> "carrot"; case POTATO -> "potato"; case BEETROOT -> "beetroot_seeds";
        });
        stockItems = Set.copyOf(List.of(next.step().output(), plantingItem));
        player = client.player; world = client.world; network = client.getNetworkHandler();
        connection = client.getNetworkHandler().getConnection(); menu = client.player.playerScreenHandler; input = client.player.input;
        origin = client.player.getBlockPos().toImmutable(); originalSlot = ClientAccess.selectedSlot(client.player.getInventory());
        selectedSlot = originalSlot;
        inputAvailability = InputAvailability.ORIGINAL; movementInputLease = null;
        target = null; matureState = farmlandState = null; before = beforePlant = beforeSwap = null;
        harvestSent = plantSent = swapSent = restoreSwapSent = swapConfirmed = plantRejected = plantAcknowledged = restoreSwapConfirmed = false;
        reservedPlanting = drainRequested = paused = rightsLost = navigationLost = timedOut = repairAllowedAtDrain = false;
        harvestReceipt = replantReceipt = manualRepairReceipt = breakSequence = plantSequence = swapSequence = 0;
        searchIndex = routeFailures = waitTicks = 0; swapSource = -1; pickup = null; pickupId = null;
        blocker = null; terminal = null; oldDrops.clear(); expectedSwap = null;
        airObserver = movementHandedOff = dropOverflow = false;
        started = lastTick = System.nanoTime(); pauseStarted = 0;
        if (!select(empty, false)) { phase = Phase.DRAIN; blocker = "crop hand admission changed before selection"; }
        else phase = Phase.SEARCH;
        status = "searching loaded mature " + crop.name().toLowerCase(Locale.ROOT);
        return true;
    }
    @Override public Outcome tick() {
        if (!active()) return terminal == null ? new Outcome.Drained() : terminal;
        if (!currentSession()) { abandonSession(); return terminal; }
        long now = System.nanoTime();
        if (paused) { started += Math.max(0, now - pauseStarted); lastTick = now; paused = false; }
        admission.budget().observe(now - lastTick); lastTick = now;
        observeRights(); observeStock();
        if (rightsLost) { phase = Phase.DRAIN; return drain(); }
        if (now - started >= CYCLE_NANOS || admission.budget().exhausted()) {
            timedOut = true; requestDrain(DrainReason.FAILURE);
            if (blocker == null) blocker = "crop cycle exceeded 30 seconds or its job budget";
        }
        if (airObserver) return new Outcome.Pending("observing crop evidence during owned air escape");
        if (inputAvailability == InputAvailability.UNAVAILABLE) return new Outcome.Pending("crop input identity witness unavailable; effects withheld");
        if (phase == Phase.DRAIN) return drain();
        String unsafe = unsafe();
        if (unsafe != null) { block(unsafe); return drain(); }
        try {
            switch (phase) {
                case SEARCH -> search();
                case APPROACH -> {
                    if (!matureTarget()) block("mature crop or farmland changed during approach");
                    else if (actions.hit(target) != null) { movement.stop(); phase = Phase.STOPPING; }
                    else if (movement.tick()) { routeFailures++; movement.stop(); phase = Phase.STOPPING; }
                }
                case STOPPING -> {
                    if (!quiesce()) break;
                    if (!matureTarget()) { block("crop changed before harvest"); break; }
                    if (actions.hit(target) != null) harvest();
                    else if (routeFailures >= 2) block("crop remains outside loaded visible interaction reach");
                    else approach();
                }
                case HARVEST_WAIT -> {
                    if (harvestReceipt > breakSequence) { phase = Phase.COLLECT; waitTicks = 0; }
                    else status = "observing the native crop break without resending";
                }
                case COLLECT -> collect();
                case PICKUP -> {
                    if (pickup == null || !pickupId.equals(pickup.getUuid())
                            || pickup.isAlive() && (!accepts(pickup) || pickup.getStack().getCount() > pickupCount)) {
                        block("pinned crop drop changed identity, components or permission"); break;
                    }
                    if (!pickup.isAlive() || plantingReady() && outputGained()) {
                        movement.stop(); if (movement.finishCancellation()) { phase = Phase.COLLECT; pickup = null; }
                    } else if (movement.tick()) { phase = Phase.COLLECT; pickup = null; }
                }
                case SWAP_WAIT -> {
                    if (swapObserved()) { swapConfirmed = true; phase = Phase.PLANT; }
                    else status = "observing the issued planting-stack swap without resending";
                }
                case PLANT -> plant();
                case PLANT_WAIT -> {
                    if (plantRejected) block("server rejected replant; harvested position remains unrepaired");
                    else if (plantConfirmed()) { phase = Phase.DRAIN; status = "crop replanted; draining hand ownership"; }
                    else status = "observing age-zero crop and exact ordinary planting debit without resending";
                }
                default -> { }
            }
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) loseNavigationRights();
            else block(failure.getMessage());
        } catch (RuntimeException failure) { block(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
        refreshCropMovementLease();
        return phase == Phase.DRAIN || phase == Phase.RESTORE_SWAP_WAIT ? drain() : new Outcome.Pending(status);
    }
    private void search() {
        if (!effectAdmission(false)) { block("crop search lost its admitted acquisition phase"); return; }
        int side = RADIUS * 2 + 1, volume = side * side * 9;
        for (int count = 0; count < SEARCH_PER_TICK && searchIndex < Math.min(volume, MAX_SEARCH); count++, searchIndex++) {
            int index = searchIndex;
            int x = index % side - RADIUS; index /= side;
            int z = index % side - RADIUS, y = index / side - 4;
            BlockPos pos = origin.add(x, y, z);
            if (origin.getSquaredDistance(pos) > RADIUS * RADIUS || !loaded(pos) || !loaded(pos.down())) continue;
            BlockState state = client.world.getBlockState(pos), soil = client.world.getBlockState(pos.down());
            if (!GameApi.cropMature(state, crop) || !soil.isOf(Blocks.FARMLAND)
                    || !protection.mayBreak(pos) || !protection.mayPlace(pos) || !protection.mayInteractBlock(pos.down())) continue;
            target = pos.toImmutable(); matureState = state; farmlandState = soil; approach(); return;
        }
        if (searchIndex >= Math.min(volume, MAX_SEARCH) || System.nanoTime() - started >= SEARCH_NANOS)
            block("no permitted mature crop on loaded farmland within the bounded search");
    }
    private void approach() {
        if (!effectAdmission(false) || !matureTarget()) { block("crop approach admission changed"); return; }
        movement.startInteraction(target, MovementController.RouteEffects.MOVEMENT_ONLY);
        movementInputLease = movement.captureCropInputLease(admission.owner(), admission.nativeSession(), admission.provenanceSession(), false);
        if (movementInputLease == null) { block("crop approach has no exact owned movement lease witness"); return; }
        phase = Phase.APPROACH; status = "approaching mature crop " + target;
    }
    private void harvest() {
        if (harvestSent) { phase = Phase.HARVEST_WAIT; return; }
        if (!effectAdmission(false) || !matureTarget() || !client.player.getMainHandStack().isEmpty()) {
            block("crop harvest or empty-hand admission changed"); return;
        }
        before = stock();
        if (before == null) { status = "waiting for coherent ordinary pre-harvest stock"; return; }
        if (!capacity(before)) { block("crop harvest lacks ordinary output and planting capacity"); return; }
        int seed = before.items().get(plantingItem).count();
        reservedPlanting = seed > plantingFloor();
        oldDrops.clear();
        for (ItemEntity item : drops()) {
            if (oldDrops.size() >= 128) { block("crop drop baseline exceeded 128 entities"); return; }
            oldDrops.put(item.getUuid(), item.getStack().getCount());
        }
        if (dropOverflow) { block("crop drop query exceeded 128 matching entities before harvest"); return; }
        if (!effectAdmission(false) || !matureTarget()) { block("crop admission changed before break send"); return; }
        boolean sent = actions.harvestCrop(target, matureState, () -> sendAdmission(false) && matureTarget(), () -> {
            breakSequence = provenance.serverReceiptSequence(); harvestSent = true; phase = Phase.HARVEST_WAIT;
        });
        if (!sent && !harvestSent) block("native crop break failed fresh admission before send");
    }
    private void collect() {
        if (!airTarget()) { block("harvested crop cell or original farmland changed before repair"); return; }
        if (!effectAdmission(false)) { requestDrain(DrainReason.PREEMPT); return; }
        List<ItemEntity> available = drops();
        if (dropOverflow) { block("crop drop query exceeded 128 matching entities"); return; }
        List<ItemEntity> fresh = available.stream().filter(item -> item.getStack().getCount() > oldDrops.getOrDefault(item.getUuid(), 0))
                .sorted(Comparator.<ItemEntity>comparingInt(item -> GameCatalog.id(item.getStack().getItem()).equals(plantingItem) ? 0 : 1)
                        .thenComparingDouble(client.player::squaredDistanceTo)).toList();
        if (!fresh.isEmpty()) {
            ItemEntity item = fresh.stream().filter(ItemEntity::isOnGround).findFirst().orElse(null);
            if (item == null) { status = "waiting for ordinary crop drops to land"; return; }
            pickup = item; pickupId = item.getUuid(); pickupCount = item.getStack().getCount();
            movement.startOwnedPickup(item, admission.provenanceSession(), MovementController.RouteEffects.MOVEMENT_ONLY);
            movementInputLease = movement.captureCropInputLease(admission.owner(), admission.nativeSession(), admission.provenanceSession(), true);
            if (movementInputLease == null) { block("crop pickup has no exact owned movement lease witness"); return; }
            phase = Phase.PICKUP; return;
        }
        if (plantingReady() && (outputGained() || ++waitTicks >= 20)) {
            if (!quiesce()) return;
            preparePlanting(); return;
        }
        if (++waitTicks >= 40) block("harvest has no observed unreserved ordinary planting unit; repair retained at " + target);
    }
    private void preparePlanting() {
        if (!reservedPlanting || !effectAdmission(true) || !airTarget()) {
            block("reserved planting unit or safe repair admission unavailable at " + target); return;
        }
        PlacementProvenance.JointStock live = stock();
        if (live == null) return;
        int source = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = live.slots().get(slot);
            if (!stack.isEmpty() && AnimalHarvestAction.ordinary(stack)
                    && GameCatalog.id(stack.getItem()).equals(plantingItem)) { source = slot; break; }
        }
        if (source < 0) { block("reserved ordinary planting stack disappeared"); return; }
        if (source < 9) {
            if (!select(source, true)) { block("planting hotbar selection lost admission"); return; }
            phase = Phase.PLANT; return;
        }
        beforeSwap = live; swapSource = source;
        expectedSwap = swapSnapshot(live.slots(), source, selectedSlot);
        if (!effectAdmission(true)) { block("planting swap lost fresh repair admission"); return; }
        actions.swapCropHand(source, selectedSlot, () -> sendAdmission(true), () -> {
            swapSequence = provenance.serverReceiptSequence(); swapSent = true; phase = Phase.SWAP_WAIT;
        });
        if (!swapSent) block("planting swap failed fresh admission before send");
    }
    private void plant() {
        if (plantSent) { phase = Phase.PLANT_WAIT; return; }
        if (!reservedPlanting || !effectAdmission(true) || !airTarget()) {
            block("safe replant admission unavailable at " + target); return;
        }
        beforePlant = stock();
        if (beforePlant == null) return;
        ItemStack held = beforePlant.slots().get(selectedSlot);
        if (held.isEmpty() || !AnimalHarvestAction.ordinary(held) || !GameCatalog.id(held.getItem()).equals(plantingItem)
                || beforePlant.items().get(plantingItem).count() <= plantingFloor()) {
            block("planting unit would cross the current shared stock floor"); return;
        }
        boolean sent = actions.replantCrop(target, plantingItem, () -> sendAdmission(true) && airTarget(), () -> {
            plantSequence = provenance.serverReceiptSequence(); plantSent = true; phase = Phase.PLANT_WAIT;
        });
        if (!sent && !plantSent) block("replant failed fresh loaded reach or mutation admission before send");
    }
    void serverBlock(PlacementProvenance.ServerBlockReceipt receipt) {
        if (!active() || admission == null || target == null || !receipt.session().equals(admission.provenanceSession())
                || !receipt.position().equals(target) || !currentSession()) return;
        if (harvestSent && !plantSent && receipt.sequence() > breakSequence) {
            if (receipt.state().isAir()) harvestReceipt = receipt.sequence();
            else if (GameApi.cropReplanted(receipt.state(), crop)) manualRepairReceipt = receipt.sequence();
        }
        if (plantSent && receipt.sequence() > plantSequence) {
            if (GameApi.cropReplanted(receipt.state(), crop)) { replantReceipt = receipt.sequence(); plantRejected = false; }
            else if (receipt.state().isAir()) plantRejected = true;
        }
    }
    private void observeStock() {
        if (!plantAcknowledged && provePlant()) plantAcknowledged = true;
        if (restoreSwapSent && !restoreSwapConfirmed && swapObserved()) restoreSwapConfirmed = true;
        if (before != null && harvestReceipt > breakSequence && !plantSent) plantingReady();
    }
    private int plantingFloor() {
        int requestedFloor = Math.max(0, floors.getOrDefault(plantingItem, 0));
        return before == null ? requestedFloor : Math.min(requestedFloor, before.items().get(plantingItem).count());
    }
    private boolean plantingReady() {
        PlacementProvenance.JointStock live = stock();
        if (live == null || before == null) return false;
        var seed = live.items().get(plantingItem);
        if (seed.count() > before.items().get(plantingItem).count() && seed.increaseSequence() > breakSequence
                && seed.count() > plantingFloor()) reservedPlanting = true;
        return reservedPlanting && seed.count() > plantingFloor();
    }
    private boolean outputGained() {
        PlacementProvenance.JointStock live = stock();
        if (live == null || before == null) return false;
        var output = live.items().get(admission.step().output());
        int debit = plantingItem.equals(admission.step().output()) ? 1 : 0;
        return output.count() > before.items().get(admission.step().output()).count() + debit
                && output.increaseSequence() > breakSequence;
    }
    private boolean plantConfirmed() {
        return plantAcknowledged && loaded(target) && loaded(target.down())
                && client.world.getBlockState(target.down()).equals(farmlandState)
                && GameApi.cropMatches(client.world.getBlockState(target), crop);
    }
    private boolean provePlant() {
        if (!plantSent || replantReceipt <= plantSequence || beforePlant == null || !loaded(target)
                || !loaded(target.down()) || !client.world.getBlockState(target.down()).equals(farmlandState)
                || !GameApi.cropMatches(client.world.getBlockState(target), crop)) return false;
        PlacementProvenance.JointStock live = stock();
        if (live == null || live.sequence() <= plantSequence) return false;
        var seed = live.items().get(plantingItem);
        if (seed.changeSequence() <= plantSequence || seed.count() != beforePlant.items().get(plantingItem).count() - 1) return false;
        List<ItemStack> expected = copy(beforePlant.slots());
        expected.get(selectedSlot).decrement(1);
        return sameInventory(live.slots(), expected);
    }
    private boolean swapObserved() {
        PlacementProvenance.JointStock live = stock();
        return live != null && live.sequence() > swapSequence && sameInventory(live.slots(), expectedSwap);
    }
    private Outcome drain() {
        if (!currentSession()) { abandonSession(); return terminal; }
        observeRights(); observeStock();
        if (airObserver) return new Outcome.Pending("retaining crop debt until owned air movement drains");
        if (inputAvailability == InputAvailability.UNAVAILABLE) return retained("crop input witness unavailable; retaining evidence without effects");
        try { if (!navigationLost && !quiesce()) return retained("crop movement cancellation remains pending"); }
        catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) loseNavigationRights();
            else return retained("crop cancellation needs attention " + failure.getMessage());
        }
        if (swapSent && !swapConfirmed) {
            if (!swapObserved()) return retained("issued planting swap remains uncertain; no resend");
            swapConfirmed = true;
        }
        if (restoreSwapSent && !restoreSwapConfirmed)
            return retained("crop hand restoration swap remains uncertain; no resend");
        if (harvestSent && harvestReceipt == 0)
            return retained("native crop break remains uncertain at " + target + "; no resend");
        if (plantSent && !plantConfirmed() && !plantRejected)
            return retained("native replant and exact seed debit remain uncertain at " + target + "; no resend");
        if (unrepaired()) {
            if (!plantSent && !rightsLost && reservedPlanting && repairAllowedAtDrain && !timedOut
                    && effectAdmission(true) && airTarget()) {
                preparePlanting();
                if (phase == Phase.PLANT) plant();
                if (phase == Phase.SWAP_WAIT || phase == Phase.PLANT_WAIT) return new Outcome.Pending(status);
            }
            return retained((blocker == null ? "harvested crop requires repair" : blocker) + " at " + target);
        }
        if (rightsLost) return retained("crop original authority lost; retaining original evidence and hand debt");
        if (swapSent) {
            if (!restoreSwapSent) {
                PlacementProvenance.JointStock live = stock();
                if (live == null) return retained("crop hand restoration lacks coherent full stock");
                List<ItemStack> expected = copy(beforeSwap.slots());
                if (plantSent && plantConfirmed()) expected.get(swapSource).decrement(1);
                List<ItemStack> stillSwapped = swapSnapshot(expected, swapSource, selectedSlot);
                if (!sameInventory(live.slots(), stillSwapped)) {
                    loseRights("planting layout changed; restoration rights relinquished"); return retained(blocker);
                }
                else if (!cleanupAdmission()) return retained("crop hand restoration awaits original safe ownership");
                else {
                    expectedSwap = expected;
                    actions.swapCropHand(swapSource, selectedSlot, () -> restorationAdmission(true), () -> {
                        swapSequence = provenance.serverReceiptSequence(); restoreSwapSent = true; phase = Phase.RESTORE_SWAP_WAIT;
                    });
                    return retained("observing crop hand layout restoration");
                }
            } else if (!restoreSwapConfirmed) return retained("crop hand restoration swap remains uncertain; no resend");
        }
        if (!cleanupAdmission()) return retained("crop drain awaits coherent stock and original safe cleanup authority");
        if (!rightsLost && originalSlot != selectedSlot) {
            if (!restorationAdmission(false) || !actions.selectSlot(originalSlot)) return retained("crop selection restoration awaits original safe ownership");
            selectedSlot = originalSlot;
        }
        PlacementProvenance.JointStock live = stock();
        var output = live.items().get(admission.step().output());
        ObservedStock observed = new ObservedStock(admission.provenanceSession(), admission.jobToken(), admission.catalogGeneration(),
                admission.step().output(), output.count(), output.increaseSequence());
        boolean positive = before != null && output.count() > before.items().get(admission.step().output()).count()
                && output.increaseSequence() > breakSequence;
        if (plantSent && plantConfirmed() && positive && !rightsLost)
            terminal = new Outcome.Yielded(observed, "repaired crop with coherent net ordinary stock");
        else if (blocker != null) terminal = new Outcome.Blocked(blocker + (target == null ? "" : " at " + target));
        else if (harvestSent) terminal = new Outcome.Yielded(observed, "repaired crop yielded no positive net requested output");
        else terminal = new Outcome.Drained();
        phase = Phase.DONE; return terminal;
    }
    private Outcome retained(String reason) {
        status = reason;
        return rightsLost || timedOut || blocker != null ? new Outcome.Blocked(reason) : new Outcome.Pending(reason);
    }
    boolean airObserver() { return airObserver; }
    boolean handOffMovementForAir() {
        observeRights();
        if (!active() || !currentSession() || navigationLost || rightsLost
                || inputAvailability == InputAvailability.UNAVAILABLE) return false;
        requestDrain(DrainReason.PREEMPT);
        if (airObserver) return true;
        movement.checkAirRecoveryOwnership();
        if (!movementHandedOff) movement.stop();
        if (!movement.finishCancellation()) { status = "finishing crop cancellation before air escape"; return false; }
        movementHandedOff = airObserver = true;
        return true;
    }
    void finishAirObservation() { airObserver = movementHandedOff = false; }

    @Override public void requestDrain(DrainReason reason) {
        if (!active()) return;
        if (!drainRequested) repairAllowedAtDrain = reservedPlanting && harvestSent;
        drainRequested = true; phase = Phase.DRAIN;
        status = "draining crop cycle for " + reason;
    }
    @Override public void pause() {
        if (!active()) return;
        if (!currentSession()) { abandonSession(); return; }
        observeRights(); observeStock();
        if (!paused) { paused = true; pauseStarted = System.nanoTime(); }
        if (inputAvailability == InputAvailability.UNAVAILABLE) return;
        try { if (!navigationLost && !movementHandedOff) movement.suspend(); }
        catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) loseNavigationRights();
            else throw failure;
        }
    }
    void abandonNavigationOwnership() { if (active()) loseNavigationRights(); }
    private void loseNavigationRights() {
        navigationLost = true; airObserver = movementHandedOff = false; loseRights("crop navigation ownership lost");
    }
    private void loseRights(String reason) { rightsLost = drainRequested = true; phase = Phase.DRAIN; blocker = reason; status = reason; }
    @Override public void abandonSession() {
        rightsLost = navigationLost = true; airObserver = movementHandedOff = false; phase = Phase.DONE; terminal = new Outcome.Drained();
        player = world = network = connection = menu = input = null; pickup = null;
        movementInputLease = null; inputAvailability = InputAvailability.UNAVAILABLE;
        status = "crop evidence abandoned with its original native session";
    }
    @Override public boolean safeToRelease() { return phase == Phase.DONE; }
    private boolean pendingEvidence() {
        return harvestSent && harvestReceipt == 0 || plantSent && !plantConfirmed() && !plantRejected
                || swapSent && !swapConfirmed || restoreSwapSent && !restoreSwapConfirmed;
    }
    private boolean unrepaired() {
        return harvestSent && !(plantSent && plantConfirmed())
                && !(manualRepairReceipt > breakSequence && loaded(target) && loaded(target.down())
                    && client.world.getBlockState(target.down()).equals(farmlandState)
                    && GameApi.cropMatches(client.world.getBlockState(target), crop));
    }
    private void block(String reason) {
        if (blocker == null) blocker = reason;
        if (!drainRequested) repairAllowedAtDrain = reservedPlanting && harvestSent;
        drainRequested = true; phase = Phase.DRAIN; status = reason;
    }
    private boolean currentSession() {
        return admission != null && client.player == player && client.world == world && network != null && connection != null
                && client.getNetworkHandler() == network && client.getNetworkHandler().getConnection() == connection
                && client.getNetworkHandler().getConnection().isOpen()
                && OwnedKernelRuntime.current() == admission.owner() && admission.owner().isCurrent(admission.nativeSession())
                && admission.nativeSession().world() == world
                && provenance.session().filter(admission.provenanceSession()::equals).isPresent();
    }
    private boolean effectAdmission(boolean repair) {
        observeRights();
        if (!currentSession() || rightsLost || inputAvailability == InputAvailability.UNAVAILABLE
                || paused || unsafe() != null || !admission.catalog().ready()
                || admission.catalog().generation() != admission.catalogGeneration()
                || repair && drainRequested && !repairAllowedAtDrain || !repair && drainRequested) return false;
        return (repair ? admission.repairEffects() : admission.newEffects()).getAsBoolean();
    }
    private boolean sendAdmission(boolean repair) {
        return effectAdmission(repair) && cleanupAdmission();
    }
    private boolean cleanupAdmission() {
        observeRights();
        if (!currentSession() || rightsLost || inputAvailability != InputAvailability.ORIGINAL
                || paused || airObserver || unsafe() != null
                || !admission.catalog().ready() || admission.catalog().generation() != admission.catalogGeneration()
                || !admission.cleanupEffects().getAsBoolean() || stock() == null || client.getNetworkHandler() == null
                || !client.getNetworkHandler().getConnection().isOpen() || manualInput() || client.player.input != input
                || ClientAccess.selectedSlot(client.player.getInventory()) != selectedSlot
                || client.currentScreen != null || client.player.currentScreenHandler != menu
                || !client.player.currentScreenHandler.getCursorStack().isEmpty() || client.player.isUsingItem()) return false;
        movement.checkAirRecoveryOwnership();
        return movement.finishCancellation() && actions.cropNativeQuiescent(admission.owner());
    }
    private boolean restorationAdmission(boolean swap) {
        if (!cleanupAdmission()) return false;
        PlacementProvenance.JointStock live = stock();
        if (live == null) return false;
        if (!swapSent) {
            if (!plantSent) return true;
            if (!plantConfirmed() || beforePlant == null) return false;
            List<ItemStack> expected = copy(beforePlant.slots());
            expected.get(selectedSlot).decrement(1);
            return sameInventory(live.slots(), expected);
        }
        return expectedSwap != null && (swap
                ? sameInventory(live.slots(), swapSnapshot(expectedSwap, swapSource, selectedSlot))
                : restoreSwapConfirmed && sameInventory(live.slots(), expectedSwap));
    }
    private boolean quiesce() {
        movement.checkAirRecoveryOwnership(); movement.stop();
        return movement.finishCancellation() && client.player.input == input && actions.cropNativeQuiescent(admission.owner());
    }
    private void observeRights() {
        if (!currentSession() || rightsLost) return;
        WorldProtection.PolicySnapshot policy = protection.capture();
        if (policy.epoch() != admission.protectionEpoch()
                || admission.owner().policyGeneration() != admission.nativePolicyGeneration()
                || !admission.ownedAuthority().getAsBoolean()) {
            loseRights("original crop phase or edit policy authority changed; later grants cannot revive this cycle"); return;
        }
        if (policy.locked() || !policy.allowBreak() || !policy.allowPlace()
                || target != null && (!policy.mayPlace(target.getX(), target.getY(), target.getZ())
                    || !policy.mayPlace(target.getX(), target.getY() - 1, target.getZ())
                    || !harvestSent && !policy.mayBreak(target.getX(), target.getY(), target.getZ()))) {
            loseRights("original crop edit permission denied; later grants cannot revive this cycle"); return;
        }
        inputAvailability = observeInputAvailability();
        if (inputAvailability == InputAvailability.FOREIGN) {
            loseRights("foreign crop input replacement; restoration cannot revive this cycle"); return;
        }
        if (manualInput() || ClientAccess.selectedSlot(client.player.getInventory()) != selectedSlot
                || client.currentScreen != null || client.player.currentScreenHandler != menu
                || !client.player.currentScreenHandler.getCursorStack().isEmpty()) loseRights("manual crop hand or screen takeover has priority");
    }
    private InputAvailability observeInputAvailability() {
        if (!currentSession() || input == null) return InputAvailability.UNAVAILABLE;
        if (client.player.input == null) return InputAvailability.FOREIGN;
        Object observed = client.player.input;
        if (observed == input) return InputAvailability.ORIGINAL;
        try {
            Object airPredecessor = null;
            var airLease = admission.airRecovery().cropInputLease(admission.owner(), admission.nativeSession(), player, world);
            if (airLease != null && (airObserver
                    || movement.observeInputLease(airLease) == MovementController.InputLeaseState.RESTORING))
                movementInputLease = airLease;
            if (airObserver) {
                airPredecessor = admission.airRecovery().cropInputPredecessor(
                        admission.owner(), admission.nativeSession(), player, world, observed);
            }
            if (airPredecessor == input) return InputAvailability.OWNED_AIR;
            Object installed = airPredecessor == null ? observed : airPredecessor;
            if (!actions.cropMovementInputWitness(admission.owner(), admission.nativeSession(), observed, installed, input))
                return InputAvailability.FOREIGN;
            if (movementInputLease == null) return InputAvailability.UNAVAILABLE;
            var state = movement.observeInputLease(movementInputLease);
            if (state == MovementController.InputLeaseState.LOST) return InputAvailability.FOREIGN;
            if (airPredecessor != null) return InputAvailability.OWNED_AIR;
            return state == MovementController.InputLeaseState.ACTIVE
                    ? InputAvailability.OWNED_MOVEMENT : InputAvailability.RESTORING;
        } catch (RuntimeException unavailable) { return InputAvailability.UNAVAILABLE; }
    }
    private void refreshCropMovementLease() {
        if (airObserver || admission == null || phase != Phase.APPROACH && phase != Phase.PICKUP) return;
        var current = movement.captureCropInputLease(admission.owner(), admission.nativeSession(),
                admission.provenanceSession(), phase == Phase.PICKUP);
        if (current != null) movementInputLease = current;
    }
    private boolean select(int slot, boolean repair) {
        if (!effectAdmission(repair) || !quiesce() || !sendAdmission(repair)) return false;
        if (!actions.selectSlot(slot)) return false;
        selectedSlot = slot; return true;
    }
    private boolean loaded(BlockPos pos) {
        if (pos == null || client.world == null || client.world.isOutOfHeightLimit(pos.getY())
                || !client.world.getWorldBorder().contains(pos)) return false;
        WorldRevision.watch(pos.getX() >> 4, pos.getZ() >> 4);
        return client.world.isChunkLoaded(pos);
    }
    private boolean matureTarget() {
        return target != null && loaded(target) && loaded(target.down())
                && client.world.getBlockState(target).equals(matureState) && GameApi.cropMature(matureState, crop)
                && client.world.getBlockState(target.down()).equals(farmlandState) && farmlandState.isOf(Blocks.FARMLAND)
                && protection.mayBreak(target) && protection.mayPlace(target) && protection.mayInteractBlock(target.down())
                && actions.cropRepairPermitted(target);
    }
    private boolean airTarget() {
        return target != null && loaded(target) && loaded(target.down()) && client.world.getBlockState(target).isAir()
                && client.world.getBlockState(target).getFluidState().isEmpty()
                && client.world.getBlockState(target.down()).equals(farmlandState)
                && protection.mayPlace(target) && protection.mayInteractBlock(target.down());
    }
    private PlacementProvenance.JointStock stock() {
        if (!currentSession() || stockItems == null) return null;
        return provenance.confirmedOrdinaryJointStock(stockItems)
                .filter(receipt -> receipt.session().equals(admission.provenanceSession())).orElse(null);
    }
    private List<ItemEntity> drops() {
        if (target == null || !loaded(target)) return List.of();
        int[] matches = {0}; dropOverflow = false;
        return client.world.getEntitiesByClass(ItemEntity.class, new Box(target).expand(4), item -> {
            if (!accepts(item)) return false;
            if (++matches[0] > 128) { dropOverflow = true; return false; }
            return true;
        });
    }
    private boolean accepts(ItemEntity item) {
        return item != null && item.isAlive() && AnimalHarvestAction.ordinary(item.getStack())
                && stockItems.contains(GameCatalog.id(item.getStack().getItem())) && protection.mayPickupDrop(item)
                && origin.getSquaredDistance(item.getBlockPos()) <= RADIUS * RADIUS;
    }
    private String unsafe() {
        if (client.player == null || client.world == null || client.interactionManager == null || client.getNetworkHandler() == null
                || client.getNetworkHandler().getConnection() == null || !client.getNetworkHandler().getConnection().isOpen()) return "crop world or connection unavailable";
        var p = client.player;
        if (!p.isAlive() || !Float.isFinite(p.getHealth()) || p.getHealth() <= config.pauseBelowHealth
                || p.getAbilities().creativeMode || p.isSpectator() || p.isOnFire() || p.isInLava()) return "crop survival or health safeguard";
        if (p.hasVehicle() || p.isUsingItem() || p.isSneaking() || client.currentScreen != null
                || p.currentScreenHandler != p.playerScreenHandler || !p.currentScreenHandler.getCursorStack().isEmpty()) return "crop player hand or screen unavailable";
        return manualInput() ? "manual player input has priority" : null;
    }
    private boolean manualInput() {
        var o = client.options;
        return o.attackKey.isPressed() || o.useKey.isPressed() || o.forwardKey.isPressed() || o.backKey.isPressed()
                || o.leftKey.isPressed() || o.rightKey.isPressed() || o.jumpKey.isPressed() || o.sneakKey.isPressed() || o.sprintKey.isPressed();
    }
    private boolean capacity(PlacementProvenance.JointStock stock) {
        List<ItemStack> simulated = copy(stock.slots());
        for (ItemId item : List.of(plantingItem, admission.step().output())) {
            ItemStack ordinary = new ItemStack(GameCatalog.item(item));
            boolean fit = false;
            for (ItemStack slot : simulated) {
                if (!slot.isEmpty() && GameApi.canCombine(slot, ordinary) && slot.getCount() < slot.getMaxCount()) {
                    slot.increment(1); fit = true; break;
                }
            }
            if (!fit) for (int slot = 0; slot < simulated.size(); slot++) if (simulated.get(slot).isEmpty()) {
                simulated.set(slot, ordinary); fit = true; break;
            }
            if (!fit) return false;
        }
        return true;
    }
    private static List<ItemStack> copy(List<ItemStack> slots) { return slots.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); }
    private static List<ItemStack> swapSnapshot(List<ItemStack> slots, int source, int hotbar) {
        List<ItemStack> result = copy(slots); ItemStack seed = result.get(source);
        result.set(source, result.get(hotbar)); result.set(hotbar, seed); return result;
    }
    private static boolean sameInventory(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) return false;
        for (int slot = 0; slot < left.size(); slot++) if (!ItemStack.areEqual(left.get(slot), right.get(slot))) return false;
        return true;
    }
}
