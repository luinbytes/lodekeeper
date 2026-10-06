package dev.lodekeeper.fabric;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Palette-pruned, incremental discovery. Reads loaded chunks only and never loads a chunk. */
final class BlockSearch {
    private static final int MAX_REPRESENTATIVE_BLOCKS = 512;
    private final MinecraftClient client;
    private final Set<Block> blocks;
    private final Set<BlockPos> excluded;
    private final Predicate<BlockPos> candidateFilter;
    private final Consumer<ChunkPos> chunkObserver;
    private final Set<Block> matchedBlocks = new java.util.HashSet<>();
    private final BlockPos origin;
    private final int radius;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private WorldChunk chunk;
    private int chunkIndex, sectionIndex, sectionCursor, cellIndex;
    private int[] sectionOrder;
    private final PriorityQueue<BlockPos> candidates;
    private final boolean retainRepresentatives;
    private final Map<Block, BlockPos> representatives;
    private BlockPos best;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private boolean done;
    BlockSearch(MinecraftClient client, Set<Block> blocks, int radius) {
        this(client, blocks, radius, Set.of());
    }

    BlockSearch(MinecraftClient client, Set<Block> blocks, int radius, Set<BlockPos> excluded) {
        this(client, blocks, radius, excluded, false);
    }

    BlockSearch(MinecraftClient client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                boolean retainRepresentatives) {
        this(client, blocks, radius, excluded, retainRepresentatives, null);
    }

    BlockSearch(MinecraftClient client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                boolean retainRepresentatives, List<ChunkPos> selectedChunks) {
        this(client, blocks, radius, excluded, retainRepresentatives, selectedChunks,
                position -> true, ignored -> {}, false);
    }

    BlockSearch(MinecraftClient client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                List<ChunkPos> selectedChunks, Predicate<BlockPos> candidateFilter,
                Consumer<ChunkPos> chunkObserver) {
        this(client, blocks, radius, excluded, true, selectedChunks, candidateFilter, chunkObserver, true);
    }

