package dev.lodekeeper.navigation.kernel.snapshot;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

public final class ChunkSnapshot {
    private final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session;
    private final long revision;
    private final int x;
    private final int z;
    private final int minY;
    private final PalettedContainer<BlockState>[] sections;

    ChunkSnapshot(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session, long revision, int x, int z, int minY, PalettedContainer<BlockState>[] ownedSections) {
        this.session = session;
        this.revision = revision;
        this.x = x;
        this.z = z;
        this.minY = minY;
        this.sections = ownedSections.clone();
    }

    PalettedContainer<BlockState>[] sections() { return sections.clone(); }

    public dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session() { return session; }
    public long revision() { return revision; }
    public int x() { return x; }
    public int z() { return z; }
    public int minY() { return minY; }
    public int height() { return sections.length * 16; }

    public BlockState get(int localX, int worldY, int localZ) {
        int relative = worldY - minY;
        if (relative < 0 || relative >= height()) { return Blocks.AIR.defaultBlockState(); }
        PalettedContainer<BlockState> section = sections[relative >> 4];
        return section == null ? Blocks.AIR.defaultBlockState() : section.get(localX & 15, relative & 15, localZ & 15);
    }
}
