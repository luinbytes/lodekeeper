package dev.lodekeeper.fabric;

import dev.lodekeeper.nav.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** One route at a time; every destructive action and next stance is revalidated live. */
final class MovementController {
    private final MinecraftClient client;
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
    private double edgeStartX, edgeStartY, edgeStartZ;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private int replans;
    private boolean explorationRoute;
    static final class NavigationFailure extends IllegalStateException {
        NavigationFailure(String reason) { super(reason); }
    }
    void startExploration(BlockPos waypoint) {
        stop(); explorationRoute = true;
        goal = Goal.exact(waypoint.getX(), waypoint.getY(), waypoint.getZ()); replans = 0; search();
    }
    MovementController(MinecraftClient client, LodekeeperConfig config, PlayerActions actions, BotInput input, GameTerrain terrain) {
        this.client = client; this.config = config; this.actions = actions; this.input = input; this.terrain = terrain;
    }
    void start(BlockPos target, int radius) {
        stop(); goal = Goal.near(target.getX(), target.getY(), target.getZ(), radius); replans = 0;
        search();
    }
    void startInteraction(BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dy = -2; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos stance = target.add(dx, dy, dz);
            if (stance.down().equals(target)) continue; // Mining must not remove the support for arrival.
            terrain.probeStance(stance.getX(), stance.getY(), stance.getZ(), probe);
            if (!probe.loaded || probe.hazard || !probe.bodyClear || !(probe.fullSupport || probe.water || probe.climbable)) continue;
            Vec3d eye = new Vec3d(stance.getX() + .5, stance.getY() + client.player.getStandingEyeHeight(), stance.getZ() + .5);
            Vec3d aim = Vec3d.ofCenter(target);
            double reach = GameApi.blockReach(client);
            if (eye.squaredDistanceTo(aim) > reach * reach) continue;
            var hit = client.world.raycast(new net.minecraft.world.RaycastContext(eye, aim, net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) continue;
            double distance = client.player.squaredDistanceTo(Vec3d.ofCenter(stance));
            if (distance < bestDistance) { bestDistance = distance; best = stance; }
        }
        if (best == null) { start(target, 1); return; } // Buried targets require a mined approach.
        stop(); goal = Goal.exact(best.getX(), best.getY(), best.getZ()); replans = 0; search();
    }
    private void search() {
        terrain.beginSearch();
        BlockPos start = client.player.getBlockPos();
        Block scaffold = actions.count(Blocks.COBBLESTONE.asItem()) > 16 ? Blocks.COBBLESTONE : Blocks.DIRT;
        int spare = Math.max(0, actions.count(scaffold.asItem()) - 16); // Preserve a conservative supply reserve.
        Planner.Options options = new Planner.Options().maxNodes(config.pathNodeLimit).maxDrop(explorationRoute ? 1 : 3)
            .allowBreaking(!explorationRoute && config.allowBreaking).allowBuilding(!explorationRoute && config.allowBuilding && spare > 0)
            .allowParkour(!explorationRoute && config.allowParkour).allowSwimming(!explorationRoute).allowClimbing(!explorationRoute)
            .placements(Math.min(32, spare), Registries.ITEM.getRawId(scaffold.asItem()));
        planner = new Planner(terrain, start.getX(), start.getY(), start.getZ(), goal, options);
        path = null; pathIndex = 1; actionIndex = 0; ticksWithoutProgress = 0; lastDistance = Double.POSITIVE_INFINITY;
        validatedPathIndex = -1; validatedRevision = Long.MIN_VALUE;
    }
    boolean tick() {
        input.acquire(); input.idle();
        if (client.player == null || client.world == null) throw new IllegalStateException("World unavailable");
        if (path == null) {
            NavStatus status = planner.advance(config.pathNodesPerTick, config.pathMillisPerTick * 1_000_000L);
            if (status == NavStatus.IN_PROGRESS) return false;
            if (status == NavStatus.STALE) { retry("Terrain changed during search"); return false; }
            if (status != NavStatus.FOUND && status != NavStatus.PARTIAL_LIMIT) throw new NavigationFailure("Navigation: " + status + " to " + goal.x + "," + goal.y + "," + goal.z + " after " + planner.getExpandedNodes() + " expansions");
            path = planner.getPath();
            validatedPathIndex = -1;
            validatedRevision = path == null ? Long.MIN_VALUE : path.terrainRevision;
            if (path == null || path.length() < 2) {
                if (goal.matches(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ())) return true;
                throw new NavigationFailure("No useful route in loaded terrain");
            }
        }
        if (pathIndex == path.length()) {
            if (goal.matches(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ())) { input.idle(); return true; }
            retry("Route segment ended before goal"); return false;
        }
        Path.Step next = path.step(pathIndex);
        if (actionIndex < next.actionCount()) {
            Action action = next.action(actionIndex);
            BlockPos position = new BlockPos(action.x, action.y, action.z);
            var state = client.world.getBlockState(position);
            Path.Step source = path.step(pathIndex - 1);
            if (!PathEdgeValidator.isCurrentStanceSafe(terrain, source,
                    client.player.getX(), client.player.getY(), client.player.getZ(), sourceProbe, emptyProbe)) {
                retry("Current stance became unsafe before world action"); return false;
            }
            if (action.type == Action.Type.BREAK_BLOCK) {
                if (state.isAir() || state.getCollisionShape(client.world, position).isEmpty()) { actionIndex++; actions.cancel(); return false; }
                if (!config.allowBreaking) throw new IllegalStateException("Route requires mining, but allowBreaking=false");
                if (Block.getRawIdFromState(state) != action.token) { retry("Mining obstruction changed"); return false; }
                if (!PathEdgeValidator.isBreakActionSafe(terrain, source, next, action, probe)) {
                    retry("Mining obstruction is no longer safe or reachable"); return false;
                }
                if (!actions.mine(position)) { retry("Obstruction cannot be mined from this stance"); return false; }
            } else {
                Item item = Registries.ITEM.get(action.token);
                Block block = Block.getBlockFromItem(item);
                if (state.isOf(block)) { actionIndex++; return false; }
                if (!config.allowBuilding) throw new IllegalStateException("Route requires placement, but allowBuilding=false");
                if (actions.count(item) <= 0) { retry("Reserved bridge block is no longer available"); return false; }
                if (!state.isReplaceable()) { retry("Bridge target is no longer replaceable"); return false; }
                if (!PathEdgeValidator.isBridgeActionSafe(terrain, source, next, action, sourceProbe, probe)) {
                    retry("Bridge placement is no longer safe or supported"); return false;
                }
                input.drive(0, 0, false, true);
                if (!actions.place(position, block)) {
                    // A side face below the player cannot be seen from the center of its support.
                    // Sneak to the safe lip before placing; vanilla sneak clamps movement at the edge.
                    Path.Step previous = path.step(pathIndex - 1);
                    Vec3d edge = new Vec3d(previous.x + .5 + (next.x - previous.x) * .7, previous.y, previous.z + .5 + (next.z - previous.z) * .7);
                    Vec3d delta = edge.subtract(ClientAccess.position(client.player));
                    if (Math.hypot(delta.x, delta.z) > .08) {
                        if (!PathEdgeValidator.isSweepClear(terrain,
                                client.player.getX(), client.player.getY(), client.player.getZ(),
                                edge.x, edge.y, edge.z, emptyProbe)) {
                            retry("Bridge approach became unsafe"); return false;
                        }
                        client.player.setSprinting(false);
                        client.player.setYaw((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
                        input.drive(.4f, 0, false, true);
                    }
                    if (++ticksWithoutProgress > 100) retry("Bridge face is unreachable or placement denied");
                    return false;
                }
            }
            if (++ticksWithoutProgress > config.actionTimeoutTicks) throw new IllegalStateException("World action made no progress");
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
                    client.player.isOnGround(), config.allowParkour, sourceProbe, probe);
            if (!safe) {
                retry("Route edge became unsafe"); return false;
            }
            if (newEdge) { edgeStartX = feetX; edgeStartY = feetY; edgeStartZ = feetZ; }
            validatedPathIndex = pathIndex;
            validatedRevision = terrain.revision();
        }
        Vec3d destination = new Vec3d(next.x + .5, next.y, next.z + .5);
        Vec3d delta = destination.subtract(ClientAccess.position(client.player));
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
                client.player.isOnGround(), sourceProbe, emptyProbe)) {
            retry("Current player volume or support became unsafe"); return false;
        }
        if (horizontal < .22 && Math.abs(delta.y) < .35 && (client.player.isOnGround() || probe.water || probe.climbable)) {
            client.player.setSprinting(false);
            pathIndex++; actionIndex = 0; lastDistance = Double.POSITIVE_INFINITY; ticksWithoutProgress = 0; return false;
        }
        double distance = delta.lengthSquared();
        if (distance < lastDistance - .002) { lastDistance = distance; ticksWithoutProgress = 0; }
        else if (++ticksWithoutProgress > 80) { retry("Movement stalled"); return false; }
        client.player.setYaw((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
        client.player.setPitch(0);
        boolean jump = next.movement == Path.Movement.JUMP || next.movement == Path.Movement.PARKOUR || next.movement == Path.Movement.CLIMB || next.movement == Path.Movement.SWIM && delta.y > 0;
        boolean sneak = next.movement == Path.Movement.BRIDGE;
        input.drive(horizontal > .12 ? 1 : 0, 0, jump, sneak);
        client.player.setSprinting(next.movement == Path.Movement.PARKOUR);
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
        if (planner != null) planner.cancel(); planner = null; path = null; goal = null;
        input.idle(); actions.cancel();
        if (client.player != null) client.player.setSprinting(false);
    }
    String status() { return path == null ? "route search" : "route " + pathIndex + "/" + path.length(); }
}
