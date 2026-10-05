package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.GatherSource;
import dev.lodekeeper.core.PlanningPreferences;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Small, loaded-world observations used only to order plans, never to prove absence or reachability. */
final class NearbyResources {
    private static final int RADIUS = 8;
    private static final int WIDTH = RADIUS * 2 + 1;
    private static final int MAX_INDEXED_SOURCES = 4096;
    private static final int MAX_INDEXED_BLOCKS = 8192;
    private static final int MAX_SOURCES_PER_BLOCK = 32;
    private static final int MAX_OBSERVED_SOURCES = 256;
    private static final int[] OFFSETS = java.util.stream.IntStream.range(0, WIDTH * WIDTH * WIDTH).boxed()
            .sorted(Comparator.comparingInt(NearbyResources::distance).thenComparingInt(Integer::intValue))
            .mapToInt(Integer::intValue).toArray();

    private final Minecraft client;
    private final Map<Block, Set<String>> sourcesByBlock = new HashMap<>();
    private final Map<String, BlockPos> observations = new HashMap<>();
    private final Map<String, Integer> fallbackEffort = new HashMap<>();
    private BlockPos origin;
    private long generation = -1;
    private long version;
    private int cursor, sourceCursor, blockCursor, indexedSources, indexedBlocks;
    private GatherSource indexingGather;
    private boolean indexComplete;
    private final Map<Block, Float> hardnessCache = new HashMap<>();

    NearbyResources(Minecraft client) { this.client = client; }

    void reset() {
        origin = null;
        generation = -1;
        cursor = 0;
        sourceCursor = blockCursor = indexedSources = indexedBlocks = 0;
        indexingGather = null;
        indexComplete = false;
        hardnessCache.clear();
        sourcesByBlock.clear();
        fallbackEffort.clear();
        observations.clear();
        version++;
    }

    void tick(GameCatalog catalog, int configuredProbeBudget) {
        if (client.level == null || client.player == null || catalog == null || !catalog.ready()) return;
        if (generation != catalog.generation()) {
            reset();
            generation = catalog.generation();
        }
        long deadline = System.nanoTime() + 500_000;
        advanceIndex(catalog, deadline);
        if (!indexComplete) return;
        BlockPos feet = client.player.blockPosition();
        if (origin == null || feet.distSqr(origin) > 16) {
            origin = feet.immutable();
            observations.clear();
            cursor = 0;
            version++;
        }
        int probes = 0, budget = Math.min(128, configuredProbeBudget);
        while (cursor < OFFSETS.length && probes < budget && System.nanoTime() < deadline) {
            int offset = OFFSETS[cursor++];
            BlockPos position = origin.offset(dx(offset), dy(offset), dz(offset));
            probes++;
            if (!chunkPresent(position)) continue;
            Block block = client.level.getBlockState(position).getBlock();
            Set<String> sources = sourcesByBlock.get(block);
            if (sources == null) continue;
            for (String source : sources) {
                if (System.nanoTime() >= deadline) break;
                BlockPos previous = observations.get(source);
                if (previous == null) {
                    if (observations.size() >= MAX_OBSERVED_SOURCES) continue;
                    observations.put(source, position);
                    version++;
                } else if (position.distSqr(origin) < previous.distSqr(origin)) {
                    observations.put(source, position);
                }
            }
        }
    }

    private void advanceIndex(GameCatalog catalog, long deadline) {
        int units = 0;
        while (!indexComplete && units++ < 512 && System.nanoTime() < deadline) {
            if (indexedBlocks >= MAX_INDEXED_BLOCKS) { indexComplete = true; break; }
            if (indexingGather == null) {
                if (sourceCursor >= catalog.sources.size() || indexedSources >= MAX_INDEXED_SOURCES
                        || indexedBlocks >= MAX_INDEXED_BLOCKS) {
                    indexComplete = true;
                    break;
                }
                var source = catalog.sources.get(sourceCursor++);
                if (!(source instanceof GatherSource gather)) continue;
                indexedSources++;
                indexingGather = gather;
                blockCursor = 0;
            }
            if (blockCursor >= indexingGather.blocks().size()) {
                indexingGather = null;
                continue;
            }
            var blockId = indexingGather.blocks().get(blockCursor++);
            indexedBlocks++;
            Block block = GameCatalog.block(blockId);
            if (block == null || block == Blocks.AIR) continue;
            Set<String> fanOut = sourcesByBlock.computeIfAbsent(block, ignored -> new LinkedHashSet<>());
            if (fanOut.size() < MAX_SOURCES_PER_BLOCK) fanOut.add(indexingGather.sourceId());
            // Only native vanilla blocks get an unobserved effort estimate. Custom sources stay unknown.
            if (!blockId.namespace().equals("minecraft") || !block.getClass().getName().startsWith("net.minecraft.")) continue;
            Float hardness = hardnessCache.get(block);
            if (hardness == null) {
                try { hardness = block.defaultBlockState().getDestroySpeed(client.level, client.player.blockPosition()); }
                catch (RuntimeException ignored) { hardness = Float.NaN; }
                hardnessCache.put(block, hardness);
            }
            if (Float.isFinite(hardness) && hardness >= 0) {
                int effort = 1_000_000 + (int) Math.min(100_000_000,
                        Math.ceil(hardness * 1000.0 / indexingGather.outputCount()));
                fallbackEffort.merge(indexingGather.sourceId(), effort, Math::min);
            }
        }
    }

    boolean ready() { return indexComplete; }

    PlanningPreferences snapshot() {
        if (client.level == null || origin == null) return PlanningPreferences.NONE;
        Map<String, Integer> ranks = new HashMap<>(fallbackEffort);
        boolean invalidated = false;
        var entries = observations.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            BlockPos position = entry.getValue();
            Set<String> sources = chunkPresent(position)
                    ? sourcesByBlock.get(client.level.getBlockState(position).getBlock()) : null;
            if (sources == null || !sources.contains(entry.getKey())) {
                entries.remove();
                invalidated = true;
                continue;
            }
            ranks.put(entry.getKey(), 1 + (int) position.distSqr(origin));
        }
        if (invalidated) {
            // A mined nearest block must not hide farther live candidates in the same window.
            cursor = 0;
            version++;
        }
        return new PlanningPreferences(ranks);
    }

    private boolean chunkPresent(BlockPos position) {
        return client.level.getChunkSource().getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }

    long version() { return version; }
    private static int dx(int offset) { return offset % WIDTH - RADIUS; }
    private static int dy(int offset) { return offset / WIDTH % WIDTH - RADIUS; }
    private static int dz(int offset) { return offset / (WIDTH * WIDTH) - RADIUS; }
    private static int distance(int offset) {
        int x = dx(offset), y = dy(offset), z = dz(offset);
        return x * x + y * y + z * z;
    }
}
