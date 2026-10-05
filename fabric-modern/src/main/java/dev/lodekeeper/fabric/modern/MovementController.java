package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;

/** One route at a time; every destructive action and next stance is revalidated live. */
final class MovementController {
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    private final GameTerrain terrain;
    private final StanceProbe probe = new StanceProbe();
    private final StanceProbe sourceProbe = new StanceProbe();
    private final StanceProbe emptyProbe = new StanceProbe();
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
    private int replans;
    private boolean explorationRoute;
    static final class NavigationFailure extends IllegalStateException {
        NavigationFailure(String reason) { super(reason); }
    }
    void startExploration(BlockPos waypoint) {
        stop(); explorationRoute = true;
        goal = Goal.exact(waypoint.getX(), waypoint.getY(), waypoint.getZ()); replans = 0; ticksWithoutProgress = 0; search();
    }
    MovementController(Minecraft client, LodekeeperConfig config, PlayerActions actions, BotInput input, GameTerrain terrain) {
        this.client = client; this.config = config; this.actions = actions; this.input = input; this.terrain = terrain;
    }
    void start(BlockPos target, int radius) {
        stop(); goal = Goal.near(target.getX(), target.getY(), target.getZ(), radius); replans = 0; ticksWithoutProgress = 0;
        search();
    }
    void startInteraction(BlockPos target) {
        java.util.ArrayList<Long> stances = new java.util.ArrayList<>();
        for (int dy = -2; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos stance = target.offset(dx, dy, dz);
            if (stance.below().equals(target)) continue;
            terrain.probeStance(stance.getX(), stance.getY(), stance.getZ(), probe);
            if (!probe.loaded || probe.hazard || !probe.bodyClear || !(probe.fullSupport || probe.water || probe.climbable)) continue;
            Vec3 eye = new Vec3(stance.getX() + .5, stance.getY() + client.player.getEyeHeight(), stance.getZ() + .5);
            Vec3 aim = Vec3.atCenterOf(target);
            double reach = client.player.blockInteractionRange();
            if (eye.distanceToSqr(aim) > reach * reach) continue;
            var hit = client.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) continue;
            stances.add(Position.pack(stance.getX(), stance.getY(), stance.getZ()));
        }
        if (stances.isEmpty()) { start(target, 1); return; }
        stop(); goal = Goal.anyOf(stances.stream().mapToLong(Long::longValue).toArray()); replans = 0; ticksWithoutProgress = 0; search();
    }
    void startPickup(net.minecraft.world.entity.item.ItemEntity item) {
        java.util.ArrayList<Long> stances = new java.util.ArrayList<>();
        BlockPos target = item.blockPosition();
        for (int dy = -2; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos stance = target.offset(dx, dy, dz);
            terrain.probeStance(stance.getX(), stance.getY(), stance.getZ(), probe);
            if (!probe.loaded || probe.hazard || !probe.bodyClear || !(probe.fullSupport || probe.water || probe.climbable)) continue;
            // Reserve .25 blocks for the navigator's arrival tolerance.
            var contact = new net.minecraft.world.phys.AABB(stance.getX() - .55, stance.getY(), stance.getZ() - .55,
                stance.getX() + 1.55, stance.getY() + 1.8, stance.getZ() + 1.55);
            if (!contact.intersects(item.getBoundingBox())) continue;
            stances.add(Position.pack(stance.getX(), stance.getY(), stance.getZ()));
        }
        if (stances.isEmpty()) throw new NavigationFailure("No safe collection stance for dropped item");
        stop(); goal = Goal.anyOf(stances.stream().mapToLong(Long::longValue).toArray()); replans = 0; ticksWithoutProgress = 0; search();
    }

    private void search() {
        clearPendingWorldAction();
        terrain.beginSearch();
        BlockPos start = client.player.blockPosition();
        Block scaffold = actions.count(Blocks.COBBLESTONE.asItem()) > 16 ? Blocks.COBBLESTONE : Blocks.DIRT;
        int spare = Math.max(0, actions.count(scaffold.asItem()) - 16); // Preserve a conservative supply reserve.
        Planner.Options options = new Planner.Options().maxNodes(config.pathNodeLimit).maxDrop(explorationRoute ? 1 : 3)
            .allowBreaking(!explorationRoute && config.allowBreaking).allowBuilding(!explorationRoute && config.allowBuilding && spare > 0)
            .allowParkour(!explorationRoute && config.allowParkour).allowSwimming(!explorationRoute).allowClimbing(!explorationRoute)
            .placements(Math.min(32, spare), BuiltInRegistries.ITEM.getId(scaffold.asItem()));
        planner = new Planner(terrain, start.getX(), start.getY(), start.getZ(), goal, options);
        path = null; pathIndex = 1; actionIndex = 0; lastDistance = Double.POSITIVE_INFINITY;
        validatedPathIndex = -1; validatedRevision = Long.MIN_VALUE;
    }
    long progressToken() { return progressToken; }
    void recordConfirmedWorldAction() { recordProgress(); }
    void observeConfirmedProgress() {
        if (client.level == null) return;
        if (pendingBreakPosition != null && hasLoadedChunk(pendingBreakPosition)) {
            BlockState state = client.level.getBlockState(pendingBreakPosition);
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
        if (path == null) {
            NavStatus status = planner.advance(config.pathNodesPerTick, config.pathMillisPerTick * 1_000_000L);
            if (status == NavStatus.IN_PROGRESS) return false;
            if (status == NavStatus.STALE) { retry("Terrain changed during search"); return false; }
            if (status != NavStatus.FOUND && status != NavStatus.PARTIAL_LIMIT) throw new NavigationFailure("Navigation: " + status
                    + " to " + goal.x + "," + goal.y + "," + goal.z + " after " + planner.getExpandedNodes() + " expansions");
            path = planner.getPath();
            validatedPathIndex = -1;
            validatedRevision = path == null ? Long.MIN_VALUE : path.terrainRevision;
            if (path == null || path.length() < 2) {
                BlockPos player = client.player.blockPosition();
                if (goal.matches(player.getX(), player.getY(), player.getZ())) return finishArrival();
                throw new NavigationFailure("No useful route in loaded terrain");
            }
        }
        if (pathIndex == path.length()) {
            BlockPos player = client.player.blockPosition();
            if (goal.matches(player.getX(), player.getY(), player.getZ())) { input.idle(); return finishArrival(); }
            retry("Route segment ended before goal"); return false;
        }
        Path.Step next = path.step(pathIndex);
        if (actionIndex < next.actionCount()) {
            Action action = next.action(actionIndex);
            BlockPos position = new BlockPos(action.x, action.y, action.z);
            BlockState state = client.level.getBlockState(position);
            Path.Step source = path.step(pathIndex - 1);
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
                Item item = Item.byId(action.token);
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
                    Vec3 edge = new Vec3(previous.x + .5 + (next.x - previous.x) * .7, previous.y, previous.z + .5 + (next.z - previous.z) * .7);
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
            validatedRevision = terrain.revision();
        }
        Vec3 destination = new Vec3(next.x + .5, next.y, next.z + .5);
        Vec3 delta = destination.subtract(client.player.position());
        double horizontal = Math.hypot(delta.x, delta.z);
        double currentFeetX = client.player.getX();
        double currentFeetY = client.player.getY();
        double currentFeetZ = client.player.getZ();
        if (!PathEdgeValidator.isWithinEdgeCorridor(path.step(pathIndex - 1), next,
                edgeStartX, edgeStartY, edgeStartZ, currentFeetX, currentFeetY, currentFeetZ)) {
            retry("Player left the safe route corridor"); return false;
        }
        if (!PathEdgeValidator.isCurrentMotionSafe(terrain, next.movement,
                currentFeetX, currentFeetY, currentFeetZ,
                client.player.onGround(), sourceProbe, emptyProbe)) {
            retry("Current player volume or support became unsafe"); return false;
        }
        if (horizontal < .22 && Math.abs(delta.y) < .35 && (client.player.onGround() || probe.water || probe.climbable)) {
            client.player.setSprinting(false);
            pathIndex++; actionIndex = 0; lastDistance = Double.POSITIVE_INFINITY; recordProgress(); return false;
        }
        double distance = delta.lengthSqr();
        if (distance < lastDistance - .002) { lastDistance = distance; ticksWithoutProgress = 0; }
        else if (++ticksWithoutProgress > 80) { retry("Movement stalled"); return false; }
        client.player.setYRot((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
        client.player.setXRot(0);
        boolean jump = next.movement == Path.Movement.JUMP || next.movement == Path.Movement.PARKOUR || next.movement == Path.Movement.CLIMB || next.movement == Path.Movement.SWIM && delta.y > 0;
        boolean sneak = next.movement == Path.Movement.BRIDGE;
        input.drive(horizontal > .12 ? 1 : 0, 0, jump, sneak);
        client.player.setSprinting(next.movement == Path.Movement.PARKOUR);
        return false;
    }
    private boolean finishArrival() {
        if (goal.kind != Goal.Kind.ANY || path.length() != 1) return true;
        BlockPos feet = client.player.blockPosition();
        double dx = feet.getX() + .5 - client.player.getX();
        double dz = feet.getZ() + .5 - client.player.getZ();
        if (Math.hypot(dx, dz) < .12) return true;
        terrain.probeStance(feet.getX(), feet.getY(), feet.getZ(), probe);
        if (!probe.loaded || probe.hazard || !probe.bodyClear || !(probe.fullSupport || probe.water || probe.climbable)
                || !terrain.isMotionClear(client.player.getX(), client.player.getY(), client.player.getZ(),
                feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, probe)) {
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
        input.idle(); actions.cancel();
        if (client.player != null) client.player.setSprinting(false);
        if (++replans > 8) throw new NavigationFailure(reason + " (retry limit reached)");
        search();
    }
    void stop() {
        explorationRoute = false;
        clearPendingWorldAction();
        if (planner != null) planner.cancel(); planner = null; path = null; goal = null;
        input.idle(); actions.cancel();
        if (client.player != null) client.player.setSprinting(false);
    }
    String status() { return path == null ? "route search" : "route " + pathIndex + "/" + path.length(); }
}
