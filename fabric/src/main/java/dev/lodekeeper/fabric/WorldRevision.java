package dev.lodekeeper.fabric;

import dev.lodekeeper.nav.RegionRevisionTracker;

/** Client-thread invalidation for chunks sampled by the active navigation search. */
public final class WorldRevision {
    private static final RegionRevisionTracker TRACKER = new RegionRevisionTracker();
    static { TRACKER.begin(); }
    private WorldRevision() {}
    static void beginSearch() { TRACKER.begin(); }
    static void watch(int chunkX, int chunkZ) { TRACKER.watch(chunkX, chunkZ); }
    public static void changed(int blockX, int blockZ) { TRACKER.changed(blockX >> 4, blockZ >> 4); }
    static void changedChunk(int chunkX, int chunkZ) { TRACKER.changed(chunkX, chunkZ); }
    public static long value() { return TRACKER.currentRevision(); }
}
