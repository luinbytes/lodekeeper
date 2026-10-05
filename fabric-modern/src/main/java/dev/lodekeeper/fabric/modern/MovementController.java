package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    private Planner planner;
    private Path path;
    private Goal goal;
    private int pathIndex, actionIndex, ticksWithoutProgress;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private int replans;
    MovementController(Minecraft client, LodekeeperConfig config, PlayerActions actions, BotInput input, GameTerrain terrain) {
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
            double distance = client.player.distanceToSqr(Vec3.atCenterOf(stance));
            if (distance < bestDistance) { bestDistance = distance; best = stance; }
        }
        if (best == null) { start(target, 1); return; }
        stop(); goal = Goal.exact(best.getX(), best.getY(), best.getZ()); replans = 0;
        search();
    }
    private void search() {
        terrain.beginSearch();
        BlockPos start = client.player.blockPosition();
        Block scaffold = actions.count(Blocks.COBBLESTONE.asItem()) > 16 ? Blocks.COBBLESTONE : Blocks.DIRT;
        int spare = Math.max(0, actions.count(scaffold.asItem()) - 16); // Preserve a conservative supply reserve.
        Planner.Options options = new Planner.Options().maxNodes(config.pathNodeLimit).maxDrop(3)
            .allowBreaking(config.allowBreaking).allowBuilding(config.allowBuilding && spare > 0)
            .allowParkour(config.allowParkour).allowSwimming(true).allowClimbing(true)
            .placements(Math.min(32, spare), BuiltInRegistries.ITEM.getId(scaffold.asItem()));
        planner = new Planner(terrain, start.getX(), start.getY(), start.getZ(), goal, options);
        path = null; pathIndex = 1; actionIndex = 0; ticksWithoutProgress = 0; lastDistance = Double.POSITIVE_INFINITY;
    }
    boolean tick() {
        input.acquire(client); input.idle();
        if (client.player == null || client.level == null) throw new IllegalStateException("World unavailable");
        if (path == null) {
            NavStatus status = planner.advance(config.pathNodesPerTick, config.pathMillisPerTick * 1_000_000L);
            if (status == NavStatus.IN_PROGRESS) return false;
            if (status == NavStatus.STALE) { retry("Terrain changed during search"); return false; }
            if (status != NavStatus.FOUND && status != NavStatus.PARTIAL_LIMIT) throw new IllegalStateException("Navigation: " + status
                    + " to " + goal.x + "," + goal.y + "," + goal.z + " after " + planner.getExpandedNodes() + " expansions");
            path = planner.getPath();
            if (path == null || path.length() < 2) {
                BlockPos player = client.player.blockPosition();
                if (goal.matches(player.getX(), player.getY(), player.getZ())) return true;
                throw new IllegalStateException("No useful route in loaded terrain");
            }
        }
        if (pathIndex == path.length()) {
            BlockPos player = client.player.blockPosition();
            if (goal.matches(player.getX(), player.getY(), player.getZ())) { input.idle(); return true; }
            retry("Route segment ended before goal"); return false;
        }
        Path.Step next = path.step(pathIndex);
        if (actionIndex < next.actionCount()) {
            Action action = next.action(actionIndex);
            BlockPos position = new BlockPos(action.x, action.y, action.z);
            BlockState state = client.level.getBlockState(position);
            if (action.type == Action.Type.BREAK_BLOCK) {
                if (state.isAir() || state.getCollisionShape(client.level, position).isEmpty()) { actionIndex++; actions.cancel(); return false; }
                if (!config.allowBreaking) throw new IllegalStateException("Route requires mining, but allowBreaking=false");
                if (Block.getId(state) != action.token) { retry("Mining obstruction changed"); return false; }
                if (!actions.mine(position)) { retry("Obstruction cannot be mined from this stance"); return false; }
            } else {
                Item item = Item.byId(action.token);
                Block block = Block.byItem(item);
                if (state.is(block)) { actionIndex++; return false; }
                if (!config.allowBuilding) throw new IllegalStateException("Route requires placement, but allowBuilding=false");
                input.drive(0, 0, false, true);
                if (!actions.place(position, block)) {
                    // A side face below the player cannot be seen from the center of its support.
                    // Sneak to the safe lip before placing; vanilla sneak clamps movement at the edge.
                    Path.Step previous = path.step(pathIndex - 1);
                    Vec3 edge = new Vec3(previous.x + .5 + (next.x - previous.x) * .7, previous.y, previous.z + .5 + (next.z - previous.z) * .7);
                    Vec3 delta = edge.subtract(client.player.position());
                    if (Math.hypot(delta.x, delta.z) > .08) {
                        client.player.setYRot((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
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
        Vec3 destination = new Vec3(next.x + .5, next.y, next.z + .5);
        Vec3 delta = destination.subtract(client.player.position());
        double horizontal = Math.hypot(delta.x, delta.z);
        if (horizontal < .22 && Math.abs(delta.y) < .35 && (client.player.onGround() || probe.water || probe.climbable)) {
            pathIndex++; actionIndex = 0; lastDistance = Double.POSITIVE_INFINITY; ticksWithoutProgress = 0; return false;
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
