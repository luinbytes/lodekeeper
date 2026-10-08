package dev.lodekeeper.navigation.kernel.snapshot;

import dev.lodekeeper.navigation.kernel.cache.CachedChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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
    private final ResourceKey<Level> dimensionId;
    private final int minY;
    private final int height;

    public ImmutableWorldView(Map<Long, ChunkSnapshot> live, Map<Long, CachedChunk> cached, DimensionType dimension, ResourceKey<Level> dimensionId) {
        this.live = Map.copyOf(live);
        this.cached = Map.copyOf(cached);
        this.dimension = dimension;
        this.dimensionId = dimensionId;
        minY = dimension.minY();
        height = dimension.height();
    }

    public BlockState get(int x, int y, int z) {
        if (y < minY || y >= minY + height) { return Blocks.AIR.defaultBlockState(); }
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        ChunkSnapshot chunk = live.get(key);
        if (chunk != null) { return chunk.get(x & 15, y, z & 15); }
        CachedChunk packed = cached.get(key);
        return packed == null ? Blocks.AIR.defaultBlockState() : packed.getBlock(x & 15, y - minY, z & 15, dimension, dimensionId);
    }

    public boolean hasLiveChunk(int x, int z) { return live.containsKey(ChunkPos.asLong(x >> 4, z >> 4)); }
    public boolean isLoaded(int x, int z) {
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        return live.containsKey(key) || cached.containsKey(key);
    }

    public List<BlockPos> scan(Predicate<BlockState> filter, int maximum, BlockPos center) {
        checkScanInterrupted();
        int limit = Math.max(0, Math.min(maximum, 65_536));
        if (limit == 0) { return List.of(); }
        int centerX = center.getX(), centerY = center.getY(), centerZ = center.getZ();
        long topY = (long) minY + height;
        List<ScanRegion> regions = new ArrayList<>();
        for (ChunkSnapshot chunk : live.values()) {
            checkScanInterrupted();
            long x0 = (long) chunk.x() * 16, z0 = (long) chunk.z() * 16;
            for (long y0 = minY; y0 < topY; y0 += 16) {
                checkScanInterrupted();
                long y1 = Math.min(y0 + 16, topY);
                double lowerD2 = scanDistanceSquared(scanAxisGap(x0, x0 + 15, centerX),
                        scanAxisGap(y0, y1 - 1, centerY), scanAxisGap(z0, z0 + 15, centerZ));
                regions.add(new ScanRegion(chunk, x0, z0, y0, y1, lowerD2));
            }
        }
        checkScanInterrupted();
        regions.sort((left, right) -> {
            checkScanInterrupted();
            return Double.compare(left.lowerD2(), right.lowerD2());
        });
        checkScanInterrupted();
        var retained = new java.util.PriorityQueue<ScanHit>(11, java.util.Comparator.reverseOrder());
        for (ScanRegion region : regions) {
            checkScanInterrupted();
            if (retained.size() == limit && region.lowerD2() > retained.peek().distanceSquared()) { break; }
            for (long y = region.y0(); y < region.y1(); y++) {
                int worldY = Math.toIntExact(y);
                for (int z = 0; z < 16; z++) {
                    checkScanInterrupted();
                    int worldZ = Math.toIntExact(region.z0() + z);
                    for (int x = 0; x < 16; x++) {
                        int worldX = Math.toIntExact(region.x0() + x);
                        double distance = scanDistanceSquared((long) worldX - centerX,
                                (long) worldY - centerY, (long) worldZ - centerZ);
                        if (retained.size() == limit && distance > retained.peek().distanceSquared()) { continue; }
                        if (!filter.test(region.chunk().get(x, worldY, z))) { continue; }
                        if (retained.size() < limit) {
                            retained.add(new ScanHit(new BlockPos(worldX, worldY, worldZ), distance));
                        } else if (compareScanHit(distance, worldX, worldY, worldZ, retained.peek()) < 0) {
                            retained.poll();
                            retained.add(new ScanHit(new BlockPos(worldX, worldY, worldZ), distance));
                        }
                    }
                }
            }
        }
        checkScanInterrupted();
        var ordered = new ArrayList<>(retained);
        checkScanInterrupted();
        ordered.sort(null);
        checkScanInterrupted();
        List<BlockPos> result = new ArrayList<>(ordered.size());
        for (ScanHit hit : ordered) {
            checkScanInterrupted();
            result.add(hit.position());
        }
        checkScanInterrupted();
        List<BlockPos> immutable = List.copyOf(result);
        checkScanInterrupted();
        return immutable;
    }

    private record ScanRegion(ChunkSnapshot chunk, long x0, long z0, long y0, long y1, double lowerD2) {}

    private record ScanHit(BlockPos position, double distanceSquared) implements Comparable<ScanHit> {
        @Override public int compareTo(ScanHit other) {
            return compareScanHit(distanceSquared, position.getX(), position.getY(), position.getZ(), other);
        }
    }

    private static int compareScanHit(double distance, int x, int y, int z, ScanHit other) {
        checkScanInterrupted();
        int order = Double.compare(distance, other.distanceSquared());
        if (order == 0) { order = Integer.compare(x, other.position().getX()); }
        if (order == 0) { order = Integer.compare(y, other.position().getY()); }
        if (order == 0) { order = Integer.compare(z, other.position().getZ()); }
        return order;
    }

    private static long scanAxisGap(long low, long high, int center) {
        return Math.max(0L, Math.max(low - center, (long) center - high));
    }

    private static double scanDistanceSquared(long dx, long dy, long dz) {
        double x = dx, y = dy, z = dz;
        return x * x + y * y + z * z;
    }

    private static void checkScanInterrupted() {
        if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException(); }
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
    @Override public int getMinY() { return minY; }
}
