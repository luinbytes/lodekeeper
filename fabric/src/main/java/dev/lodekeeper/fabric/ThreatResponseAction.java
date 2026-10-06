package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Caller-owned inventory arbitration; native movement and attacks provide all effects. */
final class ThreatResponseAction {
    private record RetreatRoute(List<BlockPos> threats, List<BlockPos> goals) { }
    private enum Phase { IDLE, STOPPING, MELEE, CONTACT_WAIT, RETREAT, FINISHING, COMPLETE, STOPPED }
    private static final long MAX_NANOS = 15_000_000_000L;
    private static final int MAX_TICKS = 300, MAX_ATTACKS = 24, MAX_RETREATS = 2, MAX_THREATS = 16;
    private final MinecraftClient client;
    private final PlayerActions actions;
    private final MovementController movement;
    private final List<MobEntity> tracked = new ArrayList<>();
    private Map<ItemId, Integer> protection = Map.of();
    private Phase phase = Phase.IDLE;
    private String status = "threat response idle";
    private Object ownerPlayer, ownerWorld;
    private double originX, originY, originZ;
    private long startedAt;
    private int ticks, attacks, retreats, lastContactTick;
    private boolean retreatBlocked;
    private int originalSlot = -1, selectedSlot = -1;
    private MobEntity target;
    private RetreatRoute retreatRoute;
    private final Set<BlockPos> rejectedRetreatGoals = new HashSet<>();
    private double retreatProgressX, retreatProgressZ;
    private int retreatProgressTick;


    ThreatResponseAction(MinecraftClient client, PlayerActions actions, MovementController movement) {
        this.client = client;
        this.actions = actions;
        this.movement = movement;
    }

