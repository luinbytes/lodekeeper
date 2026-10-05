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
import java.util.Set;

/** Palette-pruned, incremental discovery. Reads loaded chunks only and never loads a chunk. */
final class BlockSearch {
    private final MinecraftClient client;
    private final Set<Block> blocks;
    private final BlockPos origin;
    private final int radius;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private WorldChunk chunk;
    private int chunkIndex, sectionIndex, cellIndex;
    private BlockPos best;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private boolean done;
    BlockSearch(MinecraftClient client, Set<Block> blocks, int radius) {
        this.client = client; this.blocks = Set.copyOf(blocks); this.radius = radius;
        origin = client.player.getBlockPos();
        int chunkRadius = (radius + 15) / 16;
        ChunkPos center = new ChunkPos(origin);
        for (int x = -chunkRadius; x <= chunkRadius; x++) for (int z = -chunkRadius; z <= chunkRadius; z++) chunks.add(new ChunkPos(center.x + x, center.z + z));
        chunks.sort(Comparator.comparingDouble(p -> Math.pow(p.getCenterX() - origin.getX(), 2) + Math.pow(p.getCenterZ() - origin.getZ(), 2)));
    }
    boolean advance(int blockBudget, long nanosBudget) {
        if (done || client.world == null) return true;
        long deadline = System.nanoTime() + nanosBudget;
        int probes = 0, sections = 0;
        while (probes < blockBudget && sections < 32 && System.nanoTime() < deadline) {
            if (chunk == null) {
                if (chunkIndex == chunks.size()) { done = true; break; }
                ChunkPos pos = chunks.get(chunkIndex++);
                chunk = client.world.getChunkManager().getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
                sectionIndex = 0; cellIndex = 0;
                if (chunk == null) continue;
            }
            if (sectionIndex == chunk.getSectionArray().length) { chunk = null; continue; }
            ChunkSection section = chunk.getSection(sectionIndex);
            if (cellIndex == 0) {
                sections++;
                if (section.isEmpty() || !section.hasAny(s -> blocks.contains(s.getBlock()))) { sectionIndex++; continue; }
            }
            int x = cellIndex & 15, z = cellIndex >> 4 & 15, y = cellIndex >> 8;
            if (blocks.contains(section.getBlockState(x, y, z).getBlock())) {
                BlockPos pos = new BlockPos(chunk.getPos().getStartX() + x, client.world.getBottomY() + sectionIndex * 16 + y, chunk.getPos().getStartZ() + z);
                double horizontal = Math.pow(pos.getX() - origin.getX(), 2) + Math.pow(pos.getZ() - origin.getZ(), 2);
                double distance = pos.getSquaredDistance(origin);
                if (horizontal <= radius * radius && distance < bestDistance) { bestDistance = distance; best = pos; }
            }
            probes++;
            if (++cellIndex == 4096) { cellIndex = 0; sectionIndex++; }
        }
        return done;
    }
    BlockPos result() { return best; }
}
