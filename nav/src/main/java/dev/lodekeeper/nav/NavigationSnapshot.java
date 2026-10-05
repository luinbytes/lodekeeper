package dev.lodekeeper.nav;

/** Immutable client-thread observation; never drives navigation or samples terrain while rendering. */
public record NavigationSnapshot(Path path, int nextStep, long expanded, int discovered, int open,
                                 long searchNanos, int searchTicks, int retries, boolean searching,
                                 long[] nodePositions, byte[] nodeFractions, boolean[] nodeClosed) {
    public static final NavigationSnapshot EMPTY = new NavigationSnapshot(null, 0, 0, 0, 0,
            0, 0, 0, false, new long[0], new byte[0], new boolean[0]);

    public NavigationSnapshot {
        if (nodePositions == null || nodeFractions == null || nodeClosed == null
                || nodePositions.length != nodeFractions.length || nodePositions.length != nodeClosed.length
                || nodePositions.length > 256) throw new IllegalArgumentException("Invalid bounded node observation");
        nodePositions = nodePositions.clone();
        nodeFractions = nodeFractions.clone();
        nodeClosed = nodeClosed.clone();
    }
    @Override public long[] nodePositions() { return nodePositions.clone(); }
    @Override public byte[] nodeFractions() { return nodeFractions.clone(); }
    @Override public boolean[] nodeClosed() { return nodeClosed.clone(); }
    public int nodeCount() { return nodePositions.length; }
    public int nodeX(int index) { return Position.x(nodePositions[index]); }
    public double nodeY(int index) { return Position.y(nodePositions[index]) + nodeFractions[index] / 16.0; }
    public int nodeZ(int index) { return Position.z(nodePositions[index]); }
    public boolean nodeIsClosed(int index) { return nodeClosed[index]; }
}
