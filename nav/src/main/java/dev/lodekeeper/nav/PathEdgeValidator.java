package dev.lodekeeper.nav;

/** Live, allocation-free validation for one edge immediately before its executor sends input. */
public final class PathEdgeValidator {
    private static final double JUMP_ARC = 0.85;
    private static final double PARKOUR_ARC = 1.35;
    private static final double MAX_ARC_CHORD_ERROR = 0.0005;
    private static final double STRAIGHT_ARC = 1.0e-12;
    private static final double MAX_LATERAL_DRIFT = 0.75;

    private PathEdgeValidator() {}

    /**
     * Re-probes the destination and its complete swept body volume. Set
     * {@code validateSourceStance} for a newly entered edge; continuation checks after a terrain
     * revision use the player's current feet as the sweep origin and must not require support while
     * the player is already airborne. {@code requireSourceOrigin} rejects a correction that moves
     * the player away from the planned source on edge entry; for a grounded continuation, a false
     * value lets the check probe the player's actual current stance instead.
     */
    public static boolean isSafeEdge(Terrain terrain, Path.Step source, Path.Step destination,
                                     double fromX, double fromFeetY, double fromZ,
                                     boolean validateSourceStance, boolean requireSourceOrigin,
                                     boolean allowParkour,
                                     StanceProbe sourceProbe, StanceProbe destinationProbe) {
        if (terrain == null || source == null || destination == null
                || sourceProbe == null || destinationProbe == null) return false;
        if (destination.movement == Path.Movement.PARKOUR && !allowParkour) return false;

        sourceProbe.breakCount = 0;
        if (validateSourceStance) {
            if (nearSource(source, fromX, fromFeetY, fromZ)) {
                terrain.probeStance(source.x, source.y, source.z, sourceProbe);
            } else {
                if (requireSourceOrigin) return false;
                terrain.probeStance((int) Math.floor(fromX), (int) Math.floor(fromFeetY),
                        (int) Math.floor(fromZ), sourceProbe);
            }
            if (!safeStance(sourceProbe, source.movement, false)) return false;
        }

        terrain.probeStance(destination.x, destination.y, destination.z, destinationProbe);
        if (!safeStance(destinationProbe, destination.movement, true)) return false;

        double arc = switch (destination.movement) {
            case JUMP -> JUMP_ARC;
            case PARKOUR -> PARKOUR_ARC;
            default -> 0.0;
        };
        return terrain.isMotionClear(fromX, fromFeetY, fromZ,
                destination.x + 0.5, destination.y, destination.z + 0.5,
                arc, sourceProbe, destinationProbe);
    }

