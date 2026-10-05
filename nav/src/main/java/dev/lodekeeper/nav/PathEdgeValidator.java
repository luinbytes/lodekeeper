package dev.lodekeeper.nav;

/** Live, allocation-free validation for one edge immediately before its executor sends input. */
public final class PathEdgeValidator {
    private static final double JUMP_ARC = 0.85;
    /** Conservative vanilla jump apex; boosted jumps require their own movement model. */
    private static final double JUMP_MAX_RISE = 1.35;
    private static final double PARKOUR_ARC = 1.35;
    private static final double MAX_ARC_CHORD_ERROR = 0.0005;
    private static final double STRAIGHT_ARC = 1.0e-12;
    private static final double MAX_LATERAL_DRIFT = 0.75;
    private static final double FEET_Y16_TOLERANCE = 0.05;
    private static final int INVALID_FEET_Y16 = Integer.MIN_VALUE;

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
        if (destination.movement != Path.Movement.WALK
                && (!isIntegralFeetHeight(source) || !isIntegralFeetHeight(destination))) return false;

        sourceProbe.breakCount = 0;
        if (validateSourceStance) {
            if (requireSourceOrigin && !nearSource(source, fromX, fromFeetY, fromZ)) return false;
            int actualFeetY16 = quantizedFeetY16(fromFeetY);
            if (!probeCurrentStance(terrain, fromX, fromFeetY, fromZ,
                    source.movement, sourceProbe)) return false;
            if (!safeStance(sourceProbe, source.movement, false)) return false;
            boolean mediumSource = destination.movement == Path.Movement.SWIM && sourceProbe.water
                    || destination.movement == Path.Movement.CLIMB && sourceProbe.climbable;
            if (requireSourceOrigin && !mediumSource && actualFeetY16 != source.feetY16) return false;
            if (requiresFullLaunchSupport(destination.movement) && !sourceProbe.fullSupport) return false;
        }

        terrain.probeStance16(destination.x, destination.feetY16, destination.z, destinationProbe);
        if (!safeStance(destinationProbe, destination.movement, true)) return false;

        if (destination.movement == Path.Movement.WALK) {
            if (!validateSourceStance) {
                return terrain.isMotionClear(fromX, fromFeetY, fromZ,
                        destination.x + 0.5, destination.feetY(), destination.z + 0.5,
                        0.0, sourceProbe, destinationProbe);
            }
            int fromFeetY16 = quantizedFeetY16(fromFeetY);
            return fromFeetY16 != INVALID_FEET_Y16
                    && terrain.isGroundedWalkClear(fromX, fromFeetY16, fromZ,
                    destination.x + 0.5, destination.feetY16, destination.z + 0.5,
                    sourceProbe, destinationProbe);
        }