    void updateProtection(Map<ItemId, Integer> reserved) {
        if (reserved == null || reserved.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0))
            throw new IllegalArgumentException("invalid defense protection counts");
        protection = Map.copyOf(reserved);
    }

    boolean ready() {
        return !active() && unsafeContextReason() == null && !nearbyThreats().isEmpty();
    }

    boolean begin() {
        if (!ready()) return false;
        ownerPlayer = client.player;
        ownerWorld = client.world;
        originX = client.player.getX();
        originY = client.player.getY();
        originZ = client.player.getZ();
        originalSlot = ClientAccess.selectedSlot(client.player.getInventory());
        selectedSlot = -1;
        ticks = attacks = retreats = lastContactTick = 0;
        retreatBlocked = false;
        startedAt = System.nanoTime();
        tracked.clear();
        tracked.addAll(nearbyThreats());
        phase = Phase.STOPPING;
        status = "stopping movement before defense";
        try {
            movement.stopForDefense();
            log("started");
            return true;
        } catch (RuntimeException failure) {
            throw abort(failure);
        }
    }

    boolean active() {
        return phase == Phase.STOPPING || phase == Phase.MELEE || phase == Phase.CONTACT_WAIT || phase == Phase.RETREAT || phase == Phase.FINISHING;
    }

    boolean tick() {
        if (phase == Phase.COMPLETE) return true;
        if (!active()) return false;
        try {
            String reason = unsafeContextReason();
            if (reason != null) throw new IllegalStateException(reason);
            if (client.player != ownerPlayer || client.world != ownerWorld)
                throw new IllegalStateException("defense player or world changed");
            if (++ticks > MAX_TICKS || System.nanoTime() - startedAt >= MAX_NANOS)
                throw new IllegalStateException("defense exceeded its 15 second budget");
            double dx = client.player.getX() - originX, dy = client.player.getY() - originY, dz = client.player.getZ() - originZ;
            if (!(dx * dx + dy * dy + dz * dz <= 32.0 * 32.0))
                throw new IllegalStateException("defense exceeded 32 blocks from its start");
            if (selectedSlot >= 0 && ClientAccess.selectedSlot(client.player.getInventory()) != selectedSlot)
                throw new IllegalStateException("player changed the defense selection");
            List<MobEntity> threats = remainingThreats();
            switch (phase) {
                case STOPPING -> {
                    movement.stopForDefense();
                    if (!movement.finishCancellationForDefense()) return false;
                    return chooseResponse(threats);
                }
                case MELEE -> {
                    movement.stopForDefense();
                    if (!movement.finishCancellationForDefense()) return false;
                    if (threats.isEmpty()) {
                        if (!movement.finishCancellation()) return false;
                        return complete();
                    }
                    if (!selectContactTarget(threats)) {
                        if (threats.stream().anyMatch(ThreatResponseAction::creeper)) stopForRetreat();
                        else {
                            phase = Phase.CONTACT_WAIT;
                            status = "waiting for live contact after knockback";
                            log("contact-wait");
                        }
                        return false;
                    }
                    lastContactTick = ticks;
                    int slot = chooseWeaponSlot();
                    if (slot < 0) { stopForRetreat(); return false; }
                    if (selectedSlot != slot || ClientAccess.selectedSlot(client.player.getInventory()) != slot) {
                        if (!actions.selectSlot(slot)) throw new IllegalStateException("safe defense selection failed");
                        selectedSlot = slot;
                        return false;
                    }
                    // The native attribute and attack cooldown tick must observe the selected hand first.
                    if (!(client.player.getAttackCooldownProgress(0.0f) >= 1.0f)) return false;
                    if (!safeStack(client.player.getMainHandStack()) || !canHit(target)) {
                        stopForRetreat();
                        return false;
                    }
                    actions.look(target.getBoundingBox().getCenter());
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(Hand.MAIN_HAND);
                    attacks++;
                    status = "native defense attack " + attacks;
                }
                case CONTACT_WAIT -> {
                    movement.stopForDefense();
                    if (!movement.finishCancellationForDefense()) return false;
                    if (threats.isEmpty() || selectContactTarget(threats)) return chooseResponse(threats);
                    if (threats.stream().anyMatch(ThreatResponseAction::creeper)) stopForRetreat();
                    else if (!retreatBlocked && ticks - lastContactTick >= 20 && movement.finishCancellation())
                        startRetreat(threats);
                }
                case RETREAT -> {
                    if (selectContactTarget(threats)) {
                        movement.stopForDefense();
                        phase = Phase.STOPPING;
                        status = "stopping retreat before contact defense";
                        log("contact");
                    } else if (threats.isEmpty() || movement.tick()) {
                        movement.stopForDefense();
                        phase = Phase.FINISHING;
                        status = "verifying current threat clearance";
                    } else {
                        double rx = client.player.getX() - retreatProgressX, rz = client.player.getZ() - retreatProgressZ;
                        if (rx * rx + rz * rz >= .25) {
                            retreatProgressX = client.player.getX();
                            retreatProgressZ = client.player.getZ();
                            retreatProgressTick = ticks;
                        }
                        boolean drifted = threats.stream().anyMatch(mob -> retreatRoute.threats().stream().noneMatch(position -> {
                            double tx = mob.getX() - position.getX(), tz = mob.getZ() - position.getZ();
                            return tx * tx + tz * tz < 4.0;
                        }));
                        if (ticks - retreatProgressTick >= 40) {
                            BlockPos destination = movement.retreatDestination();
                            if (destination != null && retreatRoute.goals().contains(destination) && rejectedRetreatGoals.size() < 32)
                                rejectedRetreatGoals.add(destination);
                            status = drifted ? "refreshing moved threat positions" : "replanning stalled retreat";
                            log(drifted ? "repath-moving-threat" : "repath-stalled");
                            movement.stopForDefense();
                            phase = Phase.FINISHING;
                        }
                    }
                }
                case FINISHING -> {
                    movement.stopForDefense();
                    if (!movement.finishCancellationForDefense()) return false;
                    return chooseResponse(threats);
                }
                default -> { }
            }
            return false;
        } catch (RuntimeException failure) {
            throw abort(failure);
        }
    }

    void stop() {
        if (!active()) { clearOwnership(); return; }
        boolean cancelled = cancelMovement();
        restoreSelection();
        clearOwnership();
        phase = Phase.STOPPED;
        status = cancelled ? "threat response stopped" : "threat response stopped; movement cancellation pending";
        log("stopped");
    }

    String status() { return status; }

    private boolean chooseResponse(List<MobEntity> threats) {
        if (threats.isEmpty()) {
            if (!movement.finishCancellation()) return false;
            return complete();
        }
        if (selectContactTarget(threats)) {
            phase = Phase.MELEE;
            lastContactTick = ticks;
            status = "native melee defense";
            log("melee");
        } else if (retreatBlocked && threats.stream().noneMatch(ThreatResponseAction::creeper)) {
            phase = Phase.CONTACT_WAIT;
            status = "waiting for live contact without an open retreat";
        } else if (movement.finishCancellation()) {
            startRetreat(threats);
        }
        return false;
    }

    private boolean selectContactTarget(List<MobEntity> threats) {
        if (attacks >= MAX_ATTACKS || threats.stream().anyMatch(ThreatResponseAction::creeper)) return false;
        for (MobEntity mob : threats) {
            if (!canHit(mob)) continue;
            target = mob;
            if (chooseWeaponSlot() >= 0) return true;
        }
        target = null;
        return false;
    }

    private void stopForRetreat() {
        movement.stopForDefense();
        restoreSelection();
        selectedSlot = -1;
        phase = Phase.STOPPING;
        status = "stopping before retreat";
    }

    private void startRetreat(List<MobEntity> threats) {
        if (++retreats > MAX_RETREATS) throw new IllegalStateException("moving threats remained after two retreat routes");
        restoreSelection();
        selectedSlot = -1;
        int distance = threats.stream().anyMatch(ThreatResponseAction::creeper) ? 10 : 8;
        List<BlockPos> positions = threats.stream().map(mob -> mob.getBlockPos().toImmutable()).toList();
        List<MovementController.RetreatThreat> capturedThreats = threats.stream()
                .map(mob -> new MovementController.RetreatThreat(mob.getX(), mob.getZ())).toList();
        List<BlockPos> goals;
        try {
            goals = movement.startRetreat(capturedThreats, distance, rejectedRetreatGoals,
                    new BlockPos((int) Math.floor(originX), (int) Math.floor(originY), (int) Math.floor(originZ)));
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.NO_RETREAT_STANCE
                    || threats.stream().anyMatch(ThreatResponseAction::creeper)) throw failure;
            retreatBlocked = true;
            phase = Phase.CONTACT_WAIT;
            status = "waiting for live contact without an open retreat";
            log("no-open-retreat");
            return;
        }
        retreatRoute = new RetreatRoute(positions, goals);
        retreatProgressX = client.player.getX();
        retreatProgressZ = client.player.getZ();
        retreatProgressTick = ticks;
        if (movement.debugLogging())
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] THREAT_RETREAT threats={} dryGoals={} rejected={}",
                    threats.stream().map(mob -> mob.getType() + "@" + mob.getBlockPos()).toList(), goals, rejectedRetreatGoals);

        phase = Phase.RETREAT;
        status = "retreating from live threats " + retreats;
        log("retreat");
    }

    private boolean complete() {
        restoreSelection();
        clearOwnership();
        phase = Phase.COMPLETE;
        status = "live threats cleared";
        log("cleared");
        return true;
    }

    private List<MobEntity> nearbyThreats() {
        if (client.player == null || client.world == null) return List.of();
        List<MobEntity> threats = client.world.getEntitiesByClass(MobEntity.class,
                client.player.getBoundingBox().expand(12.0), mob -> eligible(mob) && (
                        mob.getTarget() == client.player && client.player.squaredDistanceTo(mob) < clearanceSquared(mob)
                        || mob.getTarget() == null && client.player.squaredDistanceTo(mob) <= 36.0 && client.player.canSee(mob)));
        if (threats.size() > MAX_THREATS) throw new IllegalStateException("too many nearby threats for bounded defense");
        return threats.stream().sorted(Comparator.comparingDouble(client.player::squaredDistanceTo)).toList();
    }

    private List<MobEntity> remainingThreats() {
        for (MobEntity mob : nearbyThreats()) if (!tracked.contains(mob)) {
            if (tracked.size() >= MAX_THREATS) throw new IllegalStateException("defense threat tracking limit exceeded");
            tracked.add(mob);
        }
        return tracked.stream().filter(this::eligible)
                .filter(mob -> {
                    double clearance = Math.sqrt(clearanceSquared(mob))
                            + (phase == Phase.RETREAT || phase == Phase.FINISHING ? 2 : 0);
                    return client.player.squaredDistanceTo(mob) < clearance * clearance;
                })
                .sorted(Comparator.comparingDouble(client.player::squaredDistanceTo)).toList();
    }

    private boolean eligible(MobEntity mob) {
        return mob.isAlive() && !mob.isRemoved() && Float.isFinite(mob.getHealth()) && mob.getHealth() > 0
                && GameApi.isHostileMob(mob) && (mob.getTarget() == null || mob.getTarget() == client.player)
                && Double.isFinite(mob.getX()) && Double.isFinite(mob.getY()) && Double.isFinite(mob.getZ());
    }

    private static boolean creeper(MobEntity mob) { return mob.getType() == EntityType.CREEPER; }
    private static double clearanceSquared(MobEntity mob) { return creeper(mob) ? 100.0 : 64.0; }

    private boolean canHit(MobEntity mob) {
        return eligible(mob) && client.player.canSee(mob) && GameApi.defenseWithinReach(client.player, mob);
    }

    private int chooseWeaponSlot() {
        int current = ClientAccess.selectedSlot(client.player.getInventory()), best = -1;
        double damage = Double.NEGATIVE_INFINITY;
        boolean sweepCollateral = GameApi.defenseHasSweepCollateral(client.world, client.player, target);
        for (int offset = 0; offset < 9; offset++) {
            int slot = (current + offset) % 9;
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!safeStack(stack) || sweepCollateral && GameApi.isSword(stack)) continue;
            double candidate = GameApi.defenseAttackDamage(client.player, stack);
            if (Double.isFinite(candidate) && candidate > damage) { best = slot; damage = candidate; }
        }
        return best;
    }

    private boolean safeStack(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (!stack.isDamageable() || stack.hasEnchantments() || GameApi.hasCustomName(stack)
                || actions.count(stack.getItem()) <= protection.getOrDefault(GameCatalog.id(stack.getItem()), 0)) return false;
        int wear = GameApi.defenseAttackWear(stack);
        if (wear < 0 || stack.getMaxDamage() - stack.getDamage() <= Math.max(2, wear)) return false;
        ItemStack normalized = stack.copy(), ordinary = new ItemStack(stack.getItem());
        normalized.setDamage(0);
        ordinary.setDamage(0);
        return GameApi.canCombine(normalized, ordinary);
    }

    private String unsafeContextReason() {
        if (client.player == null || client.world == null || client.interactionManager == null) return "defense world unavailable";
        var player = client.player;
        if (!Double.isFinite(player.getX()) || !Double.isFinite(player.getY()) || !Double.isFinite(player.getZ()))
            return "player position is not finite";
        if (!player.isAlive() || !Float.isFinite(player.getHealth()) || player.getHealth() <= 0) return "player is not alive";
        if (player.getAbilities().creativeMode || player.isSpectator()) return "defense requires survival play";
        if (player.hasVehicle() || player.isUsingItem()) return "player is riding or using an item";
        if (client.currentScreen != null || player.currentScreenHandler != player.playerScreenHandler
                || !player.currentScreenHandler.getCursorStack().isEmpty()) return "defense inventory context is unsafe";
        return null;
    }

    private void restoreSelection() {
        if (client.player != ownerPlayer || client.world != ownerWorld || unsafeContextReason() != null
                || selectedSlot < 0 || originalSlot < 0 || ClientAccess.selectedSlot(client.player.getInventory()) != selectedSlot) return;
        actions.selectSlot(originalSlot);
        selectedSlot = -1;
    }

    private boolean cancelMovement() {
        try { movement.stopForDefense(); return movement.finishCancellation(); }
        catch (RuntimeException ignored) { return false; }
    }

    private IllegalStateException abort(RuntimeException failure) {
        boolean cancelled = cancelMovement();
        restoreSelection();
        clearOwnership();
        phase = Phase.STOPPED;
        status = "threat response failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (!cancelled) status += "; movement cancellation pending";
        log("failed");
        return new IllegalStateException(status, failure);
    }

    private void clearOwnership() {
        tracked.clear();
        retreatRoute = null;
        rejectedRetreatGoals.clear();
        retreatProgressTick = 0;
        retreatProgressX = retreatProgressZ = 0;
        target = null;
        ownerPlayer = ownerWorld = null;
        originalSlot = selectedSlot = -1;
    }

    private void log(String outcome) {
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] THREAT_RESPONSE outcome={} attacks={} retreats={} ticks={} status={}",
                outcome, attacks, retreats, ticks, status);
    }
}
