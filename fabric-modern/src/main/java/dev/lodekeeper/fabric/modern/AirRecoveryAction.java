package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Breath recovery owns navigation until the player's eyes and air supply recover. */
final class AirRecoveryAction {
    interface DiagnosticInput { String diagnosticState(); }
    private static final int MAX_DIAGNOSTIC_SAMPLES = 16;
    private static final int MAX_DIAGNOSTIC_ENTITIES = 32;
    private static final int MAX_DIAGNOSTIC_HOSTILES = 8;
    private enum Phase { IDLE, DRAINING, STOPPING, SWIMMING, ESCAPING, REFILLING }
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final MovementController movement;
    private final BotInput input;
    private Phase phase = Phase.IDLE;
    private Object ownerPlayer, ownerWorld;
    private long startedAt, progressAt;
    private double progressX, progressY, progressZ;
    private int attempts;
    private final Set<BlockPos> rejected = new HashSet<>();
    private List<BlockPos> offeredGoals = List.of();
    private List<BlockPos> swimRoute = List.of();
    private int swimIndex;
    private int diagnosticSamples;
    private long diagnosticLastSampleAt;

    AirRecoveryAction(Minecraft client, LodekeeperConfig config, MovementController movement, BotInput input) {
        this.client = client; this.config = config; this.movement = movement; this.input = input;
    }

    boolean ready() {
        return client.player != null && client.level != null && client.player.isAlive()
                && client.player.isUnderWater()
                && (client.player.getAirSupply() <= client.player.getMaxAirSupply() * 2 / 3 || client.player.getHealth() <= config.pauseBelowHealth);
    }

    boolean active() { return phase != Phase.IDLE; }

    void begin() {
        movement.checkAirRecoveryOwnership();
        ownerPlayer = client.player; ownerWorld = client.level;
        startedAt = progressAt = System.nanoTime();
        attempts = 0; rejected.clear(); phase = Phase.DRAINING;
        diagnosticSamples = 0; diagnosticLastSampleAt = 0;
        log("begin");
    }

    boolean tick() {
        try { return tickOwned(); }
        finally { sampleDiagnostics(); }
    }

