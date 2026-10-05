package dev.lodekeeper.nav;

import java.util.Arrays;

/** An exact stance goal, bounded arrival region, or bounded set of stance goals. */
public final class Goal {
    private static final int MAX_ANY_POSITIONS = 128;
    private static final long[] NO_POSITIONS = new long[0];

    public enum Kind { EXACT, NEAR, ANY }

    public final Kind kind;
    public final int x;
    public final int y;
    public final int z;
    public final int radius;
    private final long[] packedPositions;
    private final int[] anyX;
    private final int[] anyY;
    private final int[] anyZ;

    private Goal(Kind kind, int x, int y, int z, int radius) {
        this(kind, x, y, z, radius, NO_POSITIONS);
    }

    private Goal(Kind kind, int x, int y, int z, int radius, long[] packedPositions) {
        Position.pack(x, y, z);
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.packedPositions = packedPositions;
        if (kind == Kind.ANY) {
            anyX = new int[packedPositions.length];
            anyY = new int[packedPositions.length];
            anyZ = new int[packedPositions.length];
            for (int i = 0; i < packedPositions.length; i++) {
                long packed = packedPositions[i];
                anyX[i] = Position.x(packed);
                anyY[i] = Position.y(packed);
                anyZ[i] = Position.z(packed);
            }
        } else {
            anyX = null;
            anyY = null;
            anyZ = null;
        }
    }

    public static Goal exact(int x, int y, int z) {
        return new Goal(Kind.EXACT, x, y, z, 0);
    }

    /** Radius is measured in Euclidean block distance between stance centers. */
    public static Goal near(int x, int y, int z, int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        return new Goal(Kind.NEAR, x, y, z, radius);
    }

    /** Match any of at most 128 packed stance positions. */
    public static Goal anyOf(long... packedPositions) {
        if (packedPositions == null) throw new NullPointerException("packedPositions");
        if (packedPositions.length == 0 || packedPositions.length > MAX_ANY_POSITIONS) {
            throw new IllegalArgumentException("anyOf requires between one and 128 positions");
        }

        long[] sortedPositions = Arrays.copyOf(packedPositions, packedPositions.length);
        for (long packed : sortedPositions) {
            // Unpack and repack through the public coordinate API to enforce its bounds.
            if (Position.pack(Position.x(packed), Position.y(packed), Position.z(packed)) != packed) {
                throw new IllegalArgumentException("position is not canonically packed");
            }
        }
        Arrays.sort(sortedPositions);
        int uniqueCount = 0;
        for (long packed : sortedPositions) {
            if (uniqueCount == 0 || sortedPositions[uniqueCount - 1] != packed) {
                sortedPositions[uniqueCount++] = packed;
            }
        }
        if (uniqueCount != sortedPositions.length) {
            sortedPositions = Arrays.copyOf(sortedPositions, uniqueCount);
        }

        long first = packedPositions[0];
        return new Goal(Kind.ANY, Position.x(first), Position.y(first), Position.z(first), 0,
                sortedPositions);
    }

    public boolean matches(int px, int py, int pz) {
        if (kind == Kind.ANY) {
            return Arrays.binarySearch(packedPositions, Position.pack(px, py, pz)) >= 0;
        }
        long dx = (long) px - x;
        long dy = (long) py - y;
        long dz = (long) pz - z;
        if (kind == Kind.EXACT) return dx == 0 && dy == 0 && dz == 0;
        long r2 = (long) radius * radius;
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    long heuristic(int px, int py, int pz) {
        if (kind == Kind.ANY) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < anyX.length; i++) {
                best = Math.min(best, exactHeuristic(px, py, pz, anyX[i], anyY[i], anyZ[i], 0));
            }
            return best;
        }
        return exactHeuristic(px, py, pz, x, y, z, radius);
    }

    private static long exactHeuristic(int px, int py, int pz, int gx, int gy, int gz, int radius) {
        long dx = Math.abs((long) px - gx);
        long dz = Math.abs((long) pz - gz);
        long diagonal = Math.min(dx, dz);
        // Every horizontal edge costs at least the octile metric (10 cardinal, 14 diagonal).
        // Subtracting 14 per arrival-radius block is conservative in every direction.
        long horizontal = Math.max(0L, 14L * diagonal + 10L * (Math.max(dx, dz) - diagonal) - 14L * radius);
        // Rising one block costs at least 17; a three-block fall costs at least 22.
        // Combining with max avoids charging twice for a ledge move that advances both axes.
        long rise = Math.max(0L, (long) gy - py - radius);
        long fall = Math.max(0L, (long) py - gy - radius);
        long vertical = rise > 0 ? 17L * rise : 22L * fall / 3L;
        return Math.max(horizontal, vertical);
    }
}
