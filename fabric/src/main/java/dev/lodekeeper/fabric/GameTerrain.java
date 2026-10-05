package dev.lodekeeper.fabric;

import dev.lodekeeper.nav.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.ChunkStatus;

/** Conservative centered-player geometry; fractional slab/stair stances await a dedicated adapter. */
final class GameTerrain implements Terrain {
    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final BlockPos.Mutable position = new BlockPos.Mutable();
    private long revision;
    private final double[] trajectoryPoint = new double[3];
    GameTerrain(MinecraftClient client, LodekeeperConfig config) { this.client = client; this.config = config; }
    void changed() { revision++; }
    @Override public long revision() { return revision + WorldRevision.value(); }
    private boolean loaded(int x, int y, int z) {
        return client.world != null && y >= client.world.getBottomY() && y < client.world.getTopY() && client.world.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }
    private boolean hazardous(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.WITHER_ROSE);
    }
    @Override public void probeStance(int x, int y, int z, StanceProbe out) {
        out.clear();
        if (client.player == null || !loaded(x, y - 1, z) || !loaded(x, y + 1, z)) return;
        out.loaded = true; out.hazard = false;
        BlockState floor = client.world.getBlockState(position.set(x, y - 1, z));
        out.fullSupport = Block.isShapeFullCube(floor.getCollisionShape(client.world, position));
        out.hazard = hazardous(floor);
        out.bodyClear = true;
        for (int dy = 0; dy <= 1; dy++) {
            BlockState state = client.world.getBlockState(position.set(x, y + dy, z));
            out.hazard |= hazardous(state);
            out.water |= state.getFluidState().isIn(FluidTags.WATER);
            out.climbable |= state.isIn(BlockTags.CLIMBABLE);
            var shape = state.getCollisionShape(client.world, position);
            Box body = new Box(.2, dy == 0 ? 0 : 0, .2, .8, dy == 0 ? 1 : .8, .8);
            boolean intersects = shape.getBoundingBoxes().stream().anyMatch(body::intersects);
            if (!intersects) continue;
            out.bodyClear = false;
            if (!config.allowBreaking || !canMine(state, x, y + dy, z)) { out.breakCount = StanceProbe.MAX_BREAK_TARGETS + 1; continue; }
            if (out.breakCount >= StanceProbe.MAX_BREAK_TARGETS) continue;
            var target = out.breakTargets[out.breakCount++];
            target.x = x; target.y = y + dy; target.z = z;
            target.stateToken = Block.getRawIdFromState(state);
            target.cost = Math.max(30, (int) (state.getHardness(client.world, position) * 100));
        }
        // Adjacent fluid can flow into a tunnel as soon as the obstruction is mined.
        if (out.breakCount > 0) for (var direction : net.minecraft.util.math.Direction.values()) {
            BlockPos adjacent = new BlockPos(x, y, z).offset(direction);
            if (!loaded(adjacent.getX(), adjacent.getY(), adjacent.getZ()) || client.world.getFluidState(adjacent).isIn(FluidTags.LAVA)) out.hazard = true;
        }
    }
    private boolean canMine(BlockState state, int x, int y, int z) {
        position.set(x, y, z);
        if (state.getHardness(client.world, position) < 0 || state.hasBlockEntity() || hazardous(state)) return false;
        if (!state.isToolRequired()) return true;
        for (ItemStack tool : client.player.getInventory().main) if (!tool.isEmpty() && tool.isSuitableFor(state) && (!tool.isDamageable() || tool.getMaxDamage() - tool.getDamage() > 2)) return true;
        return false;
    }
    @Override public boolean isMotionClear(double fx, double fy, double fz, double tx, double ty, double tz, double arc, StanceProbe destination) {
        return isMotionClear(fx, fy, fz, tx, ty, tz, arc, null, destination);
    }
    @Override public boolean isMotionClear(double fx, double fy, double fz, double tx, double ty, double tz, double arc, StanceProbe source, StanceProbe destination) {
        int samples = Math.max(2, (int) Math.ceil(Math.sqrt(Math.pow(tx - fx, 2) + Math.pow(ty - fy, 2) + Math.pow(tz - fz, 2)) / .2));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            MovementTrajectory.sample(fx, fy, fz, tx, ty, tz, arc, t, trajectoryPoint);
            double x = trajectoryPoint[0], y = trajectoryPoint[1], z = trajectoryPoint[2];
            Box body = new Box(x - .3, y + .001, z - .3, x + .3, y + 1.8, z + .3);
            for (int bx = (int) Math.floor(body.minX); bx <= (int) Math.floor(body.maxX); bx++) for (int by = (int) Math.floor(body.minY); by <= (int) Math.floor(body.maxY); by++) for (int bz = (int) Math.floor(body.minZ); bz <= (int) Math.floor(body.maxZ); bz++) {
                if (!loaded(bx, by, bz)) return false;
                BlockState state = client.world.getBlockState(position.set(bx, by, bz));
                if (hazardous(state)) return false;
                boolean ignored = false;
                for (int b = 0; b < Math.min(destination.breakCount, 2); b++) {
                    var target = destination.breakTargets[b];
                    if (target.x == bx && target.y == by && target.z == bz) { ignored = true; break; }
                }
                if (source != null) for (int b = 0; b < Math.min(source.breakCount, 2); b++) {
                    var target = source.breakTargets[b];
                    if (target.x == bx && target.y == by && target.z == bz) { ignored = true; break; }
                }
                if (ignored) continue;
                for (Box box : state.getCollisionShape(client.world, position).getBoundingBoxes()) if (box.offset(bx, by, bz).intersects(body)) return false;
            }
        }
        return true;
    }
    @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe destination, int index) {
        if (index >= destination.breakCount || index >= 2) return false;
        var target = destination.breakTargets[index];
        double distance = Math.pow(target.x - x, 2) + Math.pow(target.y + .5 - y - 1.62, 2) + Math.pow(target.z - z, 2);
        return distance <= 16;
    }
    @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz, int token, boolean plannedSupport) {
        if (!config.allowBuilding || !loaded(bx, by, bz) || !client.world.getBlockState(position.set(bx, by, bz)).isReplaceable()) return false;
        if (Math.abs(bx - x) + Math.abs(bz - z) != 1 || by != y - 1) return false;
        if (!plannedSupport && !Block.isShapeFullCube(client.world.getBlockState(position.set(x, y - 1, z)).getCollisionShape(client.world, position))) return false;
        return true;
    }
}
