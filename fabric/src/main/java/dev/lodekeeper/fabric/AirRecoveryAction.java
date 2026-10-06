package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Breath recovery owns navigation until the player's eyes and air supply recover. */
final class AirRecoveryAction {
    private enum Phase { IDLE, DRAINING, STOPPING, SWIMMING, ESCAPING, REFILLING }
    private final MinecraftClient client;
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
        log("begin");
    }

    boolean tick() {
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
            else launch();
        }
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

    private void launch() {
        if (++attempts > 3) throw new IllegalStateException("no breathable route after three attempts");
        swimRoute = client.player.isSubmergedInWater() ? movement.airSwimRoute() : List.of();
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
        if (!client.player.isSubmergedInWater() && client.player.isOnGround()) {
            input.release(); phase = Phase.REFILLING; log("breathing");
            return;
        }
        double dx = client.player.getX() - progressX, dy = client.player.getY() - progressY,
                dz = client.player.getZ() - progressZ;
        if (dx * dx + dy * dy + dz * dz >= .25) observeProgress();
        while (swimIndex < swimRoute.size()) {
            BlockPos next = swimRoute.get(swimIndex);
            dx = next.getX() + .5 - client.player.getX();
            dz = next.getZ() + .5 - client.player.getZ();
            dy = next.getY() - client.player.getY();
            boolean terminal = swimIndex == swimRoute.size() - 1;
            if (dx * dx + dz * dz > .0784
                    || (terminal ? Math.floor(client.player.getY()) < next.getY() : Math.abs(dy) > .35)) break;
            swimIndex++; observeProgress();
        }
        if (swimIndex == swimRoute.size()) {
            if (!client.player.isSubmergedInWater()) launchNative();
            else { input.release(); phase = Phase.STOPPING; log("swim-retry"); }
            return;
        }
        BlockPos next = swimRoute.get(swimIndex);
        if (!movement.airSwimStepClear(next) || System.nanoTime() - progressAt >= 3_000_000_000L) {
            input.release(); phase = Phase.STOPPING; log("swim-retry");
            return;
        }
        dx = next.getX() + .5 - client.player.getX();
        dz = next.getZ() + .5 - client.player.getZ();
        boolean horizontal = dx * dx + dz * dz > .04;
        boolean descend = next.getY() < client.player.getY() - .4;
        if (horizontal) {
            client.player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            client.player.setPitch(0);
        }
        input.acquire();
        input.drive(horizontal ? 1 : 0, 0, !descend, descend);
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
    }

    String status() {
        return "recovering air · " + phase.name().toLowerCase(java.util.Locale.ROOT)
                + " · " + client.player.getAir() + "/" + client.player.getMaxAir();
    }

    private void log(String event) {
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] AIR event={} phase={} air={} maxAir={} submerged={} attempts={} elapsedMs={} swimIndex={} swimSteps={} feet={},{},{}",
                event, phase, client.player.getAir(), client.player.getMaxAir(), client.player.isSubmergedInWater(),
                attempts, (System.nanoTime() - startedAt) / 1_000_000L, swimIndex, swimRoute.size(),
                client.player.getX(), client.player.getY(), client.player.getZ());
    }
}
