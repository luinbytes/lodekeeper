package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.StanceProbe;
import dev.lodekeeper.nav.Terrain;
import dev.lodekeeper.nav.BreakTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/** Conservative full-block geometry; unknown or unloaded terrain is blocked. */
final class GameTerrain implements Terrain {
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private long revision;
    private final double[] trajectoryPoint = new double[3];

    GameTerrain(Minecraft client, LodekeeperConfig config) { this.client = client; this.config = config; }
    void changed() { revision++; }
    void beginSearch() { WorldRevision.beginSearch(); }
    void changedChunk(int chunkX, int chunkZ) { WorldRevision.changedChunk(chunkX, chunkZ); }
    @Override public long revision() { return revision + WorldRevision.value(); }

    private boolean loaded(int x, int y, int z) {
        if (client.level == null || y < client.level.getMinY() || y >= client.level.getMaxY()) return false;
        WorldRevision.watch(x >> 4, z >> 4);
        return client.level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    private boolean hazardous(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
    }

    @Override public void probeStance(int x, int y, int z, StanceProbe out) {
        out.clear();
        if (client.player == null || !loaded(x, y - 1, z) || !loaded(x, y + 1, z)) return;
        out.loaded = true;
        BlockState floor = client.level.getBlockState(position.set(x, y - 1, z));
        out.fullSupport = Block.isShapeFullBlock(floor.getCollisionShape(client.level, position));
        out.hazard = hazardous(floor);
        out.bodyClear = true;
        for (int dy = 0; dy <= 1; dy++) {
            BlockState state = client.level.getBlockState(position.set(x, y + dy, z));
            out.hazard |= hazardous(state);
            out.water |= state.getFluidState().is(FluidTags.WATER);
            out.climbable |= state.is(BlockTags.CLIMBABLE);
            var shape = state.getCollisionShape(client.level, position);
            AABB body = new AABB(.2, 0, .2, .8, dy == 0 ? 1 : .8, .8);
            if (shape.toAabbs().stream().noneMatch(body::intersects)) continue;
            out.bodyClear = false;
            if (!config.allowBreaking || !canMine(state, x, y + dy, z)) {
                out.breakCount = StanceProbe.MAX_BREAK_TARGETS + 1;
                continue;
            }
            if (out.breakCount >= StanceProbe.MAX_BREAK_TARGETS) continue;
            BreakTarget target = out.breakTargets[out.breakCount++];
            target.x = x; target.y = y + dy; target.z = z;
            target.stateToken = Block.getId(state);
            target.cost = Math.max(30, (int) (state.getDestroySpeed(client.level, position) * 100));
        }
        if (out.breakCount > 0) for (Direction direction : Direction.values()) {
            BlockPos adjacent = new BlockPos(x, y, z).relative(direction);
            if (!loaded(adjacent.getX(), adjacent.getY(), adjacent.getZ())
                    || client.level.getFluidState(adjacent).is(FluidTags.LAVA)) out.hazard = true;
        }
    }

    private boolean canMine(BlockState state, int x, int y, int z) {
        position.set(x, y, z);
        if (state.getDestroySpeed(client.level, position) < 0 || state.hasBlockEntity() || hazardous(state)) return false;
        if (!state.requiresCorrectToolForDrops()) return true;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack tool = inventory.getItem(i);
            if (!tool.isEmpty() && tool.isCorrectToolForDrops(state)
                    && (!tool.isDamageableItem() || tool.getMaxDamage() - tool.getDamageValue() > 2)) return true;
        }
        return false;
    }

    @Override public boolean isMotionClear(double fx, double fy, double fz, double tx, double ty, double tz,
                                          double arc, StanceProbe destination) {
        return isMotionClear(fx, fy, fz, tx, ty, tz, arc, null, destination);
    }

    @Override public boolean isMotionClear(double fx, double fy, double fz, double tx, double ty, double tz,
                                          double arc, StanceProbe source, StanceProbe destination) {
        int samples = Math.max(2, (int) Math.ceil(Math.sqrt(Math.pow(tx - fx, 2) + Math.pow(ty - fy, 2)
                + Math.pow(tz - fz, 2)) / .2));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            MovementTrajectory.sample(fx, fy, fz, tx, ty, tz, arc, t, trajectoryPoint);
            double x = trajectoryPoint[0], y = trajectoryPoint[1], z = trajectoryPoint[2];
            AABB body = new AABB(x - .3, y + .001, z - .3, x + .3, y + 1.8, z + .3);
            for (int bx = (int) Math.floor(body.minX); bx <= (int) Math.floor(body.maxX); bx++)
                for (int by = (int) Math.floor(body.minY); by <= (int) Math.floor(body.maxY); by++)
                    for (int bz = (int) Math.floor(body.minZ); bz <= (int) Math.floor(body.maxZ); bz++) {
                        if (!loaded(bx, by, bz)) return false;
                        BlockState state = client.level.getBlockState(position.set(bx, by, bz));
                        if (hazardous(state)) return false;
                        if (isBreakTarget(destination, bx, by, bz) || isBreakTarget(source, bx, by, bz)) continue;
                        for (AABB box : state.getCollisionShape(client.level, position).toAabbs()) {
                            if (box.move(bx, by, bz).intersects(body)) return false;
                        }
                    }
        }
        return true;
    }

    private static boolean isBreakTarget(StanceProbe probe, int x, int y, int z) {
        if (probe == null) return false;
        for (int i = 0; i < Math.min(probe.breakCount, 2); i++) {
            var target = probe.breakTargets[i];
            if (target.x == x && target.y == y && target.z == z) return true;
        }
        return false;
    }

    @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe destination, int index) {
        if (index >= destination.breakCount || index >= 2) return false;
        var target = destination.breakTargets[index];
        double distance = Math.pow(target.x - x, 2) + Math.pow(target.y + .5 - y - 1.62, 2)
                + Math.pow(target.z - z, 2);
        return distance <= 16;
    }

    @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz,
                                                 int token, boolean plannedSupport) {
        if (!config.allowBuilding || !loaded(bx, by, bz)
                || !client.level.getBlockState(position.set(bx, by, bz)).canBeReplaced()) return false;
        if (Math.abs(bx - x) + Math.abs(bz - z) != 1 || by != y - 1) return false;
        return plannedSupport || Block.isShapeFullBlock(
                client.level.getBlockState(position.set(x, y - 1, z)).getCollisionShape(client.level, position));
    }
}
