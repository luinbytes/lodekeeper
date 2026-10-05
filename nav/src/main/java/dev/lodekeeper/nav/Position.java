package dev.lodekeeper.nav;

/** Vanilla-compatible packed block coordinates (26-bit X, 12-bit Y, 26-bit Z). */
public final class Position {
    private Position() {}

    public static long pack(int x, int y, int z) {
        if (x < -33_554_432 || x > 33_554_431 || z < -33_554_432 || z > 33_554_431
                || y < -2_048 || y > 2_047) {
            throw new IllegalArgumentException("position exceeds packed coordinate range");
        }
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | ((long) y & 0xfffL);
    }

    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    public static int y(long packed) {
        int y = (int) (packed & 0xfffL);
        return y >= 0x800 ? y - 0x1000 : y;
    }

    public static int z(long packed) {
        int z = (int) ((packed << 26) >> 38);
        return z;
    }
}
