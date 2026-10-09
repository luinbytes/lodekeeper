package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Breath recovery owns navigation until the player's eyes and air supply recover. */
final class AirRecoveryAction {
    interface DiagnosticInput { String diagnosticState(); }
    private static final int MAX_DIAGNOSTIC_SAMPLES = 16;
    private static final int MAX_DIAGNOSTIC_ENTITIES = 32;
    private static final int MAX_DIAGNOSTIC_HOSTILES = 8;
    private static final int MAX_DIAGNOSTIC_HEIGHT_HOLDS = 8;
    private enum Phase { IDLE, DRAINING, STOPPING, PLANNING, SWIMMING, ESCAPING, REFILLING }
    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final MovementController movement;
    private final BotInput input;
    private Phase phase = Phase.IDLE;
    private Object ownerPlayer, ownerWorld;
    private long startedAt, progressAt;
    private double progressX, progressY, progressZ;
    private int attempts;
    private boolean planningStarted, planningSwim;
    private MovementController.AirExitPreference planningPreference;
    private final Set<BlockPos> rejected = new HashSet<>();
    private final Set<BlockPos> heightHolds = new HashSet<>();
    private List<BlockPos> offeredGoals = List.of();
    private List<BlockPos> swimRoute = List.of();
    private int swimIndex;
    private int diagnosticSamples;
    private long diagnosticLastSampleAt;

    AirRecoveryAction(MinecraftClient client, LodekeeperConfig config, MovementController movement, BotInput input) {
        this.client = client; this.config = config; this.movement = movement; this.input = input;
    }

    boolean ready() {
        return client.player != null && client.world != null && client.player.isAlive()
                && client.player.isSubmergedInWater()
                && (client.player.getAir() <= client.player.getMaxAir() * 2 / 3 || client.player.getHealth() <= config.pauseBelowHealth);
    }

    boolean active() { return phase != Phase.IDLE; }

    void begin() {
        movement.checkAirRecoveryOwnership();
        ownerPlayer = client.player; ownerWorld = client.world;
        startedAt = progressAt = System.nanoTime();
        attempts = 0; rejected.clear(); phase = Phase.DRAINING;
        diagnosticSamples = 0; diagnosticLastSampleAt = 0; heightHolds.clear();
        log("begin");
    }

    boolean tick() {
        try { return tickOwned(); }
        catch (RuntimeException failed) { movement.discardAirPlanning(); input.release(); throw failed; }
        finally { sampleDiagnostics(); }
    }

    private boolean tickOwned() {
        if (!active()) return true;
        if (ownerPlayer != client.player || ownerWorld != client.world)
            throw new IllegalStateException("player or world changed during air recovery");
        if (manualInput()) throw new IllegalStateException("manual input has priority over air recovery");
        movement.checkAirRecoveryOwnership();
        if (System.nanoTime() - startedAt >= 20_000_000_000L)
            throw new IllegalStateException("air escape exceeded 20 seconds");
        if (phase == Phase.DRAINING) { movement.stop(); phase = Phase.STOPPING; }
        if (phase == Phase.STOPPING) {
            if (!movement.finishCancellation()) return false;
            if (!client.player.isSubmergedInWater() && client.player.isOnGround()) phase = Phase.REFILLING;
            else startPlanning(true, false);
        }
        // Planning owns one slice for this tick; admitted movement starts on its later live tick.
        if (phase == Phase.PLANNING) { tickPlanning(); return false; }
        if (phase == Phase.SWIMMING) tickSwimming();
        if (phase == Phase.ESCAPING) {
            if (!client.player.isSubmergedInWater() && client.player.isOnGround()) {
                movement.stop(); phase = Phase.REFILLING; log("breathing");
            } else {
                boolean ended;
                try { ended = movement.tick(); }
                catch (MovementController.NavigationFailure failure) {
                    if (failure.kind != MovementController.NavigationFailure.Kind.PROCESS_ENDED) throw failure;
                    ended = true;
                }
                double dx = client.player.getX() - progressX, dy = client.player.getY() - progressY,
                        dz = client.player.getZ() - progressZ;
                if (dx * dx + dy * dy + dz * dz >= .25) observeProgress();
                if (ended && !client.player.isSubmergedInWater()) {
                    movement.stop(); phase = Phase.REFILLING; log("breathing");
                } else if (ended || System.nanoTime() - progressAt >= 3_000_000_000L) {
                    BlockPos destination = movement.airRecoveryDestination();
                    if (destination != null && rejected.size() < 32) rejected.add(destination.toImmutable());
                    else for (BlockPos offered : offeredGoals) {
                        if (rejected.size() == 32) break;
                        rejected.add(offered);
                    }
                    movement.stop(); phase = Phase.STOPPING; log("retry");
                }
            }
        }
        if (phase == Phase.REFILLING) {
            if (!movement.finishCancellation()) return false;
            boolean floating = !client.player.isOnGround() && client.player.isTouchingWater();
            if (floating) { input.acquire(); input.drive(0, 0, true, false); }
            else input.release();
            if (client.player.isSubmergedInWater()) {
                if (!floating) phase = Phase.STOPPING;
                return false;
            }
            if (client.player.getAir() >= client.player.getMaxAir() * 9 / 10) {
                log("complete"); abandon(); return true;
            }
        }
        return false;
    }

