package dev.lodekeeper.fabric;

/** Connection-scoped invalidation for observed block changes; no world objects cross threads. */
public final class WorldRevision {
    private static long value;
    private WorldRevision() {}
    public static void changed() { value++; }
    public static long value() { return value; }
}
