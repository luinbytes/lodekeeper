package dev.lodekeeper.nav;

import java.util.Arrays;

/** An exact stance goal, bounded arrival region, or bounded set of stance goals. */
public final class Goal {
    private static final int MAX_ANY_POSITIONS = 128;
    private static final long[] NO_POSITIONS = new long[0];
    private static final byte[] NO_FRACTIONS = new byte[0];

    public enum Kind { EXACT, NEAR, ANY }

    public final Kind kind;
    public final int x;
    /** Floor block containing the goal feet; retained for legacy consumers. */
    public final int y;
    public final int z;
    /** Exact goal feet height in sixteenths. */
    public final int feetY16;
    /** Legacy block radius. For near16 goals this is the radius rounded down to blocks. */
    public final int radius;
    private final long radius16;
    private final long[] packedPositions;
    private final byte[] fractions;
    private final int[] anyX;
    private final int[] anyY;
    private final int[] anyZ;

    private Goal(Kind kind, int x, int feetY16, int z, long radius16,
                 long[] packedPositions, byte[] fractions) {
        int y = Math.floorDiv(feetY16, 16);
        Position.pack(x, y, z);
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.feetY16 = feetY16;
        this.radius16 = radius16;
        this.radius = (int) Math.min(Integer.MAX_VALUE, radius16 / 16L);
        this.packedPositions = packedPositions;
        this.fractions = fractions;
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
        Position.pack(x, y, z);
        return exact16(x, y * 16, z);
    }

    /** Create an exact goal whose feet height is supplied in sixteenths of a block. */
    public static Goal exact16(int x, int feetY16, int z) {
        return new Goal(Kind.EXACT, x, feetY16, z, 0L, NO_POSITIONS, NO_FRACTIONS);
    }

    /** Radius is measured in Euclidean block distance between stance centers. */
    public static Goal near(int x, int y, int z, int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        Position.pack(x, y, z);
        return near16(x, y * 16, z, (long) radius * 16L);
    }

    /** Radius is measured in sixteenths of Euclidean block distance. */
    public static Goal near16(int x, int feetY16, int z, int radius16) {
        if (radius16 < 0) throw new IllegalArgumentException("radius16 must be non-negative");
        return near16(x, feetY16, z, (long) radius16);
    }

    private static Goal near16(int x, int feetY16, int z, long radius16) {
        return new Goal(Kind.NEAR, x, feetY16, z, radius16, NO_POSITIONS, NO_FRACTIONS);
    }

    /** Match any of at most 128 packed stance positions. */
    public static Goal anyOf(long... packedPositions) {
        if (packedPositions == null) throw new NullPointerException("packedPositions");
        byte[] fractions = new byte[packedPositions.length];
        return anyOf16(packedPositions, fractions);
    }

    /** Match any of at most 128 packed floor positions paired with feet-height fractions 0..15. */
    public static Goal anyOf16(long[] packedPositions, byte[] fractions) {
        if (packedPositions == null || fractions == null) throw new NullPointerException("positions and fractions");
        if (packedPositions.length != fractions.length) {
            throw new IllegalArgumentException("positions and fractions must have equal lengths");
        }
        if (packedPositions.length == 0 || packedPositions.length > MAX_ANY_POSITIONS) {
            throw new IllegalArgumentException("anyOf16 requires between one and 128 positions");
        }

        long[] sortedPositions = Arrays.copyOf(packedPositions, packedPositions.length);
        byte[] sortedFractions = Arrays.copyOf(fractions, fractions.length);
        for (int i = 0; i < sortedPositions.length; i++) {
            long packed = sortedPositions[i];
            if (Position.pack(Position.x(packed), Position.y(packed), Position.z(packed)) != packed) {
                throw new IllegalArgumentException("position is not canonically packed");
            }
            if (sortedFractions[i] < 0 || sortedFractions[i] > 15) {
                throw new IllegalArgumentException("fractions must be between zero and fifteen");
            }
        }
        for (int i = 1; i < sortedPositions.length; i++) {
            long position = sortedPositions[i];
            byte fraction = sortedFractions[i];
            int j = i;
            while (j > 0 && comparePair(sortedPositions[j - 1], sortedFractions[j - 1], position, fraction) > 0) {
                sortedPositions[j] = sortedPositions[j - 1];
                sortedFractions[j] = sortedFractions[j - 1];
                j--;
            }
            sortedPositions[j] = position;
            sortedFractions[j] = fraction;
        }
        int uniqueCount = 0;
        for (int i = 0; i < sortedPositions.length; i++) {
            if (uniqueCount == 0 || comparePair(sortedPositions[uniqueCount - 1], sortedFractions[uniqueCount - 1],
                    sortedPositions[i], sortedFractions[i]) != 0) {
                sortedPositions[uniqueCount] = sortedPositions[i];
                sortedFractions[uniqueCount] = sortedFractions[i];
                uniqueCount++;
            }
        }
        sortedPositions = Arrays.copyOf(sortedPositions, uniqueCount);
        sortedFractions = Arrays.copyOf(sortedFractions, uniqueCount);

        long first = packedPositions[0];
        int firstY16 = Position.y(first) * 16 + fractions[0];
        return new Goal(Kind.ANY, Position.x(first), firstY16, Position.z(first), 0L,
                sortedPositions, sortedFractions);
    }

