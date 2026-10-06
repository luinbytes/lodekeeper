package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Block;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Incremental palette-pruned search over already loaded chunks. It never requests a chunk load. */
final class BlockSearch {
    private static final int MAX_REPRESENTATIVE_BLOCKS = 512;
    private final Minecraft client;
    private final Set<Block> blocks;
    private final Set<BlockPos> excluded;
    private final Predicate<BlockPos> candidateFilter;
    private final Consumer<ChunkPos> chunkObserver;
    private final Set<Block> matchedBlocks = new java.util.HashSet<>();
    private final BlockPos origin;
    private final int radius;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private LevelChunk chunk;
    private int chunkIndex, sectionIndex, sectionCursor, cellIndex;
    private int[] sectionOrder;
    private final PriorityQueue<BlockPos> candidates;
    private final boolean retainRepresentatives;
    private final Map<Block, BlockPos> representatives;
    private BlockPos best;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private boolean done;

    BlockSearch(Minecraft client, Set<Block> blocks, int radius) {
        this(client, blocks, radius, Set.of());
    }

    BlockSearch(Minecraft client, Set<Block> blocks, int radius, Set<BlockPos> excluded) {
        this(client, blocks, radius, excluded, false);
    }

    BlockSearch(Minecraft client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                boolean retainRepresentatives) {
        this(client, blocks, radius, excluded, retainRepresentatives, null);
    }

    BlockSearch(Minecraft client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                boolean retainRepresentatives, List<ChunkPos> selectedChunks) {
        this(client, blocks, radius, excluded, retainRepresentatives, selectedChunks,
                position -> true, ignored -> {}, false);
    }

    BlockSearch(Minecraft client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                List<ChunkPos> selectedChunks, Predicate<BlockPos> candidateFilter,
                Consumer<ChunkPos> chunkObserver) {
        this(client, blocks, radius, excluded, true, selectedChunks, candidateFilter, chunkObserver, true);
    }

    private BlockSearch(Minecraft client, Set<Block> blocks, int radius, Set<BlockPos> excluded,
                        boolean retainRepresentatives, List<ChunkPos> selectedChunks,
                        Predicate<BlockPos> candidateFilter, Consumer<ChunkPos> chunkObserver,
                        boolean selectedChunksOrdered) {
        this.client = client;
        this.blocks = Set.copyOf(blocks);
        this.excluded = Set.copyOf(excluded);
        this.candidateFilter = candidateFilter;
        this.chunkObserver = chunkObserver;
        this.radius = radius;
        this.retainRepresentatives = retainRepresentatives;
        this.representatives = retainRepresentatives ? new java.util.HashMap<>() : Map.of();
        origin = client.player.blockPosition();
        candidates = new PriorityQueue<>(Comparator.comparingDouble((BlockPos position) -> position.distSqr(origin)).reversed());
        if (selectedChunks == null) {
            int chunkRadius = (radius + 15) / 16;
            ChunkPos center = ChunkPos.containing(origin);
            for (int x = -chunkRadius; x <= chunkRadius; x++) for (int z = -chunkRadius; z <= chunkRadius; z++)
                chunks.add(new ChunkPos(center.x() + x, center.z() + z));
            chunks.sort(Comparator.comparingDouble(p -> Math.pow(p.getMiddleBlockX() - origin.getX(), 2)
                    + Math.pow(p.getMiddleBlockZ() - origin.getZ(), 2)));
        } else {
            chunks.addAll(selectedChunks);
            if (!selectedChunksOrdered) chunks.sort(Comparator.comparingDouble(p -> Math.pow(p.getMiddleBlockX() - origin.getX(), 2)
                    + Math.pow(p.getMiddleBlockZ() - origin.getZ(), 2)));
        }
    }

    boolean advance(int blockBudget, long nanosBudget) {
        if (done || client.level == null) return true;
        long deadline = System.nanoTime() + nanosBudget;
        int probes = 0, sections = 0;
        while (probes < blockBudget && sections < 32 && System.nanoTime() < deadline) {
            if (chunk == null) {
                if (chunkIndex == chunks.size()) { done = true; break; }
                ChunkPos pos = chunks.get(chunkIndex++);
                chunkObserver.accept(pos);
                var loaded = client.level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                chunk = loaded instanceof LevelChunk levelChunk ? levelChunk : null;
                sectionCursor = 0;
                cellIndex = 0;
                if (chunk == null) continue;
                if (sectionOrder == null) sectionOrder = orderSections(chunk.getSections().length, client.level.getMinY());
            }
            LevelChunkSection[] sectionsArray = chunk.getSections();
            if (sectionCursor == sectionOrder.length) { chunk = null; continue; }
            sectionIndex = sectionOrder[sectionCursor];
            LevelChunkSection section = sectionsArray[sectionIndex];
            if (cellIndex == 0) {
                sections++;
                if (section.hasOnlyAir() || !section.maybeHas(state -> blocks.contains(state.getBlock()))) {
                    sectionCursor++;
                    continue;
                }
            }
            int x = cellIndex & 15, z = cellIndex >> 4 & 15, y = cellIndex >> 8;
            if (blocks.contains(section.getBlockState(x, y, z).getBlock())) {
                BlockPos position = new BlockPos(chunk.getPos().getMinBlockX() + x,
                        client.level.getMinY() + sectionIndex * 16 + y,
                        chunk.getPos().getMinBlockZ() + z);
                double horizontal = Math.pow(position.getX() - origin.getX(), 2)
                        + Math.pow(position.getZ() - origin.getZ(), 2);
                double distance = position.distSqr(origin);
                if (horizontal <= radius * radius) {
                    Block matched = section.getBlockState(x, y, z).getBlock();
                    matchedBlocks.add(matched);
                    if (excluded.contains(position) || !candidateFilter.test(position)) {
                        probes++;
                        if (++cellIndex == 4096) { cellIndex = 0; sectionCursor++; }
                        continue;
                    }
                    if (retainRepresentatives) {
                        BlockPos representative = representatives.get(matched);
                        if (representative != null) {
                            if (distance < representative.distSqr(origin)) representatives.put(matched, position);
                        } else if (representatives.size() < MAX_REPRESENTATIVE_BLOCKS) {
                            representatives.put(matched, position);
                        }
                    }
                    if (candidates.size() < 512) candidates.add(position);
                    else if (distance < candidates.peek().distSqr(origin)) {
                        candidates.remove();
                        candidates.add(position);
                    }
                    if (distance < bestDistance) { bestDistance = distance; best = position; }
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
    List<BlockPos> representativeResults() {
        return retainRepresentatives ? representatives.values().stream()
                .sorted(Comparator.comparingDouble(position -> position.distSqr(origin))).toList() : List.of();
    }
    List<BlockPos> results() {
        return candidates.stream().sorted(Comparator.comparingDouble(position -> position.distSqr(origin))).toList();
    }
}
