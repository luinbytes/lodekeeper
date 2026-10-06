package dev.lodekeeper.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementTrajectoryTest {
    @Test void dropClearsStartingSupportBeforeDescending() {
        double[] point = new double[3];
        for (int i = 0; i <= 100; i++) {
            MovementTrajectory.sample(.5, 64, .5, 1.5, 63, .5, 0, i / 100d, point);
            boolean overlapsStartSupport = point[0] - .3 < 1 && point[0] + .3 > 0 && point[1] < 64 && point[1] + 1.8 > 63;
            assertFalse(overlapsStartSupport, "Descent may not clip the supporting block at sample " + i);
        }
        assertArrayEquals(new double[]{1.5, 63, .5}, point, .00001);
    }
    @Test void ascendingJumpClearsLedgeAfterVerticalLaunch() {
        double[] point = new double[3];
        for (double startX : new double[]{.5, .699999988079}) {
            for (int i = 0; i <= 100; i++) {
                double time = i / 100d;
                MovementTrajectory.sample(startX, 64, .5, 1.5, 65, .5, .85, time, point);
                if (time <= .4) assertEquals(startX, point[0], .00001);
                boolean hitsLedge = point[0] + .3 > 1 && point[0] - .3 < 2
                        && point[1] + .001 < 65 && point[1] + 1.8 > 64;
                assertFalse(hitsLedge, "Jump must clear the leading face at sample " + i);
            }
        }
        assertArrayEquals(new double[]{1.5, 65, .5}, point, .00001);
    }
    @Test void nativeSweptChordsRequireACenteredLaunchEvenWhenIndividualPointsClear() {
        for (double startX : new double[]{.5, .59, .599}) {
            assertFalse(nativeJumpChordsHitLedge(startX), "centered launch must clear every native chord");
        }
        assertTrue(nativeJumpChordsHitLedge(.699999988079071),
                "the native seven-sample chord union rejects the near-wall launch");
    }

    private static boolean nativeJumpChordsHitLedge(double startX) {
        int samples = Math.max(2, (int) Math.ceil(Math.hypot(1.5 - startX, 1) / .2));
        double[] previous = {startX, 64, .5}, current = new double[3];
        for (int i = 1; i <= samples; i++) {
            MovementTrajectory.sample(startX, 64, .5, 1.5, 65, .5, .85, (double) i / samples, current);
            if (Math.max(previous[0], current[0]) + .3 > 1
                    && Math.min(previous[0], current[0]) - .3 < 2
                    && Math.min(previous[1], current[1]) + .001 < 65
                    && Math.max(previous[1], current[1]) + 1.8 > 64) return true;
            System.arraycopy(current, 0, previous, 0, 3);
        }
        return false;
    }
    @Test void infinitesimalArcKeepsContinuationChordsStraight() {
        double[] point = new double[3];
        MovementTrajectory.sample(.5, 64, .5, 1.5, 65, .5, 1e-12, .25, point);
        assertArrayEquals(new double[]{.75, 64.25, .5}, point, .00001);
    }
    @Test void walkHasNoArtificialJumpOrFall() {
        double[] point = new double[3];
        MovementTrajectory.sample(.5, 64, .5, 1.5, 64, .5, 0, .5, point);
        assertArrayEquals(new double[]{1, 64, .5}, point, .00001);
    }
}
