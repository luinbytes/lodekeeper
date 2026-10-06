package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LaunchApproachTest {
    @Test void fastIncomingWalkBrakesBeforeTheLedgeAndHandsOffSettled() {
        Ground terrain = new Ground();
        double x = 11.35, velocity = .12;
        assertFalse(LaunchApproach.isSettled(.05, velocity, 0));
        boolean settled = false;
        for (int tick = 0; tick < 80; tick++) {
            if (LaunchApproach.isSettled(Math.abs(11.5 - x), velocity, 0)) {
                settled = true;
                break;
            }
            LaunchApproach.Control control = control(terrain, x, 1040, .5, velocity, 0, false, .6);
            assertNotNull(control, "a grounded approach must remain proved at tick " + tick);
            double travel = velocity + control.directionX() * control.input() * .09800000907407408;
            x += travel;
            velocity = travel * .546;
            if (Math.abs(velocity) < .003) velocity = 0;
            assertTrue(x <= 11.7, "the player's leading face must stay clear of the ledge");
        }
        assertTrue(settled, "arrival needs both the .10 position radius and speed below .01");
        assertTrue(Math.abs(11.5 - x) < .10);
        assertTrue(Math.abs(velocity) < .01);
    }

    @Test void settledNearWallStartCanRecenterWithAProvedReverseSweep() {
        Ground terrain = new Ground();
        LaunchApproach.Control result = control(terrain, 11.699999988079071, 1040, .5, 0, 0, true, .6);
        assertNotNull(result);
        assertEquals(-1, result.directionX(), 1e-10);
        assertTrue(result.input() > 0);
        assertTrue(terrain.groundedSweeps > 0);
    }

    @Test void reverseCorrectionRejectsBlockedHazardUnknownAndSupportLoss() {
        for (int failure = 0; failure < 4; failure++) {
            Ground terrain = new Ground();
            terrain.failure = failure;
            assertNull(control(terrain, 11.6, 1040, .5, -.02, 0, true, .6),
                    "reverse motion must fail closed for ground failure " + failure);
        }
        Ground terrain = new Ground();
        terrain.sweepClear = false;
        assertNull(control(terrain, 11.6, 1040, .5, -.02, 0, true, .6),
                "clear endpoints cannot replace the complete grounded sweep");
    }

    @Test void sidewaysCoastCannotLeaveTheCorridorOrSupport() {
        Ground terrain = new Ground();
        terrain.supportZMax = .68;
        assertNull(control(terrain, 11.5, 1040, .67, 0, .3, true, .6));
        terrain.supportZMax = 100;
        assertNull(control(terrain, 11.5, 1040, 1.24, 0, .3, true, .6));
    }

    @Test void ordinaryFractionalApproachKeepsExactHeightAndSurfaceSupport() {
        Ground terrain = new Ground();
        terrain.fullSupport = false;
        LaunchApproach.Control control = control(terrain, 11.35, 1045, .5, .08, 0, false, .6);
        assertNotNull(control, "the approach must retain grounded fractional WALK coverage");
        assertEquals(1045, terrain.lastFeetY16);
        assertNull(control(terrain, 11.35, 1045, .5, .08, 0, true, .6),
                "the strict maneuver gate still requires full support");
    }

    @Test void invalidFrictionAndMotionFailClosed() {
        Ground terrain = new Ground();
        for (double friction : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -.6, .49, 1.01}) {
            assertNull(control(terrain, 11.4, 1040, .5, .08, 0, false, friction));
        }
        assertNull(control(terrain, 11.4, 1040, .5, Double.NaN, 0, false, .6));
        assertNull(control(terrain, 11.4, 1040, .5, 0, Double.POSITIVE_INFINITY, false, .6));
        assertFalse(LaunchApproach.isSettled(.05, Double.NaN, 0));
        assertFalse(LaunchApproach.isSettled(Double.NaN, 0, 0));
        assertFalse(LaunchApproach.isSettled(.10, 0, 0));
        assertFalse(LaunchApproach.isSettled(.05, .01, 0));
        for (double acceleration : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -.1}) {
            assertNull(LaunchApproach.control(terrain, step(10, 1040, Path.Movement.START),
                    step(11, 1040, Path.Movement.WALK), 10.5, 65, .5,
                    11.4, 1040, .5, 11.5, .5, .08, 0, true, false, acceleration,
                    (x, y, z) -> .6, new StanceProbe(), new StanceProbe()));
        }
    }

    @Test void aFrictionChangeWithinTheCoastRequiresANewMotionModel() {
        Ground terrain = new Ground();
        assertNull(LaunchApproach.control(terrain, step(10, 1040, Path.Movement.START),
                step(11, 1040, Path.Movement.WALK), 10.5, 65, .5,
                11.4, 1040, .5, 11.5, .5, .08, 0, true, false, .09800000907407408,
                (x, y, z) -> x < 11.42 ? .6 : .98, new StanceProbe(), new StanceProbe()));
    }

    @Test void firstLaunchBehindSourceCenterUsesItsActualCorridorOrigin() {
        Ground terrain = new Ground();
        Path.Step source = step(11, 1040, Path.Movement.START);
        Path.Step destination = step(12, 1056, Path.Movement.JUMP);
        assertNull(LaunchApproach.control(terrain, source, destination, 11.5, 65, .5,
                11.25, 1040, .5, 11.5, .5, 0, 0, true, true, .09800000907407408,
                (x, y, z) -> .6, new StanceProbe(), new StanceProbe()));
        LaunchApproach.Control correction = LaunchApproach.control(terrain, source, destination, 11.25, 65, .5,
                11.25, 1040, .5, 11.5, .5, 0, 0, true, true, .09800000907407408,
                (x, y, z) -> .6, new StanceProbe(), new StanceProbe());
        assertNotNull(correction);
        assertTrue(correction.directionX() > 0);
        assertTrue(correction.input() > 0);
    }

    @Test void completedBridgeCanReturnFromItsLipWithinTheSourceCorridor() {
        Ground terrain = new Ground();
        terrain.supportXMax = 13;
        Path.Step source = step(11, 1040, Path.Movement.START);
        Path.Step destination = step(12, 1040, Path.Movement.BRIDGE);
        LaunchApproach.Control correction = LaunchApproach.control(terrain, source, destination, 11.5, 65, .5,
                12.2, 1040, .5, 11.5, .5, 0, 0, true, true, .09800000907407408,
                (x, y, z) -> .6, new StanceProbe(), new StanceProbe());
        assertNotNull(correction);
        assertTrue(correction.directionX() < 0);
        assertTrue(correction.input() > 0);
        assertTrue(terrain.groundedSweeps > 0);
    }

    @Test void crouchClearanceNeedsItsOwnIdleCoastProof() {
        Ground terrain = new Ground();
        assertTrue(coast(terrain, 11.4, .5, .02, 0));
        assertTrue(coast(terrain, 11.5, .5, 0, 0));
        assertFalse(coast(terrain, 11.69, .5, .02, 0), "idle drift must not clip the ledge");
        assertNotNull(control(terrain, 11.69, 1040, .5, .02, 0, true, .6),
                "a safe braking input cannot justify unsafe zero input");
        terrain.supportZMax = .68;
        assertFalse(coast(terrain, 11.5, .67, 0, .02), "idle drift must retain full support");
        assertNotNull(control(terrain, 11.5, 1040, .67, 0, .02, true, .6));
        terrain.supportZMax = 100;
        terrain.fullSupport = false;
        assertFalse(coast(terrain, 11.5, .5, 0, 0));
        terrain.fullSupport = true;
        terrain.sweepClear = false;
        assertFalse(coast(terrain, 11.4, .5, .02, 0));
    }

    @Test void idleCoastRejectsUnsupportedAndNonfiniteInputs() {
        Ground terrain = new Ground();
        assertFalse(coast(terrain, Double.NaN, .5, 0, 0));
        assertFalse(coast(terrain, 11.5, .5, Double.POSITIVE_INFINITY, 0));
        for (int failure = 0; failure < 4; failure++) {
            terrain.failure = failure;
            assertFalse(coast(terrain, 11.5, .5, 0, 0));
        }
        terrain.failure = -1;
        Path.Step source = step(11, 1040, Path.Movement.START);
        Path.Step destination = step(12, 1056, Path.Movement.JUMP);
        assertFalse(LaunchApproach.isSafeCoast(terrain, source, destination, 11.5, 65, .5,
                11.5, 1040, .5, 0, 0, false, true,
                (x, y, z) -> .6, new StanceProbe(), new StanceProbe()));
        assertFalse(LaunchApproach.isSafeCoast(terrain, source, destination, 11.5, 65, .5,
                11.5, 1040, .5, 0, 0, true, true,
                (x, y, z) -> Double.NaN, new StanceProbe(), new StanceProbe()));
        assertFalse(LaunchApproach.isSafeCoast(terrain, source, destination, Double.NaN, 65, .5,
                11.5, 1040, .5, 0, 0, true, true,
                (x, y, z) -> .6, new StanceProbe(), new StanceProbe()));
    }

    private static boolean coast(Ground terrain, double x, double z, double velocityX, double velocityZ) {
        return LaunchApproach.isSafeCoast(terrain, step(11, 1040, Path.Movement.START),
                step(12, 1056, Path.Movement.JUMP), 11.5, 65, .5,
                x, 1040, z, velocityX, velocityZ, true, true,
                (fx, y, fz) -> .6, new StanceProbe(), new StanceProbe());
    }

    private static LaunchApproach.Control control(Ground terrain, double x, int feetY16, double z,
                                                   double velocityX, double velocityZ,
                                                   boolean strict, double friction) {
        Path.Step source = step(strict ? 11 : 10, feetY16, Path.Movement.START);
        Path.Step destination = step(strict ? 12 : 11, strict ? feetY16 + 16 : feetY16,
                strict ? Path.Movement.JUMP : Path.Movement.WALK);
        return LaunchApproach.control(terrain, source, destination, source.x + .5, source.feetY(), .5,
                x, feetY16, z, 11.5, .5, velocityX, velocityZ, true, strict, .09800000907407408,
                (fx, y, fz) -> friction, new StanceProbe(), new StanceProbe());
    }

    private static Path.Step step(int x, int feetY16, Path.Movement movement) {
        return Path.Step.atFeetY16(x, feetY16, 0, movement, new Action[0]);
    }

    private static final class Ground implements Terrain {
        int failure = -1, lastFeetY16, groundedSweeps;
        boolean fullSupport = true, sweepClear = true;
        double supportXMax = 12, supportZMax = 100;

        @Override public void probeStance(int x, int y, int z, StanceProbe out) {
            probeCurrentStance(x + .5, y * 16, z + .5, out);
        }

        @Override public boolean probeCurrentStance(double x, int y16, double z, StanceProbe out) {
            out.clear();
            lastFeetY16 = y16;
            out.loaded = failure != 2;
            out.bodyClear = failure != 0;
            out.hazard = failure == 1;
            out.fullSupport = fullSupport && failure != 3 && z <= supportZMax;
            out.surfaceSupport = !fullSupport && failure != 3 && z <= supportZMax;
            return out.loaded;
        }

        @Override public boolean isMotionClear(double fx, double fy, double fz,
                                               double tx, double ty, double tz, double arc,
                                               StanceProbe destination) {
            return failure != 0 && failure != 1 && failure != 2
                    && Math.max(fx, tx) + .3 <= supportXMax + 1.0e-8;
        }

        @Override public boolean isGroundedWalkClear(double fx, int fy16, double fz,
                                                     double tx, int ty16, double tz,
                                                     StanceProbe source, StanceProbe destination) {
            groundedSweeps++;
            return sweepClear && fy16 == ty16 && Math.max(fz, tz) <= supportZMax
                    && isMotionClear(fx, fy16 / 16.0, fz, tx, ty16 / 16.0, tz, 0, destination);
        }

        @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe stance, int index) { return false; }
        @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz,
                                                    int token, boolean planned) { return false; }
    }
}
