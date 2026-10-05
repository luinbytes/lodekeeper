package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FootprintCoverageTest {
    @Test
    void overlappingRectanglesContributeTheirUnionOnlyOnce() {
        FootprintCoverage coverage = new FootprintCoverage();
        coverage.clear(0, 0, 1, 1);
        assertTrue(coverage.add(0, 0, 0.75, 1));
        assertTrue(coverage.add(0.25, 0, 1, 1));
        assertEquals(1.0, coverage.coveredArea(), 1.0e-12);
        assertEquals(0.0, coverage.uncoveredArea(), 1.0e-12);
        assertTrue(coverage.coversAll());
    }

    @Test
    void fourTouchingQuadrantsCoverTheWholeFootprint() {
        FootprintCoverage coverage = new FootprintCoverage();
        coverage.clear(0, 0, 1, 1);
        assertTrue(coverage.add(0, 0, 0.5, 0.5));
        assertTrue(coverage.add(0.5, 0, 1, 0.5));
        assertTrue(coverage.add(0, 0.5, 0.5, 1));
        assertTrue(coverage.add(0.5, 0.5, 1, 1));
        assertTrue(coverage.coversAll());
        assertEquals(0.0, coverage.uncoveredArea(), 1.0e-12);
    }

    @Test
    void interiorHolesAndThinSlitsFailFullCoverage() {
        FootprintCoverage interiorHole = new FootprintCoverage();
        interiorHole.clear(0, 0, 1, 1);
        assertTrue(interiorHole.add(0, 0, 1, 0.45));
        assertTrue(interiorHole.add(0, 0.55, 1, 1));
        assertTrue(interiorHole.add(0, 0.45, 0.45, 0.55));
        assertTrue(interiorHole.add(0.55, 0.45, 1, 0.55));
        assertFalse(interiorHole.coversAll());
        assertTrue(interiorHole.uncoveredArea() > 0.0);

        FootprintCoverage slit = new FootprintCoverage();
        slit.clear(0, 0, 1, 1);
        assertTrue(slit.add(0, 0, 0.49, 1));
        assertTrue(slit.add(0.51, 0, 1, 1));
        assertFalse(slit.coversAll());
        assertEquals(0.02, slit.uncoveredArea(), 1.0e-12);
    }

    @Test
    void halfFootprintStairContactUsesItsOwnThresholdAndLowerSupportCanRemainFull() {
        FootprintCoverage lower = new FootprintCoverage();
        lower.clear(0, 0, 0.6, 0.6);
        assertTrue(lower.add(0, 0, 0.6, 0.6));
        assertTrue(lower.coversAll());

        FootprintCoverage upper = new FootprintCoverage();
        upper.clear(0, 0, 0.6, 0.6);
        assertTrue(upper.add(0, 0, 0.3, 0.6));
        assertTrue(upper.coversFraction(0.5));
        assertFalse(upper.coversAll());
    }

    @Test
    void overflowInvalidatesEvenPreviouslyCompleteCoverage() {
        FootprintCoverage coverage = new FootprintCoverage();
        coverage.clear(0, 0, 1, 1);
        assertTrue(coverage.add(0, 0, 1, 1));
        for (int i = 1; i < FootprintCoverage.MAX_RECTANGLES; i++) {
            assertTrue(coverage.add(0, 0, 1, 1));
        }
        assertTrue(coverage.coversAll());
        assertFalse(coverage.add(0, 0, 1, 1));
        assertFalse(coverage.isComplete());
        assertFalse(coverage.coversAll());
        assertTrue(Double.isNaN(coverage.coveredArea()));
    }

    @Test
    void invalidAndNonFiniteGeometryFailsClosed() {
        FootprintCoverage coverage = new FootprintCoverage();
        coverage.clear(0, 0, 1, 1);
        assertFalse(coverage.add(0, 0, Double.NaN, 1));
        assertFalse(coverage.isComplete());
        assertFalse(coverage.coversFraction(0.5));

        coverage.clear(0, 0, 1, 1);
        assertFalse(coverage.add(0.8, 0, 0.2, 1));
        assertFalse(coverage.coversAll());

        coverage.clear(0, 0, 0, 1);
        assertFalse(coverage.isComplete());
        assertFalse(coverage.coversFraction(0.0));
    }
}
