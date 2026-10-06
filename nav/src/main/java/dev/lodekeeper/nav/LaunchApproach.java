package dev.lodekeeper.nav;

/** Grounded input and coast proof for a stance that precedes a strict launch. */
public final class LaunchApproach {
    public static final double ARRIVAL_RADIUS = .10;
    public static final double SETTLED_SPEED = .01;
    private static final int MAX_COAST_TICKS = 64;
    private static final int MAX_SUPPORT_SAMPLES = 128;

    @FunctionalInterface
    public interface Friction {
        double at(double x, int feetY16, double z);
    }

    public record Control(double directionX, double directionZ, float input) {}

    private LaunchApproach() {}

    public static boolean isSettled(double distance, double velocityX, double velocityZ) {
        return Double.isFinite(distance) && distance >= 0 && distance < ARRIVAL_RADIUS
                && Double.isFinite(velocityX) && Double.isFinite(velocityZ)
                && Math.hypot(velocityX, velocityZ) < SETTLED_SPEED;
    }

    /** Returns null when neither the correction nor idle coast has a safe grounded proof. */
    public static Control control(Terrain terrain, Path.Step source, Path.Step destination,
                                  double edgeStartX, double edgeStartY, double edgeStartZ,
                                  double x, int feetY16, double z, double targetX, double targetZ,
                                  double velocityX, double velocityZ, boolean grounded,
                                  boolean requireFullSupport, double acceleration, Friction friction,
                                  StanceProbe stance, StanceProbe empty) {
        if (!validGroundedMotion(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                x, feetY16, z, velocityX, velocityZ, grounded, friction, stance, empty)
                || !Double.isFinite(targetX) || !Double.isFinite(targetZ)
                || !Double.isFinite(acceleration) || acceleration <= 0 || acceleration > .5) return null;
        double blockFriction = friction.at(x, feetY16, z);
        if (!validFriction(blockFriction)) return null;
        double dx = targetX - x, dz = targetZ - z;
        double distance = Math.hypot(dx, dz);
        double desiredTravel = Math.min(.12, distance * .35);
        double desiredX = distance > 1.0e-8 ? dx / distance * desiredTravel : 0;
        double desiredZ = distance > 1.0e-8 ? dz / distance * desiredTravel : 0;
        double correctionX = desiredX - velocityX, correctionZ = desiredZ - velocityZ;
        double correction = Math.hypot(correctionX, correctionZ);
        float amount = (float) Math.min(1, correction / acceleration);
        double directionX = correction > 1.0e-8 ? correctionX / correction : 0;
        double directionZ = correction > 1.0e-8 ? correctionZ / correction : 0;
        if (safeInput(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                x, feetY16, z, velocityX, velocityZ, directionX * acceleration * amount,
                directionZ * acceleration * amount, requireFullSupport, friction, stance, empty)) {
            return new Control(directionX, directionZ, amount);
        }
        if (safeInput(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                x, feetY16, z, velocityX, velocityZ, 0, 0, requireFullSupport, friction, stance, empty)) {
            return new Control(0, 0, 0);
        }
        return null;
    }

    /** Proves zero input through both native stop rules without requiring an input acceleration model. */
    public static boolean isSafeCoast(Terrain terrain, Path.Step source, Path.Step destination,
                                      double edgeStartX, double edgeStartY, double edgeStartZ,
                                      double x, int feetY16, double z, double velocityX, double velocityZ,
                                      boolean grounded, boolean requireFullSupport, Friction friction,
                                      StanceProbe stance, StanceProbe empty) {
        return validGroundedMotion(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                x, feetY16, z, velocityX, velocityZ, grounded, friction, stance, empty)
                && safeInput(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                x, feetY16, z, velocityX, velocityZ, 0, 0,
                requireFullSupport, friction, stance, empty);
    }

    private static boolean validGroundedMotion(Terrain terrain, Path.Step source, Path.Step destination,
                                               double edgeStartX, double edgeStartY, double edgeStartZ,
                                               double x, int feetY16, double z, double velocityX, double velocityZ,
                                               boolean grounded, Friction friction,
                                               StanceProbe stance, StanceProbe empty) {
        return terrain != null && source != null && destination != null && friction != null
                && stance != null && empty != null && grounded && feetY16 != Integer.MIN_VALUE
                && Double.isFinite(edgeStartX) && Double.isFinite(edgeStartY) && Double.isFinite(edgeStartZ)
                && Double.isFinite(x) && Double.isFinite(z)
                && Double.isFinite(velocityX) && Double.isFinite(velocityZ)
                && Math.hypot(velocityX, velocityZ) <= .5;
    }