        double arc = switch (destination.movement) {
            case JUMP -> JUMP_ARC;
            case PARKOUR -> PARKOUR_ARC;
            default -> 0.0;
        };
        return terrain.isMotionClear(fromX, fromFeetY, fromZ,
                destination.x + 0.5, destination.feetY(), destination.z + 0.5,
                arc, sourceProbe, destinationProbe);
    }

    /**
     * Revalidates the remaining movement from the actual airborne position. A jump below landing
     * height proves only its vertical lift; executors must validate again before releasing forward
     * input, even when terrain revision is unchanged. Above that height overlapping constant-height
     * sweeps cover the whole landing-to-apex envelope. Parkour follows the remaining original curve
     * using bounded chords,
     * without restarting a full arc at the elevated feet or querying terrain behind the player.
     */
    public static boolean isSafeContinuation(Terrain terrain, Path.Step source, Path.Step destination,
                                             double edgeStartX, double edgeStartFeetY, double edgeStartZ,
                                             double currentFeetX, double currentFeetY, double currentFeetZ,
                                             boolean validateCurrentStance, boolean allowParkour,
                                             StanceProbe sourceProbe, StanceProbe destinationProbe) {
        if (terrain == null || source == null || destination == null
                || sourceProbe == null || destinationProbe == null) return false;
        if (destination.movement == Path.Movement.PARKOUR && !allowParkour) return false;
        if (destination.movement != Path.Movement.WALK
                && (!isIntegralFeetHeight(source) || !isIntegralFeetHeight(destination))) return false;

        sourceProbe.breakCount = 0;
        if (validateCurrentStance) {
            if (!probeCurrentStance(terrain, currentFeetX, currentFeetY, currentFeetZ,
                    source.movement, sourceProbe)) return false;
            if (!safeStance(sourceProbe, source.movement, false)) return false;
            boolean landedJump = destination.movement == Path.Movement.JUMP
                    && currentFeetY >= destination.feetY();
            if (requiresFullLaunchSupport(destination.movement) && !landedJump && !sourceProbe.fullSupport) return false;
        }

        terrain.probeStance16(destination.x, destination.feetY16, destination.z, destinationProbe);
        if (!safeStance(destinationProbe, destination.movement, true)) return false;
        if (!terrain.isMotionClear(currentFeetX, currentFeetY, currentFeetZ,
                currentFeetX, currentFeetY, currentFeetZ, 0.0, destinationProbe)) return false;

        if (destination.movement == Path.Movement.WALK) {
            if (!validateCurrentStance) {
                return terrain.isMotionClear(currentFeetX, currentFeetY, currentFeetZ,
                        destination.x + 0.5, destination.feetY(), destination.z + 0.5,
                        0.0, sourceProbe, destinationProbe);
            }
            int currentFeetY16 = quantizedFeetY16(currentFeetY);
            return currentFeetY16 != INVALID_FEET_Y16
                    && terrain.isGroundedWalkClear(currentFeetX, currentFeetY16, currentFeetZ,
                    destination.x + 0.5, destination.feetY16, destination.z + 0.5,
                    sourceProbe, destinationProbe);
        }

        if (destination.movement == Path.Movement.JUMP) {
            double apex = source.feetY() + JUMP_MAX_RISE;
            if (currentFeetY > apex || currentFeetY < source.feetY() - 0.05) return false;
            if (currentFeetY < destination.feetY()) {
                // The executor holds forward input until the feet clear the landing height.
                // Revalidate the remaining vertical lift, without restarting a forward arc.
                return terrain.isMotionClear(currentFeetX, currentFeetY, currentFeetZ,
                        currentFeetX, apex, currentFeetZ, STRAIGHT_ARC, sourceProbe, destinationProbe);
            }
            // Cover the entire landing-height-to-apex band at every remaining XZ position.
            // Native adapters reject bodies shorter than .5; .25-spaced body sweeps overlap,
            // so this envelope cannot omit a low obstacle or an ahead-only ceiling near apex.
            // It deliberately avoids assuming where horizontal progress reaches jump apex.
            double envelopeHeight = apex - destination.feetY();
            if (envelopeHeight < 0 || envelopeHeight > JUMP_MAX_RISE) return false;
            int bands = Math.max(1, (int) Math.ceil(envelopeHeight / .25));
            for (int band = 0; band <= bands; band++) {
                double feetY = destination.feetY() + envelopeHeight * band / bands;
                if (!terrain.isMotionClear(currentFeetX, feetY, currentFeetZ,
                        destination.x + .5, feetY, destination.z + .5,
                        STRAIGHT_ARC, sourceProbe, destinationProbe)) return false;
            }
            return true;
        }

        double arc = arcFor(destination.movement);
        if (arc <= 0.0) {
            return terrain.isMotionClear(currentFeetX, currentFeetY, currentFeetZ,
                    destination.x + 0.5, destination.feetY(), destination.z + 0.5,
                    0.0, sourceProbe, destinationProbe);
        }

        double endX = destination.x + 0.5;
        double endY = destination.feetY();
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
        if (source == null) return false;
        return isWithinEdgeCorridor(source, destination,
                source.x + 0.5, source.feetY(), source.z + 0.5, feetX, feetY, feetZ);
    }

    /**
     * Corridor variant anchored to the actual feet position captured when this edge was validated.
     * This keeps ordinary fractional within-block start positions from appearing behind the edge.
     */
    public static boolean isWithinEdgeCorridor(Path.Step source, Path.Step destination,
                                               double edgeStartX, double edgeStartFeetY, double edgeStartZ,
                                               double feetX, double feetY, double feetZ) {
        if (source == null || destination == null || !Double.isFinite(feetX)
                || !Double.isFinite(feetY) || !Double.isFinite(feetZ)
                || !Double.isFinite(edgeStartX) || !Double.isFinite(edgeStartFeetY)
                || !Double.isFinite(edgeStartZ)
                || destination.movement == Path.Movement.START) return false;
        double startX = edgeStartX;
        double startY = edgeStartFeetY;
        double startZ = edgeStartZ;
        double endX = destination.x + 0.5;
        double endY = destination.feetY();
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

        if (!probeCurrentStance(terrain, feetX, feetY, feetZ, movement, stanceProbe)) return false;
        return safeStance(stanceProbe, movement, true);
    }

    /**
     * Proves a short vertical settlement after a validated WALK left its upper support.
     * This permits an idle physics wait, never forward movement or a world action.
     * The native on-ground flag can retain its preceding value for one tick at a ledge.
     */
    public static boolean isSafeWalkSettlement(Terrain terrain, double feetX, double feetY,
                                                double feetZ, StanceProbe lowerProbe,
                                                StanceProbe emptyProbe) {
        if (terrain == null || lowerProbe == null || emptyProbe == null
                || !Double.isFinite(feetX) || !Double.isFinite(feetY) || !Double.isFinite(feetZ)
                || feetY < -2048.0 || feetY >= 2048.0
                || !terrain.isMotionClear(feetX, feetY, feetZ, feetX, feetY, feetZ, 0.0, emptyProbe)) return false;
        int top = (int) Math.ceil(feetY * 16.0) - 1;
        int bottom = (int) Math.ceil(feetY * 16.0 - 9.0);
        for (int lowerY16 = top; lowerY16 >= bottom; lowerY16--) {
            if (!terrain.probeCurrentStance(feetX, lowerY16, feetZ, lowerProbe)
                    || !lowerProbe.loaded || lowerProbe.hazard || !lowerProbe.bodyClear
                    || lowerProbe.breakCount != 0) return false;
            if (!lowerProbe.hasGroundSupport()) continue;
            return terrain.isMotionClear(feetX, feetY, feetZ,
                    feetX, lowerY16 / 16.0, feetZ, 0.0, emptyProbe);
        }
        return false;
    }

    /** Checks the live source stance and actual player-sized volume before a break/place action. */
    public static boolean isCurrentStanceSafe(Terrain terrain, Path.Step stance,
                                              double feetX, double feetY, double feetZ,
                                              StanceProbe stanceProbe, StanceProbe emptyProbe) {
        if (terrain == null || stance == null || stanceProbe == null || emptyProbe == null
                || !nearSource(stance, feetX, feetY, feetZ)
                || !probeCurrentStance(terrain, feetX, feetY, feetZ,
                stance.movement, stanceProbe)) return false;
        boolean mediumSource = stance.movement == Path.Movement.SWIM && stanceProbe.water
                || stance.movement == Path.Movement.CLIMB && stanceProbe.climbable
                || stance.movement == Path.Movement.START && (stanceProbe.water || stanceProbe.climbable);
        if (!mediumSource && quantizedFeetY16(feetY) != stance.feetY16) return false;
        if (!safeStance(stanceProbe, stance.movement, false)) return false;
        return terrain.isMotionClear(feetX, feetY, feetZ, feetX, feetY, feetZ,
                0.0, emptyProbe);
    }

    /** Rechecks that an outstanding break action is still a safe, reachable obstruction. */
    public static boolean isBreakActionSafe(Terrain terrain, Path.Step source,
                                            Path.Step destination, Action action,
                                            StanceProbe destinationProbe) {
        if (terrain == null || source == null || destination == null || action == null
                || action.type != Action.Type.BREAK_BLOCK || destinationProbe == null
                || !isIntegralFeetHeight(source) || !isIntegralFeetHeight(destination)) return false;
        terrain.probeStance16(destination.x, destination.feetY16, destination.z, destinationProbe);
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
                || Math.floorMod(source.feetY16, 16) != 0
                || Math.floorMod(destination.feetY16, 16) != 0
                || action.x != destination.x || action.y != destination.y - 1
                || action.z != destination.z) return false;
        terrain.probeStance16(source.x, source.feetY16, source.z, sourceProbe);
        if (!sourceProbe.loaded || sourceProbe.hazard || !sourceProbe.bodyClear
                || !sourceProbe.fullSupport || sourceProbe.breakCount != 0) return false;
        terrain.probeStance16(destination.x, destination.feetY16, destination.z, destinationProbe);
        if (!destinationProbe.loaded || destinationProbe.hazard || !destinationProbe.bodyClear
                || destinationProbe.breakCount != 0 || destinationProbe.fullSupport
                || destinationProbe.surfaceSupport
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
        return dx * dx + dz * dz <= 0.75 * 0.75 && Math.abs(y - source.feetY()) < 1.0;
    }

    private static boolean isIntegralFeetHeight(Path.Step step) {
        return Math.floorMod(step.feetY16, 16) == 0;
    }

    private static boolean requiresFullLaunchSupport(Path.Movement movement) {
        return movement == Path.Movement.JUMP || movement == Path.Movement.DROP
                || movement == Path.Movement.PARKOUR || movement == Path.Movement.BRIDGE;
    }

    private static boolean safeStance(StanceProbe probe, Path.Movement movement,
                                      boolean destination) {
        if (!probe.loaded || probe.hazard || !probe.bodyClear || probe.breakCount != 0) return false;
        if (movement == Path.Movement.START) {
            return !destination && (probe.hasGroundSupport() || probe.water || probe.climbable);
        }
        return switch (movement) {
            case WALK -> probe.hasGroundSupport();
            case JUMP, DROP, PARKOUR, BRIDGE -> probe.fullSupport;
            case SWIM -> probe.fullSupport || probe.water;
            case CLIMB -> probe.fullSupport || probe.climbable;
            case START -> false;
        };
    }

    private static boolean probeCurrentStance(Terrain terrain, double feetX, double feetY,
                                              double feetZ, Path.Movement movement,
                                              StanceProbe out) {
        int feetY16 = quantizedFeetY16(feetY);
        if (feetY16 != INVALID_FEET_Y16
                && terrain.probeCurrentStance(feetX, feetY16, feetZ, out) && out.loaded) return true;

        // Swimming and climbing players can move continuously within a block. Preserve the old
        // integer cell summary for these media while still checking the real body point separately.
        // This fallback never turns an unquantized grounded point into a supported WALK stance.
        if (!Double.isFinite(feetX) || !Double.isFinite(feetY) || !Double.isFinite(feetZ)
                || (movement != Path.Movement.START
                && movement != Path.Movement.SWIM && movement != Path.Movement.CLIMB)) {
            out.clear();
            return false;
        }
        double floorX = Math.floor(feetX);
        double floorY = Math.floor(feetY);
        double floorZ = Math.floor(feetZ);
        if (floorX < Integer.MIN_VALUE || floorX > Integer.MAX_VALUE
                || floorY < Integer.MIN_VALUE || floorY > Integer.MAX_VALUE
                || floorZ < Integer.MIN_VALUE || floorZ > Integer.MAX_VALUE) {
            out.clear();
            return false;
        }
        terrain.probeStance((int) floorX, (int) floorY, (int) floorZ, out);
        if (!out.loaded) return false;
        return switch (movement) {
            case SWIM -> out.water;
            case CLIMB -> out.climbable;
            case START -> out.water || out.climbable;
            default -> false;
        };
    }

    private static int quantizedFeetY16(double feetY) {
        if (!Double.isFinite(feetY)) return INVALID_FEET_Y16;
        double scaled = feetY * 16.0;
        if (!Double.isFinite(scaled)) return INVALID_FEET_Y16;
        double rounded = Math.rint(scaled);
        if (Math.abs(scaled - rounded) > FEET_Y16_TOLERANCE
                || rounded < Integer.MIN_VALUE + 1.0 || rounded > Integer.MAX_VALUE) {
            return INVALID_FEET_Y16;
        }
        return (int) rounded;
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