    /**
     * Revalidates only the remaining portion of an active jump/parkour curve. The original edge
     * origin prevents restarting its full arc at the player's elevated midair feet. A point sweep
     * checks the player's actual box; short, vertically bounded chords then cover the remaining
     * original curve without querying terrain behind the player's projected progress.
     */
    public static boolean isSafeContinuation(Terrain terrain, Path.Step source, Path.Step destination,
                                             double edgeStartX, double edgeStartFeetY, double edgeStartZ,
                                             double currentFeetX, double currentFeetY, double currentFeetZ,
                                             boolean validateCurrentStance, boolean allowParkour,
                                             StanceProbe sourceProbe, StanceProbe destinationProbe) {
        if (terrain == null || source == null || destination == null
                || sourceProbe == null || destinationProbe == null) return false;
        if (destination.movement == Path.Movement.PARKOUR && !allowParkour) return false;

        sourceProbe.breakCount = 0;
        if (validateCurrentStance) {
            if (nearSource(source, currentFeetX, currentFeetY, currentFeetZ)) {
                terrain.probeStance(source.x, source.y, source.z, sourceProbe);
            } else {
                terrain.probeStance((int) Math.floor(currentFeetX), (int) Math.floor(currentFeetY),
                        (int) Math.floor(currentFeetZ), sourceProbe);
            }
            if (!safeStance(sourceProbe, source.movement, false)) return false;
        }

        terrain.probeStance(destination.x, destination.y, destination.z, destinationProbe);
        if (!safeStance(destinationProbe, destination.movement, true)) return false;
        if (!terrain.isMotionClear(currentFeetX, currentFeetY, currentFeetZ,
                currentFeetX, currentFeetY, currentFeetZ, 0.0, destinationProbe)) return false;

        double arc = arcFor(destination.movement);
        if (arc <= 0.0) {
            return terrain.isMotionClear(currentFeetX, currentFeetY, currentFeetZ,
                    destination.x + 0.5, destination.y, destination.z + 0.5,
                    0.0, sourceProbe, destinationProbe);
        }

        double endX = destination.x + 0.5;
        double endY = destination.y;
        double endZ = destination.z + 0.5;
        double progress = projectedProgress(edgeStartX, edgeStartFeetY, edgeStartZ,
                endX, endY, endZ, currentFeetX, currentFeetY, currentFeetZ);
        if (progress >= 1.0) {
            return terrain.isMotionClear(currentFeetX, currentFeetY, currentFeetZ,
                    endX, endY, endZ, 0.0, sourceProbe, destinationProbe);
        }

        double originalX = interpolate(edgeStartX, endX, progress);
        double originalY = trajectoryY(edgeStartFeetY, endY, arc, progress);
        double originalZ = interpolate(edgeStartZ, endZ, progress);
        double correctionX = currentFeetX - originalX;
        double correctionY = currentFeetY - originalY;
        double correctionZ = currentFeetZ - originalZ;
        int segments = arcSegments(arc, 1.0 - progress);
        double previousT = progress;
        for (int index = 1; index <= segments; index++) {
            double t = progress + (1.0 - progress) * index / segments;
            double correctionScale0 = (1.0 - previousT) / (1.0 - progress);
            double correctionScale1 = (1.0 - t) / (1.0 - progress);
            double x0 = interpolate(edgeStartX, endX, previousT) + correctionX * correctionScale0;
            double y0 = trajectoryY(edgeStartFeetY, endY, arc, previousT) + correctionY * correctionScale0;
            double z0 = interpolate(edgeStartZ, endZ, previousT) + correctionZ * correctionScale0;
            double x1 = interpolate(edgeStartX, endX, t) + correctionX * correctionScale1;
            double y1 = trajectoryY(edgeStartFeetY, endY, arc, t) + correctionY * correctionScale1;
            double z1 = interpolate(edgeStartZ, endZ, t) + correctionZ * correctionScale1;
            double dt = t - previousT;
            double margin = arc * Math.PI * Math.PI * dt * dt / 8.0 + 1.0e-7;
            if (!sweptChord(terrain, x0, y0, z0, x1, y1, z1, margin,
                    sourceProbe, destinationProbe)) return false;
            previousT = t;
        }
        return true;
    }

    /** Cheap per-tick guard against corrections or knockback outside the active path envelope. */
    public static boolean isWithinEdgeCorridor(Path.Step source, Path.Step destination,
                                               double feetX, double feetY, double feetZ) {
        if (source == null || destination == null || !Double.isFinite(feetX)
                || !Double.isFinite(feetY) || !Double.isFinite(feetZ)
                || destination.movement == Path.Movement.START) return false;
        double startX = source.x + 0.5;
        double startY = source.y;
        double startZ = source.z + 0.5;
        double endX = destination.x + 0.5;
        double endY = destination.y;
        double endZ = destination.z + 0.5;
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        double horizontalSquared = dx * dx + dz * dz;
        double progress;
        if (horizontalSquared > 1.0e-8) {
            progress = ((feetX - startX) * dx + (feetZ - startZ) * dz) / horizontalSquared;
        } else if (Math.abs(dy) > 1.0e-8) {
            progress = (feetY - startY) / dy;
        } else {
            return false;
        }
        if (progress < -0.2 || progress > 1.2) return false;
        double nearestX = startX + dx * progress;
        double nearestZ = startZ + dz * progress;
        double lateralX = feetX - nearestX;
        double lateralZ = feetZ - nearestZ;
        if (lateralX * lateralX + lateralZ * lateralZ > MAX_LATERAL_DRIFT * MAX_LATERAL_DRIFT) return false;

        double minY = Math.min(startY, endY);
        double maxY = Math.max(startY, endY);
        double lowMargin = 0.75;
        double highMargin = 0.65;
        switch (destination.movement) {
            case JUMP -> highMargin += JUMP_ARC;
            case PARKOUR -> highMargin += PARKOUR_ARC;
            case DROP -> lowMargin += 0.35;
            case START -> { return false; }
            default -> { }
        }
        return feetY >= minY - lowMargin && feetY <= maxY + highMargin;
    }

