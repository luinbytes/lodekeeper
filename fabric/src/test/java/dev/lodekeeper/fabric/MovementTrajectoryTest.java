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
    @Test void walkHasNoArtificialJumpOrFall() {
        double[] point = new double[3];
        MovementTrajectory.sample(.5, 64, .5, 1.5, 64, .5, 0, .5, point);
        assertArrayEquals(new double[]{1, 64, .5}, point, .00001);
    }
}
