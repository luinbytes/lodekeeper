package dev.lodekeeper.nav;

/** Bounded renderer-neutral world geometry. Coordinates are absolute; adapters own projection/depth. */
public final class NavigationOverlay {
    public static final int WALK = 0xff55dce8, JUMP = 0xffffc45e, BREAK = 0xffff786e,
            PLACE = 0xff9ee875, TARGET = 0xffffdf79, OPEN = 0xff637fa6, CLOSED = 0xffa8c2df,
            PARKOUR_PREVIEW = 0xffd59bff, ACTIVE_ACTION = 0xffff4f9a, CONFIRMED_ACTION = 0xff5ee58a,
            PLANNED_NATIVE_BREAK = 0xffe99b78, PLANNED_NATIVE_PLACE = 0xffb6df79,
            CLAIM = 0xff8ba7d9, PREFERRED_STATION = 0xff68d5a1,
            OWNED_STATION = 0xfff3c969, STATION_RECOVERY = 0xffff9c68,
            BACKFILL_PENDING = 0xffefaa75, BACKFILL_MATCHED = 0xff6addab;
    public static final int MAX_ROUTE_SEGMENTS = 64;
    public static final int MAX_PARKOUR_ARCS = 16;
    public static final int PARKOUR_ARC_SEGMENTS = 8;

    private NavigationOverlay() {}

