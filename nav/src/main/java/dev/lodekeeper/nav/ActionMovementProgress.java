package dev.lodekeeper.nav;

import java.util.HashSet;

public final class ActionMovementProgress {
    private static final int MAX_EDGES = 8_192;
    private record DirectedEdge(long from, long to) { }

    private final HashSet<DirectedEdge> edges = new HashSet<>();
    private long previousCell;
    private boolean seeded;

    public boolean observe(long cell) {
        if (!seeded) {
            rebase(cell);
            return false;
        }
        if (cell == previousCell) return false;
        long from = previousCell;
        previousCell = cell;
        return !saturated() && edges.add(new DirectedEdge(from, cell));
    }

    public void rebase(long cell) {
        previousCell = cell;
        seeded = true;
    }

    public boolean saturated() {
        return edges.size() == MAX_EDGES;
    }
}
