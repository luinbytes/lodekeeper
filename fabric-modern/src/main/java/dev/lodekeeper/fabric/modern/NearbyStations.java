package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.StationId;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class NearbyStations {
    private static final Map<Block, StationId> KINDS = Map.of(
            Blocks.CRAFTING_TABLE, StationId.parse("minecraft:crafting_table"),
            Blocks.FURNACE, StationId.parse("minecraft:furnace"),
            Blocks.SMOKER, StationId.parse("minecraft:smoker"),
            Blocks.BLAST_FURNACE, StationId.parse("minecraft:blast_furnace"),
            Blocks.STONECUTTER, StationId.parse("minecraft:stonecutter"));
    private static final List<BlockPos> OFFSETS = offsets();
    private final Minecraft client;
    private final Map<BlockPos, StationId> observations = new HashMap<>();
    private final Map<Long, Boolean> loadedChunks = new HashMap<>();
    private BlockPos origin;
    private int cursor, completedTicks;

    NearbyStations(Minecraft client) { this.client = client; }

    void reset() {
        observations.clear(); loadedChunks.clear(); origin = null;
        cursor = completedTicks = 0;
    }

    boolean advance() {
        if (client.player == null || client.level == null) { reset(); return false; }
        BlockPos feet = client.player.blockPosition();
        if (origin == null || cursor == OFFSETS.size()
                && (feet.distSqr(origin) > 16 || ++completedTicks >= 100)) {
            reset(); origin = feet.immutable();
        }
        long deadline = System.nanoTime() + 500_000L;
        for (int probes = 0; cursor < OFFSETS.size() && probes < 256 && System.nanoTime() < deadline; probes++) {
            BlockPos offset = OFFSETS.get(cursor++);
            BlockPos position = origin.offset(offset);
            if (client.level.isOutsideBuildHeight(position) || !loaded(position)) continue;
            StationId kind = KINDS.get(client.level.getBlockState(position).getBlock());
            if (kind != null) observations.put(position.immutable(), kind);
        }
        return cursor == OFFSETS.size();
    }

    Map<BlockPos, StationId> observations() {
        if (client.player == null || client.level == null) return Map.of();
        Map<BlockPos, StationId> valid = new HashMap<>();
        observations.forEach((position, kind) -> {
            if (client.level.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) != null
                    && kind.equals(KINDS.get(client.level.getBlockState(position).getBlock()))) valid.put(position, kind);
        });
        return Map.copyOf(valid);
    }

    private boolean loaded(BlockPos position) {
        int x = position.getX() >> 4, z = position.getZ() >> 4;
        long key = ((long) x << 32) | (z & 0xffffffffL);
        return loadedChunks.computeIfAbsent(key,
                ignored -> client.level.getChunk(x, z, ChunkStatus.FULL, false) != null);
    }

    private static List<BlockPos> offsets() {
        List<BlockPos> result = new ArrayList<>();
        for (int x = -6; x <= 6; x++) for (int y = -2; y <= 2; y++) for (int z = -6; z <= 6; z++)
            result.add(new BlockPos(x, y, z));
        result.sort(Comparator.comparingInt(position -> position.getX() * position.getX()
                + position.getY() * position.getY() + position.getZ() * position.getZ()));
        return List.copyOf(result);
    }
}
