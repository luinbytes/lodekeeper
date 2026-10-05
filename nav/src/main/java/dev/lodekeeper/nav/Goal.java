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
        long dy = Math.abs((long) py - y);
        long dz = Math.abs((long) pz - z);
        long distance = Math.max(dx, Math.max(dy, dz));
        if (kind == Kind.NEAR) distance = Math.max(0L, distance - radius);
        // One parkour edge can advance at most three blocks and costs at least ten.
        // This lower bound remains admissible for diagonal and vertical movement too.
        return ((distance + 2L) / 3L) * 10L;
    }
}
