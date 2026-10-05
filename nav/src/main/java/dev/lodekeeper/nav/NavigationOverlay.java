package dev.lodekeeper.nav;

/** Bounded renderer-neutral world geometry. Coordinates are absolute; adapters own projection/depth. */
public final class NavigationOverlay {
    public static final int WALK = 0xff55dce8, JUMP = 0xffffc45e, BREAK = 0xffff786e,
            PLACE = 0xff9ee875, TARGET = 0xffffdf79, OPEN = 0xff637fa6, CLOSED = 0xffa8c2df;
    public static final int MAX_ROUTE_SEGMENTS = 64;
    private NavigationOverlay() {}
    @FunctionalInterface public interface Lines {
        void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb);
    }
    public static void draw(NavigationSnapshot view, Lines lines, double cameraX, double cameraY,
                            double cameraZ, boolean searchNodes, boolean hasTarget,
                            int targetX, int targetY, int targetZ) {
        Path path = view.path();
        if (path != null) {
            int first = Math.max(1, view.nextStep());
            int last = Math.min(path.length(), first + MAX_ROUTE_SEGMENTS);
            for (int i = first; i < last; i++) {
                Path.Step a = path.step(i - 1), b = path.step(i);
                if (!near(a.x + .5, a.feetY(), a.z + .5, cameraX, cameraY, cameraZ)
                        || !near(b.x + .5, b.feetY(), b.z + .5, cameraX, cameraY, cameraZ)) continue;
                int color = switch (b.movement) {
                    case JUMP, DROP, PARKOUR -> JUMP;
                    case BRIDGE -> PLACE;
                    default -> WALK;
                };
                lines.line(a.x + .5, a.feetY() + .08, a.z + .5,
                        b.x + .5, b.feetY() + .08, b.z + .5, color);
                if (i == first && b.actionCount() > 0) {
                    Action action = b.action(0);
                    box(lines, action.x, action.y, action.z,
                            action.type == Action.Type.BREAK_BLOCK ? BREAK : PLACE);
                }
            }
        }
        if (searchNodes) for (int i = 0; i < view.nodeCount(); i++) {
            double x = view.nodeX(i) + .5, y = view.nodeY(i) + .1, z = view.nodeZ(i) + .5;
            if (!near(x, y, z, cameraX, cameraY, cameraZ)) continue;
            int color = view.nodeIsClosed(i) ? CLOSED : OPEN;
            lines.line(x - .1, y, z, x + .1, y, z, color);
            lines.line(x, y, z - .1, x, y, z + .1, color);
        }
        if (hasTarget && near(targetX + .5, targetY + .5, targetZ + .5, cameraX, cameraY, cameraZ))
            box(lines, targetX, targetY, targetZ, TARGET);
    }
    private static boolean near(double x, double y, double z, double cx, double cy, double cz) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return dx * dx + dy * dy + dz * dz <= 64 * 64;
    }
    private static void box(Lines lines, int x, int y, int z, int color) {
        // A small outward offset avoids fighting the block's faces; depth testing remains enabled.
        double loX = x - .003, hiX = x + 1.003, loY = y - .003, hiY = y + 1.003,
                loZ = z - .003, hiZ = z + 1.003;
        for (int i = 0; i < 4; i++) {
            double xx = (i & 1) == 0 ? loX : hiX, zz = (i & 2) == 0 ? loZ : hiZ;
            lines.line(xx, loY, zz, xx, hiY, zz, color);
        }
        for (int height = 0; height < 2; height++) {
            double yy = height == 0 ? loY : hiY;
            lines.line(loX, yy, loZ, hiX, yy, loZ, color);
            lines.line(loX, yy, hiZ, hiX, yy, hiZ, color);
            lines.line(loX, yy, loZ, loX, yy, hiZ, color);
            lines.line(hiX, yy, loZ, hiX, yy, hiZ, color);
        }
    }
}
