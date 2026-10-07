package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActionMovementProgressTest {
    @Test
    void repeatedMotionCreditsEachDirectedEdgeOnce() {
        ActionMovementProgress progress = new ActionMovementProgress();
        long low = Position.pack(0, 61, 0);
        long middle = Position.pack(0, 62, 0);
        long high = Position.pack(0, 63, 0);

        assertFalse(progress.observe(low));
        assertFalse(progress.observe(low));
        assertTrue(progress.observe(middle));
        assertFalse(progress.observe(middle));
        assertTrue(progress.observe(high));
        assertTrue(progress.observe(middle));
        assertTrue(progress.observe(low));
        for (int cycle = 0; cycle < 2_000; cycle++) {
            assertFalse(progress.observe(middle));
            assertFalse(progress.observe(high));
            assertFalse(progress.observe(middle));
            assertFalse(progress.observe(low));
        }
        assertFalse(progress.saturated());
    }

    @Test
    void rebasingAfterPauseOrReplanKeepsEdgeHistory() {
        ActionMovementProgress progress = new ActionMovementProgress();
        long first = Position.pack(0, 62, 0);
        long second = Position.pack(1, 62, 0);
        long auxiliary = Position.pack(100, 70, 100);

        assertFalse(progress.observe(first));
        assertTrue(progress.observe(second));
        assertTrue(progress.observe(first));
        progress.rebase(auxiliary);
        assertFalse(progress.observe(auxiliary));
        progress.rebase(first);
        assertFalse(progress.observe(first));
        assertFalse(progress.observe(second));
        progress.rebase(second);
        assertFalse(progress.observe(first));
        assertTrue(progress.observe(auxiliary));
    }

    @Test
    void saturationNeverEvictsOrRecreditsEdges() {
        ActionMovementProgress progress = new ActionMovementProgress();
        assertFalse(progress.observe(Position.pack(0, 62, 0)));
        for (int x = 1; x <= 8_192; x++) {
            assertFalse(progress.saturated());
            assertTrue(progress.observe(Position.pack(x, 62, 0)));
        }
        assertTrue(progress.saturated());
        for (int x = 8_193; x < 16_384; x++) {
            assertFalse(progress.observe(Position.pack(x, 62, 0)));
        }
        progress.rebase(Position.pack(0, 62, 0));
        assertFalse(progress.observe(Position.pack(1, 62, 0)));
        assertFalse(progress.observe(Position.pack(0, 62, 0)));
        assertTrue(progress.saturated());
    }
}
