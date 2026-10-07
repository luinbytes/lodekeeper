/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lodekeeper.navigation.kernel.cache;

import dev.lodekeeper.navigation.kernel.api.utils.BlockUtils;
import dev.lodekeeper.navigation.kernel.pathing.movement.MovementHelper;
import dev.lodekeeper.navigation.kernel.utils.pathing.PathingBlockType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import dev.lodekeeper.navigation.kernel.snapshot.ChunkSnapshot;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.*;


/**
 * @author Brady
 * @since 8/3/2018
 */
public final class ChunkPacker {

    private ChunkPacker() {}

    public static CachedChunk pack(ChunkSnapshot chunk) {
        //long start = System.nanoTime() / 1000000L;

        Map<String, List<BlockPos>> specialBlocks = new HashMap<>();
        final int height = chunk.height();
        BitSet bitSet = new BitSet(CachedChunk.size(height));
        try {
            for (int y0 = 0; y0 < height / 16; y0++) {
                if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException(); }
                int yReal = y0 << 4;
                // the mapping of BlockStateContainer.getIndex from xyz to index is y << 8 | z << 4 | x;
                // for better cache locality, iterate in that order
                for (int y1 = 0; y1 < 16; y1++) {
                    int y = y1 | yReal;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int index = CachedChunk.getPositionIndex(x, y, z);
                            BlockState state = chunk.get(x, y + chunk.minY(), z);
                            boolean[] bits = getPathingBlockType(state, chunk, x, y + chunk.minY(), z).getBits();
                            bitSet.set(index, bits[0]);
                            bitSet.set(index + 1, bits[1]);
                            Block block = state.getBlock();
                            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                                String name = BlockUtils.blockToString(block);
                                specialBlocks.computeIfAbsent(name, b -> new ArrayList<>()).add(new BlockPos(x, y+chunk.minY(), z));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Immutable chunk packing failed", e);
        }
        //long end = System.nanoTime() / 1000000L;
        //System.out.println("Chunk packing took " + (end - start) + "ms for " + chunk.x + "," + chunk.z);
        BlockState[] blocks = new BlockState[256];

        // get top block in columns
        // @formatter:off
        for (int z = 0; z < 16; z++) {
            https://www.ibm.com/developerworks/library/j-perry-writing-good-java-code/index.html
            for (int x = 0; x < 16; x++) {
                for (int y = height - 1; y >= 0; y--) {
                    int index = CachedChunk.getPositionIndex(x, y, z);
                    if (bitSet.get(index) || bitSet.get(index + 1)) {
                        blocks[z << 4 | x] = chunk.get(x, y + chunk.minY(), z);
                        continue https;
                    }
                }
                blocks[z << 4 | x] = Blocks.AIR.defaultBlockState();
            }
        }
        // @formatter:on
        return new CachedChunk(chunk.x(), chunk.z(), height, bitSet, blocks, specialBlocks, System.currentTimeMillis());
    }

    private static PathingBlockType getPathingBlockType(BlockState state, ChunkSnapshot chunk, int x, int y, int z) {
        Block block = state.getBlock();
        if (MovementHelper.isWater(state)) {
            // only water source blocks are plausibly usable, flowing water should be avoid
            // FLOWING_WATER is a waterfall, it doesn't really matter and caching it as AVOID just makes it look wrong
            if (MovementHelper.possiblyFlowing(state)) {
                return PathingBlockType.AVOID;
            }
            int adjY = y - chunk.minY();
            if (
                    (x != 15 && MovementHelper.possiblyFlowing(chunk.get(x + 1, adjY + chunk.minY(), z)))
                            || (x != 0 && MovementHelper.possiblyFlowing(chunk.get(x - 1, adjY + chunk.minY(), z)))
                            || (z != 15 && MovementHelper.possiblyFlowing(chunk.get(x, adjY + chunk.minY(), z + 1)))
                            || (z != 0 && MovementHelper.possiblyFlowing(chunk.get(x, adjY + chunk.minY(), z - 1)))
            ) {
                return PathingBlockType.AVOID;
            }
            if (x == 0 || x == 15 || z == 0 || z == 15) {
                return PathingBlockType.AVOID;
            }
            return PathingBlockType.WATER;
        }

        if (MovementHelper.avoidWalkingInto(state) || MovementHelper.isBottomSlab(state)) {
            return PathingBlockType.AVOID;
        }
        // We used to do an AABB check here
        // however, this failed in the nether when you were near a nether fortress
        // because fences check their adjacent blocks in the world for their fence connection status to determine AABB shape
        // this caused a nullpointerexception when we saved chunks on unload, because they were unable to check their neighbors
        if (block instanceof AirBlock || block instanceof TallGrassBlock || block instanceof DoublePlantBlock || block instanceof FlowerBlock) {
            return PathingBlockType.AIR;
        }

        return PathingBlockType.SOLID;
    }

    public static BlockState pathingTypeToBlock(PathingBlockType type, DimensionType dimension, ResourceKey<Level> dimensionId) {
        switch (type) {
            case AIR:
                return Blocks.AIR.defaultBlockState();
            case WATER:
                return Blocks.WATER.defaultBlockState();
            case AVOID:
                return Blocks.LAVA.defaultBlockState();
            case SOLID:
                // Dimension solid types
                if (Level.OVERWORLD.equals(dimensionId)) {
                    return Blocks.STONE.defaultBlockState();
                }
                if (Level.NETHER.equals(dimensionId)) {
                    return Blocks.NETHERRACK.defaultBlockState();
                }
                if (Level.END.equals(dimensionId)) {
                    return Blocks.END_STONE.defaultBlockState();
                }
            default:
                return null;
        }
    }
}
