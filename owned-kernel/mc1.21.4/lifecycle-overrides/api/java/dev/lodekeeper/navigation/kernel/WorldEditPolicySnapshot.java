package dev.lodekeeper.navigation.kernel;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class WorldEditPolicySnapshot implements WorldEditPolicy {
    private static final int MAX_REGIONS = 128;
    private static final int MAX_INDEX_REFERENCES = 65_536;
    private final List<BlockRegion> protectedBreakRegions;
    private final List<BlockRegion> protectedPlaceRegions;
    private final RegionIndex breakIndex;
    private final RegionIndex placeIndex;

    public WorldEditPolicySnapshot(List<BlockRegion> breaks, List<BlockRegion> places) {
        if (breaks.size() > MAX_REGIONS || places.size() > MAX_REGIONS) {
            throw new IllegalArgumentException("at most 128 protected regions per operation");
        }
        protectedBreakRegions = List.copyOf(breaks);
        protectedPlaceRegions = List.copyOf(places);
        breakIndex = new RegionIndex(protectedBreakRegions, MAX_INDEX_REFERENCES);
        placeIndex = protectedBreakRegions.equals(protectedPlaceRegions) ? breakIndex
                : new RegionIndex(protectedPlaceRegions, MAX_INDEX_REFERENCES - breakIndex.references);
    }

    public WorldEditPolicySnapshot(List<BlockRegion> protectedRegions) {
        this(protectedRegions, protectedRegions);
    }

    public List<BlockRegion> protectedBreakRegions() { return protectedBreakRegions; }
    public List<BlockRegion> protectedPlaceRegions() { return protectedPlaceRegions; }
    public boolean mayBreak(BlockPos position) { return !breakIndex.contains(position); }
    public boolean mayPlace(BlockPos position) { return !placeIndex.contains(position); }

    private static long chunkKey(int x, int z) { return (x & 0xffffffffL) | ((long) z << 32); }

    private static final class RegionIndex {
        final java.util.Map<Long, List<BlockRegion>> chunks;
        final List<BlockRegion> oversized;
        final int references;

        RegionIndex(List<BlockRegion> regions, int budget) {
            var mutable = new java.util.HashMap<Long, java.util.ArrayList<BlockRegion>>();
            var fallback = new java.util.ArrayList<BlockRegion>();
            int count = 0;
            for (BlockRegion region : regions) {
                int minX = region.minX() >> 4;
                int maxX = region.maxX() >> 4;
                int minZ = region.minZ() >> 4;
                int maxZ = region.maxZ() >> 4;
                long cells = ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
                if (cells > budget - count) { fallback.add(region); continue; }
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        mutable.computeIfAbsent(chunkKey(x, z), key -> new java.util.ArrayList<>()).add(region);
                        count++;
                    }
                }
            }
            var frozen = new java.util.HashMap<Long, List<BlockRegion>>();
            mutable.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            chunks = java.util.Map.copyOf(frozen);
            oversized = List.copyOf(fallback);
            references = count;
        }

        boolean contains(BlockPos position) {
            for (BlockRegion region : oversized) {
                if (region.contains(position)) { return true; }
            }
            var candidates = chunks.get(chunkKey(position.getX() >> 4, position.getZ() >> 4));
            if (candidates != null) {
                for (BlockRegion region : candidates) {
                    if (region.contains(position)) { return true; }
                }
            }
            return false;
        }
    }

    public record BlockRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public BlockRegion {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("region minimum exceeds maximum");
            }
        }

        public boolean contains(BlockPos position) {
            return position.getX() >= minX && position.getX() <= maxX
                    && position.getY() >= minY && position.getY() <= maxY
                    && position.getZ() >= minZ && position.getZ() <= maxZ;
        }
    }
}
