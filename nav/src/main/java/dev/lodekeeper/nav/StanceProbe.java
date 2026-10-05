package dev.lodekeeper.nav;

/**
 * Reusable result of a terrain stance probe. Fields summarize
 * the player's centered, full bounding box at one candidate feet position; they are not raw
 * properties of the single block at (x,y,z).
 */
public final class StanceProbe {
    public static final int MAX_BREAK_TARGETS = 2;

    /** False if any block needed to evaluate this stance is unloaded or otherwise unknown. */
    public boolean loaded;
    /** True if the actual player bounding box is collision-free at this stance. */
    public boolean bodyClear;
    /** True only when a full-block support surface safely supports the centered player. */
    public boolean fullSupport;
    /** True when a non-full-block surface physically supports the player's footprint. */
    public boolean surfaceSupport;
    /** True if any portion of the player box intersects a lethal or policy-blocked hazard. */
    public boolean hazard;
    /** True when water intersects the player box. */
    public boolean water;
    /** True when a climbable block intersects the player box. */
    public boolean climbable;
    /** Number of break targets needed to make this stance clear; values above two are rejected. */
    public int breakCount;
    public final BreakTarget[] breakTargets = { new BreakTarget(), new BreakTarget() };

    public StanceProbe clear() {
        loaded = false;
        bodyClear = false;
        fullSupport = false;
        surfaceSupport = false;
        hazard = true;
        water = false;
        climbable = false;
        breakCount = 0;
        return this;
    }

    /** True when full-block or adapter-proven partial-surface support exists. */
    public boolean hasGroundSupport() { return fullSupport || surfaceSupport; }

    void copyFrom(StanceProbe other) {
        loaded = other.loaded;
        bodyClear = other.bodyClear;
        fullSupport = other.fullSupport;
        surfaceSupport = other.surfaceSupport;
        hazard = other.hazard;
        water = other.water;
        climbable = other.climbable;
        breakCount = other.breakCount;
        if (breakCount > 0) breakTargets[0].copyFrom(other.breakTargets[0]);
        if (breakCount > 1) breakTargets[1].copyFrom(other.breakTargets[1]);
    }
}
