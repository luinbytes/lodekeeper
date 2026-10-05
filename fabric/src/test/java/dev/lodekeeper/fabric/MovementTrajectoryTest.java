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