    /**
     * Checks only the player's current body volume and, while grounded, its live support stance.
     * This bounded point probe runs every motion tick so a small correction cannot enter an
     * adjacent hazard between terrain revisions; the full edge sweep remains cached.
     */
    public static boolean isCurrentMotionSafe(Terrain terrain, Path.Movement movement,
                                              double feetX, double feetY, double feetZ,
                                              boolean grounded, StanceProbe stanceProbe,
                                              StanceProbe emptyProbe) {
        if (terrain == null || movement == null || stanceProbe == null || emptyProbe == null
                || !Double.isFinite(feetX) || !Double.isFinite(feetY) || !Double.isFinite(feetZ)) return false;
        if (!terrain.isMotionClear(feetX, feetY, feetZ, feetX, feetY, feetZ,
                0.0, emptyProbe)) return false;
        if (!grounded) return true;

        terrain.probeStance((int) Math.floor(feetX), (int) Math.floor(feetY),
                (int) Math.floor(feetZ), stanceProbe);
        return safeStance(stanceProbe, movement, true);
    }

    /** Checks the live source stance and actual player-sized volume before a break/place action. */
    public static boolean isCurrentStanceSafe(Terrain terrain, Path.Step stance,
                                              double feetX, double feetY, double feetZ,
                                              StanceProbe stanceProbe, StanceProbe emptyProbe) {
        if (terrain == null || stance == null || stanceProbe == null || emptyProbe == null
                || !nearSource(stance, feetX, feetY, feetZ)) return false;
        terrain.probeStance(stance.x, stance.y, stance.z, stanceProbe);
        if (!safeStance(stanceProbe, stance.movement, false)) return false;
        return terrain.isMotionClear(feetX, feetY, feetZ, feetX, feetY, feetZ,
                0.0, emptyProbe);
    }

    /** Rechecks that an outstanding break action is still a safe, reachable obstruction. */
    public static boolean isBreakActionSafe(Terrain terrain, Path.Step source,
                                            Path.Step destination, Action action,
                                            StanceProbe destinationProbe) {
        if (terrain == null || source == null || destination == null || action == null
                || action.type != Action.Type.BREAK_BLOCK || destinationProbe == null) return false;
        terrain.probeStance(destination.x, destination.y, destination.z, destinationProbe);
        if (!destinationProbe.loaded || destinationProbe.hazard || destinationProbe.bodyClear
                || destinationProbe.breakCount < 1
                || destinationProbe.breakCount > StanceProbe.MAX_BREAK_TARGETS) return false;
        for (int i = 0; i < destinationProbe.breakCount; i++) {
            BreakTarget target = destinationProbe.breakTargets[i];
            if (target.x == action.x && target.y == action.y && target.z == action.z
                    && target.stateToken == action.token
                    && target.cost > 0
                    && terrain.canBreakFrom(source.x, source.y, source.z, destinationProbe, i)) return true;
        }
        return false;
    }

