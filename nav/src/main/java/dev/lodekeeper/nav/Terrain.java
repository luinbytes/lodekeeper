package dev.lodekeeper.nav;

/**
 * Read-only adapter seam. Implementations run on the caller's thread and must never load chunks.
 * Return only facts from already loaded terrain; unknown geometry is blocked.
 */
public interface Terrain {
    /**
     * Probe a player stance at block-centered X/Z and integer feet Y. This is a stance summary of
     * the player's full AABB, not the state of the block at those coordinates. Set every field of
     * {@code out}; use {@link StanceProbe#clear()} first if convenient. Full support must be a
     * conservative full-block surface, and bodyClear must account for neighboring cells and
     * actual collision shapes. Break targets name the exact intersecting cells and state tokens.
     */
    void probeStance(int feetX, int feetY, int feetZ, StanceProbe out);

    /**
     * Add every safe grounded feet height available near {@code referenceFeetY16} at the given
     * block X/Z. Results are absolute feet heights in sixteenths. Implementations must mark the
     * buffer incomplete if the physical candidate set exceeds its capacity.
     */
    default boolean collectGroundedStances(int x, int referenceFeetY16, int z,
                                           GroundedStanceBuffer out) {
        if (out == null) throw new NullPointerException("out");
        out.clear();
        if (Math.floorMod(referenceFeetY16, 16) == 0) out.add(referenceFeetY16);
        return out.isComplete();
    }

    /** Probe a block-centered stance at an exact feet height in sixteenths. */
    default boolean probeStance16(int feetX, int feetY16, int feetZ, StanceProbe out) {
        if (out == null) throw new NullPointerException("out");
        if (Math.floorMod(feetY16, 16) != 0) {
            out.clear();
            return false;
        }
        probeStance(feetX, Math.floorDiv(feetY16, 16), feetZ, out);
        return out.loaded;
    }

    /** Probe support at the player's real X/Z after a strict sixteenth-block Y quantization. */
    default boolean probeCurrentStance(double feetX, int feetY16, double feetZ, StanceProbe out) {
        if (out == null) throw new NullPointerException("out");
        if (!Double.isFinite(feetX) || !Double.isFinite(feetZ)
                || Math.floorMod(feetY16, 16) != 0) {
            out.clear();
            return false;
        }
        probeStance((int) Math.floor(feetX), Math.floorDiv(feetY16, 16),
                (int) Math.floor(feetZ), out);
        return out.loaded;
    }

    /**
     * Check the complete swept player box for a movement. The feet follow a straight interpolation
     * plus {@code sin(pi*t)*arcHeight}; negative or zero height means no jump arc. The check must
     * reject unloaded cells, collisions and hazards. It may ignore only the destination probe's
     * listed break targets, because those are mined before movement.
     */
    boolean isMotionClear(double fromX, double fromFeetY, double fromZ,
                          double toX, double toFeetY, double toZ,
                          double arcHeight, StanceProbe destinationAfterBreak);

    /**
     * Check motion from a stance whose entry obstruction may already have been mined. A null
     * {@code sourceAfterBreak} means there is no virtual source. The default fails closed for a
     * non-null virtual source; adapters that support routes through successive obstructions
     * override this overload and ignore the source's listed break cells.
     */
    default boolean isMotionClear(double fromX, double fromFeetY, double fromZ,
                                  double toX, double toFeetY, double toZ,
                                  double arcHeight, StanceProbe sourceAfterBreak,
                                  StanceProbe destinationAfterBreak) {
        if (sourceAfterBreak != null && sourceAfterBreak.breakCount != 0) return false;
        return isMotionClear(fromX, fromFeetY, fromZ, toX, toFeetY, toZ,
                arcHeight, destinationAfterBreak);
    }

    /**
     * Prove a grounded walk sweep between two exact stance heights. The default preserves legacy
     * integer, same-height movement and fails closed for fractional or rising/falling steps.
     */
    default boolean isGroundedWalkClear(double fromX, int fromFeetY16, double fromZ,
                                        double toX, int toFeetY16, double toZ,
                                        StanceProbe sourceAfterBreak,
                                        StanceProbe destinationAfterBreak) {
        if (fromFeetY16 != toFeetY16 || Math.floorMod(fromFeetY16, 16) != 0) return false;
        return isMotionClear(fromX, fromFeetY16 / 16.0, fromZ,
                toX, toFeetY16 / 16.0, toZ, 0.0, sourceAfterBreak, destinationAfterBreak);
    }

    /** True only when this exact break target is mineable from the current stance and in reach. */
    boolean canBreakFrom(int fromFeetX, int fromFeetY, int fromFeetZ,
                         StanceProbe destination, int targetIndex);

    /**
     * Validate placing the selected full-block item into targetBlockX/Y/Z from the current
     * stance. When {@code supportWasPlanned} is true, the source support is an earlier bridge
     * action in this same path and will exist by the time this call executes.
     */
    boolean canPlaceBridgeFrom(int fromFeetX, int fromFeetY, int fromFeetZ,
                               int targetBlockX, int targetBlockY, int targetBlockZ,
                               int blockItemToken, boolean supportWasPlanned);

    /**
     * Monotonic revision for terrain sampled by this planner. Change it when any relevant loaded
     * block/chunk/placement fact changes. The default describes a stable snapshot.
     */
    default long revision() { return 0L; }
}
