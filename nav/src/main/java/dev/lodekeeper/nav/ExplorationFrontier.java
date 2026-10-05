package dev.lodekeeper.nav;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Bounded surface exploration. Proposes loaded safe waypoints; routing remains the caller's job. */
public final class ExplorationFrontier {
    public enum Status { IN_PROGRESS, READY, EXHAUSTED }
    public record Waypoint(int x, int y, int z) {}
    private static final int[][] DIRECTIONS = {{1,0},{0,1},{-1,0},{0,-1},{1,1},{-1,1},{-1,-1},{1,-1}};
    private static final int[] HEIGHTS = {0,1,-1,2,-2,3,-3};
    private static final int CANDIDATES = DIRECTIONS.length * 4 * HEIGHTS.length;
    private final int originX, originZ, maxAttempts, maxDistance;
    private final LongSupplier clock;
    private final Set<Long> visited = new HashSet<>();
    private final StanceProbe probe = new StanceProbe();
    private int currentX, currentY, currentZ, index, attempts;
    private long bestScore = Long.MIN_VALUE;
    private Waypoint best;
    private Status status = Status.EXHAUSTED;

    public ExplorationFrontier(int originX, int originZ, int maxAttempts, int maxDistance) {
        this(originX, originZ, maxAttempts, maxDistance, System::nanoTime);
    }
    ExplorationFrontier(int originX, int originZ, int maxAttempts, int maxDistance, LongSupplier clock) {
        if (maxAttempts < 1 || maxAttempts > 128 || maxDistance < 16 || maxDistance > 2048) {
            throw new IllegalArgumentException("exploration limits out of range");
        }
        this.originX = originX; this.originZ = originZ;
        this.maxAttempts = maxAttempts; this.maxDistance = maxDistance; this.clock = clock;
        visited.add(region(originX, originZ));
    }
    public void beginAt(int x, int y, int z) {
        currentX = x; currentY = y; currentZ = z; index = 0; best = null; bestScore = Long.MIN_VALUE;
        status = attempts >= maxAttempts || !withinBound(x,z) ? Status.EXHAUSTED : Status.IN_PROGRESS;
        // Reserve the attempt before probing: interrupted/timed-out searches also consume the bound.
        if (status == Status.IN_PROGRESS) attempts++;
    }
    public Status advance(Terrain terrain, int probeBudget, long nanosBudget) {
        if (probeBudget < 1 || nanosBudget < 1) throw new IllegalArgumentException("positive search budgets required");
        if (status != Status.IN_PROGRESS) return status;
        long started = clock.getAsLong();
        int probes = 0;
        while (index < CANDIDATES && probes < probeBudget && clock.getAsLong() - started < nanosBudget) {
            int candidate = index++; probes++;
            int height = HEIGHTS[candidate % HEIGHTS.length];
            int radius = 4 * (1 + candidate / HEIGHTS.length % 4);
            int[] direction = DIRECTIONS[candidate / (HEIGHTS.length * 4)];
            long x = (long) currentX + direction[0] * radius;
            long y = (long) currentY + height;
            long z = (long) currentZ + direction[1] * radius;
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                    || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
            int px = (int) x, py = (int) y, pz = (int) z;
            long localDistance = (x - currentX) * (x - currentX) + (z - currentZ) * (z - currentZ);
            if (localDistance < 64 || localDistance > 256 || !withinBound(px,pz)
                    || visited.contains(region(px,pz)) || region(px,pz) == region(currentX,currentZ)) continue;
            terrain.probeStance(px,py,pz,probe.clear());
            if (!probe.loaded || !probe.bodyClear || !probe.fullSupport || probe.hazard || probe.water
                    || probe.climbable || probe.breakCount != 0) continue;
            long ox = x - originX, oz = z - originZ;
            long score = (ox * ox + oz * oz) * 32 + localDistance - Math.abs(height);
            if (score > bestScore) { bestScore = score; best = new Waypoint(px,py,pz); }
        }
        if (index == CANDIDATES) {
            if (best == null) status = Status.EXHAUSTED;
            else { visited.add(region(best.x,best.z)); status = Status.READY; }
        }
        return status;
    }
    public Waypoint waypoint() {
        if (status != Status.READY) throw new IllegalStateException("no ready exploration waypoint");
        return best;
    }
    public int attempts() { return attempts; }
    private boolean withinBound(int x, int z) {
        long dx = (long) x - originX, dz = (long) z - originZ;
        return Math.abs(dx) <= maxDistance && Math.abs(dz) <= maxDistance
                && dx * dx + dz * dz <= (long) maxDistance * maxDistance;
    }
    private static long region(int x, int z) {
        return ((long) Math.floorDiv(x,8) << 32) | (Math.floorDiv(z,8) & 0xffff_ffffL);
    }
}