    /** Rechecks a planned bridge's source support, empty landing volume, and placement face. */
    public static boolean isBridgeActionSafe(Terrain terrain, Path.Step source,
                                             Path.Step destination, Action action,
                                             StanceProbe sourceProbe,
                                             StanceProbe destinationProbe) {
        if (terrain == null || source == null || destination == null || action == null
                || sourceProbe == null || destinationProbe == null
                || action.type != Action.Type.PLACE_BLOCK
                || destination.movement != Path.Movement.BRIDGE
                || action.x != destination.x || action.y != destination.y - 1
                || action.z != destination.z) return false;
        terrain.probeStance(source.x, source.y, source.z, sourceProbe);
        if (!safeStance(sourceProbe, source.movement, false)) return false;
        terrain.probeStance(destination.x, destination.y, destination.z, destinationProbe);
        if (!destinationProbe.loaded || destinationProbe.hazard || !destinationProbe.bodyClear
                || destinationProbe.breakCount != 0 || destinationProbe.fullSupport
                || destinationProbe.water || destinationProbe.climbable) return false;
        return terrain.canPlaceBridgeFrom(source.x, source.y, source.z,
                action.x, action.y, action.z, action.token, false);
    }

    /** Checks a short repositioning sweep that has no planned stance at its fractional endpoint. */
    public static boolean isSweepClear(Terrain terrain,
                                       double fromX, double fromFeetY, double fromZ,
                                       double toX, double toFeetY, double toZ,
                                       StanceProbe emptyProbe) {
        return terrain != null && emptyProbe != null
                && terrain.isMotionClear(fromX, fromFeetY, fromZ,
                toX, toFeetY, toZ, STRAIGHT_ARC, emptyProbe);
    }

    private static boolean nearSource(Path.Step source, double x, double y, double z) {
        double dx = x - (source.x + 0.5);
        double dz = z - (source.z + 0.5);
        return dx * dx + dz * dz <= 0.75 * 0.75 && Math.abs(y - source.y) < 1.0;
    }

    private static boolean safeStance(StanceProbe probe, Path.Movement movement,
                                      boolean destination) {
        if (!probe.loaded || probe.hazard || !probe.bodyClear || probe.breakCount != 0) return false;
        if (movement == Path.Movement.START) {
            return !destination && (probe.fullSupport || probe.water || probe.climbable);
        }
        return switch (movement) {
            case WALK, JUMP, DROP, PARKOUR, BRIDGE -> probe.fullSupport;
            case SWIM -> probe.fullSupport || probe.water;
            case CLIMB -> probe.fullSupport || probe.climbable;
            case START -> false;
        };
    }

    private static double arcFor(Path.Movement movement) {
        return switch (movement) {
            case JUMP -> JUMP_ARC;
            case PARKOUR -> PARKOUR_ARC;
            default -> 0.0;
        };
    }

    private static int arcSegments(double arc, double remaining) {
        double required = remaining * Math.sqrt(arc * Math.PI * Math.PI / (8.0 * MAX_ARC_CHORD_ERROR));
        return Math.max(1, Math.min(64, (int) Math.ceil(required)));
    }

    private static double projectedProgress(double fromX, double fromY, double fromZ,
                                            double toX, double toY, double toZ,
                                            double x, double y, double z) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double horizontalSquared = dx * dx + dz * dz;
        if (horizontalSquared > 1.0e-8) {
            return clamp(((x - fromX) * dx + (z - fromZ) * dz) / horizontalSquared);
        }
        double dy = toY - fromY;
        return Math.abs(dy) <= 1.0e-8 ? 0.0 : clamp((y - fromY) / dy);
    }

    private static double trajectoryY(double fromY, double toY, double arc, double t) {
        return fromY + (toY - fromY) * t + Math.sin(Math.PI * t) * arc;
    }

    private static double interpolate(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean sweptChord(Terrain terrain,
                                      double fromX, double fromY, double fromZ,
                                      double toX, double toY, double toZ, double verticalMargin,
                                      StanceProbe sourceProbe, StanceProbe destinationProbe) {
        return terrain.isMotionClear(fromX, fromY + verticalMargin, fromZ,
                toX, toY + verticalMargin, toZ, STRAIGHT_ARC, sourceProbe, destinationProbe)
                && terrain.isMotionClear(fromX, fromY - verticalMargin, fromZ,
                toX, toY - verticalMargin, toZ, STRAIGHT_ARC, sourceProbe, destinationProbe);
    }
}
