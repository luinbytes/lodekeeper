package dev.lodekeeper.fabric;

import dev.lodekeeper.core.TravelGoal;
import dev.lodekeeper.nav.ExplorationFrontier;
import dev.lodekeeper.nav.StanceProbe;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import java.util.function.BooleanSupplier;

final class TravelAction implements NativeRun {
    private enum Phase { ADMISSION, SEARCH, MOVE, FOLLOW, SUSPENDED, DRAIN, DONE }
    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final MovementController movement;
    private final GameTerrain terrain;
    private final AutomationEngine.TravelRequest request;
    private final BooleanSupplier contextCurrent, effectCurrent;
    private final StanceProbe probe = new StanceProbe();
    private final int originX, originZ;
    private final ExplorationFrontier frontier;
    private Phase phase = Phase.ADMISSION;
    private Outcome outcome = new Outcome.Pending("travel admission");
    private TravelResult terminal;
    private String reason = "";
    private ExplorationFrontier.Waypoint waypoint;
    private int arrivedSegments, failures;
    private net.minecraft.entity.Entity followedEntity;
    private boolean routeStarted, resumeAllowed = true;
    private long missingSince, lastProgressNanos;
    private double progressX, progressY, progressZ;

    TravelAction(MinecraftClient client, LodekeeperConfig config, MovementController movement,
                 GameTerrain terrain, AutomationEngine.TravelRequest request, BooleanSupplier contextCurrent, BooleanSupplier effectCurrent) {
        this.client = client;
        this.config = config;
        this.movement = movement;
        this.terrain = terrain;
        this.request = request;
        this.contextCurrent = contextCurrent;
        this.effectCurrent = effectCurrent;
        originX = client.player.getBlockPos().getX();
        originZ = client.player.getBlockPos().getZ();
        frontier = request.goal() instanceof TravelGoal.ExploreGoal goal
                ? new ExplorationFrontier(originX, originZ, goal.segments() + 4, goal.radius()) : null;
        lastProgressNanos = System.nanoTime();
        sampleProgress();
    }

    boolean observingDrain() { return phase == Phase.DRAIN || phase == Phase.DONE; }
    boolean suspended() { return phase == Phase.SUSPENDED; }
    void resume() { if (terminal == null) resumeAllowed = true; }
    String status() { return outcome instanceof Outcome.Pending pending ? pending.status() : reason; }

    @Override public Outcome tick() {
        if (phase == Phase.DONE) return outcome;
        if (!contextCurrent.getAsBoolean()) { abandonSession(); return outcome; }
        long now = System.nanoTime();
        if (terminal == null && now - request.context().deadlineNanos() >= 0)
            end(request.goal() instanceof TravelGoal.FollowGoal ? TravelResult.FOLLOW_EXPIRED : TravelResult.REFUSED,
                    "travel deadline expired");
        try {
            if (phase == Phase.DRAIN) return drain();
            if (phase == Phase.SUSPENDED) {
                if (!resumeAllowed) return outcome;
                phase = Phase.ADMISSION;
            }
            int admittedRadius = request.goal() instanceof TravelGoal.ExploreGoal explore ? explore.radius() : 256;
            if (!GameApi.travelPose(client) || !withinOrigin(client.player.getBlockPos(), admittedRadius)) {
                end(TravelResult.REFUSED, "travel pose or origin radius is no longer admitted");
                return drain();
            }
            movement.checkAirRecoveryOwnership();
            if (phase == Phase.ADMISSION) admit();
            if (phase == Phase.DRAIN) return drain();
            if (phase == Phase.SEARCH) search();
            if (phase == Phase.DRAIN) return drain();
            if (phase == Phase.MOVE) move(now);
            else if (phase == Phase.FOLLOW) follow(now);
            return outcome;
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) {
                routeStarted = false;
                resumeAllowed = false;
                if (terminal != null) {
                    phase = Phase.DONE;
                    outcome = new Outcome.TravelFinished(new TravelReceipt(request.context().provenanceSession(),
                            request.jobToken(), terminal, arrivedSegments, reason));
                } else {
                    phase = Phase.SUSPENDED;
                    reason = failure.getMessage();
                    outcome = new Outcome.Blocked(reason);
                }
                return outcome;
            }
            failures++;
            if (failures >= 4 || frontier == null) end(TravelResult.REFUSED, failure.getMessage());
            else { waypoint = null; requestDrain(DrainReason.REPLAN); }
            return outcome;
        } catch (RuntimeException failure) {
            end(TravelResult.REFUSED, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            return outcome;
        }
    }

