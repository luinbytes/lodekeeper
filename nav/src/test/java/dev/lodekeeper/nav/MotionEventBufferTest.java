package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MotionEventBufferTest {
    private static final double MERGE_TOLERANCE = 1.0e-7;

    @Test
    void exactCriticalContactWinsRegardlessOfInsertionOrder() {
        assertCriticalWins(0.2, 0.199999988079);
        assertCriticalWins(0.199999988079, 0.2);
        assertCriticalWins(0.8, 0.80000001192);
        assertCriticalWins(0.80000001192, 0.8);
    }

    @Test
    void eventsSeparatedByPointOneMillisecondRemainDistinct() {
        MotionEventBuffer buffer = new MotionEventBuffer(8).clear(MERGE_TOLERANCE);

        assertTrue(buffer.add(0.5 - 0.0001, false));
        assertTrue(buffer.add(0.5, true));
        assertTrue(buffer.add(0.5 + 0.0001, false));
        buffer.sort();

        assertEquals(3, buffer.size());
        assertEquals(0.5 - 0.0001, buffer.get(0), 0.0);
        assertEquals(0.5, buffer.get(1), 0.0);
        assertEquals(0.5 + 0.0001, buffer.get(2), 0.0);
        assertTrue(buffer.isComplete());
    }

    @Test
    void repeatedFaceEventsMergeWithoutExhaustingCapacity() {
        MotionEventBuffer buffer = new MotionEventBuffer(1).clear(MERGE_TOLERANCE);

        assertTrue(buffer.add(0.2, false));
        assertTrue(buffer.add(0.199999988079, true));
        for (int index = 0; index < 1000; index++) {
            assertTrue(buffer.add(0.2, false));
        }

        assertEquals(1, buffer.size());
        assertEquals(0.199999988079, buffer.get(0), 0.0);
        assertTrue(buffer.isComplete());
    }

    @Test
    void bridgeEventsDoNotCollapseDistinctCriticalTimes() {
        double firstContact = 0.2;
        double secondContact = 0.20000015;
        double bridge = 0.200000075;
        assertTrue(secondContact - firstContact > MERGE_TOLERANCE);

        MotionEventBuffer buffer = new MotionEventBuffer(3).clear(MERGE_TOLERANCE);
        assertTrue(buffer.add(firstContact, true));
        assertTrue(buffer.add(secondContact, true));
        assertTrue(buffer.add(bridge, false));
        assertEquals(2, buffer.size());
        assertEquals(firstContact, buffer.get(0), 0.0);
        assertEquals(secondContact, buffer.get(1), 0.0);

        buffer.clear(MERGE_TOLERANCE);
        assertTrue(buffer.add(firstContact, true));
        assertTrue(buffer.add(secondContact, true));
        assertTrue(buffer.add(bridge, true));
        assertEquals(2, buffer.size());
        assertEquals(firstContact, buffer.get(0), 0.0);
        assertEquals(secondContact, buffer.get(1), 0.0);
    }

    @Test
    void uniqueCapacityOverflowFailsClosedUntilClear() {
        MotionEventBuffer buffer = new MotionEventBuffer(3).clear(MERGE_TOLERANCE);
        assertTrue(buffer.add(0.8, false));
        assertTrue(buffer.add(0.2, false));
        assertTrue(buffer.add(0.5, false));
        buffer.sort();
        assertEquals(0.2, buffer.get(0), 0.0);
        assertEquals(0.5, buffer.get(1), 0.0);
        assertEquals(0.8, buffer.get(2), 0.0);

        assertFalse(buffer.add(1.0, true));
        assertFalse(buffer.isComplete());
        assertFalse(buffer.add(0.2, false));

        buffer.clear(MERGE_TOLERANCE);
        assertTrue(buffer.isComplete());
        assertTrue(buffer.add(1.0, true));
        assertEquals(1.0, buffer.get(0), 0.0);
    }

    @Test
    void endpointsAreValidButNonFiniteAndOutOfRangeEventsFailClosed() {
        MotionEventBuffer buffer = new MotionEventBuffer(4).clear(0.0);
        assertTrue(buffer.add(0.0, false));
        assertTrue(buffer.add(1.0, true));
        buffer.sort();
        assertEquals(0.0, buffer.get(0), 0.0);
        assertEquals(1.0, buffer.get(1), 0.0);

        for (double invalid : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -Double.MIN_VALUE, Math.nextUp(1.0)
        }) {
            buffer.clear(MERGE_TOLERANCE);
            assertFalse(buffer.add(invalid, false), "invalid event " + invalid);
            assertFalse(buffer.isComplete(), "invalid event must make the result incomplete");
            assertFalse(buffer.add(0.5, false), "incomplete buffer rejects later events");
        }
    }

    @Test
    void invalidCapacityAndToleranceAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MotionEventBuffer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new MotionEventBuffer(MotionEventBuffer.MAX_CAPACITY + 1));

        MotionEventBuffer buffer = new MotionEventBuffer(1);
        assertThrows(IllegalArgumentException.class, () -> buffer.clear(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> buffer.clear(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> buffer.clear(-Double.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> buffer.clear(Math.nextUp(1.0)));
    }

    private static void assertCriticalWins(double regularTime, double criticalTime) {
        MotionEventBuffer buffer = new MotionEventBuffer(2).clear(MERGE_TOLERANCE);

        assertTrue(buffer.add(regularTime, false));
        assertTrue(buffer.add(criticalTime, true));
        assertEquals(1, buffer.size());
        assertEquals(criticalTime, buffer.get(0), 0.0);

        buffer.clear(MERGE_TOLERANCE);
        assertTrue(buffer.add(criticalTime, true));
        assertTrue(buffer.add(regularTime, false));
        assertEquals(1, buffer.size());
        assertEquals(criticalTime, buffer.get(0), 0.0);
    }
}
