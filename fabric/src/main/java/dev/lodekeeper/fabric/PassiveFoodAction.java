package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Acquires one verified edible drop from a nearby ordinary cow, pig, or sheep. */
final class PassiveFoodAction {
    private static final double MAX_DISTANCE = 32.0;
    private static final double MAX_DISTANCE_SQUARED = MAX_DISTANCE * MAX_DISTANCE;
    private static final double DROP_RADIUS = 8.0;
    private static final long MAX_DURATION_NANOS = 60_000_000_000L;
    private static final long FAILED_APPROACH_COOLDOWN_NANOS = 30_000_000_000L;
    private static final int MAX_FAILED_APPROACHES = 128;
    private static final int MAX_FAILED_ROUTES = 4;
    private static final int MAX_ATTACKS = 32;
    private static final Map<UUID, Long> FAILED_APPROACH_UNTIL = new LinkedHashMap<>();
    private static WeakReference<Object> cooldownWorld = new WeakReference<>(null);

    private enum Phase {
        IDLE, APPROACH, STOPPING_FOR_ATTACK, ATTACK, WAITING_FOR_DROP, PICKUP,
        COMPLETING, COMPLETE, STOPPED
    }

    private record Meat(Item raw, Item cooked, String animal) {
        List<Item> foods() { return List.of(raw, cooked); }
        boolean accepts(Item item) { return item == raw || item == cooked; }
    }

    private record Candidate(AnimalEntity entity, Meat meat) { }

    private static final Meat BEEF = new Meat(Items.BEEF, Items.COOKED_BEEF, "cow");
    private static final Meat PORK = new Meat(Items.PORKCHOP, Items.COOKED_PORKCHOP, "pig");
    private static final Meat MUTTON = new Meat(Items.MUTTON, Items.COOKED_MUTTON, "sheep");

    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final MovementController movement;

    private Phase phase = Phase.IDLE;
    private String status = "passive food idle";
    private Candidate target;
    private BlockPos deathPosition;
    private Map<Item, Integer> startingFoodCounts = Map.of();
    private double originX;
    private double originY;
    private double originZ;
    private long startedAtNanos;
    private int approachCount;
    private int failedRouteCount;
    private float observedTargetHealth;
    private int attackCount;
    private int originalSlot = -1;
    private int selectedSlot = -1;
    private boolean attackSelectionSettled;
    private Map<ItemId, Integer> protection = Map.of();
    private Item acquiredFood;
    private boolean attacked;
    private boolean recoveryMode;
    private Object owningPlayer;
    private Object actionWorld;

    PassiveFoodAction(MinecraftClient client, LodekeeperConfig config, PlayerActions actions,
                      MovementController movement) {
        this.client = client;
        this.config = config;
        this.actions = actions;
        this.movement = movement;
    }