    private void admit() {
        if (!currentStance()) { end(TravelResult.REFUSED, "current travel stance is unsupported"); return; }
        if (request.goal() instanceof TravelGoal.PointGoal point) {
            waypoint = new ExplorationFrontier.Waypoint(point.x(), point.feetY(), point.z());
            if (!destinationAdmitted(waypoint, 256)) { end(TravelResult.REFUSED, "coordinate stance is not loaded, safe or within bounds"); return; }
            startLeg(false);
        } else if (request.goal() instanceof TravelGoal.ExploreGoal) {
            if (!config.allowExploration) { end(TravelResult.REFUSED, "allowExploration is disabled"); return; }
            if (waypoint != null && destinationAdmitted(waypoint, ((TravelGoal.ExploreGoal) request.goal()).radius())) startLeg(true);
            else beginSearch();
        } else {
            phase = Phase.FOLLOW;
            outcome = new Outcome.Pending("following the pinned player");
        }
    }

    private void beginSearch() {
        int feet = exactFeetY16();
        if (feet == GameTerrain.INVALID_FEET_Y16) { end(TravelResult.REFUSED, "unsupported exploration feet height"); return; }
        terrain.beginSearch();
        BlockPos position = client.player.getBlockPos();
        frontier.beginAt16(position.getX(), feet, position.getZ());
        waypoint = null;
        phase = Phase.SEARCH;
    }

    private void search() {
        if (!config.allowExploration) { end(TravelResult.REFUSED, "allowExploration is disabled"); return; }
        ExplorationFrontier.Status state = frontier.advance(terrain, 32, 1_000_000L);
        outcome = new Outcome.Pending("travel frontier " + frontier.attempts() + "; arrived " + arrivedSegments);
        if (state == ExplorationFrontier.Status.EXHAUSTED) {
            end(TravelResult.REFUSED, "exploration exhausted after " + arrivedSegments + " observed legs");
        } else if (state == ExplorationFrontier.Status.READY) {
            waypoint = frontier.waypoint();
            if (!destinationAdmitted(waypoint, ((TravelGoal.ExploreGoal) request.goal()).radius())) {
                if (++failures >= 4) end(TravelResult.REFUSED, "four exploration routes were refused");
                else beginSearch();
                return;
            }
            startLeg(true);
        }
    }

    private void startLeg(boolean exploration) {
        if (!effectCurrent.getAsBoolean() || System.nanoTime() - request.context().deadlineNanos() >= 0
                || System.nanoTime() - lastProgressNanos >= 15_000_000_000L) {
            end(TravelResult.REFUSED, "travel authority expired before route start"); return;
        }
        movement.checkAirRecoveryOwnership();
        if (exploration) movement.startExploration(waypoint, MovementController.RouteEffects.MOVEMENT_ONLY, effectCurrent);
        else movement.startCoordinate(new BlockPos(waypoint.x(), waypoint.y(), waypoint.z()), MovementController.RouteEffects.MOVEMENT_ONLY, effectCurrent);
        routeStarted = true;
        phase = Phase.MOVE;
        outcome = new Outcome.Pending("travelling to " + waypoint.x() + "," + waypoint.feetY() + "," + waypoint.z());
    }

    private void move(long now) {
        if (frontier != null && !config.allowExploration) { end(TravelResult.REFUSED, "allowExploration is disabled"); return; }
        if (progressed()) lastProgressNanos = now;
        if (now - lastProgressNanos >= 15_000_000_000L) { end(TravelResult.REFUSED, "15 seconds without observed travel progress"); return; }
        if (!movement.tick()) return;
        routeStarted = false;
        if (!atWaypoint() || !movement.travelReleased()) { end(TravelResult.REFUSED, "route ended without exact supported arrival and release"); return; }
        arrivedSegments++;
        if (request.goal() instanceof TravelGoal.PointGoal) end(TravelResult.ARRIVED, "client/native observed arrival");
        else if (arrivedSegments >= ((TravelGoal.ExploreGoal) request.goal()).segments())
            end(TravelResult.EXPLORED, "client/native observed " + arrivedSegments + " exploration legs");
        else { waypoint = null; beginSearch(); }
    }

    private void follow(long now) {
        var goal = (TravelGoal.FollowGoal) request.goal();
        var target = GameApi.loadedTravelPlayer(client, goal.target());
        if (missingSince != 0 && now - missingSince >= 5_000_000_000L) {
            end(TravelResult.REFUSED, "pinned player missing for five seconds"); return;
        }
        if (target == null || routeStarted && target != followedEntity) {
            if (target == null && missingSince == 0) missingSince = now;
            if (routeStarted) {
                movement.checkAirRecoveryOwnership(); movement.stop();
                if (!movement.finishCancellation()) return;
                routeStarted = false; followedEntity = null;
            }
            if (target != null) { outcome = new Outcome.Pending("rebinding the same pinned player after entity replacement"); return; }
            if (now - missingSince >= 5_000_000_000L) end(TravelResult.REFUSED, "pinned player missing for five seconds");
            else outcome = new Outcome.Pending("waiting for the same pinned player");
            return;
        }
        missingSince = 0;
        boolean waiting = routeStarted && movement.followGoalSettled(target, goal.target());
        if (waiting || progressed()) lastProgressNanos = now;
        if (now - lastProgressNanos >= 15_000_000_000L) { end(TravelResult.REFUSED, "15 seconds without observed follow progress"); return; }
        if (!routeStarted) {
            if (System.nanoTime() - request.context().deadlineNanos() >= 0) { end(TravelResult.FOLLOW_EXPIRED, "follow expired"); return; }
            movement.startFollowingPlayer(goal.target(), target, MovementController.RouteEffects.MOVEMENT_ONLY, effectCurrent);
            followedEntity = target;
            routeStarted = true;
        }
        movement.tick();
        outcome = new Outcome.Pending(waiting ? "following; waiting at native goal" : "following the pinned player");
    }

