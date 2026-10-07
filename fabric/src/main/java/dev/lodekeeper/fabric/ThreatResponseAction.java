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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Caller-owned inventory arbitration; native movement and attacks provide all effects. */
final class ThreatResponseAction {
    private record RetreatRoute(List<BlockPos> threats, List<BlockPos> goals) { }
    private record RouteHazard(UUID uuid, EntityType<?> type, boolean creeper, double x, double y, double z) {
        int clearance() { return creeper ? 20 : 11; }
        double dangerRadius() { return creeper ? 6.0 : 3.5; }
        MovementController.RetreatThreat movementThreat() {
            return new MovementController.RetreatThreat(x, y, z, clearance(), dangerRadius());
        }
    }
    private record AttackChoice(MobEntity target, int slot, double damage, MovementController.DefenseHop hop) { }
    private enum Phase { IDLE, STOPPING, MELEE, HOP_ASCENT, HOP_LANDING, CONTACT_WAIT, RETREAT, FINISHING, COMPLETE, STOPPED }
    private static final long MAX_NANOS = 15_000_000_000L;
    private static final int MAX_TICKS = 300, MAX_ATTACKS = 24, MAX_RETREAT_STARTS = 4,
            MAX_COMPLETED_RETREATS = 2, MAX_THREATS = 16;
    private final MinecraftClient client;
    private final PlayerActions actions;
    private final MovementController movement;
    private final ShieldController shield;
    private boolean useShield;
    private final List<MobEntity> tracked = new ArrayList<>();
    private Map<ItemId, Integer> protection = Map.of();
    private Phase phase = Phase.IDLE;
    private String status = "threat response idle";
    private Object ownerPlayer, ownerWorld;
    private double originX, originY, originZ;
    private long startedAt;
    private int ticks, attacks, retreatStarts, completedRetreats, lastContactTick, hopLaunchWaitTicks;
    private boolean retreatBlocked;
    private int originalSlot = -1, selectedSlot = -1;
    private AttackChoice plannedChoice;
    private double hopPeakY;
    private RetreatRoute retreatRoute;
    private final Set<BlockPos> rejectedRetreatGoals = new HashSet<>();
    private double retreatProgressX, retreatProgressZ;
    private int retreatProgressTick;


    ThreatResponseAction(MinecraftClient client, PlayerActions actions, MovementController movement) {
        this.client = client;
        this.actions = actions;
        this.movement = movement;
        shield = new ShieldController(client, actions);
    }

    void useShield(boolean enabled) { useShield = enabled; if (!enabled) shield.stop(); }
    boolean hasAvailableShield() { return shield.available(protection); }