    private boolean tickOwned() {
        if (!active()) return true;
        if (ownerPlayer != client.player || ownerWorld != client.level)
            throw new IllegalStateException("player or world changed during air recovery");
        if (manualInput()) throw new IllegalStateException("manual input has priority over air recovery");
        movement.checkAirRecoveryOwnership();
        if (System.nanoTime() - startedAt >= 20_000_000_000L)
            throw new IllegalStateException("air escape exceeded 20 seconds");
        if (phase == Phase.DRAINING) { movement.stop(); phase = Phase.STOPPING; }
        if (phase == Phase.STOPPING) {
            if (!movement.finishCancellation()) return false;
            if (!client.player.isUnderWater() && client.player.onGround()) phase = Phase.REFILLING;
            else launch();
        }
        if (phase == Phase.SWIMMING) tickSwimming();
        if (phase == Phase.ESCAPING) {
            if (!client.player.isUnderWater() && client.player.onGround()) {
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
                if (ended && !client.player.isUnderWater()) {
                    movement.stop(); phase = Phase.REFILLING; log("breathing");
                } else if (ended || System.nanoTime() - progressAt >= 3_000_000_000L) {
                    BlockPos destination = movement.airRecoveryDestination();
                    if (destination != null && rejected.size() < 32) rejected.add(destination.immutable());
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
            boolean floating = !client.player.onGround() && client.player.isInWater();
            if (floating) { input.acquire(client); input.drive(0, 0, true, false); }
            else input.release();
            if (client.player.isUnderWater()) {
                if (!floating) phase = Phase.STOPPING;
                return false;
            }
            if (client.player.getAirSupply() >= client.player.getMaxAirSupply() * 9 / 10) {
                log("complete"); abandon(); return true;
            }
        }
        return false;
    }

    private boolean manualInput() {
        var options = client.options;
        return options.keyAttack.isDown() || options.keyUse.isDown()
                || options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown() || options.keyShift.isDown() || options.keySprint.isDown();
    }

    private void launch() {
        if (++attempts > 3) throw new IllegalStateException("no breathable route after three attempts");
        swimRoute = client.player.isUnderWater() ? movement.airSwimRoute() : List.of();
        swimIndex = 0;
        if (!swimRoute.isEmpty()) {
            observeProgress(); phase = Phase.SWIMMING; log("swim");
            return;
        }
        launchNative();
    }

    private void launchNative() {
        input.release();
        offeredGoals = movement.startAirRecovery(Set.copyOf(rejected), attempts == 1
                ? MovementController.AirExitPreference.DRY : MovementController.AirExitPreference.SURFACE);
        observeProgress(); phase = Phase.ESCAPING; log("route");
    }

    private void tickSwimming() {
        if (!client.player.isUnderWater() && client.player.onGround()) {
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
            if (dx * dx + dz * dz > .0784
                    || (terminal ? Math.floor(client.player.getY()) < next.getY() : Math.abs(dy) > .35)) break;
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
            if (!client.player.isUnderWater()) launchNative();
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
            client.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            client.player.setXRot(0);
        }
        input.acquire(client);
        input.drive(forward, 0, !descend, descend);
    }

    private void observeProgress() {
        progressAt = System.nanoTime();
        progressX = client.player.getX(); progressY = client.player.getY(); progressZ = client.player.getZ();
    }

    void stop() {
        if (active()) movement.stop();
        abandon();
    }

    void abandon() {
        input.release(); phase = Phase.IDLE; ownerPlayer = ownerWorld = null;
        rejected.clear(); offeredGoals = swimRoute = List.of(); swimIndex = 0;
        diagnosticSamples = 0; diagnosticLastSampleAt = 0;
    }

    String status() {
        return "recovering air · " + phase.name().toLowerCase(java.util.Locale.ROOT)
                + " · " + client.player.getAirSupply() + "/" + client.player.getMaxAirSupply();
    }

    private void sampleDiagnostics() {
        try {
            if (!config.debugLogging || diagnosticSamples >= MAX_DIAGNOSTIC_SAMPLES) return;
            if (!client.isSameThread() || !active() || client.player == null || client.level == null
                    || ownerPlayer != client.player || ownerWorld != client.level) return;
            long now = System.nanoTime();
            if (diagnosticSamples != 0 && now - diagnosticLastSampleAt < 1_000_000_000L) return;
            var logger = org.slf4j.LoggerFactory.getLogger("lodekeeper");
            if (!logger.isInfoEnabled()) return;
            diagnosticLastSampleAt = now;
            diagnosticSamples++;
            var player = client.player;
            var velocity = player.getDeltaMovement();
            var eye = player.getEyePosition();
            var feetCell = player.blockPosition();
            var feetFluid = client.level.getFluidState(feetCell);
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
                var entities = client.level.entitiesForRendering().iterator();
                while (inspected < MAX_DIAGNOSTIC_ENTITIES && hostiles < MAX_DIAGNOSTIC_HOSTILES && entities.hasNext()) {
                    var entity = entities.next();
                    inspected++;
                    if (!(entity instanceof net.minecraft.world.entity.Mob mob)
                            || !(entity instanceof net.minecraft.world.entity.monster.Enemy) || !mob.isAlive()
                            || player.distanceToSqr(mob) > 256.0) continue;
                    var target = mob.getTarget();
                    if (hostiles++ != 0) nearby.append(';');
                    nearby.append("uuid=").append(mob.getUUID()).append("/type=").append(mob.getType())
                            .append("/xyz=").append(mob.getX()).append(',').append(mob.getY()).append(',').append(mob.getZ())
                            .append("/health=").append(mob.getHealth()).append("/velocity=").append(mob.getDeltaMovement())
                            .append("/bbox=").append(mob.getBoundingBox())
                            .append("/clientTargetUuid=").append(target == null ? "none" : target.getUUID())
                            .append("/clientTargetsPlayer=").append(target == player);
                }
            } catch (Throwable failure) {
                nearby.setLength(0); hostiles = 0;
                hostileCapability = "unavailable-" + failure.getClass().getSimpleName();
            }
            logger.info(
                    "[Lodekeeper] AIR_SAMPLE sample={} sampleCap=16 sampleIntervalMs=1000 observationPoint=air-tick-exit phase={} action={} actionStartNanos={} movement={} ownerPlayer={} player={} playerUuid={} ownerWorld={} world={} input={} air={} maxAir={} submerged={} waterContact={} onGround={} pose={} health={} feet={},{},{} feetCell={} feetFluidAtCell={} eye={},{},{} velocity={},{},{} horizontalCollision={} verticalCollision={} bbox={} attempts={} elapsedMs={} swimIndex={} routeSize={} currentTarget={} followingTarget={} nativeTarget=unavailable-read-only-api inputOwned={} inputClass={} ownedInputState={} appliedTiming=last-vanilla-input-update nativeForcedInput=unavailable-air-action-api hostileCapability={} hostilePolicy=monster-marker-includes-neutral nearbyRadius=16 coverage=loaded-entity-prefix inspected={} inspectedCap=32 hostileCount={} hostileCap=8 coverageCapped={} nearbyHostiles={}",
                    diagnosticSamples, phase, System.identityHashCode(this), startedAt, System.identityHashCode(movement),
                    System.identityHashCode(ownerPlayer), System.identityHashCode(player), player.getUUID(),
                    System.identityHashCode(ownerWorld), System.identityHashCode(client.level), System.identityHashCode(input),
                    player.getAirSupply(), player.getMaxAirSupply(), player.isUnderWater(), player.isInWater(),
                    player.onGround(), player.getPose(), player.getHealth(), player.getX(), player.getY(), player.getZ(), feetCell, feetFluid,
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
                event, phase, client.player.getAirSupply(), client.player.getMaxAirSupply(), client.player.isUnderWater(),
                attempts, (System.nanoTime() - startedAt) / 1_000_000L, swimIndex, swimRoute.size(),
                client.player.getX(), client.player.getY(), client.player.getZ());
        } catch (Throwable ignored) {
        }
    }
}