    private BlockSearch(MinecraftClient client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                        boolean retainRepresentatives, List<ChunkPos> selectedChunks,
                        Predicate<BlockPos> candidateFilter, Consumer<ChunkPos> chunkObserver,
                        boolean selectedChunksOrdered) {
        this.client = client; this.blocks = Set.copyOf(blocks);
        this.excluded = Set.copyOf(excluded); this.radius = radius;
        this.candidateFilter = candidateFilter;
        this.chunkObserver = chunkObserver;
        this.retainRepresentatives = retainRepresentatives;
        this.representatives = retainRepresentatives ? new java.util.HashMap<>() : Map.of();
        origin = client.player.getBlockPos();
        candidates = new PriorityQueue<>(Comparator.comparingDouble((BlockPos pos) -> pos.getSquaredDistance(origin)).reversed());
        if (selectedChunks == null) {
            int chunkRadius = (radius + 15) / 16;
            ChunkPos center = new ChunkPos(origin);
            for (int x = -chunkRadius; x <= chunkRadius; x++) for (int z = -chunkRadius; z <= chunkRadius; z++) chunks.add(new ChunkPos(center.x + x, center.z + z));
            chunks.sort(Comparator.comparingDouble(p -> Math.pow(p.getCenterX() - origin.getX(), 2) + Math.pow(p.getCenterZ() - origin.getZ(), 2)));
        } else {
            chunks.addAll(selectedChunks);
            if (!selectedChunksOrdered) chunks.sort(Comparator.comparingDouble(p -> Math.pow(p.getCenterX() - origin.getX(), 2) + Math.pow(p.getCenterZ() - origin.getZ(), 2)));
        }
    }
    boolean advance(int blockBudget, long nanosBudget) {
        if (done || client.world == null) return true;
        long deadline = System.nanoTime() + nanosBudget;
        int probes = 0, sections = 0;
        while (probes < blockBudget && sections < 32 && System.nanoTime() < deadline) {
            if (chunk == null) {
                if (chunkIndex == chunks.size()) { done = true; break; }
                ChunkPos pos = chunks.get(chunkIndex++);
                chunkObserver.accept(pos);
                chunk = client.world.getChunkManager().getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
                sectionCursor = 0; cellIndex = 0;
                if (chunk == null) continue;
                if (sectionOrder == null) sectionOrder = orderSections(chunk.getSectionArray().length, client.world.getBottomY());
            }
            if (sectionCursor == sectionOrder.length) { chunk = null; continue; }
            sectionIndex = sectionOrder[sectionCursor];
            ChunkSection section = chunk.getSection(sectionIndex);
            if (cellIndex == 0) {
                sections++;
                if (section.isEmpty() || !section.hasAny(s -> blocks.contains(s.getBlock()))) { sectionCursor++; continue; }
            }
            int x = cellIndex & 15, z = cellIndex >> 4 & 15, y = cellIndex >> 8;
            if (blocks.contains(section.getBlockState(x, y, z).getBlock())) {
                BlockPos pos = new BlockPos(chunk.getPos().getStartX() + x, client.world.getBottomY() + sectionIndex * 16 + y, chunk.getPos().getStartZ() + z);
                double horizontal = Math.pow(pos.getX() - origin.getX(), 2) + Math.pow(pos.getZ() - origin.getZ(), 2);
                double distance = pos.getSquaredDistance(origin);
                if (horizontal <= radius * radius) {
                    Block matched = section.getBlockState(x, y, z).getBlock();
                    matchedBlocks.add(matched);
                    if (excluded.contains(pos) || !candidateFilter.test(pos)) {
                        probes++;
                        if (++cellIndex == 4096) { cellIndex = 0; sectionCursor++; }
                        continue;
                    }
                    if (retainRepresentatives) {
                        BlockPos representative = representatives.get(matched);
                        if (representative != null) {
                            if (distance < representative.getSquaredDistance(origin)) representatives.put(matched, pos);
                        } else if (representatives.size() < MAX_REPRESENTATIVE_BLOCKS) {
                            representatives.put(matched, pos);
                        }
                    }
                    if (candidates.size() < 512) candidates.add(pos);
                    else if (distance < candidates.peek().getSquaredDistance(origin)) { candidates.remove(); candidates.add(pos); }
                    if (distance < bestDistance) { bestDistance = distance; best = pos; }
                }
            }
            probes++;
            if (++cellIndex == 4096) { cellIndex = 0; sectionCursor++; }
        }
        return done;
    }
    private int[] orderSections(int count, int bottomY) {
        return java.util.stream.IntStream.range(0, count).boxed()
                .sorted(Comparator.comparingInt((Integer section) -> {
                    int min = bottomY + section * 16;
                    return origin.getY() < min ? min - origin.getY()
                            : origin.getY() > min + 15 ? origin.getY() - min - 15 : 0;
                }).thenComparingInt(Integer::intValue))
                .mapToInt(Integer::intValue).toArray();
    }

    /** Candidates can be used before discovery finishes; absence is proven only after completion. */
    long progressToken() { return ((long) chunkIndex << 32) | ((long) sectionCursor << 16) | cellIndex; }
    String progressDescription() { return "chunk " + Math.min(chunkIndex, chunks.size()) + "/" + chunks.size(); }
    boolean hasCandidates() { return !candidates.isEmpty(); }
    boolean complete() { return done; }
    boolean found(Block block) { return matchedBlocks.contains(block); }
    BlockPos result() { return best; }
    List<BlockPos> results() { return candidates.stream().sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin))).toList(); }
    List<BlockPos> representativeResults() {
        return retainRepresentatives ? representatives.values().stream()
                .sorted(Comparator.comparingDouble(position -> position.getSquaredDistance(origin))).toList() : List.of();
    }
}
