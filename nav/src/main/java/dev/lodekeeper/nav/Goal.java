package dev.lodekeeper.nav;

/** An exact stance goal or a bounded three-dimensional arrival region. */
public final class Goal {
    public enum Kind { EXACT, NEAR }

    public final Kind kind;
    public final int x;
    public final int y;
    public final int z;
    public final int radius;

    private Goal(Kind kind, int x, int y, int z, int radius) {
        Position.pack(x, y, z);
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
    }

    public static Goal exact(int x, int y, int z) {
        return new Goal(Kind.EXACT, x, y, z, 0);
    }

    /** Radius is measured in Euclidean block distance between stance centers. */
    public static Goal near(int x, int y, int z, int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        return new Goal(Kind.NEAR, x, y, z, radius);
    }

    public boolean matches(int px, int py, int pz) {
        long dx = (long) px - x;
        long dy = (long) py - y;
        long dz = (long) pz - z;
        if (kind == Kind.EXACT) return dx == 0 && dy == 0 && dz == 0;
        long r2 = (long) radius * radius;
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    long heuristic(int px, int py, int pz) {
        long dx = Math.abs((long) px - x);
        long dz = Math.abs((long) pz - z);
        long diagonal = Math.min(dx, dz);
        // Every horizontal edge costs at least the octile metric (10 cardinal, 14 diagonal).
        // Subtracting 14 per arrival-radius block is conservative in every direction.
        long horizontal = Math.max(0L, 14L * diagonal + 10L * (Math.max(dx, dz) - diagonal) - 14L * radius);
        // Rising one block costs at least 17; a three-block fall costs at least 22.
        // Combining with max avoids charging twice for a ledge move that advances both axes.
        long rise = Math.max(0L, (long) y - py - radius);
        long fall = Math.max(0L, (long) py - y - radius);
        long vertical = rise > 0 ? 17L * rise : 22L * fall / 3L;
        return Math.max(horizontal, vertical);
    }
}
