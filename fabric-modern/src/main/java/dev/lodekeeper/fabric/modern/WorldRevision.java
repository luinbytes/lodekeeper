package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.RegionRevisionTracker;

/** Connection-scoped invalidation for observed block changes; no world objects cross threads. */
public final class WorldRevision {
    private static final RegionRevisionTracker TRACKER = new RegionRevisionTracker();
    private static final RegionRevisionTracker PREFERRED_STATIONS = new RegionRevisionTracker(4_229);
    static { TRACKER.begin(); PREFERRED_STATIONS.begin(); }
    private WorldRevision() {}
    static void beginSearch() { TRACKER.begin(); }
    static void watch(int chunkX, int chunkZ) { TRACKER.watch(chunkX, chunkZ); }
    static void beginPreferredStationScan() { PREFERRED_STATIONS.begin(); }
    static void watchPreferredStationChunk(int chunkX, int chunkZ) { PREFERRED_STATIONS.watch(chunkX, chunkZ); }
    static long preferredStationRevision() { return PREFERRED_STATIONS.currentRevision(); }
    public static void changed(int blockX, int blockZ) {
        TRACKER.changed(blockX >> 4, blockZ >> 4);
        PREFERRED_STATIONS.changed(blockX >> 4, blockZ >> 4);
    }
    static void changedChunk(int chunkX, int chunkZ) {
        TRACKER.changed(chunkX, chunkZ);
        PREFERRED_STATIONS.changed(chunkX, chunkZ);
    }
    public static long value() { return TRACKER.currentRevision(); }
}