    private static boolean safeInput(Terrain terrain, Path.Step source, Path.Step destination,
                                      double edgeStartX, double edgeStartY, double edgeStartZ,
                                      double x, int feetY16, double z, double velocityX, double velocityZ,
                                      double accelerationX, double accelerationZ, boolean requireFullSupport,
                                      Friction friction, StanceProbe stance, StanceProbe empty) {
        // Native versions stop small motion either per component or by horizontal magnitude.
        // Prove both rules so the shared executor cannot omit either coast envelope.
        for (int stopRule = 0; stopRule < 2; stopRule++) {
            boolean componentStop = stopRule == 0;
            double vx = componentStop ? stoppedComponent(velocityX)
                    : Math.hypot(velocityX, velocityZ) < .003 ? 0 : velocityX;
            double vz = componentStop ? stoppedComponent(velocityZ)
                    : Math.hypot(velocityX, velocityZ) < .003 ? 0 : velocityZ;
            if (!safeCoast(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                    x, feetY16, z, vx + accelerationX, vz + accelerationZ,
                    requireFullSupport, friction, stance, empty, componentStop)) return false;
        }
        return true;
    }

    private static double stoppedComponent(double velocity) {
        return Math.abs(velocity) < .003 ? 0 : velocity;
    }

    private static boolean safeCoast(Terrain terrain, Path.Step source, Path.Step destination,
                                     double edgeStartX, double edgeStartY, double edgeStartZ,
                                     double x, int feetY16, double z, double travelX, double travelZ,
                                     boolean requireFullSupport, Friction friction,
                                     StanceProbe stance, StanceProbe empty, boolean componentStop) {
        if (!safePoint(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                x, feetY16, z, requireFullSupport, stance, empty)) return false;
        double blockFriction = friction.at(x, feetY16, z);
        if (!validFriction(blockFriction)) return false;
        int samples = 0;
        for (int tick = 0; tick < MAX_COAST_TICKS; tick++) {
            double endX = x + travelX, endZ = z + travelZ;
            int intervals = Math.max(1, (int) Math.ceil(Math.hypot(travelX, travelZ) / .04));
            if ((samples += intervals) > MAX_SUPPORT_SAMPLES) return false;
            for (int i = 1; i <= intervals; i++) {
                double sampleX = x + travelX * i / intervals, sampleZ = z + travelZ * i / intervals;
                if (!safePoint(terrain, source, destination, edgeStartX, edgeStartY, edgeStartZ,
                        sampleX, feetY16, sampleZ,
                        requireFullSupport, stance, empty)) return false;
                double sampleFriction = friction.at(sampleX, feetY16, sampleZ);
                if (!validFriction(sampleFriction) || sampleFriction != blockFriction) return false;
            }
            empty.clear();
            if (!terrain.isGroundedWalkClear(x, feetY16, z, endX, feetY16, endZ, empty, empty)) return false;
            float damping = (float) blockFriction * .91f;
            travelX *= damping;
            travelZ *= damping;
            if (componentStop) {
                travelX = stoppedComponent(travelX);
                travelZ = stoppedComponent(travelZ);
            }
            x = endX; z = endZ;
            if (Math.hypot(travelX, travelZ) < .003) {
                return true;
            }
        }
        return false;
    }

    private static boolean safePoint(Terrain terrain, Path.Step source, Path.Step destination,
                                      double edgeStartX, double edgeStartY, double edgeStartZ,
                                      double x, int feetY16, double z, boolean requireFullSupport,
                                      StanceProbe stance, StanceProbe empty) {
        double y = feetY16 / 16.0;
        empty.clear();
        return PathEdgeValidator.isWithinEdgeCorridor(source, destination,
                edgeStartX, edgeStartY, edgeStartZ, x, y, z)
                && terrain.probeCurrentStance(x, feetY16, z, stance)
                && stance.loaded && !stance.hazard && stance.bodyClear && stance.breakCount == 0
                && !stance.water && !stance.climbable
                && (requireFullSupport ? stance.fullSupport : stance.hasGroundSupport())
                && terrain.isMotionClear(x, y, z, x, y, z, 0, empty);
    }

    private static boolean validFriction(double friction) {
        return Double.isFinite(friction) && friction >= .5 && friction <= 1;
    }
}
