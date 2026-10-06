package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.ItemId;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Caller-owned inventory arbitration; native movement and attacks provide all effects. */
final class ThreatResponseAction {
    private enum Phase { IDLE, STOPPING, MELEE, RETREAT, FINISHING, COMPLETE, STOPPED }
    private static final long MAX_NANOS = 15_000_000_000L;
    private static final int MAX_TICKS = 300, MAX_ATTACKS = 12, MAX_RETREATS = 2, MAX_THREATS = 16;
    private final Minecraft client;
    private final PlayerActions actions;
    private final MovementController movement;
    private final List<Mob> tracked = new ArrayList<>();
    private Map<ItemId, Integer> protection = Map.of();
    private Phase phase = Phase.IDLE;
    private String status = "threat response idle";
    private Object ownerPlayer, ownerWorld;
    private double originX, originY, originZ;
    private long startedAt;
    private int ticks, attacks, retreats;
    private boolean retreatRequired;
    private int originalSlot = -1, selectedSlot = -1;
    private Mob target;

    ThreatResponseAction(Minecraft client, PlayerActions actions, MovementController movement) {
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
        ownerWorld = client.level;
        originX = client.player.getX();
        originY = client.player.getY();
        originZ = client.player.getZ();
        originalSlot = client.player.getInventory().getSelectedSlot();
        selectedSlot = -1;
        ticks = attacks = retreats = 0;
        retreatRequired = false;
        startedAt = System.nanoTime();
        tracked.clear();
        tracked.addAll(nearbyThreats());
        phase = Phase.STOPPING;
        status = "stopping movement before defense";
        try {
            movement.stop();
            log("started");
            return true;
        } catch (RuntimeException failure) {
            throw abort(failure);
        }
    }

    boolean active() {
        return phase == Phase.STOPPING || phase == Phase.MELEE || phase == Phase.RETREAT || phase == Phase.FINISHING;
    }

    boolean tick() {
        if (phase == Phase.COMPLETE) return true;
        if (!active()) return false;
        try {
            String reason = unsafeContextReason();
            if (reason != null) throw new IllegalStateException(reason);
            if (client.player != ownerPlayer || client.level != ownerWorld)
                throw new IllegalStateException("defense player or world changed");
            if (++ticks > MAX_TICKS || System.nanoTime() - startedAt >= MAX_NANOS)
                throw new IllegalStateException("defense exceeded its 15 second budget");
            double dx = client.player.getX() - originX, dy = client.player.getY() - originY, dz = client.player.getZ() - originZ;
            if (!(dx * dx + dy * dy + dz * dz <= 32.0 * 32.0))
                throw new IllegalStateException("defense exceeded 32 blocks from its start");
            List<Mob> threats = remainingThreats();
            switch (phase) {
                case STOPPING -> {
                    movement.stop();
                    if (!movement.finishCancellation()) return false;
                    if (threats.isEmpty()) return complete();
                    target = threats.get(0);
                    if (retreatRequired || client.player.getHealth() <= 10.0f || threats.size() > 1 || creeper(target)
                            || attacks >= MAX_ATTACKS || !canHit(target) || chooseWeaponSlot() < 0) {
                        startRetreat(threats);
                    } else {
                        phase = Phase.MELEE;
                        status = "native melee defense";
                        log("melee");
                    }
                }
                case MELEE -> {
                    if (selectedSlot >= 0 && client.player.getInventory().getSelectedSlot() != selectedSlot)
                        throw new IllegalStateException("player changed the defense selection");
                    if (threats.isEmpty()) return complete();
                    target = threats.get(0);
                    if (retreatRequired || client.player.getHealth() <= 10.0f || threats.size() > 1 || creeper(target)
                            || attacks >= MAX_ATTACKS || !canHit(target)) {
                        stopForRetreat();
                        return false;
                    }
                    int slot = chooseWeaponSlot();
                    if (slot < 0) { stopForRetreat(); return false; }
                    if (selectedSlot != slot || client.player.getInventory().getSelectedSlot() != slot) {
                        if (!actions.selectSlot(slot)) throw new IllegalStateException("safe defense selection failed");
                        selectedSlot = slot;
                        return false;
                    }
                    // The native attribute and attack cooldown tick must observe the selected hand first.
                    if (!(client.player.getAttackStrengthScale(0.0f) >= 1.0f)) return false;
                    if (!safeStack(client.player.getMainHandItem()) || !canHit(target)) {
                        stopForRetreat();
                        return false;
                    }
                    actions.look(target.getBoundingBox().getCenter());
                    client.gameMode.attack(client.player, target);
                    GameApi.swing(client.player, InteractionHand.MAIN_HAND);
                    attacks++;
                    status = "native defense attack " + attacks;
                }
                case RETREAT -> {
                    if (threats.isEmpty() || movement.tick()) {
                        movement.stop();
                        phase = Phase.FINISHING;
                        status = "verifying current threat clearance";
                    }
                }
                case FINISHING -> {
                    movement.stop();
                    if (!movement.finishCancellation()) return false;
                    // GoalRunAway uses captured positions; mobs can follow while the route executes.
                    if (remainingThreats().isEmpty()) return complete();
                    startRetreat(remainingThreats());
                }
                default -> { }
            }
            return false;
        } catch (RuntimeException failure) {
            throw abort(failure);
        }
    }

