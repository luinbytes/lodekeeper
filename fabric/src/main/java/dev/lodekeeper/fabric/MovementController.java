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
    private Planner planner;
    private Path path;
    private Goal goal;
    private int pathIndex, actionIndex, ticksWithoutProgress;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private int replans;
    MovementController(MinecraftClient client, LodekeeperConfig config, PlayerActions actions, BotInput input, GameTerrain terrain) {
        this.client = client; this.config = config; this.actions = actions; this.input = input; this.terrain = terrain;
    }
    void start(BlockPos target, int radius) {
        stop(); goal = Goal.near(target.getX(), target.getY(), target.getZ(), radius); replans = 0;
        search();
    }
    private void search() {
        BlockPos start = client.player.getBlockPos();
        Block scaffold = actions.count(Blocks.COBBLESTONE.asItem()) > 16 ? Blocks.COBBLESTONE : Blocks.DIRT;
        int spare = Math.max(0, actions.count(scaffold.asItem()) - 16); // Preserve a conservative supply reserve.
        Planner.Options options = new Planner.Options().maxNodes(config.pathNodeLimit).maxDrop(3)
            .allowBreaking(config.allowBreaking).allowBuilding(config.allowBuilding && spare > 0)
            .allowParkour(config.allowParkour).allowSwimming(true).allowClimbing(true)
            .placements(Math.min(32, spare), Registries.ITEM.getRawId(scaffold.asItem()));
        planner = new Planner(terrain, start.getX(), start.getY(), start.getZ(), goal, options);
        path = null; pathIndex = 1; actionIndex = 0; ticksWithoutProgress = 0; lastDistance = Double.POSITIVE_INFINITY;
    }
    boolean tick() {
        input.acquire(); input.idle();
        if (client.player == null || client.world == null) throw new IllegalStateException("World unavailable");
        if (path == null) {
            NavStatus status = planner.advance(config.pathNodesPerTick, config.pathMillisPerTick * 1_000_000L);
            if (status == NavStatus.IN_PROGRESS) return false;
            if (status == NavStatus.STALE) { retry("Terrain changed during search"); return false; }
            if (status != NavStatus.FOUND && status != NavStatus.PARTIAL_LIMIT) throw new IllegalStateException("Navigation: " + status + " after " + planner.getExpandedNodes() + " expansions");
            path = planner.getPath();
            if (path == null || path.length() < 2) {
                if (goal.matches(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ())) return true;
                throw new IllegalStateException("No useful route in loaded terrain");
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
            if (action.type == Action.Type.BREAK_BLOCK) {
                if (state.isAir() || state.getCollisionShape(client.world, position).isEmpty()) { actionIndex++; actions.cancel(); return false; }
                if (Block.getRawIdFromState(state) != action.token) { retry("Mining obstruction changed"); return false; }
                if (!actions.mine(position)) { retry("Obstruction cannot be mined from this stance"); return false; }
            } else {
                Item item = Registries.ITEM.get(action.token);
                Block block = Block.getBlockFromItem(item);
                if (state.isOf(block)) { actionIndex++; return false; }
                input.drive(0, 0, false, true);
                if (!actions.place(position, block)) {
                    // A side face below the player cannot be seen from the center of its support.
                    // Sneak to the safe lip before placing; vanilla sneak clamps movement at the edge.
                    Path.Step previous = path.step(pathIndex - 1);
                    Vec3d edge = new Vec3d(previous.x + .5 + (next.x - previous.x) * .7, previous.y, previous.z + .5 + (next.z - previous.z) * .7);
                    Vec3d delta = edge.subtract(client.player.getPos());
                    if (Math.hypot(delta.x, delta.z) > .08) {
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
        terrain.probeStance(next.x, next.y, next.z, probe);
        if (!probe.loaded || probe.hazard || !probe.bodyClear || !(probe.fullSupport || probe.water || probe.climbable)) { retry("Next stance became unsafe"); return false; }
        Vec3d destination = new Vec3d(next.x + .5, next.y, next.z + .5);
        Vec3d delta = destination.subtract(client.player.getPos());
        double horizontal = Math.hypot(delta.x, delta.z);
        if (horizontal < .22 && Math.abs(delta.y) < .35 && (client.player.isOnGround() || probe.water || probe.climbable)) {
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
        if (++replans > 8) throw new IllegalStateException(reason + " (retry limit reached)");
        search();
    }
    void stop() {
        if (planner != null) planner.cancel(); planner = null; path = null; goal = null;
        input.idle(); actions.cancel();
        if (client.player != null) client.player.setSprinting(false);
    }
    String status() { return path == null ? "route search" : "route " + pathIndex + "/" + path.length(); }
}