    private boolean manualInput() {
        var options = client.options;
        return options.attackKey.isPressed() || options.useKey.isPressed()
                || options.forwardKey.isPressed() || options.backKey.isPressed()
                || options.leftKey.isPressed() || options.rightKey.isPressed()
                || options.jumpKey.isPressed() || options.sneakKey.isPressed() || options.sprintKey.isPressed();
    }

    private void startPlanning(boolean allowSwim, boolean continuation) {
        if (attempts >= 3) throw new IllegalStateException("no breathable route after three attempts");
        input.release(); movement.discardAirPlanning();
        offeredGoals = swimRoute = List.of(); swimIndex = 0;
        planningStarted = false;
        planningSwim = allowSwim && client.player.isSubmergedInWater();
        if (!continuation) planningPreference = attempts == 0
                ? MovementController.AirExitPreference.DRY : MovementController.AirExitPreference.SURFACE;
        phase = Phase.PLANNING;
    }

    private void tickPlanning() {
        input.release();
        if (!client.player.isSubmergedInWater() && client.player.isOnGround()) {
            movement.discardAirPlanning(); planningStarted = false; phase = Phase.REFILLING;
            return;
        }
        long deadline = movement.airSwimObservationDeadline();
        if (!planningStarted) {
            if (!movement.beginAirPlanning(planningSwim, Set.copyOf(rejected), planningPreference, deadline)) return;
            planningStarted = true;
        }
        var result = movement.pollAirPlanning(deadline);
        if (result instanceof MovementController.AirPlanPending) return;
        if (result instanceof MovementController.AirPlanRefused refused)
            throw new IllegalStateException(refused.reason());
        var ready = (MovementController.AirPlanReady) result;
        attempts++;
        planningStarted = false;
        if (ready.kind() == MovementController.AirPlanKind.SWIM) {
            swimRoute = ready.positions(); swimIndex = 0;
            phase = Phase.SWIMMING; observeProgress(); log("swim");
        } else {
            offeredGoals = ready.positions();
            phase = Phase.ESCAPING; observeProgress(); log("route");
        }
    }

    private void tickSwimming() {
        if (!client.player.isSubmergedInWater() && client.player.isOnGround()) {
            input.release(); phase = Phase.REFILLING; log("breathing");
            return;
        }
        double dx = client.player.getX() - progressX, dy = client.player.getY() - progressY,
                dz = client.player.getZ() - progressZ;
        if (dx * dx + dy * dy + dz * dz >= .25) observeProgress();
        long deadline = movement.airSwimObservationDeadline();
        BlockPos admitted = null;
        boolean centering = false;
        while (swimIndex < swimRoute.size()) {
            BlockPos next = swimRoute.get(swimIndex);
            dx = next.getX() + .5 - client.player.getX();
            dz = next.getZ() + .5 - client.player.getZ();
            dy = next.getY() - client.player.getY();
            boolean terminal = swimIndex == swimRoute.size() - 1;
            if (!terminal && dy > 0 && dy <= .35 && dx * dx + dz * dz <= .0784) logHeightHold(next);
            // Reach the waypoint's feet height before a following-edge refusal can enable lateral centering.
            if (dx * dx + dz * dz > .0784
                    || (terminal ? Math.floor(client.player.getY()) < next.getY() : dy > 0 || Math.abs(dy) > .35)) break;
            // Finish a clear current segment before a conservative actual-body sweep can take the turn.
            BlockPos following = terminal ? null : swimRoute.get(swimIndex + 1);
            if (!terminal && !movement.airSwimStepClear(following, deadline)) {
                centering = true;
                break;
            }
            if (System.nanoTime() > deadline) {
                input.release(); phase = Phase.STOPPING; log("swim-retry");
                return;
            }
            admitted = following;
            swimIndex++; observeProgress();
        }
        if (swimIndex == swimRoute.size()) {
            if (!client.player.isSubmergedInWater()) startPlanning(false, true);
            else { input.release(); phase = Phase.STOPPING; log("swim-retry"); }
            return;
        }
        BlockPos next = swimRoute.get(swimIndex);
        if ((!next.equals(admitted) && !movement.airSwimStepClear(next, deadline))
                || System.nanoTime() > deadline || System.nanoTime() - progressAt >= 3_000_000_000L) {
            input.release(); phase = Phase.STOPPING; log("swim-retry");
            return;
        }
        dx = next.getX() + .5 - client.player.getX();
        dz = next.getZ() + .5 - client.player.getZ();
        double distanceSquared = dx * dx + dz * dz;
        boolean horizontal = distanceSquared > .04 || centering && distanceSquared > 0;
        // Reuse the normal .2 drive radius to damp correction without leaving a centering dead zone.
        float forward = horizontal ? 1 : 0;
        if (centering) forward = (float) Math.min(1, Math.sqrt(distanceSquared) / .2);
        boolean descend = next.getY() < client.player.getY() - .4;
        if (System.nanoTime() > deadline) {
            input.release(); phase = Phase.STOPPING; log("swim-retry");
            return;
        }
        if (horizontal) {
            client.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            client.player.setPitch(0);
        }
        input.acquire();
        input.drive(forward, 0, !descend, descend);
    }