    @FunctionalInterface public interface Lines {
        void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb);
    }

    public record Options(boolean showPath, boolean showSearch, boolean showNextBreak,
                          boolean showNextPlace, boolean showParkour, boolean showClaims,
                          boolean showStations, boolean showBackfill, int visualizationDistance) {
        public Options {
            visualizationDistance = Math.max(8, Math.min(128, visualizationDistance));
        }
    }

    /** Retains the original renderer-facing entry point for existing version adapters. */
    public static void draw(NavigationSnapshot view, Lines lines, double cameraX, double cameraY,
                            double cameraZ, boolean searchNodes, boolean hasTarget,
                            int targetX, int targetY, int targetZ) {
        draw(view, view.scene(), lines, cameraX, cameraY, cameraZ,
                new Options(true, searchNodes, false, false, false, false, false, false, 64),
                hasTarget, targetX, targetY, targetZ);
    }

    public static void draw(NavigationSnapshot view, NavigationSceneSnapshot scene, Lines lines,
                            double cameraX, double cameraY, double cameraZ, Options options,
                            boolean hasTarget, int targetX, int targetY, int targetZ) {
        if (view == null || lines == null || options == null) return;
        if (scene == null) scene = NavigationSceneSnapshot.EMPTY;
        double radius = options.visualizationDistance();
        double radiusSquared = radius * radius;

        if (options.showPath() || options.showParkour()) {
            Path path = view.path();
            if (path != null) {
                int first = Math.max(1, Math.min(view.nextStep(), path.length()));
                int last = Math.min(path.length(), first + MAX_ROUTE_SEGMENTS);
                int arcs = 0;
                for (int i = first; i < last; i++) {
                    Path.Step a = path.step(i - 1), b = path.step(i);
                    boolean parkour = scene.isParkourMovement(i - 1);
                    if (parkour && options.showParkour() && arcs < MAX_PARKOUR_ARCS) {
                        drawParkourPreview(lines, a, b, cameraX, cameraY, cameraZ, radiusSquared);
                        arcs++;
                    } else if (options.showPath()) {
                        int color = switch (b.movement) {
                            case JUMP, DROP, PARKOUR -> JUMP;
                            case BRIDGE -> PLACE;
                            default -> WALK;
                        };
                        clippedLine(lines, a.x + .5, a.feetY() + .08, a.z + .5,
                                b.x + .5, b.feetY() + .08, b.z + .5,
                                cameraX, cameraY, cameraZ, radiusSquared,
                                parkour ? PARKOUR_PREVIEW : color);
                    }
                }
            }
        }

        Path actionPath = view.path();
        int nextActionStep = actionPath == null ? -1 : Math.max(1, view.nextStep());
        if (nextActionStep >= 0 && nextActionStep < actionPath.length()
                && (options.showNextBreak() || options.showNextPlace())) {
            drawPathActions(actionPath.step(nextActionStep), lines, options, cameraX, cameraY, cameraZ, radiusSquared);
        }

        drawNativeActions(scene, lines, options, cameraX, cameraY, cameraZ, radiusSquared);
        if (options.showSearch()) drawSearchNodes(view, lines, cameraX, cameraY, cameraZ, radiusSquared);
        if (options.showPath() && hasTarget) {
            blockBox(lines, targetX, targetY, targetZ, TARGET,
                    cameraX, cameraY, cameraZ, radiusSquared);
        }
        drawMarkers(scene, lines, options, cameraX, cameraY, cameraZ, radiusSquared);
    }

    private static void drawPathActions(Path.Step step, Lines lines, Options options,
                                        double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        int count = Math.min(step.actionCount(), NavigationSceneSnapshot.MAX_ACTIONS);
        for (int i = 0; i < count; i++) {
            Action action = step.action(i);
            boolean enabled = action.type == Action.Type.BREAK_BLOCK
                    ? options.showNextBreak() : options.showNextPlace();
            if (enabled) blockBox(lines, action.x, action.y, action.z,
                    action.type == Action.Type.BREAK_BLOCK ? BREAK : PLACE,
                    cameraX, cameraY, cameraZ, radiusSquared);
        }
    }

    private static void drawNativeActions(NavigationSceneSnapshot scene, Lines lines, Options options,
                                          double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        for (int i = 0; i < scene.actionCount(); i++) {
            NavigationSceneSnapshot.WorldAction action = scene.action(i);
            boolean enabled = action.kind() == NavigationSceneSnapshot.ActionKind.BREAK
                    ? options.showNextBreak() : options.showNextPlace();
            if (!enabled) continue;
            int color = actionColor(action.kind(), action.evidence());
            blockBox(lines, Position.x(action.position()), Position.y(action.position()),
                    Position.z(action.position()), color, cameraX, cameraY, cameraZ, radiusSquared);
        }
    }

    private static int actionColor(NavigationSceneSnapshot.ActionKind kind,
                                   NavigationSceneSnapshot.ActionEvidence evidence) {
        return switch (evidence) {
            case NATIVE_ATTEMPT -> ACTIVE_ACTION;
            case SERVER_CONFIRMED -> CONFIRMED_ACTION;
            case PLANNED_PATH -> kind == NavigationSceneSnapshot.ActionKind.BREAK ? BREAK : PLACE;
            case PLANNED_NATIVE -> kind == NavigationSceneSnapshot.ActionKind.BREAK
                    ? PLANNED_NATIVE_BREAK : PLANNED_NATIVE_PLACE;
        };
    }

    private static void drawSearchNodes(NavigationSnapshot view, Lines lines,
                                        double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        for (int i = 0; i < view.nodeCount(); i++) {
            double x = view.nodeX(i) + .5, y = view.nodeY(i) + .1, z = view.nodeZ(i) + .5;
            if (distanceSquared(x, y, z, cameraX, cameraY, cameraZ) > radiusSquared) continue;
            int color = view.nodeIsClosed(i) ? CLOSED : OPEN;
            clippedLine(lines, x - .1, y, z, x + .1, y, z,
                    cameraX, cameraY, cameraZ, radiusSquared, color);
            clippedLine(lines, x, y, z - .1, x, y, z + .1,
                    cameraX, cameraY, cameraZ, radiusSquared, color);
        }
    }

    private static void drawMarkers(NavigationSceneSnapshot scene, Lines lines, Options options,
                                    double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        for (int i = 0; i < scene.markerCount(); i++) {
            NavigationSceneSnapshot.Marker marker = scene.marker(i);
            if (marker.distanceSquared(cameraX, cameraY, cameraZ) > radiusSquared) continue;
            switch (marker.kind()) {
                case CLAIM_BOUNDARY -> {
                    boolean preferred = marker.preferredStations();
                    if (options.showClaims() || options.showStations() && preferred) {
                        int color = options.showStations() && preferred ? PREFERRED_STATION : CLAIM;
                        regionBox(lines, marker, color, cameraX, cameraY, cameraZ, radiusSquared);
                    }
                }
                case CONFIRMED_OWNED_STATION -> {
                    if (options.showStations()) pointMarker(lines, marker, OWNED_STATION,
                            cameraX, cameraY, cameraZ, radiusSquared);
                }
                case STATION_RECOVERY_PENDING -> {
                    if (options.showStations()) pointMarker(lines, marker, STATION_RECOVERY,
                            cameraX, cameraY, cameraZ, radiusSquared);
                }
                case BACKFILL_PENDING_MATCH -> {
                    if (options.showBackfill()) pointMarker(lines, marker, BACKFILL_PENDING,
                            cameraX, cameraY, cameraZ, radiusSquared);
                }
                case BACKFILL_MATCHED -> {
                    if (options.showBackfill()) pointMarker(lines, marker, BACKFILL_MATCHED,
                            cameraX, cameraY, cameraZ, radiusSquared);
                }
            }
        }
    }

    private static void pointMarker(Lines lines, NavigationSceneSnapshot.Marker marker, int color,
                                    double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        blockBox(lines, marker.minX(), marker.minY(), marker.minZ(), color,
                cameraX, cameraY, cameraZ, radiusSquared);
    }

    private static void regionBox(Lines lines, NavigationSceneSnapshot.Marker marker, int color,
                                  double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        double loX = marker.minX() - .003, hiX = marker.maxX() + 1.003;
        double loY = marker.minY() - .003, hiY = marker.maxY() + 1.003;
        double loZ = marker.minZ() - .003, hiZ = marker.maxZ() + 1.003;
        boxEdges(lines, loX, loY, loZ, hiX, hiY, hiZ, color,
                cameraX, cameraY, cameraZ, radiusSquared);
    }

    private static void blockBox(Lines lines, int x, int y, int z, int color,
                                 double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        boxEdges(lines, x - .003, y - .003, z - .003,
                x + 1.003, y + 1.003, z + 1.003, color,
                cameraX, cameraY, cameraZ, radiusSquared);
    }

    private static void boxEdges(Lines lines, double loX, double loY, double loZ,
                                 double hiX, double hiY, double hiZ, int color,
                                 double cameraX, double cameraY, double cameraZ, double radiusSquared) {
        for (int i = 0; i < 4; i++) {
            double x = (i & 1) == 0 ? loX : hiX;
            double z = (i & 2) == 0 ? loZ : hiZ;
            clippedLine(lines, x, loY, z, x, hiY, z,
                    cameraX, cameraY, cameraZ, radiusSquared, color);
        }
        for (int height = 0; height < 2; height++) {
            double y = height == 0 ? loY : hiY;
            clippedLine(lines, loX, y, loZ, hiX, y, loZ,
                    cameraX, cameraY, cameraZ, radiusSquared, color);
            clippedLine(lines, loX, y, hiZ, hiX, y, hiZ,
                    cameraX, cameraY, cameraZ, radiusSquared, color);
            clippedLine(lines, loX, y, loZ, loX, y, hiZ,
                    cameraX, cameraY, cameraZ, radiusSquared, color);
            clippedLine(lines, hiX, y, loZ, hiX, y, hiZ,
                    cameraX, cameraY, cameraZ, radiusSquared, color);
        }
    }

    private static void drawParkourPreview(Lines lines, Path.Step start, Path.Step end,
                                           double cameraX, double cameraY, double cameraZ,
                                           double radiusSquared) {
        // The curve marks a native parkour edge; it is a visual preview, not movement physics.
        double x0 = start.x + .5, y0 = start.feetY() + .08, z0 = start.z + .5;
        double x1 = end.x + .5, y1 = end.feetY() + .08, z1 = end.z + .5;
        double dx = x1 - x0, dz = z1 - z0;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(horizontal)) return;
        double rise = Math.min(1.0, .25 + horizontal * .12);
        double previousX = x0, previousY = y0, previousZ = z0;
        for (int segment = 1; segment <= PARKOUR_ARC_SEGMENTS; segment++) {
            double t = segment / (double) PARKOUR_ARC_SEGMENTS;
            double x = x0 + dx * t;
            double z = z0 + dz * t;
            double y = y0 + (y1 - y0) * t + 4.0 * rise * t * (1.0 - t);
            clippedLine(lines, previousX, previousY, previousZ, x, y, z,
                    cameraX, cameraY, cameraZ, radiusSquared, PARKOUR_PREVIEW);
            previousX = x;
            previousY = y;
            previousZ = z;
        }
    }

    private static void clippedLine(Lines lines, double x1, double y1, double z1,
                                    double x2, double y2, double z2,
                                    double cameraX, double cameraY, double cameraZ,
                                    double radiusSquared, int color) {
        if (!Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(z1)
                || !Double.isFinite(x2) || !Double.isFinite(y2) || !Double.isFinite(z2)
                || !Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)
                || !Double.isFinite(radiusSquared)) return;
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double a = dx * dx + dy * dy + dz * dz;
        if (!(a > 0.0) || !Double.isFinite(a)) return;
        double px = x1 - cameraX, py = y1 - cameraY, pz = z1 - cameraZ;
        double qx = x2 - cameraX, qy = y2 - cameraY, qz = z2 - cameraZ;
        boolean startInside = px * px + py * py + pz * pz <= radiusSquared;
        boolean endInside = qx * qx + qy * qy + qz * qz <= radiusSquared;
        double low = 0.0, high = 1.0;
        if (!(startInside && endInside)) {
            double b = 2.0 * (px * dx + py * dy + pz * dz);
            double c = px * px + py * py + pz * pz - radiusSquared;
            double discriminant = b * b - 4.0 * a * c;
            if (!(discriminant >= 0.0) || !Double.isFinite(discriminant)) return;
            double root = Math.sqrt(discriminant);
            low = Math.max(0.0, (-b - root) / (2.0 * a));
            high = Math.min(1.0, (-b + root) / (2.0 * a));
            if (endInside) high = 1.0;
            if (startInside) low = 0.0;
            if (high <= low) return;
        }
        double clippedX1 = x1 + dx * low, clippedY1 = y1 + dy * low, clippedZ1 = z1 + dz * low;
        double clippedX2 = x1 + dx * high, clippedY2 = y1 + dy * high, clippedZ2 = z1 + dz * high;
        if (Double.isFinite(clippedX1) && Double.isFinite(clippedY1) && Double.isFinite(clippedZ1)
                && Double.isFinite(clippedX2) && Double.isFinite(clippedY2) && Double.isFinite(clippedZ2))
            lines.line(clippedX1, clippedY1, clippedZ1, clippedX2, clippedY2, clippedZ2, color);
    }

    private static double distanceSquared(double x, double y, double z,
                                          double cameraX, double cameraY, double cameraZ) {
        double dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

}
