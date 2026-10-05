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
import java.util.PriorityQueue;
import java.util.Set;

/** Incremental palette-pruned search over already loaded chunks. It never requests a chunk load. */
final class BlockSearch {
    private final Minecraft client;
    private final Set<Block> blocks;
    private final Set<Block> matchedBlocks = new java.util.HashSet<>();
    private final BlockPos origin;
    private final int radius;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private LevelChunk chunk;
    private int chunkIndex, sectionIndex, cellIndex;
    private final PriorityQueue<BlockPos> candidates;
    private BlockPos best;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private boolean done;

    BlockSearch(Minecraft client, Set<Block> blocks, int radius) {
        this.client = client;
        this.blocks = Set.copyOf(blocks);
        this.radius = radius;
        origin = client.player.blockPosition();
        candidates = new PriorityQueue<>(Comparator.comparingDouble((BlockPos position) -> position.distSqr(origin)).reversed());
        int chunkRadius = (radius + 15) / 16;
        ChunkPos center = ChunkPos.containing(origin);
        for (int x = -chunkRadius; x <= chunkRadius; x++) for (int z = -chunkRadius; z <= chunkRadius; z++)
            chunks.add(new ChunkPos(center.x() + x, center.z() + z));
        chunks.sort(Comparator.comparingDouble(p -> Math.pow(p.getMiddleBlockX() - origin.getX(), 2)
                + Math.pow(p.getMiddleBlockZ() - origin.getZ(), 2)));
    }

    boolean advance(int blockBudget, long nanosBudget) {
        if (done || client.level == null) return true;
        long deadline = System.nanoTime() + nanosBudget;
        int probes = 0, sections = 0;
        while (probes < blockBudget && sections < 32 && System.nanoTime() < deadline) {
            if (chunk == null) {
                if (chunkIndex == chunks.size()) { done = true; break; }
                ChunkPos pos = chunks.get(chunkIndex++);
                var loaded = client.level.getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                chunk = loaded instanceof LevelChunk levelChunk ? levelChunk : null;
                sectionIndex = 0;
                cellIndex = 0;
                if (chunk == null) continue;
            }
            LevelChunkSection[] sectionsArray = chunk.getSections();
            if (sectionIndex == sectionsArray.length) { chunk = null; continue; }
            LevelChunkSection section = sectionsArray[sectionIndex];
            if (cellIndex == 0) {
                sections++;
                if (section.hasOnlyAir() || !section.maybeHas(state -> blocks.contains(state.getBlock()))) {
                    sectionIndex++;
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
                    matchedBlocks.add(section.getBlockState(x, y, z).getBlock());
                    if (candidates.size() < 512) candidates.add(position);
                    else if (distance < candidates.peek().distSqr(origin)) {
                        candidates.remove();
                        candidates.add(position);
                    }
                    if (distance < bestDistance) { bestDistance = distance; best = position; }
                }
            }
            probes++;
            if (++cellIndex == 4096) { cellIndex = 0; sectionIndex++; }
        }
        return done;
    }

    boolean found(Block block) { return matchedBlocks.contains(block); }
    BlockPos result() { return best; }
    List<BlockPos> results() {
        return candidates.stream().sorted(Comparator.comparingDouble(position -> position.distSqr(origin))).toList();
    }
}