    void stop() {
        if (!active()) return;
        boolean cancelled = cancelMovement();
        restoreSelection();
        phase = Phase.STOPPED;
        status = cancelled ? "threat response stopped" : "threat response stopped; movement cancellation pending";
        log("stopped");
    }

    String status() { return status; }

    private void stopForRetreat() {
        movement.stop();
        restoreSelection();
        selectedSlot = -1;
        phase = Phase.STOPPING;
        retreatRequired = true;
        status = "stopping before retreat";
    }

    private void startRetreat(List<Mob> threats) {
        if (++retreats > MAX_RETREATS) throw new IllegalStateException("moving threats remained after two retreat routes");
        restoreSelection();
        selectedSlot = -1;
        int distance = threats.stream().anyMatch(ThreatResponseAction::creeper) ? 10 : 8;
        List<BlockPos> positions = threats.stream().map(mob -> mob.blockPosition().immutable()).toList();
        movement.startRetreat(positions, distance);
        phase = Phase.RETREAT;
        status = "retreating from live threats " + retreats;
        log("retreat");
    }

    private boolean complete() {
        restoreSelection();
        phase = Phase.COMPLETE;
        status = "live threats cleared";
        log("cleared");
        return true;
    }

    private List<Mob> nearbyThreats() {
        if (client.player == null || client.level == null) return List.of();
        List<Mob> threats = client.level.getEntities(EntityTypeTest.forClass(Mob.class),
                client.player.getBoundingBox().inflate(12.0), mob -> eligible(mob) && (
                        mob.getTarget() == client.player && client.player.distanceToSqr(mob) < clearanceSquared(mob)
                        || mob.getTarget() == null && client.player.distanceToSqr(mob) <= 36.0 && client.player.hasLineOfSight(mob)));
        if (threats.size() > MAX_THREATS) throw new IllegalStateException("too many nearby threats for bounded defense");
        return threats.stream().sorted(Comparator.comparingDouble(client.player::distanceToSqr)).toList();
    }

    private List<Mob> remainingThreats() {
        for (Mob mob : nearbyThreats()) if (!tracked.contains(mob)) {
            if (tracked.size() >= MAX_THREATS) throw new IllegalStateException("defense threat tracking limit exceeded");
            tracked.add(mob);
        }
        return tracked.stream().filter(this::eligible)
                .filter(mob -> client.player.distanceToSqr(mob) < clearanceSquared(mob))
                .sorted(Comparator.comparingDouble(client.player::distanceToSqr)).toList();
    }

