package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class PlannerTest {
    @Test
    void rejectsUnknownAndHazardousTerrain() {
        FakeTerrain unloaded = new FakeTerrain();
        unloaded.stance(0, 0, 0).fullSupport = true;
        Planner unknown = planner(unloaded, 0, 0, 0, Goal.exact(1, 0, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.NO_PATH, finish(unknown));

        FakeTerrain hazard = new FakeTerrain();
        hazard.stance(0, 0, 0).fullSupport = true;
        hazard.stance(1, 0, 0).fullSupport = true;
        hazard.stances.get(Position.pack(1, 0, 0)).hazard = true;
        Planner unsafe = planner(hazard, 0, 0, 0, Goal.exact(1, 0, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.NO_PATH, finish(unsafe));
    }

    @Test
    void blocksDiagonalCornerCuttingAndBodyObstructions() {
        FakeTerrain corners = new FakeTerrain();
        corners.stance(0, 0, 0).fullSupport = true;
        corners.stance(1, 0, 1).fullSupport = true;
        corners.stance(1, 0, 0).bodyClear = false;
        corners.stance(1, 0, 0).fullSupport = true;
        corners.stance(0, 0, 1).bodyClear = false;
        corners.stance(0, 0, 1).fullSupport = true;
        assertEquals(NavStatus.NO_PATH, finish(planner(corners, 0, 0, 0, Goal.exact(1, 0, 1), new Planner.Options().maxDrop(0))));

        FakeTerrain body = new FakeTerrain();
        body.stance(0, 0, 0).fullSupport = true;
        body.stance(1, 0, 0).fullSupport = true;
        body.stance(1, 0, 0).bodyClear = false;
        assertEquals(NavStatus.NO_PATH, finish(planner(body, 0, 0, 0, Goal.exact(1, 0, 0), new Planner.Options().maxDrop(0))));
    }

    @Test
    void rejectsMotionWhenSweptBodyIntersectsAnObstacle() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(1, 0, 0).fullSupport = true;
        terrain.motionClear = false;
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(1, 0, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.NO_PATH, finish(planner));
        assertTrue(terrain.motionChecks > 0);
    }

    @Test
    void jumpAndParkourEdgesPassTheirActualArcToTheTerrainSweep() {
        FakeTerrain ledge = new FakeTerrain();
        ledge.stance(0, 0, 0).fullSupport = true;
        ledge.stance(1, 1, 0).fullSupport = true;
        Planner jump = planner(ledge, 0, 0, 0, Goal.exact(1, 1, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.FOUND, finish(jump));
        assertEquals(Path.Movement.JUMP, jump.getPath().step(1).movement);
        assertEquals(0.85, ledge.maximumArc, 0.0001);

        FakeTerrain gap = new FakeTerrain();
        gap.stance(0, 0, 0).fullSupport = true;
        gap.stance(1, 0, 0);
        gap.stance(2, 0, 0);
        gap.stance(3, 0, 0).fullSupport = true;
        gap.rejectParkourArc = true;
        Planner parkour = planner(gap, 0, 0, 0, Goal.exact(3, 0, 0),
                new Planner.Options().maxDrop(0).allowParkour(true));
        assertEquals(NavStatus.NO_PATH, finish(parkour));
        assertEquals(1.35, gap.maximumArc, 0.0001);
    }

    @Test
    void emitsExplicitBreakActionOnlyForReachableObstruction() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        StanceProbe destination = terrain.stance(1, 0, 0);
        destination.fullSupport = true;
        destination.bodyClear = false;
        destination.breakCount = 1;
        BreakTarget blocker = destination.breakTargets[0];
        blocker.x = 1;
        blocker.y = 1;
        blocker.z = 0;
        blocker.stateToken = 77;
        blocker.cost = 25;
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(1, 0, 0),
                new Planner.Options().maxDrop(0).allowBreaking(true));
        assertEquals(NavStatus.FOUND, finish(planner));
        Path path = planner.getPath();
        assertEquals(1, path.step(1).actionCount());
        Action action = path.step(1).action(0);
        assertEquals(Action.Type.BREAK_BLOCK, action.type);
        assertEquals(77, action.token);
        assertEquals(1, action.x);
        assertEquals(1, action.y);
    }

    @Test
    void continuesThroughMultipleObstructionsAfterEachEntryBreak() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        addObstruction(terrain.stance(1, 0, 0), 1, 1, 11);
        addObstruction(terrain.stance(2, 0, 0), 2, 1, 12);
        terrain.stance(3, 0, 0).fullSupport = true;
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(3, 0, 0),
                new Planner.Options().maxDrop(0).allowBreaking(true));

        assertEquals(NavStatus.FOUND, finish(planner));
        Path path = planner.getPath();
        assertEquals(4, path.length());
        assertEquals(11, path.step(1).action(0).token);
        assertEquals(12, path.step(2).action(0).token);
        assertTrue(terrain.sawSourceBreakCells, "swept checks must ignore only obstructions mined on entry");
    }

    @Test
    void expansionBudgetAndCancellationAreRespected() {
        FakeTerrain terrain = FakeTerrain.infiniteFloor();
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(12, 0, 9), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.IN_PROGRESS, planner.advance(0, Long.MAX_VALUE));
        assertEquals(0, planner.getExpandedNodes());
        assertEquals(NavStatus.IN_PROGRESS, planner.advance(1, Long.MAX_VALUE));
        assertEquals(1, planner.getExpandedNodes());
        planner.cancel();
        assertEquals(NavStatus.CANCELLED, planner.getStatus());
    }

    @Test
    void relevantTerrainRevisionMakesAnIncrementalSearchStale() {
        FakeTerrain terrain = FakeTerrain.infiniteFloor();
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(12, 0, 9), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.IN_PROGRESS, planner.advance(1, Long.MAX_VALUE));
        terrain.revision++;
        assertEquals(NavStatus.STALE, planner.advance(1, Long.MAX_VALUE));
    }

    @Test
    void returnsPartialLimitAtNodeCap() {
        FakeTerrain terrain = FakeTerrain.infiniteFloor();
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(1_000, 0, 1_000),
                new Planner.Options().maxNodes(64).maxDrop(0));
        assertEquals(NavStatus.PARTIAL_LIMIT, finish(planner));
        assertEquals(64, planner.getDiscoveredNodes());
        assertNotNull(planner.getPath());
        assertTrue(planner.getPath().length() > 1);
    }

    @Test
    void partialRouteMovesAlongAValidatedDetourEvenWhenItDoesNotReduceHeuristic() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.eastDetour = true;
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(-100, 0, 0),
                new Planner.Options().maxNodes(64).maxDrop(0));
        assertEquals(NavStatus.PARTIAL_LIMIT, finish(planner));
        assertTrue(planner.getPath().length() > 1);
        assertEquals(1, planner.getPath().step(1).x);
    }

    @Test
    void bridgeSearchReservesEveryPlacementAndNeverOverspends() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(1, 0, 0);
        terrain.stance(2, 0, 0);
        terrain.stance(3, 0, 0).fullSupport = true;

        Planner insufficient = planner(terrain, 0, 0, 0, Goal.exact(3, 0, 0),
                new Planner.Options().maxDrop(0).allowBuilding(true).placements(1, 42));
        assertEquals(NavStatus.NO_PATH, finish(insufficient));

        Planner sufficient = planner(terrain, 0, 0, 0, Goal.exact(3, 0, 0),
                new Planner.Options().maxDrop(0).allowBuilding(true).placements(2, 42));
        assertEquals(NavStatus.FOUND, finish(sufficient));
        Path path = sufficient.getPath();
        assertEquals(2, path.placementsReserved);
        assertEquals(Path.Movement.BRIDGE, path.step(1).movement);
        assertEquals(Action.Type.PLACE_BLOCK, path.step(1).action(0).type);
        assertEquals(42, path.step(1).action(0).token);
        assertEquals(2, path.step(2).action(0).x);
        assertEquals(4, path.length());
    }

    @Test
    void pathCollectionsAreDefensiveCopies() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(1, 0, 0).fullSupport = true;
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(1, 0, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.FOUND, finish(planner));
        Path path = planner.getPath();
        Path.Step expected = path.step(1);
        Path.Step[] copy = path.steps();
        copy[1] = path.step(0);
        assertSame(expected, path.step(1));
        Action[] actions = path.step(0).actions();
        assertEquals(0, actions.length);
    }

    @Test
    void heuristicIsConsistentForEveryPrimitiveMovementAndArrivalRegion() {
        var edges = new java.util.ArrayList<int[]>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            edges.add(new int[] {dx, 0, dz, dx != 0 && dz != 0 ? 14 : 10});
            if (dx != 0 && dz != 0) continue;
            edges.add(new int[] {dx, 1, dz, 18});
            for (int drop = 1; drop <= 3; drop++) edges.add(new int[] {dx, -drop, dz, 10 + drop * 4});
            edges.add(new int[] {dx * 2, 0, dz * 2, 25});
            edges.add(new int[] {dx * 3, 0, dz * 3, 34});
        }
        edges.add(new int[] {0, 1, 0, 17});
        edges.add(new int[] {0, -1, 0, 17});
        for (Goal goal : java.util.List.of(Goal.exact(0, 0, 0), Goal.near(0, 0, 0, 2))) {
            for (int x = -6; x <= 6; x++) for (int y = -6; y <= 6; y++) for (int z = -6; z <= 6; z++) {
                long h = goal.heuristic(x, y, z);
                assertTrue(h >= 0);
                if (goal.matches(x, y, z)) assertEquals(0, h);
                for (int[] edge : edges) {
                    assertTrue(h <= edge[3] + goal.heuristic(x + edge[0], y + edge[1], z + edge[2]),
                        "Heuristic must not overestimate any movement edge");
                }
            }
        }
    }

    @Test
    void straightLoadedRoutesDoNotExpandThousandsOfUnrelatedStances() {
        Planner planner = planner(FakeTerrain.infiniteFloor(), 0, 0, 0, Goal.exact(80, 0, 0), new Planner.Options());
        assertEquals(NavStatus.FOUND, finish(planner));
        assertEquals(800, planner.getPath().cost);
        assertTrue(planner.getExpandedNodes() <= 100, "An open 80-block route should stay close to its 81 stances");
    }

    private static Planner planner(FakeTerrain terrain, int x, int y, int z, Goal goal, Planner.Options options) {
        return new Planner(terrain, x, y, z, goal, options);
    }

    private static void addObstruction(StanceProbe probe, int x, int y, int token) {
        probe.fullSupport = true;
        probe.bodyClear = false;
        probe.breakCount = 1;
        BreakTarget target = probe.breakTargets[0];
        target.x = x; target.y = y; target.z = 0;
        target.stateToken = token; target.cost = 20;
    }

    private static NavStatus finish(Planner planner) {
        NavStatus status = planner.getStatus();
        int guard = 0;
        while (status == NavStatus.IN_PROGRESS && guard++ < 100) status = planner.advance(1_000, Long.MAX_VALUE);
        assertTrue(guard < 100, "search did not terminate within the test guard");
        return status;
    }

    private static final class FakeTerrain implements Terrain {
        final Map<Long, StanceProbe> stances = new HashMap<>();
        boolean infiniteFloor;
        boolean eastDetour;
        boolean motionClear = true;
        boolean rejectParkourArc;
        boolean sawSourceBreakCells;
        double maximumArc;
        int motionChecks;
        long revision;

        static FakeTerrain infiniteFloor() {
            FakeTerrain terrain = new FakeTerrain();
            terrain.infiniteFloor = true;
            return terrain;
        }

        StanceProbe stance(int x, int y, int z) {
            long key = Position.pack(x, y, z);
            return stances.computeIfAbsent(key, ignored -> {
                StanceProbe probe = new StanceProbe();
                probe.loaded = true;
                probe.bodyClear = true;
                probe.hazard = false;
                return probe;
            });
        }

        @Override public void probeStance(int x, int y, int z, StanceProbe out) {
            if (eastDetour) {
                out.clear();
                if (y == 0 && (x > 0 || (x == 0 && z == 0))) {
                    out.loaded = true;
                    out.bodyClear = true;
                    out.fullSupport = true;
                    out.hazard = false;
                } else if (y == 0 && x == 0) {
                    out.loaded = true;
                    out.bodyClear = false;
                    out.fullSupport = false;
                    out.hazard = false;
                }
                return;
            }
            StanceProbe stored = stances.get(Position.pack(x, y, z));
            if (stored == null && infiniteFloor && y == 0) {
                out.clear();
                out.loaded = true;
                out.bodyClear = true;
                out.fullSupport = true;
                out.hazard = false;
                return;
            }
            if (stored == null) { out.clear(); return; }
            out.copyFrom(stored);
        }

        @Override public boolean isMotionClear(double fromX, double fromY, double fromZ,
                                               double toX, double toY, double toZ,
                                               double arcHeight, StanceProbe destinationAfterBreak) {
            motionChecks++;
            maximumArc = Math.max(maximumArc, arcHeight);
            if (rejectParkourArc && arcHeight > 1.0) return false;
            return motionClear;
        }

        @Override public boolean isMotionClear(double fromX, double fromY, double fromZ,
                                               double toX, double toY, double toZ,
                                               double arcHeight, StanceProbe sourceAfterBreak,
                                               StanceProbe destinationAfterBreak) {
            if (sourceAfterBreak.breakCount > 0) sawSourceBreakCells = true;
            motionChecks++;
            maximumArc = Math.max(maximumArc, arcHeight);
            if (rejectParkourArc && arcHeight > 1.0) return false;
            return motionClear;
        }

        @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe destination, int index) {
            return index >= 0 && index < destination.breakCount;
        }

        @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz,
                                                    int blockItemToken, boolean supportWasPlanned) {
            return blockItemToken == 42 && by == y - 1 && bz == z && bx == x + 1;
        }

        @Override public long revision() { return revision; }
    }
}