    private boolean destinationAdmitted(ExplorationFrontier.Waypoint destination, int radius) {
        BlockPos feet = new BlockPos(destination.x(), destination.y(), destination.z());
        if (!GameApi.travelBounds(client, feet) || !withinOrigin(feet, radius)) return false;
        terrain.probeStance16(feet.getX(), Math.toIntExact(destination.feetY16()), feet.getZ(), probe.clear());
        return safeProbe();
    }
    private boolean withinOrigin(BlockPos feet, int radius) {
        long dx = (long) feet.getX() - originX, dz = (long) feet.getZ() - originZ;
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius && dx * dx + dz * dz <= (long) radius * radius;
    }
    private int exactFeetY16() {
        double scaled = client.player.getY() * 16.0, rounded = Math.rint(scaled);
        return !Double.isFinite(scaled) || Math.abs(scaled - rounded) > 1.0e-5
                || rounded <= Integer.MIN_VALUE || rounded > Integer.MAX_VALUE ? GameTerrain.INVALID_FEET_Y16 : (int) rounded;
    }
    private boolean currentStance() {
        int feet = exactFeetY16();
        if (feet == GameTerrain.INVALID_FEET_Y16 || !client.player.isOnGround()) return false;
        terrain.probeCurrentStance(client.player.getX(), feet, client.player.getZ(), probe.clear());
        return safeProbe();
    }
    private boolean safeProbe() {
        return probe.loaded && probe.bodyClear && probe.hasGroundSupport() && !probe.hazard
                && !probe.water && !probe.climbable && probe.breakCount == 0;
    }
    private boolean atWaypoint() {
        BlockPos feet = client.player.getBlockPos();
        return feet.getX() == waypoint.x() && feet.getZ() == waypoint.z()
                && exactFeetY16() == waypoint.feetY16() && currentStance();
    }
    private boolean progressed() {
        double dx = client.player.getX() - progressX, dy = client.player.getY() - progressY, dz = client.player.getZ() - progressZ;
        if (dx * dx + dy * dy + dz * dz < 0.0625) return false;
        sampleProgress(); return true;
    }
    private void sampleProgress() { progressX = client.player.getX(); progressY = client.player.getY(); progressZ = client.player.getZ(); }
    private void end(TravelResult result, String detail) {
        if (terminal == null) { terminal = result; reason = detail; }
        phase = Phase.DRAIN;
    }
    private Outcome drain() {
        movement.checkAirRecoveryOwnership();
        movement.stop();
        if (!movement.finishCancellation() || !movement.travelReleased())
            return outcome = new Outcome.Pending("draining travel movement and settings");
        routeStarted = false;
        if (terminal == null) {
            phase = Phase.SUSPENDED;
            return outcome = new Outcome.Pending("travel suspended with its original budget");
        }
        phase = Phase.DONE;
        return outcome = new Outcome.TravelFinished(new TravelReceipt(request.context().provenanceSession(),
                request.jobToken(), terminal, arrivedSegments, reason));
    }
    @Override public void requestDrain(DrainReason drain) {
        if (phase == Phase.DONE) return;
        if (drain == DrainReason.STOP) end(TravelResult.CANCELLED, "cancelled");
        else if (drain == DrainReason.FAILURE) end(TravelResult.REFUSED, "travel failed");
        else phase = Phase.DRAIN;
    }
    @Override public void pause() { resumeAllowed = false; requestDrain(DrainReason.SCREEN); }
    @Override public void abandonSession() {
        routeStarted = false;
        phase = Phase.DONE;
        terminal = TravelResult.REFUSED;
        reason = "travel context changed; old effects were abandoned";
        outcome = new Outcome.TravelFinished(new TravelReceipt(request.context().provenanceSession(),
                request.jobToken(), terminal, arrivedSegments, reason));
    }
    @Override public boolean safeToRelease() {
        return (phase == Phase.DONE || phase == Phase.SUSPENDED) && !routeStarted;
    }
}
