package dev.lodekeeper.core;

/** Conservative recipe-progress capacity for a continuously supplied fuel at a native cooking speed. */
public final class CookingFuelCapacity {
    private CookingFuelCapacity() { }

    /**
     * Native cooking divides using floats and then rounds the total timer upward. Preserve that
     * rounding before translating burn duration into recipe progress; separate fuel stacks may
     * fund one recipe as long as the executor maintains the same speed without fuel gaps.
     * Invalid or unsupported values return zero rather than authorize optimistic fuel quantities.
     */
    public static long progressTicks(long burnTicks, int recipeDuration, float speed) {
        return progressTicks(burnTicks, recipeDuration, speed, true);
    }

    /** Discontinuous fuel can fund only complete operations; its leftover progress may decay. */
    public static long progressTicks(long burnTicks, int recipeDuration, float speed, boolean continuous) {
        if (burnTicks < 1 || burnTicks > 10_000_000L || recipeDuration < 1 || recipeDuration > 10_000_000
                || !Float.isFinite(speed) || speed <= 0 || speed > 1024) return 0;
        double totalTicks = Math.ceil((double) (recipeDuration / speed));
        if (!Double.isFinite(totalTicks) || totalTicks < 1 || totalTicks > Integer.MAX_VALUE) return 0;
        long capacity = continuous ? burnTicks * recipeDuration / (long) totalTicks
                : (burnTicks / (long) totalTicks) * recipeDuration;
        return Math.min(1_000_000_000L, capacity);
    }
}