    private boolean eligible(Mob mob) {
        return mob.isAlive() && !mob.isRemoved() && Float.isFinite(mob.getHealth()) && mob.getHealth() > 0
                && GameApi.isHostileMob(mob) && (mob.getTarget() == null || mob.getTarget() == client.player)
                && Double.isFinite(mob.getX()) && Double.isFinite(mob.getY()) && Double.isFinite(mob.getZ());
    }

    private static boolean creeper(Mob mob) { return mob instanceof Creeper; }
    private static double clearanceSquared(Mob mob) { return creeper(mob) ? 100.0 : 64.0; }

    private boolean canHit(Mob mob) {
        return eligible(mob) && client.player.hasLineOfSight(mob) && GameApi.defenseWithinReach(client.player, mob);
    }

    private int chooseWeaponSlot() {
        int current = client.player.getInventory().getSelectedSlot(), best = -1;
        double damage = Double.NEGATIVE_INFINITY;
        boolean sweepCollateral = GameApi.defenseHasSweepCollateral(client.level, client.player, target);
        for (int offset = 0; offset < 9; offset++) {
            int slot = (current + offset) % 9;
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (!safeStack(stack) || sweepCollateral && GameApi.isSword(stack)) continue;
            double candidate = GameApi.defenseAttackDamage(client.player, stack);
            if (Double.isFinite(candidate) && candidate > damage) { best = slot; damage = candidate; }
        }
        return best;
    }

    private boolean safeStack(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (!stack.isDamageableItem() || stack.isEnchanted() || GameApi.hasCustomName(stack)
                || actions.count(stack.getItem()) <= protection.getOrDefault(GameCatalog.id(stack.getItem()), 0)) return false;
        int wear = GameApi.defenseAttackWear(stack);
        if (wear < 0 || stack.getMaxDamage() - stack.getDamageValue() <= Math.max(2, wear)) return false;
        ItemStack normalized = stack.copy(), ordinary = new ItemStack(stack.getItem());
        normalized.setDamageValue(0);
        ordinary.setDamageValue(0);
        return ItemStack.isSameItemSameComponents(normalized, ordinary);
    }

    private String unsafeContextReason() {
        if (client.player == null || client.level == null || client.gameMode == null) return "defense world unavailable";
        var player = client.player;
        if (!Double.isFinite(player.getX()) || !Double.isFinite(player.getY()) || !Double.isFinite(player.getZ()))
            return "player position is not finite";
        if (!player.isAlive() || !Float.isFinite(player.getHealth()) || player.getHealth() <= 0) return "player is not alive";
        if (player.isCreative() || player.isSpectator()) return "defense requires survival play";
        if (player.isPassenger() || player.isUsingItem()) return "player is riding or using an item";
        if (GameApi.screen(client) != null || player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()) return "defense inventory context is unsafe";
        return null;
    }

    private void restoreSelection() {
        if (client.player != ownerPlayer || client.level != ownerWorld || unsafeContextReason() != null
                || selectedSlot < 0 || originalSlot < 0 || client.player.getInventory().getSelectedSlot() != selectedSlot) return;
        actions.selectSlot(originalSlot);
        selectedSlot = -1;
    }

    private boolean cancelMovement() {
        try { movement.stop(); return movement.finishCancellation(); }
        catch (RuntimeException ignored) { return false; }
    }

    private IllegalStateException abort(RuntimeException failure) {
        boolean cancelled = cancelMovement();
        restoreSelection();
        phase = Phase.STOPPED;
        status = "threat response failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (!cancelled) status += "; movement cancellation pending";
        log("failed");
        return new IllegalStateException(status, failure);
    }

    private void log(String outcome) {
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] THREAT_RESPONSE outcome={} attacks={} retreats={} ticks={} status={}",
                outcome, attacks, retreats, ticks, status);
    }
}
