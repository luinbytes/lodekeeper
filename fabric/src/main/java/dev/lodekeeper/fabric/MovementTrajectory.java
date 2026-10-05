package dev.lodekeeper.fabric;

/** Geometry shared by the live collision adapter and regression checks. No game state. */
final class MovementTrajectory {
    static void sample(double fx, double fy, double fz, double tx, double ty, double tz, double arc, double t, double[] out) {
        if (t < 0 || t > 1 || out.length < 3) throw new IllegalArgumentException("Invalid trajectory sample");
        boolean dropping = ty < fy && arc <= 0;
        // A one-block ascent needs upward clearance before forward contact with its face.
        // Model the same early lift used by the executor, keeping the launch stationary.
        boolean ascendingJump = ty > fy && arc > 1.0e-9;
        double horizontalTime = dropping ? Math.min(1, t / .75)
                : ascendingJump ? Math.max(0, (t - .40) / .60) : t;
        double verticalTime = dropping ? Math.max(0, (t - .75) / .25) : t;
        out[0] = fx + (tx - fx) * horizontalTime;
        out[1] = fy + (ty - fy) * verticalTime + Math.sin(Math.PI * t) * Math.max(0, arc);
        out[2] = fz + (tz - fz) * horizontalTime;
    }
}
