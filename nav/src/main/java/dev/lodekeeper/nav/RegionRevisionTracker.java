package dev.lodekeeper.nav;

/**
 * Bounded, search-scoped tracking of terrain regions sampled by one navigation search.
 *
 * <p>Call {@link #begin()} before constructing the search. Call {@link #watch(int, int)}
 * before reading terrain in a chunk, and report later block changes or unloads with
 * {@link #changed(int, int)} or {@link #unloaded(int, int)}. Coordinates are chunk
 * coordinates, including for negative positions. The tracker stores coordinates only;
 * it never retains world, chunk, or block objects.</p>
 *
 * <p>This class is deliberately unsynchronized and thread-confined. The first call to
 * {@code begin} binds it to the calling thread, and later calls from another thread fail.
 * In particular, world reads and change notifications must be delivered on that thread.</p>
 */
public final class RegionRevisionTracker {
    public static final int DEFAULT_MAX_WATCHED_CHUNKS = 4_096;
    private static final int MAX_WATCHED_CHUNKS = 1_048_576;

    private final int maxWatchedChunks;
    private final int[] generations;
    private final long[] chunkKeys;
    private final int tableMask;

    private Thread ownerThread;
    private int generation = 1;
    private int watchedChunks;
    private long revision;
    private boolean overflowed;
    private boolean begun;

    public RegionRevisionTracker() {
        this(DEFAULT_MAX_WATCHED_CHUNKS);
    }

    /** Creates a tracker with a fixed maximum number of watched chunks. */
    public RegionRevisionTracker(int maxWatchedChunks) {
        if (maxWatchedChunks < 1 || maxWatchedChunks > MAX_WATCHED_CHUNKS) {
            throw new IllegalArgumentException("maxWatchedChunks must be between 1 and "
                    + MAX_WATCHED_CHUNKS);
        }
        this.maxWatchedChunks = maxWatchedChunks;

        int tableCapacity = 2;
        long requiredCapacity = (long) maxWatchedChunks * 2;
        while (tableCapacity < requiredCapacity) tableCapacity <<= 1;
        generations = new int[tableCapacity];
        chunkKeys = new long[tableCapacity];
        tableMask = tableCapacity - 1;
    }

    /**
     * Starts a new search observation window and invalidates any planner using the old one.
     * Call this before creating the planner whose {@code Terrain.revision()} reads this tracker.
     */
    public void begin() {
        bindOrCheckThread();
        if (generation == Integer.MAX_VALUE) {
            // Keep generation zero reserved for never-used slots.
            java.util.Arrays.fill(generations, 0);
            generation = 1;
        } else {
            generation++;
        }
        watchedChunks = 0;
        overflowed = false;
        begun = true;
        revision++;
    }

    /** Alias for {@link #begin()}, useful when resetting a tracker after a world change. */
    public void reset() {
        begin();
    }

    /**
     * Records that the current search is about to sample this chunk. Repeated watches are free.
     * If the fixed watch limit is exceeded, the current search is invalidated and the tracker
     * conservatively treats every later chunk change as relevant until the next {@link #begin()}.
     */
    public void watch(int chunkX, int chunkZ) {
        checkThreadAndStarted();
        if (overflowed) return;

        long key = chunkKey(chunkX, chunkZ);
        int slot = findSlot(key);
        if (generations[slot] == generation) return;
        if (watchedChunks == maxWatchedChunks) {
            overflowed = true;
            revision++;
            return;
        }

        generations[slot] = generation;
        chunkKeys[slot] = key;
        watchedChunks++;
    }

    /** Reports a block or placement change in a chunk. */
    public void changed(int chunkX, int chunkZ) {
        checkThreadAndStarted();
        if (overflowed || contains(chunkKey(chunkX, chunkZ))) revision++;
    }

    /** Reports that a previously loaded chunk became unavailable. */
    public void unloaded(int chunkX, int chunkZ) {
        changed(chunkX, chunkZ);
    }

    /** Current invalidation token; adapters can return this from {@link Terrain#revision()}. */
    public long currentRevision() {
        checkThreadAndStarted();
        return revision;
    }

    private void bindOrCheckThread() {
        Thread current = Thread.currentThread();
        if (ownerThread == null) {
            ownerThread = current;
        } else if (ownerThread != current) {
            throw new IllegalStateException("RegionRevisionTracker is thread-confined");
        }
    }

    private void checkThreadAndStarted() {
        bindOrCheckThread();
        if (!begun) throw new IllegalStateException("begin() must be called before using the tracker");
    }

    private boolean contains(long key) {
        int slot = hash(key) & tableMask;
        for (int probes = 0; probes < generations.length; probes++) {
            if (generations[slot] != generation) return false;
            if (chunkKeys[slot] == key) return true;
            slot = (slot + 1) & tableMask;
        }
        return false;
    }

    private int findSlot(long key) {
        int slot = hash(key) & tableMask;
        for (int probes = 0; probes < generations.length; probes++) {
            if (generations[slot] != generation || chunkKeys[slot] == key) return slot;
            slot = (slot + 1) & tableMask;
        }
        // The table is kept at no more than half capacity, so a free slot must exist.
        throw new IllegalStateException("RegionRevisionTracker hash table is full");
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
    }

    private static int hash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return (int) key;
    }
}
