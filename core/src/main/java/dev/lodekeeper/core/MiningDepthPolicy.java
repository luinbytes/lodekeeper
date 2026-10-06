package dev.lodekeeper.core;

/** A mining request keeps its depth choice as inventory changes. */
public record MiningDepthPolicy(Kind kind, int desiredY, int maximumY) {
    public enum Kind { NEAREST, BULK_DIAMOND }

    public static MiningDepthPolicy select(int missingCount, boolean vanillaDiamonds,
                                          boolean allowExploration, int worldFloor, int existingMaximumY) {
        if (missingCount <= 8 || !vanillaDiamonds || !allowExploration || worldFloor != -64
                || existingMaximumY <= worldFloor) {
            return new MiningDepthPolicy(Kind.NEAREST, Integer.MIN_VALUE, existingMaximumY);
        }
        int maximumY = Math.min(existingMaximumY, -40);
        return new MiningDepthPolicy(Kind.BULK_DIAMOND, Math.min(-55, maximumY), maximumY);
    }

    public boolean bulkDiamonds() { return kind == Kind.BULK_DIAMOND; }

    public int effectiveMaximumY(int liveMaximumY) { return Math.min(maximumY, liveMaximumY); }

    public int effectiveDesiredY(int liveMaximumY) { return Math.min(desiredY, effectiveMaximumY(liveMaximumY)); }

    public boolean shouldDescend(int currentY) { return bulkDiamonds() && currentY > maximumY; }
}
