package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.entity.Pose;

import java.util.Arrays;

/** One route at a time; every destructive action and next stance is revalidated live. */
final class MovementController {
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    private final GameTerrain terrain;
    private final SurfaceRecovery surfaceRecovery;
    private final StanceProbe probe = new StanceProbe();
    private final StanceProbe sourceProbe = new StanceProbe();
    private final StanceProbe emptyProbe = new StanceProbe();
    private final GroundedStanceBuffer candidateHeights = new GroundedStanceBuffer();
    private final long[] goalPositions = new long[128];
    private final byte[] goalFractions = new byte[128];
    private int goalCandidateCount;
    private Planner planner;
    private Path path;
    private Goal goal;
    private int pathIndex, actionIndex, ticksWithoutProgress;
    private int validatedPathIndex = -1;
    private long validatedRevision = Long.MIN_VALUE;
    private long progressToken;
    private BlockPos pendingBreakPosition, pendingPlacementPosition;
    private int pendingBreakStateId = -1;
    private Block pendingPlacementBlock;
    private double edgeStartX, edgeStartY, edgeStartZ;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private int replans, settlingTicks;
    private long searchNanos;
    private int searchTicks;
    private int nextPartialCheckTick;
    private boolean budgetedSegment;
    private int jumpEdgeIndex = -1;
    private boolean jumpWasAirborne;
    private boolean explorationRoute;
    static final class NavigationFailure extends IllegalStateException {
        NavigationFailure(String reason) { super(reason); }
    }
    void startExploration(ExplorationFrontier.Waypoint waypoint) {
        stop(); explorationRoute = true;
        int feetY16 = Math.toIntExact(waypoint.feetY16());
        goal = Goal.exact16(waypoint.x(), feetY16, waypoint.z()); replans = 0; ticksWithoutProgress = 0; prepareRoute();
    }
    MovementController(Minecraft client, LodekeeperConfig config, PlayerActions actions, BotInput input, GameTerrain terrain) {
        this.client = client; this.config = config; this.actions = actions; this.input = input; this.terrain = terrain;
        this.surfaceRecovery = new SurfaceRecovery(client, terrain, input);
    }
    void start(BlockPos target, int radius) {
        stop(); goal = Goal.near16(target.getX(), Math.multiplyExact(target.getY(), 16), target.getZ(), Math.multiplyExact(radius, 16)); replans = 0; ticksWithoutProgress = 0;
        prepareRoute();
    }
    void startInteraction(BlockPos target) {
        if (client.player == null || client.level == null) throw new NavigationFailure("World unavailable");
        resetGoalCandidates();
        int targetFeetY16 = Math.multiplyExact(target.getY(), 16);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int stanceX = target.getX() + dx, stanceZ = target.getZ() + dz;
            for (int band = 0; band < 2; band++) {
                long reference = (long) targetFeetY16 + (band == 0 ? -16L : 16L);
                if (reference < Integer.MIN_VALUE || reference > Integer.MAX_VALUE) continue;
                if (!terrain.collectGroundedStances(stanceX, (int) reference, stanceZ, candidateHeights)
                        || !candidateHeights.isComplete()) continue;
                for (int i = 0; i < candidateHeights.size(); i++) {
                    int feetY16 = candidateHeights.get(i);
                    if (band == 0 ? feetY16 > targetFeetY16
                            : feetY16 <= targetFeetY16 || feetY16 > (long) targetFeetY16 + 16L) continue;
                    terrain.probeStance16(stanceX, feetY16, stanceZ, probe);
                    if (!safeGroundedCandidate(probe, feetY16)) continue;
                    if (containsGoalCandidate(stanceX, feetY16, stanceZ)) continue;
                    if (!includeInteractionCandidate(target, stanceX, feetY16, stanceZ)) {
                        throw new NavigationFailure("Too many safe interaction stances");
                    }
                }
            }
        }
        for (int dy = -2; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int feetY16 = Math.multiplyExact(Math.addExact(target.getY(), dy), 16);
            int stanceX = target.getX() + dx, stanceZ = target.getZ() + dz;
            if (containsGoalCandidate(stanceX, feetY16, stanceZ)) continue;
            terrain.probeStance16(stanceX, feetY16, stanceZ, probe);
            if (!(probe.water || probe.climbable) || !safeGroundedCandidate(probe, feetY16)) continue;
            if (!includeInteractionCandidate(target, stanceX, feetY16, stanceZ)) {
                throw new NavigationFailure("Too many safe interaction stances");
            }
        }
        if (goalCandidateCount == 0) { start(target, 1); return; }
        stop(); goal = candidateGoal(); replans = 0; ticksWithoutProgress = 0; prepareRoute();
    }
    void startPickup(net.minecraft.world.entity.item.ItemEntity item) {
        if (client.player == null || client.level == null) throw new NavigationFailure("World unavailable");
        resetGoalCandidates();
        BlockPos target = item.blockPosition();
        int targetFeetY16 = Math.multiplyExact(target.getY(), 16);
        AABB standingBox = client.player.getDimensions(Pose.STANDING).makeBoundingBox(0, 0, 0);
        double standingHeight = standingBox.maxY - standingBox.minY;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int stanceX = target.getX() + dx, stanceZ = target.getZ() + dz;
            for (int band = 0; band < 2; band++) {
                long reference = (long) targetFeetY16 + (band == 0 ? -16L : 16L);
                if (reference < Integer.MIN_VALUE || reference > Integer.MAX_VALUE) continue;
                if (!terrain.collectGroundedStances(stanceX, (int) reference, stanceZ, candidateHeights)
                        || !candidateHeights.isComplete()) continue;
                for (int i = 0; i < candidateHeights.size(); i++) {
                    int feetY16 = candidateHeights.get(i);
                    if (band == 0 ? feetY16 > targetFeetY16
                            : feetY16 <= targetFeetY16 || feetY16 > (long) targetFeetY16 + 16L) continue;
                    terrain.probeStance16(stanceX, feetY16, stanceZ, probe);
                    if (!safeGroundedCandidate(probe, feetY16)) continue;
                    double feetY = feetY16 / 16.0;
                    if (containsGoalCandidate(stanceX, feetY16, stanceZ)) continue;
                    if (!includePickupCandidate(item, stanceX, feetY16, stanceZ, standingHeight)) {
                        throw new NavigationFailure("Too many safe collection stances");
                    }
                }
            }
        }
        for (int dy = -2; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int feetY16 = Math.multiplyExact(Math.addExact(target.getY(), dy), 16);
            int stanceX = target.getX() + dx, stanceZ = target.getZ() + dz;
            if (containsGoalCandidate(stanceX, feetY16, stanceZ)) continue;
            terrain.probeStance16(stanceX, feetY16, stanceZ, probe);
            if (!(probe.water || probe.climbable) || !safeGroundedCandidate(probe, feetY16)) continue;
            if (!includePickupCandidate(item, stanceX, feetY16, stanceZ, standingHeight)) {
                throw new NavigationFailure("Too many safe collection stances");
            }
        }
        if (goalCandidateCount == 0) throw new NavigationFailure("No safe collection stance for dropped item");
        stop(); goal = candidateGoal(); replans = 0; ticksWithoutProgress = 0; prepareRoute();
    }

    private void resetGoalCandidates() { goalCandidateCount = 0; }
    private boolean containsGoalCandidate(int x, int feetY16, int z) {
        long packed = Position.pack(x, Math.floorDiv(feetY16, 16), z);
        byte fraction = (byte) Math.floorMod(feetY16, 16);
        for (int i = 0; i < goalCandidateCount; i++) {
            if (goalPositions[i] == packed && goalFractions[i] == fraction) return true;
        }
        return false;
    }
    private boolean includeInteractionCandidate(BlockPos target, int x, int feetY16, int z) {
        int supportY = Math.floorDiv(feetY16, 16) - (Math.floorMod(feetY16, 16) == 0 ? 1 : 0);
        if (target.getX() == x && target.getZ() == z && target.getY() == supportY) return true;
        Vec3 eye = new Vec3(x + .5, feetY16 / 16.0 + client.player.getEyeHeight(), z + .5);
        Vec3 aim = Vec3.atCenterOf(target);
        double reach = client.player.blockInteractionRange();
        if (eye.distanceToSqr(aim) > reach * reach) return true;
        var hit = client.level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, client.player));
        return hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)
                || addGoalCandidate(x, feetY16, z);
    }
    private boolean includePickupCandidate(net.minecraft.world.entity.item.ItemEntity item, int x, int feetY16,
                                           int z, double standingHeight) {
        double feetY = feetY16 / 16.0;
        // Preserve the existing item-contact margin while testing the exact stance height.
        var contact = new AABB(x - .55, feetY, z - .55, x + 1.55, feetY + standingHeight, z + 1.55);
        return !contact.intersects(item.getBoundingBox()) || addGoalCandidate(x, feetY16, z);
    }
    private boolean addGoalCandidate(int x, int feetY16, int z) {
        int y = Math.floorDiv(feetY16, 16), fraction = Math.floorMod(feetY16, 16);
        long packed = Position.pack(x, y, z);
        for (int i = 0; i < goalCandidateCount; i++) {
            if (goalPositions[i] == packed && goalFractions[i] == (byte) fraction) return true;
        }
        if (goalCandidateCount == goalPositions.length) return false;
        goalPositions[goalCandidateCount] = packed;
        goalFractions[goalCandidateCount] = (byte) fraction;
        goalCandidateCount++;
        return true;
    }
    private Goal candidateGoal() {
        return Goal.anyOf16(Arrays.copyOf(goalPositions, goalCandidateCount),
                Arrays.copyOf(goalFractions, goalCandidateCount));
    }
    private static boolean safeGroundedCandidate(StanceProbe stance, int feetY16) {
        return stance.loaded && !stance.hazard && stance.bodyClear && stance.breakCount == 0
                && (stance.hasGroundSupport() || Math.floorMod(feetY16, 16) == 0
                && (stance.water || stance.climbable));
    }

    private void prepareRoute() {
        if (planner != null) planner.cancel();
        planner = null;
        path = null;
        clearPendingWorldAction();
        terrain.refreshStandingDimensions();
        if (canPlanFromCurrentStance()) {
            surfaceRecovery.stop();
            search();
        } else if (!surfaceRecovery.begin()) {
            search();
        }
    }

    private boolean canPlanFromCurrentStance() {
        if (client.player == null || client.level == null) return false;
        int feetY16 = planStartFeetY16();
        if (feetY16 == GameTerrain.INVALID_FEET_Y16) return false;
        BlockPos feet = client.player.blockPosition();
        terrain.probeStance16(feet.getX(), feetY16, feet.getZ(), probe);
        return probe.loaded && !probe.hazard && probe.bodyClear && probe.breakCount == 0
                && (probe.hasGroundSupport() || Math.floorMod(feetY16, 16) == 0 && (probe.water || probe.climbable));
    }

    private int planStartFeetY16() {
        int exactFeetY16 = GameTerrain.quantizedFeetY16(client.player.getY());
        BlockPos feet = client.player.blockPosition();
        if (exactFeetY16 != GameTerrain.INVALID_FEET_Y16) {
            terrain.probeStance16(feet.getX(), exactFeetY16, feet.getZ(), probe);
            if (probe.loaded && !probe.hazard && probe.bodyClear && probe.hasGroundSupport()) return exactFeetY16;
            if (Math.floorMod(exactFeetY16, 16) == 0) return exactFeetY16;
        }
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        if (!terrain.isMotionClear(x, y, z, x, y, z, 0.0, emptyProbe)) return GameTerrain.INVALID_FEET_Y16;
        int mediumFeetY16 = Math.multiplyExact(feet.getY(), 16);
        terrain.probeStance16(feet.getX(), mediumFeetY16, feet.getZ(), probe);
        if (!probe.loaded || probe.hazard || !probe.bodyClear || !(probe.water || probe.climbable)) {
            return GameTerrain.INVALID_FEET_Y16;
        }
        return mediumFeetY16;
    }

    private void search() {
        searchNanos = 0; searchTicks = 0; nextPartialCheckTick = 0; budgetedSegment = false;
        clearPendingWorldAction();
        terrain.beginSearch();
        BlockPos start = client.player.blockPosition();
        int startFeetY16 = planStartFeetY16();
        if (startFeetY16 == GameTerrain.INVALID_FEET_Y16) throw new NavigationFailure("Player feet are not on a modeled sixteenth-block height");
        Block scaffold = actions.count(Blocks.COBBLESTONE.asItem()) > 16 ? Blocks.COBBLESTONE : Blocks.DIRT;
        int spare = Math.max(0, actions.count(scaffold.asItem()) - 16); // Preserve a conservative supply reserve.
        Planner.Options options = new Planner.Options().maxNodes(config.pathNodeLimit).maxDrop(explorationRoute ? 1 : 3)
            .allowBreaking(!explorationRoute && config.allowBreaking).allowBuilding(!explorationRoute && config.allowBuilding && spare > 0)
            .allowParkour(!explorationRoute && config.allowParkour).allowSwimming(!explorationRoute).allowClimbing(!explorationRoute)
            .placements(Math.min(32, spare), BuiltInRegistries.ITEM.getId(scaffold.asItem()));
        planner = Planner.fromFeetY16(terrain, start.getX(), startFeetY16, start.getZ(), goal, options);
        path = null; pathIndex = 1; actionIndex = 0; lastDistance = Double.POSITIVE_INFINITY;
        jumpEdgeIndex = -1; jumpWasAirborne = false;
        validatedPathIndex = -1; validatedRevision = Long.MIN_VALUE;
    }
    long progressToken() { return progressToken; }
    void recordConfirmedWorldAction() { recordProgress(); }
    void observeConfirmedProgress() {
        if (client.level == null) return;
        if (pendingBreakPosition != null && hasLoadedChunk(pendingBreakPosition)) {
            var state = client.level.getBlockState(pendingBreakPosition);
            if (state.isAir() || Block.getId(state) != pendingBreakStateId
                    && state.getCollisionShape(client.level, pendingBreakPosition).isEmpty()) {
                recordProgress();
                clearPendingWorldAction();
            }
        }
        if (pendingPlacementPosition != null && hasLoadedChunk(pendingPlacementPosition)
                && client.level.getBlockState(pendingPlacementPosition).is(pendingPlacementBlock)) {
            recordProgress();
            clearPendingWorldAction();
        }
    }
    private void recordProgress() {
        if (progressToken < Long.MAX_VALUE) progressToken++;
        ticksWithoutProgress = 0;
    }
    private boolean hasLoadedChunk(BlockPos position) {
        return client.level != null && client.level.getChunk(
                position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }
    private void clearPendingWorldAction() {
        pendingBreakPosition = null;
        pendingBreakStateId = -1;
        pendingPlacementPosition = null;
        pendingPlacementBlock = null;
    }
    boolean tick() {
        input.acquire(client); input.idle();
        if (client.player == null || client.level == null) throw new IllegalStateException("World unavailable");
        terrain.refreshStandingDimensions();
        if (surfaceRecovery.active()) {
            if (surfaceRecovery.tick()) {
                recordProgress();
                search();
            }
            return false;
        }
        if (path == null) {
            long searchStarted = System.nanoTime();
            NavStatus status = planner.advance(config.pathNodesPerTick, config.pathMillisPerTick * 1_000_000L);
            searchNanos += Math.max(0L, System.nanoTime() - searchStarted);
            searchTicks++;
            if (status == NavStatus.IN_PROGRESS && searchTicks >= nextPartialCheckTick
                    && (searchTicks >= 20 || searchNanos >= 50_000_000L)) {
                nextPartialCheckTick = searchTicks + 10;
                status = planner.finishPartial(2);
                budgetedSegment = status == NavStatus.PARTIAL_LIMIT;
            }
            if (status == NavStatus.IN_PROGRESS) return false;
            if (status == NavStatus.STALE) { retry("Terrain changed during search"); return false; }
            if (status != NavStatus.FOUND && status != NavStatus.PARTIAL_LIMIT) throw new NavigationFailure("Navigation: " + status + " to " + goal.x + "," + goal.y + "," + goal.z + " after " + planner.getExpandedNodes() + " expansions");
            path = planner.getPath();
            validatedPathIndex = -1;
            validatedRevision = path == null ? Long.MIN_VALUE : path.terrainRevision;
            if (path == null || path.length() < 2) {
                if (goalMatchesPlayer()) return finishArrival();
                throw new NavigationFailure(budgetedSegment
                        ? "Route search budget reached without a safe forward segment after " + planner.getExpandedNodes() + " expansions"
                        : "No useful route in loaded terrain");
            }
        }
        if (pathIndex == path.length()) {
            if (goalMatchesPlayer()) { input.idle(); return finishArrival(); }
            if (budgetedSegment) { prepareRoute(); return false; }
            retry("Route segment ended before goal"); return false;
        }
        Path.Step next = path.step(pathIndex);
        if (actionIndex < next.actionCount()) {
            Action action = next.action(actionIndex);
            BlockPos position = new BlockPos(action.x, action.y, action.z);
            var state = client.level.getBlockState(position);
            Path.Step source = path.step(pathIndex - 1);
            if (action.type == Action.Type.PLACE_BLOCK && (!client.player.onGround()
                    || GameTerrain.quantizedFeetY16(client.player.getY()) != source.feetY16)) {
                retry("Bridge placement requires its grounded source height"); return false;
            }
            if (!PathEdgeValidator.isCurrentStanceSafe(terrain, source,
                    client.player.getX(), client.player.getY(), client.player.getZ(), sourceProbe, emptyProbe)) {
                retry("Current stance became unsafe before world action"); return false;
            }
            if (action.type == Action.Type.BREAK_BLOCK) {
                if (state.isAir() || state.getCollisionShape(client.level, position).isEmpty()) {
                    observeConfirmedProgress();
                    if (position.equals(pendingBreakPosition)) clearPendingWorldAction();
                    actionIndex++; actions.cancel(); return false;
                }
                if (!config.allowBreaking) throw new IllegalStateException("Route requires mining, but allowBreaking=false");
                if (Block.getId(state) != action.token) { retry("Mining obstruction changed"); return false; }
                if (!PathEdgeValidator.isBreakActionSafe(terrain, source, next, action, probe)) {
                    retry("Mining obstruction is no longer safe or reachable"); return false;
                }
                if (!actions.mine(position)) { retry("Obstruction cannot be mined from this stance"); return false; }
                pendingBreakPosition = position.immutable();
                pendingBreakStateId = action.token;
            } else {
                Item item = BuiltInRegistries.ITEM.byId(action.token);
                Block block = Block.byItem(item);
                if (state.is(block)) {
                    observeConfirmedProgress();
                    actionIndex++; return false;
                }
                if (!config.allowBuilding) throw new IllegalStateException("Route requires placement, but allowBuilding=false");
                if (actions.count(item) <= 0) { retry("Reserved bridge block is no longer available"); return false; }
                if (!state.canBeReplaced()) { retry("Bridge target is no longer replaceable"); return false; }
                if (!PathEdgeValidator.isBridgeActionSafe(terrain, source, next, action, sourceProbe, probe)) {
                    retry("Bridge placement is no longer safe or supported"); return false;
                }
                input.drive(0, 0, false, true);
                if (!actions.place(position, block)) {
                    // A side face below the player cannot be seen from the center of its support.
                    // Sneak to the safe lip before placing; vanilla sneak clamps movement at the edge.
                    Path.Step previous = path.step(pathIndex - 1);
                    Vec3 edge = new Vec3(previous.x + .5 + (next.x - previous.x) * .7, previous.feetY(), previous.z + .5 + (next.z - previous.z) * .7);
                    Vec3 delta = edge.subtract(client.player.position());
                    if (Math.hypot(delta.x, delta.z) > .08) {
                        if (!PathEdgeValidator.isSweepClear(terrain,
                                client.player.getX(), client.player.getY(), client.player.getZ(),
                                edge.x, edge.y, edge.z, emptyProbe)) {
                            retry("Bridge approach became unsafe"); return false;
                        }
                        client.player.setSprinting(false);
                        client.player.setYRot((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
                        input.drive(.4f, 0, false, true);
                    }
                    if (++ticksWithoutProgress > 100) retry("Bridge face is unreachable or placement denied");
                    return false;
                }
                pendingPlacementPosition = position.immutable();
                pendingPlacementBlock = block;
            }
            if (++ticksWithoutProgress > config.actionTimeoutTicks) throw new NavigationFailure("World action made no progress");
            return false;
        }
        if (next.movement == Path.Movement.PARKOUR && !config.allowParkour) {
            retry("Parkour was disabled while following the route"); return false;
        }
        if (next.movement == Path.Movement.WALK && validatedPathIndex == pathIndex
                && client.player.onGround()
                && !PathEdgeValidator.isCurrentMotionSafe(terrain, next.movement,
                    client.player.getX(), client.player.getY(), client.player.getZ(),
                    true, sourceProbe, emptyProbe)
                && PathEdgeValidator.isWithinEdgeCorridor(path.step(pathIndex - 1), next,
                    edgeStartX, edgeStartY, edgeStartZ,
                    client.player.getX(), client.player.getY(), client.player.getZ())
                && PathEdgeValidator.isSafeWalkSettlement(terrain,
                    client.player.getX(), client.player.getY(), client.player.getZ(), probe, emptyProbe)) {
            input.idle(); client.player.setSprinting(false);
            if (++settlingTicks > 4) retry("Player did not settle onto the safe walk surface");
            return false;
        }
        settlingTicks = 0;
        long currentRevision = terrain.revision();
        boolean newEdge = validatedPathIndex != pathIndex;
        if (newEdge || currentRevision != validatedRevision) {
            Path.Step source = path.step(pathIndex - 1);
            double feetX = client.player.getX();
            double feetY = client.player.getY();
            double feetZ = client.player.getZ();
            boolean safe = newEdge
                    ? PathEdgeValidator.isSafeEdge(terrain, source, next, feetX, feetY, feetZ,
                    true, true, config.allowParkour, sourceProbe, probe)
                    : PathEdgeValidator.isSafeContinuation(terrain, source, next,
                    edgeStartX, edgeStartY, edgeStartZ, feetX, feetY, feetZ,
                    client.player.onGround(), config.allowParkour, sourceProbe, probe);
            if (!safe) {
                retry("Route edge became unsafe"); return false;
            }
            if (newEdge) { edgeStartX = feetX; edgeStartY = feetY; edgeStartZ = feetZ; }
            validatedPathIndex = pathIndex;
            // A lift-only continuation does not prove the horizontal remainder. Keep it
            // uncached until feet clear the ledge, forcing that proof before forward input.
            validatedRevision = next.movement == Path.Movement.JUMP && feetY < next.feetY()
                    ? Long.MIN_VALUE : terrain.revision();
        }
        Vec3 destination = new Vec3(next.x + .5, next.feetY(), next.z + .5);
        Vec3 delta = destination.subtract(client.player.position());
        double horizontal = Math.hypot(delta.x, delta.z);
        double currentFeetX = client.player.getX();
        double currentFeetY = client.player.getY();
        double currentFeetZ = client.player.getZ();
        if (!PathEdgeValidator.isWithinEdgeCorridor(path.step(pathIndex - 1), next,
                edgeStartX, edgeStartY, edgeStartZ, currentFeetX, currentFeetY, currentFeetZ)) {
            retry("Player left the safe route corridor"); return false;
        }
        Path.Movement pointMovement = next.movement == Path.Movement.JUMP
                && currentFeetY >= next.feetY() ? Path.Movement.WALK : next.movement;
        if (!PathEdgeValidator.isCurrentMotionSafe(terrain, pointMovement,
                currentFeetX, currentFeetY, currentFeetZ,
                client.player.onGround(), sourceProbe, emptyProbe)) {
            retry("Current player volume or support became unsafe"); return false;
        }
        int currentFeetY16 = GameTerrain.quantizedFeetY16(currentFeetY);
        boolean mediumArrival = (next.movement == Path.Movement.SWIM || next.movement == Path.Movement.CLIMB)
                && Math.abs(delta.y) < .35 && currentMediumSafe(next.movement);
        boolean centeredLaunchRequired = pathIndex + 1 < path.length()
                && switch (path.step(pathIndex + 1).movement) {
                    case JUMP, DROP, PARKOUR, BRIDGE -> true;
                    default -> false;
                };
        double arrivalRadius = centeredLaunchRequired ? .10 : .22;
        if (horizontal < arrivalRadius && (currentFeetY16 == next.feetY16 || mediumArrival)
                && (client.player.onGround() || probe.water || probe.climbable)) {
            client.player.setSprinting(false);
            pathIndex++; actionIndex = 0; lastDistance = Double.POSITIVE_INFINITY; recordProgress(); return false;
        }
        double distance = delta.lengthSqr();
        if (distance < lastDistance - .002) { lastDistance = distance; ticksWithoutProgress = 0; }
        else if (++ticksWithoutProgress > 80) { retry("Movement stalled"); return false; }
        client.player.setYRot((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
        client.player.setXRot(0);
        if (next.movement == Path.Movement.JUMP) {
            if (jumpEdgeIndex != pathIndex) { jumpEdgeIndex = pathIndex; jumpWasAirborne = false; }
            if (!client.player.onGround()) jumpWasAirborne = true;
            if (jumpWasAirborne && client.player.onGround() && currentFeetY < next.feetY()) {
                retry("Jump landed before clearing the ledge"); return false;
            }
        }
        boolean jump = next.movement == Path.Movement.JUMP && !jumpWasAirborne || next.movement == Path.Movement.PARKOUR || next.movement == Path.Movement.CLIMB || next.movement == Path.Movement.SWIM && delta.y > 0;
        boolean sneak = next.movement == Path.Movement.BRIDGE;
        boolean risingBeforeLedge = next.movement == Path.Movement.JUMP
                && currentFeetY < next.feetY();
        input.drive(!risingBeforeLedge && horizontal > (centeredLaunchRequired ? .08 : .12) ? 1 : 0,
                0, jump, sneak);
        client.player.setSprinting(next.movement == Path.Movement.PARKOUR);
        return false;
    }
    private boolean goalMatchesPlayer() {
        int feetY16 = GameTerrain.quantizedFeetY16(client.player.getY());
        if (feetY16 != GameTerrain.INVALID_FEET_Y16 && goal.matches16(client.player.blockPosition().getX(), feetY16, client.player.blockPosition().getZ())) return true;
        return goal.matches(client.player.blockPosition().getX(), client.player.blockPosition().getY(), client.player.blockPosition().getZ()) && currentMediumSafe(Path.Movement.START);
    }
    private boolean currentMediumSafe(Path.Movement movement) {
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        terrain.probeStance16(client.player.blockPosition().getX(), Math.multiplyExact(client.player.blockPosition().getY(), 16), client.player.blockPosition().getZ(), probe);
        boolean matchingMedium = movement == Path.Movement.SWIM ? probe.water
                : movement == Path.Movement.CLIMB ? probe.climbable : probe.water || probe.climbable;
        return probe.loaded && !probe.hazard && probe.bodyClear && probe.breakCount == 0
                && matchingMedium && terrain.isMotionClear(x, y, z, x, y, z, 0.0, emptyProbe);
    }
    private boolean finishArrival() {
        if (goal.kind != Goal.Kind.ANY || path.length() != 1) return true;
        BlockPos feet = client.player.blockPosition();
        int feetY16 = planStartFeetY16();
        if (feetY16 == GameTerrain.INVALID_FEET_Y16) throw new NavigationFailure("Player feet left the modeled sixteenth-block stance");
        double dx = feet.getX() + .5 - client.player.getX();
        double dz = feet.getZ() + .5 - client.player.getZ();
        if (Math.hypot(dx, dz) < .12) return true;
        terrain.probeStance16(feet.getX(), feetY16, feet.getZ(), probe);
        if (!probe.loaded || probe.hazard || !probe.bodyClear || !(probe.hasGroundSupport() || probe.water || probe.climbable)
                || !terrain.isMotionClear(client.player.getX(), client.player.getY(), client.player.getZ(),
                feet.getX() + .5, feetY16 / 16.0, feet.getZ() + .5, 0, probe)) {
            throw new NavigationFailure("Cannot safely center at the interaction stance");
        }
        if (++ticksWithoutProgress > 80) throw new NavigationFailure("Interaction stance centering stalled");
        client.player.setSprinting(false);
        client.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90));
        client.player.setXRot(0);
        input.drive(.4f, 0, false, false);
        return false;
    }
    private void retry(String reason) {
        if (replans == 0 || replans == 8) System.getLogger("lodekeeper").log(System.Logger.Level.INFO,
            "Navigation retry " + (replans + 1) + ": " + reason + " at "
                + (client.player == null ? "unknown position" : client.player.getX() + "," + client.player.getY() + "," + client.player.getZ())
                + retryStanceDetail());
        input.idle(); actions.cancel();
        if (client.player != null) client.player.setSprinting(false);
        if (++replans > 8) throw new NavigationFailure(reason + " (retry limit reached)");
        prepareRoute();
    }
    private String retryStanceDetail() {
        if (client.player == null) return "";
        int feetY16 = GameTerrain.quantizedFeetY16(client.player.getY());
        if (feetY16 == GameTerrain.INVALID_FEET_Y16) return " unquantized feet";
        terrain.probeCurrentStance(client.player.getX(), feetY16, client.player.getZ(), sourceProbe);
        String detail = " onGround=" + client.player.onGround()
                + " live=" + sourceProbe.loaded + "/" + sourceProbe.bodyClear + "/" + sourceProbe.hazard
                + "/" + sourceProbe.breakCount + "/" + sourceProbe.fullSupport + "/" + sourceProbe.surfaceSupport;
        if (path != null && pathIndex > 0 && pathIndex < path.length()) {
            Path.Step source = path.step(pathIndex - 1), next = path.step(pathIndex);
            detail += " edge=" + source.x + "," + source.feetY() + "," + source.z
                    + "->" + next.x + "," + next.feetY() + "," + next.z + ":" + next.movement;
        }
        return detail;
    }

    void stop() {
        explorationRoute = false; settlingTicks = 0; jumpEdgeIndex = -1; jumpWasAirborne = false;
        surfaceRecovery.stop();
        clearPendingWorldAction();
        if (planner != null) planner.cancel(); planner = null; path = null; goal = null;
        input.idle(); actions.cancel();
        if (client.player != null) client.player.setSprinting(false);
    }

    NavigationSnapshot visualization(boolean includeNodes) {
        return planner == null ? NavigationSnapshot.EMPTY
                : planner.snapshot(pathIndex, searchNanos, searchTicks, replans, includeNodes);
    }

    String status() { return surfaceRecovery.active() ? "recovering from fractional surface"
            : path == null ? "route search" : "route " + pathIndex + "/" + path.length(); }
}