    private void logHeightHold(BlockPos waypoint) {
        if (!config.debugLogging || heightHolds.size() >= MAX_DIAGNOSTIC_HEIGHT_HOLDS || heightHolds.contains(waypoint)) return;
        try {
            var logger = org.slf4j.LoggerFactory.getLogger("lodekeeper");
            if (!logger.isInfoEnabled()) return;
            heightHolds.add(waypoint.toImmutable());
            logger.info("[Lodekeeper] AIR_HEIGHT_HOLD observationPoint=arrival-check sample={} sampleCap=8 waypoint={} actualY={} targetY={} acceptedAbove=.35 horizontalRadius=.28 attempts={} elapsedMs={}",
                    heightHolds.size(), waypoint, client.player.getY(), waypoint.getY(), attempts,
                    (System.nanoTime() - startedAt) / 1_000_000L);
        } catch (Throwable ignored) {
        }
    }

    private void observeProgress() {
        progressAt = System.nanoTime();
        progressX = client.player.getX(); progressY = client.player.getY(); progressZ = client.player.getZ();
    }

    void stop() {
        movement.discardAirPlanning();
        try {
            if (active()) { movement.checkAirRecoveryOwnership(); movement.stop(); }
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
        } finally { abandon(); }
    }

    void abandon() {
        movement.discardAirPlanning(); planningStarted = false;
        input.release(); phase = Phase.IDLE; ownerPlayer = ownerWorld = null;
        rejected.clear(); offeredGoals = swimRoute = List.of(); swimIndex = 0;
        diagnosticSamples = 0; diagnosticLastSampleAt = 0; heightHolds.clear();
    }

    String status() {
        return "recovering air · " + phase.name().toLowerCase(java.util.Locale.ROOT)
                + " · " + client.player.getAir() + "/" + client.player.getMaxAir();
    }

