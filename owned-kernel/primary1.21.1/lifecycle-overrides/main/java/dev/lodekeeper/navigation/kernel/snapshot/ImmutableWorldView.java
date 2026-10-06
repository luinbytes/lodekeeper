package dev.lodekeeper.navigation.kernel.snapshot;

import dev.lodekeeper.navigation.kernel.cache.CachedChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class ImmutableWorldView implements BlockGetter {
    private final Map<Long, ChunkSnapshot> live;
    private final Map<Long, CachedChunk> cached;
    private final DimensionType dimension;
    private final int minY;
    private final int height;

    public ImmutableWorldView(Map<Long, ChunkSnapshot> live, Map<Long, CachedChunk> cached, DimensionType dimension) {
        this.live = Map.copyOf(live);
        this.cached = Map.copyOf(cached);
        this.dimension = dimension;
        minY = dimension.minY();
        height = dimension.height();
    }

    public BlockState get(int x, int y, int z) {
        if (y < minY || y >= minY + height) { return Blocks.AIR.defaultBlockState(); }
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        ChunkSnapshot chunk = live.get(key);
        if (chunk != null) { return chunk.get(x & 15, y, z & 15); }
        CachedChunk packed = cached.get(key);
        return packed == null ? Blocks.AIR.defaultBlockState() : packed.getBlock(x & 15, y - minY, z & 15, dimension);
    }

    public boolean hasLiveChunk(int x, int z) { return live.containsKey(ChunkPos.asLong(x >> 4, z >> 4)); }
    public boolean isLoaded(int x, int z) {
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        return live.containsKey(key) || cached.containsKey(key);
    }

    public List<BlockPos> scan(Predicate<BlockState> filter, int maximum, BlockPos center) {
        int limit = Math.max(0, Math.min(maximum, 65_536));
        List<BlockPos> result = new ArrayList<>();
        var chunks = new ArrayList<>(live.values());
        chunks.sort(java.util.Comparator.comparingLong(chunk -> {
            long dx = ((long) chunk.x() << 4) - center.getX();
            long dz = ((long) chunk.z() << 4) - center.getZ();
            return dx * dx + dz * dz;
        }));
        for (ChunkSnapshot chunk : chunks) {
            for (int y = minY; y < minY + height && !Thread.currentThread().isInterrupted(); y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (result.size() == limit) { return List.copyOf(result); }
                        if (filter.test(chunk.get(x, y, z))) {
                            result.add(new BlockPos((chunk.x() << 4) + x, y, (chunk.z() << 4) + z));
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public List<BlockPos> cachedLocations(String block, int maximum) {
        List<BlockPos> result = new ArrayList<>();
        for (CachedChunk chunk : cached.values()) {
            if (Thread.currentThread().isInterrupted()) { break; }
            List<BlockPos> found = chunk.getAbsoluteBlocks(block);
            if (found == null) { continue; }
            for (BlockPos pos : found) {
                if (result.size() >= maximum) { return List.copyOf(result); }
                result.add(pos);
            }
        }
        return List.copyOf(result);
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public BlockState getBlockState(BlockPos pos) { return get(pos.getX(), pos.getY(), pos.getZ()); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public int getHeight() { return height; }
    @Override public int getMinBuildHeight() { return minY; }
}
