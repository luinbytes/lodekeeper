package dev.lodekeeper.nav;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Bounded surface exploration. Proposes loaded safe waypoints; routing remains the caller's job. */
public final class ExplorationFrontier {
    public enum Status { IN_PROGRESS, READY, EXHAUSTED }

    public record Waypoint(int x, int y, int z, long feetY16) {
        public Waypoint(int x, int y, int z) { this(x, y, z, (long) y * 16L); }
        public Waypoint {
            if (Math.floorDiv(feetY16, 16L) != y) {
                throw new IllegalArgumentException("y must be the floor block of feetY16");
            }
        }
        public double feetY() { return feetY16 / 16.0; }
    }

    private static final int[][] DIRECTIONS = {{1,0},{0,1},{-1,0},{0,-1},{1,1},{-1,1},{-1,-1},{1,-1}};
    private static final int[] HEIGHTS = {0,1,-1,2,-2,3,-3};
    private static final int CANDIDATES = DIRECTIONS.length * 4 * HEIGHTS.length;
    private final int originX, originZ, maxAttempts, maxDistance;
    private final LongSupplier clock;
    private final Set<Long> visited = new HashSet<>();
    private final StanceProbe probe = new StanceProbe();
    private final GroundedStanceBuffer stances = new GroundedStanceBuffer();
    private int currentX, currentZ, index, attempts;
    private long currentFeetY16;
    private int pendingX, pendingZ, pendingIndex;
    private long pendingReferenceFeetY16, pendingLocalDistance;
    private boolean pending, pendingLegacyHeight;
    private long pendingFeetY16;
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

    /** Legacy integer-height entry point. */
    public void beginAt(int x, int y, int z) {
        beginAtHeight(x, (long) y * 16L, z);
    }

    /** Begin an exploration search at an exact feet height in sixteenths. */
    public void beginAt16(int x, int feetY16, int z) {
        beginAtHeight(x, feetY16, z);
    }

    private void beginAtHeight(int x, long feetY16, int z) {
        currentX = x; currentZ = z;
        currentFeetY16 = feetY16;
        index = 0; best = null; bestScore = Long.MIN_VALUE;
        pending = false; pendingLegacyHeight = false; pendingIndex = 0;
        status = attempts >= maxAttempts || !withinBound(x,z) ? Status.EXHAUSTED : Status.IN_PROGRESS;
        // Reserve the attempt before probing: interrupted/timed-out searches also consume the bound.
        if (status == Status.IN_PROGRESS) attempts++;
    }

    public Status advance(Terrain terrain, int probeBudget, long nanosBudget) {
        if (terrain == null) throw new NullPointerException("terrain");
        if (probeBudget < 1 || nanosBudget < 1) throw new IllegalArgumentException("positive search budgets required");
        if (status != Status.IN_PROGRESS) return status;
        long started = clock.getAsLong();
        int probes = 0;
        while (index < CANDIDATES || pending) {
            if (probes >= probeBudget || clock.getAsLong() - started >= nanosBudget) break;
            if (!pending) {
                prepareCandidate(terrain);
                probes++; // Charge every candidate query, including empty or incomplete results.
                if (!pending) continue;
                if (probes >= probeBudget) break;
            }

            long candidateFeetY16;
            if (pendingLegacyHeight) {
                candidateFeetY16 = pendingFeetY16;
                pendingLegacyHeight = false;
            } else if (pendingIndex < stances.size()) {
                candidateFeetY16 = stances.get(pendingIndex++);
            } else {
                pending = false;
                continue;
            }
            probes++;
            probeCandidate(terrain, candidateFeetY16);
            if (pendingIndex >= stances.size()) pending = false;
        }
        if (index == CANDIDATES && !pending) {
            if (best == null) status = Status.EXHAUSTED;
            else { visited.add(region(best.x,best.z)); status = Status.READY; }
        }
        return status;
    }

    private void prepareCandidate(Terrain terrain) {
        if (index >= CANDIDATES) return;
        int candidate = index++;
        int height = HEIGHTS[candidate % HEIGHTS.length];
        int radius = 4 * (1 + candidate / HEIGHTS.length % 4);
        int[] direction = DIRECTIONS[candidate / (HEIGHTS.length * 4)];
        long x = (long) currentX + direction[0] * radius;
        long referenceFeetY16 = currentFeetY16 + (long) height * 16L;
        long z = (long) currentZ + direction[1] * radius;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE
                || Math.floorDiv(referenceFeetY16, 16L) < Integer.MIN_VALUE
                || Math.floorDiv(referenceFeetY16, 16L) > Integer.MAX_VALUE) return;
        int px = (int) x, pz = (int) z;
        long localDistance = (x - currentX) * (x - currentX) + (z - currentZ) * (z - currentZ);
        if (localDistance < 64 || localDistance > 256 || !withinBound(px,pz)
                || visited.contains(region(px,pz)) || region(px,pz) == region(currentX,currentZ)) return;

        pendingX = px; pendingZ = pz; pendingReferenceFeetY16 = referenceFeetY16;
        pendingLocalDistance = localDistance; pendingIndex = 0;
        if (referenceFeetY16 < Integer.MIN_VALUE || referenceFeetY16 > Integer.MAX_VALUE) {
            pendingLegacyHeight = true;
            pendingFeetY16 = referenceFeetY16;
            stances.clear();
        } else {
            pendingLegacyHeight = false;
            stances.clear();
            boolean complete = terrain.collectGroundedStances(px, (int) referenceFeetY16, pz, stances);
            if (!complete || !stances.isComplete()) return;
        }
        pending = pendingLegacyHeight || stances.size() > 0;
    }

    private void probeCandidate(Terrain terrain, long feetY16) {
        if (Math.abs((long) feetY16 - pendingReferenceFeetY16) > 16L) return;
        int py = Math.toIntExact(Math.floorDiv(feetY16, 16L));
        if (feetY16 < Integer.MIN_VALUE || feetY16 > Integer.MAX_VALUE) {
            terrain.probeStance(pendingX, py, pendingZ, probe.clear());
        } else {
            terrain.probeStance16(pendingX, (int) feetY16, pendingZ, probe.clear());
        }
        if (!probe.loaded || !probe.bodyClear || !probe.hasGroundSupport() || probe.hazard || probe.water
                || probe.climbable || probe.breakCount != 0) return;
        long ox = (long) pendingX - originX, oz = (long) pendingZ - originZ;
        long verticalPenalty = Math.abs(feetY16 - currentFeetY16) / 16L;
        long score = (ox * ox + oz * oz) * 32 + pendingLocalDistance - verticalPenalty;
        if (score > bestScore) {
            bestScore = score;
            best = new Waypoint(pendingX, py, pendingZ, feetY16);
        }
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