    void updateProtection(Map<ItemId, Integer> reserved) {
        if (reserved == null || reserved.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0))
            throw new IllegalArgumentException("invalid passive food protection counts");
        protection = Map.copyOf(reserved);
    }

    boolean ready() {
        if (active()) return false;
        String reason = unsafeContextReason(false);
        if (reason != null) {
            status = reason;
            return false;
        }
        var player = client.player;
        if (nearestTarget(player.getX(), player.getY(), player.getZ()) == null) {
            status = "no safe cow, pig, or sheep within 32 blocks";
            return false;
        }
        status = "safe passive food target nearby";
        return true;
    }

    boolean ready(boolean recovery) {
        if (active() || unsafeContextReason(recovery) != null) return false;
        var player = client.player;
        return nearestTarget(player.getX(), player.getY(), player.getZ()) != null;
    }

    boolean begin() {
        if (active() || !ready()) return false;
        return beginPrepared(false);
    }

    boolean begin(boolean recovery) {
        if (active() || !ready(recovery)) return false;
        return beginPrepared(recovery);
    }

    private boolean beginPrepared(boolean recovery) {
        pruneFailedApproaches();
        var player = client.player;
        originX = player.getX();
        originY = player.getY();
        originZ = player.getZ();
        target = nearestTarget(originX, originY, originZ);
        if (target == null) {
            status = "no safe cow, pig, or sheep within 32 blocks";
            return false;
        }

        originalSlot = ClientAccess.selectedSlot(player.getInventory());
        int weaponSlot = chooseWeaponSlot(originalSlot);
        if (weaponSlot < 0) {
            clearOwnership();
            status = "no safe weapon or empty hand slot is available";
            return false;
        }
        if (weaponSlot >= 0 && weaponSlot != originalSlot && !actions.selectSlot(weaponSlot)) {
            clearOwnership();
            status = "could not safely select the existing weapon";
            return false;
        }
        selectedSlot = ClientAccess.selectedSlot(player.getInventory());
        startingFoodCounts = snapshotFoodCounts(target.meat());
        startedAtNanos = System.nanoTime();
        approachCount = 0;
        failedRouteCount = 0;
        observedTargetHealth = target.entity().getHealth();
        attackCount = 0;
        attacked = false;
        deathPosition = null;
        acquiredFood = null;
        recoveryMode = recovery;
        owningPlayer = player;
        actionWorld = currentWorld();
        phase = Phase.APPROACH;
        status = "approaching " + target.meat().animal();
        try {
            startApproach();
            return true;
        } catch (RuntimeException failure) {
            throw abort("could not start the approach route", failure);
        }
    }

    boolean active() {
        return switch (phase) {
            case APPROACH, STOPPING_FOR_ATTACK, ATTACK, WAITING_FOR_DROP, PICKUP, COMPLETING -> true;
            default -> false;
        };
    }

    boolean tick() {
        if (phase == Phase.COMPLETE) return true;
        if (!active()) return false;
        try {
            if (owningPlayer != client.player || actionWorld != currentWorld()) {
                throw new IllegalStateException("player or world changed during passive food action");
            }
            String reason = unsafeContextReason(recoveryMode);
            if (reason != null) throw new IllegalStateException(reason);
            if (System.nanoTime() - startedAtNanos >= MAX_DURATION_NANOS) {
                throw new IllegalStateException("passive food action exceeded 60 seconds");
            }
            if (!withinOrigin(client.player.getX(), client.player.getY(), client.player.getZ())) {
                throw new IllegalStateException("passive food route exceeded 32 blocks from its start");
            }

            if (attacked && target != null) {
                float health = target.entity().getHealth();
                if (Float.isFinite(health) && health < observedTargetHealth) {
                    observedTargetHealth = health;
                    failedRouteCount = 0;
                    movement.recordConfirmedWorldAction();
                    logPursuit("health-progress");
                }
            }

            return switch (phase) {
                case APPROACH -> tickApproach();
                case STOPPING_FOR_ATTACK -> tickStoppingForAttack();
                case ATTACK -> tickAttack();
                case WAITING_FOR_DROP -> tickWaitingForDrop();
                case PICKUP -> tickPickup();
                case COMPLETING -> tickCompleting();
                default -> false;
            };
        } catch (RuntimeException failure) {
            if (phase == Phase.STOPPED) throw failure;
            throw abort("passive food action failed: " + diagnostic(failure), failure);
        }
    }

    void abandonNavigationOwnership() {
        recoveryMode = false;
        clearOwnership();
        phase = Phase.STOPPED;
        status = "passive food action lost movement ownership";
    }

    void stop() {
        recoveryMode = false;
        if (!active()) { clearOwnership(); return; }
        boolean cancelled;
        try {
            cancelled = cancelMovement();
        } catch (MovementController.NavigationFailure failure) {
            throw abort("passive food action lost movement ownership", failure);
        }
        restoreSelection();
        clearOwnership();
        phase = Phase.STOPPED;
        status = cancelled ? "passive food action stopped"
                : "passive food action stopped; movement cancellation remains pending";
    }

    String status() { return status; }

    private boolean tickApproach() {
        if (targetDead() && attacked) {
            movement.stop();
            phase = Phase.STOPPING_FOR_ATTACK;
            status = "stopping pursuit after the native attack";
            return false;
        }
        if (!targetStillEligible()) throw new IllegalStateException("tracked animal changed or became unsafe before attack");
        if (canHitTarget()) {
            logPursuit("in-reach");
            movement.stop();
            phase = Phase.STOPPING_FOR_ATTACK;
            status = "stopping movement before attack";
            return false;
        }
        try {
            if (!movement.tick()) return false;
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.PROCESS_ENDED) throw failure;
        }
        failedRouteCount++;
        logPursuit("route-failed");
        movement.stop();
        phase = Phase.STOPPING_FOR_ATTACK;
        status = "waiting for movement cancellation before retrying pursuit";
        return false;
    }

    private boolean tickStoppingForAttack() {
        movement.stop();
        if (!movement.finishCancellation()) return false;
        if (targetDead() && attacked) {
            beginCollection();
            return false;
        }
        if (!targetStillEligible()) throw new IllegalStateException("tracked animal changed or became unsafe before attack");
        if (canHitTarget()) {
            phase = Phase.ATTACK;
            status = "attacking " + target.meat().animal();
            return false;
        }
        startApproach();
        return false;
    }

    private boolean tickAttack() {
        int currentSlot = ClientAccess.selectedSlot(client.player.getInventory());
        if (attackSelectionSettled && currentSlot != selectedSlot)
            throw new IllegalStateException("manual hotbar selection has priority over hunting");
        if (targetDead()) {
            if (!attacked) throw new IllegalStateException("tracked animal died before a native attack was sent");
            beginCollection();
            return false;
        }
        if (!targetStillEligible()) throw new IllegalStateException("tracked animal changed or became unsafe during attack");
        if (!canHitTarget()) {
            startApproach();
            return false;
        }
        if (attackCount >= MAX_ATTACKS) throw new IllegalStateException("native attacks made no verified progress");
        int attackSlot = chooseWeaponSlot(currentSlot);
        if (attackSlot < 0) throw new IllegalStateException("no safe attack stack remains available");
        if (currentSlot != attackSlot || selectedSlot != attackSlot) {
            if (!actions.selectSlot(attackSlot))
                throw new IllegalStateException("could not safely select the attack stack");
            selectedSlot = ClientAccess.selectedSlot(client.player.getInventory());
            attackSelectionSettled = true;
            status = "waiting for the selected attack stack";
            return false;
        }
        attackSelectionSettled = true;
        // Native hand attributes and attack cooldown must observe a new slot on the next tick.
        if (!(client.player.getAttackCooldownProgress(0.0f) >= 1.0f)) {
            status = "waiting for native attack cooldown";
            return false;
        }

        logAttackStack();
        actions.look(target.entity().getBoundingBox().getCenter());
        client.interactionManager.attackEntity(client.player, target.entity());
        client.player.swingHand(Hand.MAIN_HAND);
        attacked = true;
        attackCount++;
        logPursuit("native-attack");
        status = "attacking " + target.meat().animal() + " (" + attackCount + ")";
        return false;
    }

    private boolean tickWaitingForDrop() {
        Item gained = gainedFood();
        if (gained != null) return finishAfterGain(gained);

        ItemEntity item = nearestFoodDrop();
        if (item == null) {
            status = "waiting for a matching edible drop";
            return false;
        }
        movement.startPickup(item);
        phase = Phase.PICKUP;
        status = "collecting " + GameCatalog.id(item.getStack().getItem());
        return false;
    }

    private boolean tickPickup() {
        Item gained = gainedFood();
        if (gained != null) return finishAfterGain(gained);
        if (!movement.tick()) return false;
        gained = gainedFood();
        if (gained == null) throw new IllegalStateException("pickup route ended without an inventory food increase");
        acquiredFood = gained;
        complete();
        return true;
    }

    private boolean tickCompleting() {
        movement.stop();
        if (!movement.finishCancellation()) return false;
        complete();
        return true;
    }

    private void beginCollection() {
        deathPosition = target.entity().getBlockPos().toImmutable();
        phase = Phase.WAITING_FOR_DROP;
        status = "checking the actual meat drop";
    }

    private boolean finishAfterGain(Item item) {
        acquiredFood = item;
        movement.stop();
        if (!movement.finishCancellation()) {
            phase = Phase.COMPLETING;
            status = "food gained; finishing movement cancellation";
            return false;
        }
        complete();
        return true;
    }

    private void complete() {
        restoreSelection();
        recoveryMode = false;
        clearOwnership();
        phase = Phase.COMPLETE;
        status = "acquired " + GameCatalog.id(acquiredFood);
    }

    private void startApproach() {
        if (attackSelectionSettled && ClientAccess.selectedSlot(client.player.getInventory()) != selectedSlot)
            throw new IllegalStateException("manual hotbar selection has priority over hunting");
        attackSelectionSettled = false;
        if (failedRouteCount >= MAX_FAILED_ROUTES) failUnreachableTarget();
        if (!targetStillEligible()) throw new IllegalStateException("tracked animal is no longer safe to approach");
        if (!withinOrigin(target.entity().getX(), target.entity().getY(), target.entity().getZ())) {
            throw new IllegalStateException("tracked animal moved beyond the 32-block action limit");
        }
        movement.startFollowing(target.entity(), animal -> target != null && animal == target.entity() && targetStillEligible());
        approachCount++;
        phase = Phase.APPROACH;
        status = "pursuing " + target.meat().animal() + " (" + approachCount + ")";
        logPursuit("start");
    }

    private boolean canHitTarget() {
        return withinReach(target.entity()) && client.player.canSee(target.entity());
    }

    private boolean withinReach(LivingEntity entity) {
        if (client.interactionManager == null) return false;
        double reach = Math.min(3.0, GameApi.blockReach(client));
        if (!Double.isFinite(reach) || reach <= 0.0) return false;
        return closestBoxDistanceSquared(entity) <= reach * reach;
    }

    private double closestBoxDistanceSquared(LivingEntity entity) {
        Box box = entity.getBoundingBox();
        Vec3d eye = client.player.getEyePos();
        double dx = Math.max(box.minX - eye.x, Math.max(0.0, eye.x - box.maxX));
        double dy = Math.max(box.minY - eye.y, Math.max(0.0, eye.y - box.maxY));
        double dz = Math.max(box.minZ - eye.z, Math.max(0.0, eye.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    private void failUnreachableTarget() {
        if (targetStillEligible()) {
            coolDownFailedTarget();
            logApproachFailure();
        }
        throw new IllegalStateException("animal remained outside safe hit reach");
    }

    private void logApproachFailure() {
        if (!config.debugLogging) return;
        logPursuit("failed");
        var player = client.player;
        var animal = target.entity();
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] FOOD_APPROACH_FAILED approaches={} playerFeet=({},{},{}) animalFeet=({},{},{}) closestBoxDistance={} lineOfSight={}",
                approachCount, round2(player.getX()), round2(player.getY()), round2(player.getZ()),
                round2(animal.getX()), round2(animal.getY()), round2(animal.getZ()),
                round2(Math.sqrt(closestBoxDistanceSquared(animal))), player.canSee(animal));
    }

    private void logPursuit(String event) {
        if (!config.debugLogging || target == null || client.player == null) return;
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] FOOD_PURSUIT event={} target={} routes={} failedRoutes={} attacks={} health={} closestBoxDistance={} elapsedMs={}",
                event, target.entity().getUuid(), approachCount, failedRouteCount, attackCount,
                target.entity().getHealth(), round2(Math.sqrt(closestBoxDistanceSquared(target.entity()))),
                (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean targetStillEligible() {
        if (target == null) return false;
        AnimalEntity animal = target.entity();
        return actionWorld == currentWorld() && client.world != null
                && client.world.getEntityById(animal.getId()) == animal
                && !targetDead() && eligible(animal) && profileFor(animal) == target.meat()
                && withinOrigin(animal.getX(), animal.getY(), animal.getZ());
    }

    private boolean targetDead() {
        return target != null && target.entity().getHealth() <= 0.0f;
    }

    private boolean eligible(AnimalEntity animal) {
        if (profileFor(animal) == null || !animal.isAlive() || !Float.isFinite(animal.getHealth())
                || animal.getHealth() <= 0.0f || animal.hasCustomName() || animal.isBaby()
                || animal.isLeashed() || animal.isOnFire() || animal.isInLava() || animal.hasVehicle()) return false;
        return !(animal instanceof TameableEntity tameable) || !tameable.isTamed();
    }

    private Meat profileFor(AnimalEntity animal) {
        if (animal.getClass() == CowEntity.class) return BEEF;
        if (animal.getClass() == PigEntity.class) return PORK;
        if (animal.getClass() == SheepEntity.class) return MUTTON;
        return null;
    }

    private Candidate nearestTarget(double fromX, double fromY, double fromZ) {
        if (client.player == null || client.world == null) return null;
        Box area = client.player.getBoundingBox().expand(MAX_DISTANCE);
        return client.world.getEntitiesByClass(AnimalEntity.class, area, animal ->
                        eligible(animal) && !isCoolingDown(animal.getUuid())
                                && withinOrigin(animal.getX(), animal.getY(), animal.getZ(), fromX, fromY, fromZ))
                .stream()
                .map(animal -> new Candidate(animal, profileFor(animal)))
                .min(Comparator.comparingDouble(candidate -> distanceSquared(
                        candidate.entity().getX(), candidate.entity().getY(), candidate.entity().getZ(),
                        fromX, fromY, fromZ)))
                .orElse(null);
    }

    private void pruneFailedApproaches() {
        if (cooldownWorld.get() != client.world) {
            FAILED_APPROACH_UNTIL.clear();
            cooldownWorld = new WeakReference<>(client.world);
        }
        long now = System.nanoTime();
        FAILED_APPROACH_UNTIL.entrySet().removeIf(entry -> now - entry.getValue() >= 0L);
    }

    private boolean isCoolingDown(UUID entityId) {
        if (cooldownWorld.get() != currentWorld()) return false;
        Long until = FAILED_APPROACH_UNTIL.get(entityId);
        return until != null && System.nanoTime() - until < 0L;
    }

    private void coolDownFailedTarget() {
        if (target == null || !targetStillEligible()) return;
        pruneFailedApproaches();
        FAILED_APPROACH_UNTIL.put(target.entity().getUuid(),
                System.nanoTime() + FAILED_APPROACH_COOLDOWN_NANOS);
        while (FAILED_APPROACH_UNTIL.size() > MAX_FAILED_APPROACHES) {
            Iterator<UUID> oldest = FAILED_APPROACH_UNTIL.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private ItemEntity nearestFoodDrop() {
        if (client.world == null || deathPosition == null || target == null || client.player == null) return null;
        double x = deathPosition.getX() + 0.5;
        double y = deathPosition.getY() + 0.5;
        double z = deathPosition.getZ() + 0.5;
        Box area = new Box(x - DROP_RADIUS, y - DROP_RADIUS, z - DROP_RADIUS,
                x + DROP_RADIUS, y + DROP_RADIUS, z + DROP_RADIUS);
        return client.world.getEntitiesByClass(ItemEntity.class, area, item ->
                        item.isAlive() && withinOrigin(item.getX(), item.getY(), item.getZ())
                                && safeFood(item.getStack()) && target.meat().accepts(item.getStack().getItem()))
                .stream()
                .min(Comparator.comparingDouble(client.player::squaredDistanceTo))
                .orElse(null);
    }

    private boolean safeFood(ItemStack stack) {
        if (stack.isEmpty() || target == null || !target.meat().accepts(stack.getItem())) return false;
        GameApi.FoodInfo food = GameApi.food(stack);
        return food != null && food.nutrition() > 0 && food.safe();
    }

    private Map<Item, Integer> snapshotFoodCounts(Meat meat) {
        Map<Item, Integer> counts = new HashMap<>();
        for (Item item : meat.foods()) counts.put(item, actions.count(item));
        return Map.copyOf(counts);
    }

    private Item gainedFood() {
        if (target == null || startingFoodCounts.isEmpty()) return null;
        for (Item item : target.meat().foods()) {
            if (actions.count(item) > startingFoodCounts.getOrDefault(item, 0)) return item;
        }
        return null;
    }

    private int chooseWeaponSlot(int selected) {
        var inventory = client.player.getInventory();
        int best = -1;
        double damage = Double.NEGATIVE_INFINITY;
        boolean sweepCollateral = target != null
                && GameApi.defenseHasSweepCollateral(client.world, client.player, target.entity());
        for (int offset = 0; offset < 9; offset++) {
            int slot = (selected + offset) % 9;
            ItemStack stack = inventory.getStack(slot);
            if (!safeAttackStack(stack) || sweepCollateral && GameApi.isSword(stack)) continue;
            double candidate = GameApi.defenseAttackDamage(client.player, stack);
            if (Double.isFinite(candidate) && candidate > damage) { best = slot; damage = candidate; }
        }
        return best;
    }

    private boolean safeAttackStack(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (stack.hasEnchantments() || GameApi.hasCustomName(stack)
                || protection.getOrDefault(GameCatalog.id(stack.getItem()), 0) > 0) return false;
        ItemStack normalized = stack.copy(), ordinary = new ItemStack(stack.getItem());
        if (stack.isDamageable()) {
            int wear = GameApi.defenseAttackWear(stack);
            int reserve = GameApi.isSword(stack) || GameApi.isAxe(stack) ? 2 : 16;
            if (wear < 0 || stack.getMaxDamage() - stack.getDamage() - wear < reserve) return false;
            normalized.setDamage(0); ordinary.setDamage(0);
        }
        return GameApi.canCombine(normalized, ordinary);
    }

    private void logAttackStack() {
        if (!config.debugLogging || client.player == null || selectedSlot < 0) return;
        ItemStack stack = client.player.getInventory().getStack(selectedSlot);
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] FOOD_TOOL slot={} item={} damage={} wear={} remainingDurability={}",
                selectedSlot, stack.isEmpty() ? "empty" : GameCatalog.id(stack.getItem()),
                GameApi.defenseAttackDamage(client.player, stack),
                stack.isDamageable() ? GameApi.defenseAttackWear(stack) : 0,
                stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : 0);
    }

    private String unsafeContextReason(boolean recovery) {
        if (client.player == null || client.world == null || client.interactionManager == null) return "world unavailable";
        var player = client.player;
        float health = player.getHealth();
        float healthFloor = recovery ? Math.min(3.0f, config.pauseBelowHealth) : config.pauseBelowHealth;
        if (!player.isAlive() || !Float.isFinite(health) || health <= 0.0f || health <= healthFloor) {
            return "player health safeguard";
        }
        if (player.isOnFire() || player.isInLava()) return "player is in a fire or lava hazard";
        if (player.getAbilities().creativeMode || player.isSpectator()) return "passive food action requires survival play";
        if (player.hasVehicle() || player.isUsingItem()) return "player is riding or using an item";
        if (client.currentScreen != null || player.currentScreenHandler != player.playerScreenHandler
                || !player.currentScreenHandler.getCursorStack().isEmpty()) return "inventory screen or cursor is not safe";
        if (manualInput()) return "manual player input has priority";
        return null;
    }

    private Object currentWorld() {
        return client.world;
    }

    private boolean manualInput() {
        var options = client.options;
        return options.attackKey.isPressed() || options.useKey.isPressed()
                || options.forwardKey.isPressed() || options.backKey.isPressed()
                || options.leftKey.isPressed() || options.rightKey.isPressed()
                || options.jumpKey.isPressed() || options.sneakKey.isPressed() || options.sprintKey.isPressed();
    }

    private boolean withinOrigin(double x, double y, double z) {
        return withinOrigin(x, y, z, originX, originY, originZ);
    }

    private static boolean withinOrigin(double x, double y, double z, double fromX, double fromY, double fromZ) {
        return distanceSquared(x, y, z, fromX, fromY, fromZ) <= MAX_DISTANCE_SQUARED;
    }

    private static double distanceSquared(double x, double y, double z, double fromX, double fromY, double fromZ) {
        double dx = x - fromX;
        double dy = y - fromY;
        double dz = z - fromZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private IllegalStateException abort(String reason, RuntimeException cause) {
        logPursuit("abort");
        boolean cancelled;
        try {
            cancelled = cancelMovement();
        } catch (MovementController.NavigationFailure failure) {
            cause = failure;
            cancelled = true;
        }
        boolean ownershipLost = cause instanceof MovementController.NavigationFailure failure
                && failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST;
        if (!ownershipLost) restoreSelection();
        recoveryMode = false;
        clearOwnership();
        phase = Phase.STOPPED;
        if (!cancelled) reason += "; movement cancellation remains pending";
        status = reason.length() > 180 ? reason.substring(0, 180) : reason;
        if (ownershipLost) return (MovementController.NavigationFailure) cause;
        return cause == null ? new IllegalStateException(status) : new IllegalStateException(status, cause);
    }

    private void clearOwnership() {
        target = null;
        owningPlayer = null;
        actionWorld = null;
        originalSlot = selectedSlot = -1;
        attackSelectionSettled = false;
    }

    private boolean cancelMovement() {
        try {
            movement.stop();
            return movement.finishCancellation();
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void restoreSelection() {
        if (client.player == null || client.player != owningPlayer || currentWorld() != actionWorld
                || originalSlot < 0 || selectedSlot < 0
                || selectedSlot == originalSlot || manualInput() || client.currentScreen != null
                || ClientAccess.selectedSlot(client.player.getInventory()) != selectedSlot) return;
        actions.selectSlot(originalSlot);
    }

    private static String diagnostic(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
