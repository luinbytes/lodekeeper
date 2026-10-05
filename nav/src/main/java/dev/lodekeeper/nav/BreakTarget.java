package dev.lodekeeper.nav;

/** A mutable slot describing a real block that prevents a stance from being occupied. */
public final class BreakTarget {
    public int x;
    public int y;
    public int z;
    /** Adapter-owned state token used by the executor to reject a changed target. */
    public int stateToken;
    /** Positive abstract search cost; adapters commonly scale predicted mining ticks by ten. */
    public int cost;

    void copyFrom(BreakTarget other) {
        x = other.x;
        y = other.y;
        z = other.z;
        stateToken = other.stateToken;
        cost = other.cost;
    }
}