    public boolean matches(int px, int py, int pz) {
        if (kind == Kind.ANY) {
            long packed = Position.pack(px, py, pz);
            return contains(packed, 0);
        }
        if (px < -33_554_432 || px > 33_554_431 || pz < -33_554_432 || pz > 33_554_431
                || py < -2_048 || py > 2_047) return false;
        return matches16(px, py * 16, pz);
    }

    /** Test a floor X/Z and an exact absolute feet height in sixteenths. */
    public boolean matches16(int px, int pyFeet16, int pz) {
        int py = Math.floorDiv(pyFeet16, 16);
        Position.pack(px, py, pz);
        int fraction = Math.floorMod(pyFeet16, 16);
        if (kind == Kind.ANY) return contains(Position.pack(px, py, pz), fraction);
        long dx = (long) px - x;
        long dy16 = (long) pyFeet16 - feetY16;
        long dz = (long) pz - z;
        if (kind == Kind.EXACT) return dx == 0 && dy16 == 0 && dz == 0;
        long horizontalX16 = dx * 16L;
        long horizontalZ16 = dz * 16L;
        // A radius this large contains every pair of packed world stances. Avoid squaring it.
        if (radius16 >= 2_000_000_000L) return true;
        long remainingSquared = square(radius16);
        long horizontalXSquared = square(horizontalX16);
        if (horizontalXSquared > remainingSquared) return false;
        remainingSquared -= horizontalXSquared;
        if (Math.abs(dy16) > radius16) return false;
        long verticalSquared = square(dy16);
        if (verticalSquared > remainingSquared) return false;
        remainingSquared -= verticalSquared;
        long horizontalZSquared = square(horizontalZ16);
        return horizontalZSquared <= remainingSquared;
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

    /** Conservative horizontal-only lower bound for graphs that may contain fractional stairs. */
    public long heuristic16(int px, int pyFeet16, int pz) {
        if (kind == Kind.ANY) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < anyX.length; i++) {
                best = Math.min(best, horizontalHeuristic(px, pz, anyX[i], anyZ[i], 0));
            }
            return best;
        }
        long arrivalBlocks = (radius16 + 15L) / 16L;
        return horizontalHeuristic(px, pz, x, z, arrivalBlocks);
    }

    private boolean contains(long packed, int fraction) {
        int low = 0;
        int high = packedPositions.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int compare = comparePair(packedPositions[middle], fractions[middle], packed, (byte) fraction);
            if (compare == 0) return true;
            if (compare < 0) low = middle + 1;
            else high = middle - 1;
        }
        return false;
    }

    private static int comparePair(long firstPosition, byte firstFraction,
                                   long secondPosition, byte secondFraction) {
        int position = Long.compare(firstPosition, secondPosition);
        return position != 0 ? position : Byte.compare(firstFraction, secondFraction);
    }

    private static long horizontalHeuristic(int px, int pz, int gx, int gz, long radius) {
        long dx = Math.abs((long) px - gx);
        long dz = Math.abs((long) pz - gz);
        long diagonal = Math.min(dx, dz);
        long horizontal = 14L * diagonal + 10L * (Math.max(dx, dz) - diagonal);
        return Math.max(0L, horizontal - 14L * radius);
    }

    private static long exactHeuristic(int px, int py, int pz, int gx, int gy, int gz, int radius) {
        long horizontal = horizontalHeuristic(px, pz, gx, gz, radius);
        long rise = Math.max(0L, (long) gy - py - radius);
        long fall = Math.max(0L, (long) py - gy - radius);
        long vertical = rise > 0 ? 17L * rise : 22L * fall / 3L;
        return Math.max(horizontal, vertical);
    }

    private static long square(long value) { return value * value; }
}