    private void sampleDiagnostics() {
        try {
            if (!config.debugLogging || diagnosticSamples >= MAX_DIAGNOSTIC_SAMPLES) return;
            if (!client.isOnThread() || !active() || client.player == null || client.world == null
                    || ownerPlayer != client.player || ownerWorld != client.world) return;
            long now = System.nanoTime();
            if (diagnosticSamples != 0 && now - diagnosticLastSampleAt < 1_000_000_000L) return;
            var logger = org.slf4j.LoggerFactory.getLogger("lodekeeper");
            if (!logger.isInfoEnabled()) return;
            diagnosticLastSampleAt = now;
            diagnosticSamples++;
            var player = client.player;
            var velocity = player.getVelocity();
            var eye = player.getEyePos();
            var feetCell = player.getBlockPos();
            var feetFluid = client.world.getFluidState(feetCell);
            var applied = player.input;
            boolean inputOwned = applied == input;
            String ownedInputState = "unowned";
            if (inputOwned) {
                try {
                    ownedInputState = (Object) input instanceof DiagnosticInput diagnostic
                            ? diagnostic.diagnosticState() : "unavailable-api-family";
                } catch (Throwable failure) {
                    ownedInputState = "unavailable-" + failure.getClass().getSimpleName();
                }
            }
            BlockPos currentTarget = swimIndex < swimRoute.size() ? swimRoute.get(swimIndex) : null;
            BlockPos followingTarget = swimIndex + 1 < swimRoute.size() ? swimRoute.get(swimIndex + 1) : null;
            int inspected = 0, hostiles = 0;
            StringBuilder nearby = new StringBuilder();
            String hostileCapability = "loaded-client-entities";
            try {
                var entities = client.world.getEntities().iterator();
                while (inspected < MAX_DIAGNOSTIC_ENTITIES && hostiles < MAX_DIAGNOSTIC_HOSTILES && entities.hasNext()) {
                    var entity = entities.next();
                    inspected++;
                    if (!(entity instanceof net.minecraft.entity.mob.MobEntity mob)
                            || !(entity instanceof net.minecraft.entity.mob.Monster) || !mob.isAlive()
                            || player.squaredDistanceTo(mob) > 256.0) continue;
                    var target = mob.getTarget();
                    if (hostiles++ != 0) nearby.append(';');
                    nearby.append("uuid=").append(mob.getUuid()).append("/type=").append(mob.getType())
                            .append("/xyz=").append(mob.getX()).append(',').append(mob.getY()).append(',').append(mob.getZ())
                            .append("/health=").append(mob.getHealth()).append("/velocity=").append(mob.getVelocity())
                            .append("/bbox=").append(mob.getBoundingBox())
                            .append("/clientTargetUuid=").append(target == null ? "none" : target.getUuid())
                            .append("/clientTargetsPlayer=").append(target == player);
                }
            } catch (Throwable failure) {
                nearby.setLength(0); hostiles = 0;
                hostileCapability = "unavailable-" + failure.getClass().getSimpleName();
            }
            logger.info(
                    "[Lodekeeper] AIR_SAMPLE sample={} sampleCap=16 sampleIntervalMs=1000 observationPoint=air-tick-exit phase={} action={} actionStartNanos={} movement={} ownerPlayer={} player={} playerUuid={} ownerWorld={} world={} input={} air={} maxAir={} submerged={} waterContact={} onGround={} pose={} health={} feet={},{},{} feetCell={} feetFluidAtCell={} eye={},{},{} velocity={},{},{} horizontalCollision={} verticalCollision={} bbox={} attempts={} elapsedMs={} swimIndex={} routeSize={} currentTarget={} followingTarget={} nativeTarget=unavailable-read-only-api inputOwned={} inputClass={} ownedInputState={} appliedTiming=last-vanilla-input-update nativeForcedInput=unavailable-air-action-api hostileCapability={} hostilePolicy=monster-marker-includes-neutral nearbyRadius=16 coverage=loaded-entity-prefix inspected={} inspectedCap=32 hostileCount={} hostileCap=8 coverageCapped={} nearbyHostiles={}",
                    diagnosticSamples, phase, System.identityHashCode(this), startedAt, System.identityHashCode(movement),
                    System.identityHashCode(ownerPlayer), System.identityHashCode(player), player.getUuid(),
                    System.identityHashCode(ownerWorld), System.identityHashCode(client.world), System.identityHashCode(input),
                    player.getAir(), player.getMaxAir(), player.isSubmergedInWater(), player.isTouchingWater(),
                    player.isOnGround(), player.getPose(), player.getHealth(), player.getX(), player.getY(), player.getZ(), feetCell, feetFluid,
                    eye.x, eye.y, eye.z, velocity.x, velocity.y, velocity.z, player.horizontalCollision,
                    player.verticalCollision, player.getBoundingBox(), attempts, (now - startedAt) / 1_000_000L,
                    swimIndex, swimRoute.size(), currentTarget, followingTarget,
                    inputOwned, applied.getClass().getSimpleName(), ownedInputState, hostileCapability, inspected, hostiles,
                    inspected == MAX_DIAGNOSTIC_ENTITIES || hostiles == MAX_DIAGNOSTIC_HOSTILES, nearby);
        } catch (Throwable ignored) {
        }
    }

    private void log(String event) {
        if (!config.debugLogging) return;
        try {
            var logger = org.slf4j.LoggerFactory.getLogger("lodekeeper");
            if (!logger.isInfoEnabled()) return;
            logger.info(
                "[Lodekeeper] AIR event={} phase={} air={} maxAir={} submerged={} attempts={} elapsedMs={} swimIndex={} swimSteps={} feet={},{},{}",
                event, phase, client.player.getAir(), client.player.getMaxAir(), client.player.isSubmergedInWater(),
                attempts, (System.nanoTime() - startedAt) / 1_000_000L, swimIndex, swimRoute.size(),
                client.player.getX(), client.player.getY(), client.player.getZ());
        } catch (Throwable ignored) {
        }
    }
}
