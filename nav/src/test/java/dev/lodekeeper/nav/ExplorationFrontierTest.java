package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class ExplorationFrontierTest {
    @Test void selectsOnlyLoadedSupportedHarmlessStances() {
        Surface terrain = new Surface();
        terrain.rejectEast = true;
        var frontier = new ExplorationFrontier(0,0,4,64,() -> 0);
        frontier.beginAt(0,0,0);
        assertEquals(ExplorationFrontier.Status.READY, finish(frontier,terrain));
        var point = frontier.waypoint();
        assertTrue(point.x() <= 0);
        assertEquals(0,point.y());
    }
    @Test void unknownOrUnsafeTerrainNeverProducesAWaypoint() {
        for (int unsafe = 1; unsafe <= 7; unsafe++) {
            Surface terrain = new Surface(); terrain.unsafe = unsafe;
            var frontier = new ExplorationFrontier(0,0,4,64,() -> 0);
            frontier.beginAt(0,0,0);
            assertEquals(ExplorationFrontier.Status.EXHAUSTED,finish(frontier,terrain));
            assertThrows(IllegalStateException.class,frontier::waypoint);
        }
    }
    @Test void probeAndTimeBudgetsYieldIncrementally() {
        Surface terrain = new Surface();
        var frontier = new ExplorationFrontier(0,0,4,64,() -> 0);
        frontier.beginAt(0,0,0);
        assertEquals(ExplorationFrontier.Status.IN_PROGRESS,frontier.advance(terrain,8,1));
        assertTrue(terrain.probes <= 8);
        assertEquals(ExplorationFrontier.Status.READY,finish(frontier,terrain));
        long[] clock = {0};
        var timed = new ExplorationFrontier(0,0,4,64,() -> clock[0]++);
        timed.beginAt(0,0,0);
        int before = terrain.probes;
        assertEquals(ExplorationFrontier.Status.IN_PROGRESS,timed.advance(terrain,224,1));
        assertEquals(before,terrain.probes);
    }
    @Test void boundsFailedAttemptsAndDoesNotRetrySelectedRegions() {
        Surface terrain = new Surface();
        var frontier = new ExplorationFrontier(-17,-17,4,32,() -> 0);
        Set<String> regions = new HashSet<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            frontier.beginAt(-17,0,-17); // Simulate an unreachable route: player stayed put.
            assertEquals(ExplorationFrontier.Status.READY,finish(frontier,terrain));
            var point = frontier.waypoint();
            assertTrue(regions.add(Math.floorDiv(point.x(),8)+":"+Math.floorDiv(point.z(),8)));
            assertTrue(Math.pow(point.x()+17,2)+Math.pow(point.z()+17,2) <= 32*32);
        }
        frontier.beginAt(-17,0,-17);
        assertEquals(ExplorationFrontier.Status.EXHAUSTED,frontier.advance(terrain,32,1));
        assertEquals(4,frontier.attempts());
    }
    @Test void repeatedIncompleteSearchesConsumeTheAttemptLimit() {
        var frontier = new ExplorationFrontier(0,0,3,64,() -> 0);
        Surface terrain = new Surface();
        for (int restart = 0; restart < 3; restart++) {
            frontier.beginAt(0,0,0);
            assertEquals(ExplorationFrontier.Status.IN_PROGRESS,frontier.advance(terrain,1,1));
        }
        frontier.beginAt(0,0,0);
        assertEquals(ExplorationFrontier.Status.EXHAUSTED,frontier.advance(terrain,32,1));
        assertEquals(3,frontier.attempts());
    }
    @Test void handlesWorldCoordinateAndHeightOverflowConservatively() {
        Surface terrain = new Surface(); terrain.floor = Integer.MAX_VALUE;
        var frontier = new ExplorationFrontier(Integer.MAX_VALUE,Integer.MAX_VALUE,4,64,() -> 0);
        frontier.beginAt(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
        assertEquals(ExplorationFrontier.Status.READY,finish(frontier,terrain));
        var point = frontier.waypoint();
        assertEquals(Integer.MAX_VALUE,point.y());
        assertTrue((long)Integer.MAX_VALUE-point.x() <= 16);
        assertTrue((long)Integer.MAX_VALUE-point.z() <= 16);
        frontier.beginAt(Integer.MIN_VALUE,0,Integer.MIN_VALUE);
        assertEquals(ExplorationFrontier.Status.EXHAUSTED,frontier.advance(terrain,32,1));
    }
    private static ExplorationFrontier.Status finish(ExplorationFrontier frontier, Terrain terrain) {
        for (int tick = 0; tick < 32; tick++) {
            var status = frontier.advance(terrain,16,1);
            if (status != ExplorationFrontier.Status.IN_PROGRESS) return status;
        }
        throw new AssertionError("exploration failed to finish within its finite candidate count");
    }
    private static final class Surface implements Terrain {
        int floor, probes, unsafe;
        boolean rejectEast;
        @Override public void probeStance(int x,int y,int z,StanceProbe out) {
            probes++; out.clear(); out.loaded = unsafe != 1;
            out.bodyClear = unsafe != 2; out.fullSupport = y == floor && unsafe != 3;
            out.hazard = unsafe == 4 || rejectEast && x > 0;
            out.water = unsafe == 5; out.climbable = unsafe == 6; out.breakCount = unsafe == 7 ? 1 : 0;
        }
        @Override public boolean isMotionClear(double x,double y,double z,double tx,double ty,double tz,double arc,StanceProbe destination) { return false; }
        @Override public boolean canBreakFrom(int x,int y,int z,StanceProbe target,int index) { return false; }
        @Override public boolean canPlaceBridgeFrom(int x,int y,int z,int tx,int ty,int tz,int token,boolean support) { return false; }
    }
}