    void updateProtection(Map<ItemId, Integer> reserved) {
        if (reserved == null || reserved.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0))
            throw new IllegalArgumentException("invalid defense protection counts");
        protection = Map.copyOf(reserved);
    }

    boolean ready() {
        return !active() && unsafeContextReason() == null && !nearbyThreats().isEmpty();
    }

    boolean hasPreparedMeleeWeapon() {
        if (client.player == null) return false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack weapon = client.player.getInventory().getStack(slot);
            if (weapon.isEmpty() || !safeStack(weapon)) continue;
            double damage = GameApi.defenseAttackDamage(client.player, weapon);
            if (!Double.isFinite(damage)) continue;
            if (GameApi.isSword(weapon) ? damage >= 5.0 : damage >= 7.0) return true;
        }
        return false;
    }

    boolean begin() {
        if (!ready()) return false;
        shield.begin();
        ownerPlayer = client.player;
        ownerWorld = client.world;
        originX = client.player.getX();
        originY = client.player.getY();
        originZ = client.player.getZ();
        originalSlot = ClientAccess.selectedSlot(client.player.getInventory());
        selectedSlot = -1;
        ticks = attacks = retreatStarts = completedRetreats = lastContactTick = hopLaunchWaitTicks = 0;
        retreatBlocked = false;
        startedAt = System.nanoTime();
        tracked.clear();
        tracked.addAll(nearbyThreats());
        phase = Phase.STOPPING;
        status = "stopping movement before defense";
        try {
            movement.stopForDefense();
            movement.resetRetreatPrefix();
            log("started");
            return true;
        } catch (RuntimeException failure) {
            throw abort(failure);
        }
    }

    boolean active() {
        return phase == Phase.STOPPING || phase == Phase.MELEE || phase == Phase.HOP_ASCENT || phase == Phase.HOP_LANDING || phase == Phase.CONTACT_WAIT || phase == Phase.RETREAT || phase == Phase.FINISHING;
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
            RuntimeException retreatPrefixFailure = movement.takeRetreatPrefixFailure();
            if (retreatPrefixFailure != null) throw retreatPrefixFailure;
            if (!useShield && !shield.finish()) return false;
            List<MobEntity> threats = remainingThreats();
            if (useShield && (phase == Phase.STOPPING || phase == Phase.FINISHING)
                    && GameApi.ordinaryShield(client.player.getOffHandStack())) {
                MobEntity urgent = threats.stream().filter(ThreatResponseAction::creeper).findFirst().orElse(null);
                if (urgent != null && shield.prepare(protection)) {
                    actions.look(urgent.getBoundingBox().getCenter());
                    shield.block();
                }
            }
            if (useShield && !threats.isEmpty() && threats.stream().noneMatch(ThreatResponseAction::creeper)
                    && (phase == Phase.MELEE || phase == Phase.CONTACT_WAIT)
                    && !shield.prepare(protection)) return false;
            switch (phase) {
                case STOPPING -> {
                    movement.stopForDefense();
                    if (!movement.finishCancellationForDefense()) return false;
                    if (useShield && !threats.isEmpty() && threats.stream().noneMatch(ThreatResponseAction::creeper)
                            && !shield.prepare(protection)) return false;
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
                        shield.release();
                        if (threats.stream().anyMatch(ThreatResponseAction::creeper)) stopForRetreat();
                        else {
                            phase = Phase.CONTACT_WAIT;
                            status = "waiting for live contact after knockback";
                            log("contact-wait");
                        }
                        return false;
                    }
                    lastContactTick = ticks;
                    AttackChoice choice = plannedChoice;
                    int slot = choice.slot();
                    if (selectedSlot != slot || ClientAccess.selectedSlot(client.player.getInventory()) != slot) {
                        if (!actions.selectSlot(slot)) throw new IllegalStateException("safe defense selection failed");
                        selectedSlot = slot;
                        logAttackDecision("select", slot);
                        return false;
                    }
                    // The native attribute and attack cooldown tick must observe the selected hand first.
                    if (!(client.player.getAttackCooldownProgress(0.0f) >= 1.0f)) {
                        if (useShield && canHit(choice.target())) {
                            actions.look(choice.target().getBoundingBox().getCenter());
                            shield.block();
                        }
                        return false;
                    }
                    shield.release();
                    if (choice.hop() != null) {
                        MovementController.DefenseHop launch = movement.planDefenseHop();
                        if (launch != null && movement.startDefenseHop(launch)) {
                            plannedChoice = new AttackChoice(choice.target(), choice.slot(), choice.damage(), launch);
                            hopPeakY = client.player.getY();
                            hopLaunchWaitTicks = 0;
                            phase = Phase.HOP_ASCENT;
                            status = "timing a safe airborne defense attack";
                            log("hop-started");
                        } else if (++hopLaunchWaitTicks >= 4) {
                            plannedChoice = chooseAttackChoice(threats, false);
                            hopLaunchWaitTicks = 0;
                            status = "using grounded defense after jump launch rejection";
                            log("hop-launch-fallback");
                        }
                        return false;
                    }
                    actions.look(choice.target().getBoundingBox().getCenter());
                    if (ClientAccess.selectedSlot(client.player.getInventory()) != choice.slot()
                            || selectedSlot != choice.slot())
                        throw new IllegalStateException("defense attack choice lost its selected slot");
                    if (!canAttackTarget(choice.target(), client.player.getMainHandStack())) {
                        plannedChoice = null;
                        return false;
                    }
                    logAttackDecision("attack", slot);
                    client.interactionManager.attackEntity(client.player, choice.target());
                    client.player.swingHand(Hand.MAIN_HAND);
                    attacks++;
                    status = "native defense attack " + attacks;
                    plannedChoice = null;
                    if (selectContactTarget(remainingThreats())) {
                        int nextSlot = plannedChoice.slot();
                        if (ClientAccess.selectedSlot(client.player.getInventory()) != nextSlot) {
                            if (!actions.selectSlot(nextSlot)) throw new IllegalStateException("safe defense selection failed");
                            selectedSlot = nextSlot;
                            logAttackDecision("select", nextSlot);
                        }
                    }
                }
                case HOP_ASCENT, HOP_LANDING -> {
                    if (threats.stream().anyMatch(ThreatResponseAction::creeper)) {
                        movement.stopDefenseHop();
                        stopForRetreat();
                        return false;
                    }
                    if (movement.tickDefenseHop()) {
                        plannedChoice = null;
                        phase = Phase.MELEE;
                        status = "safe defense landing complete";
                        log("hop-landed");
                        return false;
                    }
                    double observedY = client.player.getY();
                    hopPeakY = Math.max(hopPeakY, observedY);
                    if (phase != Phase.HOP_ASCENT || client.player.isOnGround()
                            || !(observedY < hopPeakY - 1.0e-5)
                            || !(client.player.getAttackCooldownProgress(0.0f) >= 1.0f)) return false;
                    ItemStack weapon = client.player.getMainHandStack();
                    MobEntity contact = canAttackTarget(plannedChoice.target(), weapon)
                            ? plannedChoice.target() : chooseTargetForSlot(threats, weapon, null);
                    if (contact == null) return false;
                    plannedChoice = new AttackChoice(contact, plannedChoice.slot(), plannedChoice.damage(), plannedChoice.hop());
                    actions.look(contact.getBoundingBox().getCenter());
                    movement.checkAirRecoveryOwnership();
                    if (client.player.isOnGround() || ClientAccess.selectedSlot(client.player.getInventory()) != plannedChoice.slot()
                            || selectedSlot != plannedChoice.slot() || !canAttackTarget(contact, client.player.getMainHandStack())) return false;
                    logAttackDecision("airborne-attack", plannedChoice.slot());
                    GameApi.attackAirborneForDefense(client, contact);
                    client.player.swingHand(Hand.MAIN_HAND);
                    attacks++;
                    phase = Phase.HOP_LANDING;
                    status = "landing after native defense attack " + attacks;
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
                    List<RouteHazard> hazards = routeHazards();
                    List<MovementController.RetreatThreat> movementHazards = hazards.stream()
                            .map(RouteHazard::movementThreat).toList();
                    movement.updateRetreatHazards(movementHazards);
                    MovementController.RetreatPrefixRejection rejection = movement.takeRetreatPrefixRejection();
                    if (rejection != null) {
                        BlockPos destination = rejection.destination();
                        if (destination != null && retreatRoute.goals().contains(destination)
                                && rejectedRetreatGoals.size() < 32) rejectedRetreatGoals.add(destination);
                        status = "draining retreat after a blocked first path prefix";
                        log("retreat-prefix-blocked");
                        phase = Phase.FINISHING;
                    } else if (selectContactTarget(threats)) {
                        movement.stopForDefense();
                        phase = Phase.STOPPING;
                        status = "stopping retreat before contact defense";
                        log("contact");
                    } else {
                        MovementController.RetreatPrefixStatus prefix = movement.checkRetreatPrefix(movementHazards);
                        if (prefix == MovementController.RetreatPrefixStatus.BLOCKED) {
                            rejectRetreatDestination();
                            status = "stopping retreat before a hostile hazard";
                            log("retreat-prefix-blocked");
                            movement.stopForDefense();
                            phase = Phase.FINISHING;
                        } else if (movement.tick()) {
                            boolean arrived = retreatRoute.goals().contains(client.player.getBlockPos());
                            status = arrived ? "verifying current threat clearance" : "draining interrupted retreat";
                            if (arrived) completedRetreats++;
                            log(arrived ? "retreat-arrived" : "retreat-interrupted");
                            movement.stopForDefense();
                            phase = Phase.FINISHING;
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
                                rejectRetreatDestination();
                                status = drifted ? "refreshing moved threat positions" : "replanning stalled retreat";
                                log(drifted ? "repath-moving-threat" : "repath-stalled");
                                movement.stopForDefense();
                                phase = Phase.FINISHING;
                            }
                        }
                    }
                }
                case FINISHING -> {
                    movement.stopForDefense();
                    if (!movement.finishCancellationForDefense()) return false;
                    log("retreat-drained");
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
        shield.stop();
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
        if (attacks >= MAX_ATTACKS || threats.stream().anyMatch(ThreatResponseAction::creeper)) {
            plannedChoice = null;
            return false;
        }
        if (plannedChoice != null) {
            AttackChoice previous = plannedChoice;
            ItemStack weapon = client.player.getInventory().getStack(previous.slot());
            if (safeStack(weapon)) {
                MovementController.DefenseHop hop = previous.hop() == null ? null : movement.planDefenseHop();
                MobEntity nextTarget = canChooseTarget(previous.target(), weapon, hop)
                        ? previous.target() : chooseTargetForSlot(threats, weapon, hop);
                if (nextTarget != null) {
                    plannedChoice = new AttackChoice(nextTarget, previous.slot(), previous.damage(), hop);
                    return true;
                }
            }
            plannedChoice = null;
        }
        plannedChoice = chooseAttackChoice(threats);
        return plannedChoice != null;
    }

    private void stopForRetreat() {
        shield.release();
        movement.stopForDefense();
        plannedChoice = null;
        restoreSelection();
        selectedSlot = -1;
        phase = Phase.STOPPING;
        status = "stopping before retreat";
    }

    private void startRetreat(List<MobEntity> threats) {
        shield.release();
        plannedChoice = null;
        restoreSelection();
        selectedSlot = -1;
        List<RouteHazard> hazards = routeHazards();
        logRetreatPlanning(hazards);
        if (retreatStarts >= MAX_RETREAT_STARTS || completedRetreats >= MAX_COMPLETED_RETREATS) {
            if (hazards.stream().anyMatch(RouteHazard::creeper))
                throw new IllegalStateException("active creeper remained after bounded retreat routes");
            retreatBlocked = true;
            phase = Phase.CONTACT_WAIT;
            status = "waiting for live contact after bounded retreat routes";
            log("retreat-budget-exhausted");
            return;
        }
        retreatStarts++;
        List<BlockPos> positions = threats.stream().map(mob -> mob.getBlockPos().toImmutable()).toList();
        List<MovementController.RetreatThreat> capturedThreats = hazards.stream()
                .map(RouteHazard::movementThreat).toList();
        List<BlockPos> goals;
        try {
            goals = movement.startRetreat(capturedThreats, rejectedRetreatGoals,
                    new BlockPos((int) Math.floor(originX), (int) Math.floor(originY), (int) Math.floor(originZ)));
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.NO_RETREAT_STANCE
                    || hazards.stream().anyMatch(RouteHazard::creeper)) throw failure;
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
        status = "retreating from live threats " + retreatStarts;
        log("retreat");
    }

    private List<RouteHazard> routeHazards() {
        if (client.player == null || client.world == null)
            throw new IllegalStateException("world unavailable while checking retreat hazards");
        var envelope = client.player.getBoundingBox().expand(24.0, 6.0, 24.0);
        Map<UUID, MobEntity> candidates = new LinkedHashMap<>();
        for (MobEntity mob : tracked) if (envelope.intersects(mob.getBoundingBox())) addRouteHazard(candidates, mob);
        for (MobEntity mob : client.world.getEntitiesByClass(MobEntity.class, envelope, this::eligible))
            addRouteHazard(candidates, mob);
        return candidates.values().stream().sorted(Comparator.comparingDouble(client.player::squaredDistanceTo))
                .map(mob -> new RouteHazard(mob.getUuid(), mob.getType(), creeper(mob), mob.getX(), mob.getY(), mob.getZ())).toList();
    }

    private void addRouteHazard(Map<UUID, MobEntity> candidates, MobEntity mob) {
        if (!eligible(mob)) return;
        UUID uuid = mob.getUuid();
        if (!candidates.containsKey(uuid) && candidates.size() == MAX_THREATS)
            throw new IllegalStateException("too many loaded route hazards for bounded retreat");
        candidates.put(uuid, mob);
    }

    private void logRetreatPlanning(List<RouteHazard> hazards) {
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] THREAT_RETREAT planning starts={} completed={} attacks={} ticks={} hazards={}",
                retreatStarts, completedRetreats, attacks, ticks,
                hazards.stream().map(hazard -> hazard.uuid() + "/" + hazard.type() + "@"
                        + hazard.x() + "," + hazard.y() + "," + hazard.z()
                        + " clearance=" + hazard.clearance()).toList());
    }

    private void rejectRetreatDestination() {
        BlockPos destination = movement.retreatDestination();
        if (destination != null && retreatRoute != null && retreatRoute.goals().contains(destination)
                && rejectedRetreatGoals.size() < 32) rejectedRetreatGoals.add(destination);
    }

    private boolean complete() {
        if (!shield.finish()) return false;
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
                    double clearance = Math.sqrt(clearanceSquared(mob));
                    if (phase == Phase.RETREAT || phase == Phase.FINISHING)
                        clearance += creeper(mob) ? 6 : 2;
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

    private boolean canAttackTarget(MobEntity mob, ItemStack weapon) {
        return !creeper(mob) && safeStack(weapon) && canHit(mob) && safeWeaponForTarget(mob, weapon);
    }

    private boolean safeWeaponForTarget(MobEntity mob, ItemStack weapon) {
        return !GameApi.isSword(weapon) || !GameApi.defenseHasSweepCollateral(client.world, client.player, mob)
                || (phase == Phase.HOP_ASCENT || phase == Phase.HOP_LANDING) && !client.player.isOnGround();
    }

    private boolean canChooseTarget(MobEntity mob, ItemStack weapon, MovementController.DefenseHop hop) {
        return !creeper(mob) && canHit(mob)
                && (safeWeaponForTarget(mob, weapon) || hop != null && GameApi.isSword(weapon));
    }

    private MobEntity chooseTargetForSlot(List<MobEntity> threats, ItemStack weapon, MovementController.DefenseHop hop) {
        return threats.stream().filter(mob -> canChooseTarget(mob, weapon, hop))
                .min(Comparator.comparingDouble(MobEntity::getHealth)
                        .thenComparingDouble(client.player::squaredDistanceTo)
                        .thenComparingInt(MobEntity::getId))
                .orElse(null);
    }

    private AttackChoice chooseAttackChoice(List<MobEntity> threats) {
        return chooseAttackChoice(threats, true);
    }

    private AttackChoice chooseAttackChoice(List<MobEntity> threats, boolean allowHop) {
        double[] damageBySlot = new double[9];
        boolean[] usable = new boolean[9], swords = new boolean[9];
        boolean swordAvailable = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack weapon = client.player.getInventory().getStack(slot);
            if (!safeStack(weapon)) continue;
            double damage = GameApi.defenseAttackDamage(client.player, weapon);
            if (!Double.isFinite(damage)) continue;
            usable[slot] = true;
            damageBySlot[slot] = damage;
            swords[slot] = GameApi.isSword(weapon);
            swordAvailable |= swords[slot];
        }
        MovementController.DefenseHop hop = allowHop && swordAvailable && threats.stream().noneMatch(ThreatResponseAction::creeper)
                && (tracked.size() > 1 || client.player.getHealth() <= 6.0f)
                ? movement.planDefenseHop() : null;
        int currentSlot = ClientAccess.selectedSlot(client.player.getInventory());
        List<AttackChoice> choices = new ArrayList<>(MAX_THREATS * 9);
        for (MobEntity mob : threats) {
            if (creeper(mob) || !canHit(mob)) continue;
            boolean sweepCollateral = swordAvailable
                    && GameApi.defenseHasSweepCollateral(client.world, client.player, mob);
            for (int slot = 0; slot < 9; slot++) {
                if (!usable[slot]) continue;
                boolean airborneOnly = sweepCollateral && swords[slot];
                if (!airborneOnly || hop != null)
                    choices.add(new AttackChoice(mob, slot, damageBySlot[slot], airborneOnly ? hop : null));
            }
        }
        return choices.stream().min(Comparator.comparingDouble(AttackChoice::damage).reversed()
                .thenComparingDouble(choice -> choice.target().getHealth())
                .thenComparingInt(choice -> choice.slot() == currentSlot ? 0 : 1)
                .thenComparingDouble(choice -> client.player.squaredDistanceTo(choice.target()))
                .thenComparingInt(choice -> choice.target().getId())
                .thenComparingInt(AttackChoice::slot)).orElse(null);
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
        if (shield.manualInput()) return "manual input interrupted defense";
        var player = client.player;
        if (!Double.isFinite(player.getX()) || !Double.isFinite(player.getY()) || !Double.isFinite(player.getZ()))
            return "player position is not finite";
        if (!player.isAlive() || !Float.isFinite(player.getHealth()) || player.getHealth() <= 0) return "player is not alive";
        if (player.getAbilities().creativeMode || player.isSpectator()) return "defense requires survival play";
        if (player.hasVehicle() || player.isUsingItem() && !shield.ownsUse()) return "player is riding or using an item";
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
        shield.stop();
        boolean cancelled = cancelMovement();
        restoreSelection();
        phase = Phase.STOPPED;
        status = "threat response failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (!cancelled) status += "; movement cancellation pending";
        log("failed");
        clearOwnership();
        return new IllegalStateException(status, failure);
    }

    private void clearOwnership() {
        tracked.clear();
        retreatRoute = null;
        rejectedRetreatGoals.clear();
        retreatProgressTick = 0;
        retreatProgressX = retreatProgressZ = 0;
        plannedChoice = null;
        ownerPlayer = ownerWorld = null;
        originalSlot = selectedSlot = -1;
    }

    private void logAttackDecision(String outcome, int slot) {
        if (!movement.debugLogging()) return;
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] DEFENSE_ATTACK outcome={} tick={} slot={} weapon={} cooldown={} target={} targetHealth={} sweepCollateral={} playerHealth={} grounded={} y={}",
                outcome, ticks, slot, GameCatalog.id(client.player.getMainHandStack().getItem()),
                client.player.getAttackCooldownProgress(0.0f), plannedChoice.target().getId(), plannedChoice.target().getHealth(),
                GameApi.defenseHasSweepCollateral(client.world, client.player, plannedChoice.target()), client.player.getHealth(), client.player.isOnGround(), client.player.getY());
    }

    private void log(String outcome) {
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] THREAT_RESPONSE outcome={} attacks={} retreatStarts={} completedRetreats={} ticks={} status={} player={} threats={}",
                outcome, attacks, retreatStarts, completedRetreats, ticks, status,
                client.player == null ? "absent" : client.player.getX() + "," + client.player.getY() + "," + client.player.getZ(),
                client.player == null ? List.of() : tracked.stream().map(mob -> mob.getUuid() + "/" + mob.getType() + "@"
                        + mob.getX() + "," + mob.getY() + "," + mob.getZ()
                        + "/alive=" + mob.isAlive() + "/removed=" + mob.isRemoved() + "/eligible=" + eligible(mob)
                        + "/distance=" + Math.sqrt(client.player.squaredDistanceTo(mob))).toList());
    }
}
