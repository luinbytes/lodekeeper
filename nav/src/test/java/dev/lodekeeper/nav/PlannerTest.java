package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class PlannerTest {
    @Test
    void timedSearchReturnsOnlyAValidatedForwardPrefixAndHonorsInvalidation() {
        FakeTerrain terrain = new FakeTerrain();
        for (int x = 0; x <= 20; x++) terrain.stance(x, 0, 0).fullSupport = true;
        Planner search = planner(terrain, 0, 0, 0, Goal.exact(20, 0, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.IN_PROGRESS, search.advance(3, Long.MAX_VALUE));
        assertEquals(NavStatus.PARTIAL_LIMIT, search.finishPartial(2));
        assertNotNull(search.getPath());
        assertEquals(4, search.getPath().length());
        for (int i = 0; i < 4; i++) {
            Path.Step step = search.getPath().step(i);
            assertEquals(i, step.x);
            assertEquals(0, step.z);
            assertEquals(0, step.feetY16);
            assertEquals(0, step.actionCount());
            if (i > 0) assertEquals(Path.Movement.WALK, step.movement);
        }
        Planner tooEarly = planner(terrain, 0, 0, 0, Goal.exact(20, 0, 0), new Planner.Options());
        tooEarly.advance(1, Long.MAX_VALUE);
        assertEquals(NavStatus.IN_PROGRESS, tooEarly.finishPartial(2));
        assertNull(tooEarly.getPath(), "a one-cell shuffle cannot masquerade as material progress");
        Planner stale = planner(terrain, 0, 0, 0, Goal.exact(20, 0, 0), new Planner.Options());
        stale.advance(3, Long.MAX_VALUE);
        terrain.revision++;
        assertEquals(NavStatus.STALE, stale.finishPartial(2));
        assertNull(stale.getPath());
    }

    @Test
    void partialCutoffPreservesSearchThroughAnInitialDetour() {
        FakeTerrain terrain = new FakeTerrain();
        for (int z = 0; z <= 8; z++) {
            terrain.stance(0, 0, z).fullSupport = true;
            terrain.stance(4, 0, z).fullSupport = true;
        }
        for (int x = 0; x <= 4; x++) terrain.stance(x, 0, 8).fullSupport = true;
        Planner search = planner(terrain, 0, 0, 0, Goal.exact(4, 0, 0),
                new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.IN_PROGRESS, search.advance(3, Long.MAX_VALUE));
        long expanded = search.getExpandedNodes();
        assertEquals(NavStatus.IN_PROGRESS, search.finishPartial(2));
        assertNull(search.getPath());
        assertEquals(expanded, search.getExpandedNodes());
        assertEquals(NavStatus.FOUND, finish(search));
        Path.Step end = search.getPath().step(search.getPath().length() - 1);
        assertEquals(4, end.x);
        assertEquals(0, end.z);
    }

    @Test
    void visualizationIsBoundedImmutableAndDoesNotExpandTheSearch() {
        FakeTerrain terrain = new FakeTerrain();
        for (int x = 0; x <= 350; x++) terrain.stance16(x, -1, 0).surfaceSupport = true;
        for (int x = 0; x <= 350; x++) terrain.groundedHeights(x, 0, -1);
        Planner search = Planner.fromFeetY16(terrain, 0, -1, 0, Goal.exact16(350, -1, 0),
                new Planner.Options().maxNodes(1024).maxDrop(0));
        search.advance(300, Long.MAX_VALUE);
        long expanded = search.getExpandedNodes();
        NavigationSnapshot view = search.snapshot(1, 500, 2, 0, true);
        assertTrue(view.nodeCount() <= 256);
        assertTrue(view.nodeCount() > 0);
        assertEquals(-1 / 16.0, view.nodeY(0));
        long original = view.nodePositions()[0];
        view.nodePositions()[0] = 1234;
        assertEquals(original, view.nodePositions()[0]);
        view.nodeFractions()[0] = 0;
        assertEquals(-1 / 16.0, view.nodeY(0));
        boolean wasClosed = view.nodeIsClosed(0);
        view.nodeClosed()[0] = !wasClosed;
        assertEquals(wasClosed, view.nodeIsClosed(0));
        assertEquals(expanded, search.getExpandedNodes());
        search.cancel();
        assertEquals(0, search.snapshot(1, 500, 2, 0, true).nodeCount());
        assertEquals(-1 / 16.0, view.nodeY(0), "published observations survive subsequent planner changes");
    }

    @Test
    void exactHeightHeuristicIncludesVerticalWorkWithoutOverpricingStairsOrDrops() {
        assertEquals(17, Goal.exact16(0, 16, 0).heuristic16(0, 0, 0));
        assertEquals(22, Goal.exact16(0, -48, 0).heuristic16(0, 0, 0));
        assertEquals(8, Goal.exact16(0, 8, 0).heuristic16(0, 0, 0));
        assertEquals(0, Goal.near16(0, 8, 0, 8).heuristic16(0, 0, 0));
        for (int rise = 1; rise <= 16; rise++) {
            long stepCost = 10 + (7L * rise + 15) / 16;
            assertTrue(Goal.exact16(0, rise, 0).heuristic16(0, 0, 0) <= stepCost);
        }
        Goal options = Goal.anyOf16(new long[]{Position.pack(0, -1, 0), Position.pack(0, 2, 0)},
                new byte[]{15, 0});
        assertEquals(0, options.heuristic16(0, -1, 0));
    }

    @Test
    void anyOfFindsAReachableStanceWhenTheFirstCandidateIsDisconnected() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(1, 0, 0).fullSupport = true;
        terrain.stance(3, 0, 0).fullSupport = true;
        Goal goal = Goal.anyOf(Position.pack(3, 0, 0), Position.pack(1, 0, 0), Position.pack(3, 0, 0));

        Planner planner = planner(terrain, 0, 0, 0, goal, new Planner.Options().maxDrop(0));

        assertEquals(NavStatus.FOUND, finish(planner));
        Path path = planner.getPath();
        assertEquals(1, path.step(path.length() - 1).x);
        assertEquals(3, goal.x, "the first supplied point remains the diagnostic coordinate");
    }

    @Test
    void anyOfReturnsNoPathWhenEveryCandidateIsBlocked() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(1, 0, 0).fullSupport = true;
        terrain.stances.get(Position.pack(1, 0, 0)).hazard = true;
        terrain.stance(2, 0, 0).fullSupport = true;
        terrain.stances.get(Position.pack(2, 0, 0)).hazard = true;

        Planner planner = planner(terrain, 0, 0, 0,
                Goal.anyOf(Position.pack(1, 0, 0), Position.pack(2, 0, 0)),
                new Planner.Options().maxDrop(0));

        assertEquals(NavStatus.NO_PATH, finish(planner));
    }

    @Test
    void anyOfCopiesInputsEnforcesItsLimitAndAcceptsPackedCoordinateBoundaries() {
        long first = Position.pack(2, 0, 0);
        long second = Position.pack(1, 0, 0);
        long[] candidates = {first, second, first};
        Goal copied = Goal.anyOf(candidates);
        candidates[0] = Position.pack(3, 0, 0);

        assertTrue(copied.matches(2, 0, 0));
        assertTrue(copied.matches(1, 0, 0));
        assertFalse(copied.matches(3, 0, 0));
        assertEquals(2, copied.x);
        assertThrows(IllegalArgumentException.class, () -> Goal.anyOf());
        assertThrows(IllegalArgumentException.class, () -> Goal.anyOf(new long[129]));
        long[] atLimit = new long[128];
        for (int i = 0; i < atLimit.length; i++) atLimit[i] = Position.pack(i, 0, 0);
        assertDoesNotThrow(() -> Goal.anyOf(atLimit));

        int minX = -33_554_432;
        int minY = -2_048;
        int minZ = -33_554_432;
        int maxX = 33_554_431;
        int maxY = 2_047;
        int maxZ = 33_554_431;
        Goal boundaries = Goal.anyOf(Position.pack(minX, minY, minZ), Position.pack(maxX, maxY, maxZ));
        assertTrue(boundaries.matches(minX, minY, minZ));
        assertTrue(boundaries.matches(maxX, maxY, maxZ));
        assertFalse(boundaries.matches(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Position.pack(maxX + 1, maxY, maxZ));
    }

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
    void fractionalNegativeFeetHeightsRemainDistinctAndHazardsBlockTheirExactStance() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance16(0, -1, 0).surfaceSupport = true;
        terrain.stance16(1, -8, 0).surfaceSupport = true;
        StanceProbe sameFloorHazard = terrain.stance16(1, -1, 0);
        sameFloorHazard.surfaceSupport = true;
        sameFloorHazard.hazard = true;
        terrain.groundedHeights(1, 0, -8, -1);

        Planner planner = Planner.fromFeetY16(terrain, 0, -1, 0,
                Goal.exact16(1, -8, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.FOUND, finish(planner));
        Path path = planner.getPath();
        assertEquals(-1, path.step(0).y, "negative heights use floorDiv, not truncation toward zero");
        assertEquals(-1, path.step(0).feetY16);
        assertEquals(-8, path.step(1).feetY16);
        assertEquals(-1, path.step(1).y);
        assertEquals(-0.5, path.step(1).feetY(), 0.0);
        assertEquals(1, terrain.exactProbeCounts.get(new FeetKey(1, -8, 0)));
        assertEquals(1, terrain.exactProbeCounts.get(new FeetKey(1, -1, 0)),
                "different fractions at one packed floor coordinate must use distinct cache entries");
        assertEquals(1, terrain.groundedChecks);

        Goal alternatives = Goal.anyOf16(
                new long[] {Position.pack(1, -1, 0), Position.pack(1, -1, 0)},
                new byte[] {8, 15});
        assertTrue(alternatives.matches16(1, -8, 0));
        assertTrue(alternatives.matches16(1, -1, 0));
        assertFalse(alternatives.matches16(1, -7, 0));

        FakeTerrain onlyHazard = new FakeTerrain();
        onlyHazard.stance16(0, -8, 0).surfaceSupport = true;
        onlyHazard.stance16(1, -8, 0).surfaceSupport = true;
        onlyHazard.stance16(1, -1, 0).surfaceSupport = true;
        onlyHazard.stances16.get(new FeetKey(1, -1, 0)).hazard = true;
        onlyHazard.groundedHeights(1, 0, -8, -1);
        Planner blocked = Planner.fromFeetY16(onlyHazard, 0, -8, 0,
                Goal.exact16(1, -1, 0), new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.NO_PATH, finish(blocked));
    }

    @Test
    void groundedWalkKeepsDistinctFractionsAtTheSamePackedPosition() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance16(0, -1, 0).surfaceSupport = true;
        terrain.stance16(1, -8, 0).surfaceSupport = true;
        terrain.stance16(1, -1, 0).surfaceSupport = true;
        terrain.groundedHeights(1, 0, -8, -1);

        Planner planner = Planner.fromFeetY16(terrain, 0, -1, 0,
                Goal.exact16(1, -1, 0), new Planner.Options().maxDrop(0));

        assertEquals(NavStatus.FOUND, finish(planner));
        assertEquals(10, planner.getPath().cost);
        assertEquals(2, planner.getPath().length());
        assertEquals(-1, planner.getPath().step(1).feetY16);
    }

    @Test
    void groundedWalkAddsSevenCostPerFullBlockRiseAndKeepsLegacyMediumStarts() {
        FakeTerrain stairs = new FakeTerrain();
        stairs.stance(0, 0, 0).fullSupport = true;
        stairs.stance(1, 1, 0).fullSupport = true;
        stairs.groundedHeights(1, 0, 16);
        Planner uphill = planner(stairs, 0, 0, 0, Goal.exact(1, 1, 0),
                new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.FOUND, finish(uphill));
        assertEquals(Path.Movement.WALK, uphill.getPath().step(1).movement);
        assertEquals(17, uphill.getPath().cost,
                "a one-block rising walk keeps the minimum vertical cost bound");

        FakeTerrain water = new FakeTerrain();
        water.stance(0, 0, 0).water = true;
        water.stance(1, 0, 0).water = true;
        Planner swim = planner(water, 0, 0, 0, Goal.exact(1, 0, 0),
                new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.FOUND, finish(swim));
        assertEquals(Path.Movement.SWIM, swim.getPath().step(1).movement);
    }

    @Test
    void dominatedGroundedWalksSkipSweepsAndPreserveRouteActions() {
        FakeTerrain terrain = FakeTerrain.infiniteFloor();
        StanceProbe obstruction = terrain.stance(0, 0, 3);
        obstruction.fullSupport = true;
        obstruction.bodyClear = false;
        obstruction.breakCount = 1;
        BreakTarget blocker = obstruction.breakTargets[0];
        blocker.x = 0;
        blocker.y = 1;
        blocker.z = 3;
        blocker.stateToken = 77;
        blocker.cost = 1;
        Planner planner = planner(terrain, 0, 0, 0, Goal.exact(0, 0, 5),
                new Planner.Options().maxDrop(0).allowBreaking(true));

        assertEquals(NavStatus.IN_PROGRESS, planner.advance(2, Long.MAX_VALUE));
        assertEquals(2, planner.getExpandedNodes());
        assertEquals(11, terrain.groundedChecks,
                "only three successors from the second stance are new exact states");

        assertEquals(NavStatus.FOUND, finish(planner));
        Path path = planner.getPath();
        assertEquals(51, path.cost);
        assertEquals(6, path.length());
        for (int i = 0; i < path.length(); i++) {
            assertEquals(0, path.step(i).x);
            assertEquals(i, path.step(i).z);
            assertEquals(i == 3 ? 1 : 0, path.step(i).actionCount());
        }
        Action breakAction = path.step(3).action(0);
        assertEquals(Action.Type.BREAK_BLOCK, breakAction.type);
        assertEquals(77, breakAction.token);
        assertEquals(0, breakAction.x);
        assertEquals(1, breakAction.y);
        assertEquals(3, breakAction.z);
    }

    @Test
    void mediumCanExitToSupportedBankAndPlannedBridgeCanBeWalkedOff() {
        FakeTerrain waterBank = new FakeTerrain();
        waterBank.stance(0, 0, 0).water = true;
        waterBank.stance(1, 0, 0).fullSupport = true;
        Planner fromWater = planner(waterBank, 0, 0, 0, Goal.exact(1, 0, 0),
                new Planner.Options().maxDrop(0));
        assertEquals(NavStatus.FOUND, finish(fromWater));
        assertEquals(Path.Movement.SWIM, fromWater.getPath().step(1).movement,
                "a full-block bank remains reachable as a medium transition");

        FakeTerrain climbBank = new FakeTerrain();
        climbBank.stance(0, 0, 0).climbable = true;
        climbBank.stance(1, 0, 0).fullSupport = true;
        Planner fromClimb = planner(climbBank, 0, 0, 0, Goal.exact(1, 0, 0),
                new Planner.Options().maxDrop(0).allowSwimming(false));
        assertEquals(NavStatus.FOUND, finish(fromClimb));
        assertEquals(Path.Movement.CLIMB, fromClimb.getPath().step(1).movement,
                "a full-block bank remains reachable from a climbable start");

        FakeTerrain bridgeBank = new FakeTerrain();
        bridgeBank.stance(0, 0, 0).fullSupport = true;
        bridgeBank.stance(1, 0, 0); // Empty until the planned placement executes.
        bridgeBank.stance(2, 0, 0).fullSupport = true;
        bridgeBank.requirePhysicalGroundedSource = true;
        Planner acrossBridge = planner(bridgeBank, 0, 0, 0, Goal.exact(2, 0, 0),
                new Planner.Options().maxDrop(0).allowBuilding(true).placements(1, 42));
        assertEquals(NavStatus.FOUND, finish(acrossBridge));
        Path path = acrossBridge.getPath();
        assertEquals(3, path.length());
        assertEquals(Path.Movement.BRIDGE, path.step(1).movement);
        assertEquals(Path.Movement.WALK, path.step(2).movement);
        assertFalse(bridgeBank.stances.get(Position.pack(1, 0, 0)).fullSupport,
                "planning must leave the unplaced bridge tile physically unsupported in its snapshot");
    }

    @Test
    void groundedCandidateAndTransitionCapsReturnPartialLimit() {
        FakeTerrain terrain = new FakeTerrain();
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.supportEveryFractionalStance = true;
        int[] heights = new int[33];
        for (int i = 0; i < heights.length; i++) heights[i] = i - 16;
        for (int[] direction : new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
            terrain.groundedHeights(direction[0], direction[1], heights);
        }
        Planner planner = Planner.fromFeetY16(terrain, 0, 0, 0,
                Goal.exact(10, 10, 0), new Planner.Options().maxNodes(128).maxDrop(0));
        assertEquals(NavStatus.PARTIAL_LIMIT, finish(planner));
        assertTrue(planner.getDiscoveredNodes() > 1);
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
        for (Goal goal : java.util.List.of(Goal.exact(0, 0, 0), Goal.near(0, 0, 0, 2),
                Goal.anyOf(Position.pack(0, 0, 0), Position.pack(3, 0, 0)))) {
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
        final Map<FeetKey, StanceProbe> stances16 = new HashMap<>();
        final Map<FeetKey, Integer> exactProbeCounts = new HashMap<>();
        final Map<Long, int[]> groundedHeights = new HashMap<>();
        boolean infiniteFloor;
        boolean eastDetour;
        boolean motionClear = true;
        boolean rejectParkourArc;
        boolean sawSourceBreakCells;
        boolean supportEveryFractionalStance;
        boolean requirePhysicalGroundedSource;
        double maximumArc;
        int motionChecks;
        int groundedChecks;
        int rejectedGroundedProofsWithoutSourceSupport;
        int lastFromFeetY16, lastToFeetY16;
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

        StanceProbe stance16(int x, int feetY16, int z) {
            FeetKey key = new FeetKey(x, feetY16, z);
            return stances16.computeIfAbsent(key, ignored -> {
                StanceProbe probe = new StanceProbe();
                probe.loaded = true;
                probe.bodyClear = true;
                probe.hazard = false;
                return probe;
            });
        }

        void groundedHeights(int x, int z, int... values) {
            groundedHeights.put(Position.pack(x, 0, z), values.clone());
        }

        @Override public boolean collectGroundedStances(int x, int referenceFeetY16, int z,
                                                        GroundedStanceBuffer out) {
            int[] values = groundedHeights.get(Position.pack(x, 0, z));
            if (values == null) return Terrain.super.collectGroundedStances(x, referenceFeetY16, z, out);
            out.clear();
            for (int value : values) out.add(value);
            return out.isComplete();
        }

        @Override public boolean probeStance16(int x, int feetY16, int z, StanceProbe out) {
            FeetKey key = new FeetKey(x, feetY16, z);
            exactProbeCounts.merge(key, 1, Integer::sum);
            StanceProbe stored = stances16.get(key);
            if (stored != null) { out.copyFrom(stored); return out.loaded; }
            if (supportEveryFractionalStance) {
                out.clear();
                out.loaded = true;
                out.bodyClear = true;
                out.hazard = false;
                out.surfaceSupport = true;
                return true;
            }
            if (Math.floorMod(feetY16, 16) == 0) {
                probeStance(x, Math.floorDiv(feetY16, 16), z, out);
                return out.loaded;
            }
            out.clear();
            return false;
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

        @Override public boolean isGroundedWalkClear(double fromX, int fromFeetY16, double fromZ,
                                                     double toX, int toFeetY16, double toZ,
                                                     StanceProbe sourceAfterBreak,
                                                     StanceProbe destinationAfterBreak) {
            groundedChecks++;
            motionChecks++;
            lastFromFeetY16 = fromFeetY16;
            lastToFeetY16 = toFeetY16;
            if (sourceAfterBreak.breakCount > 0) sawSourceBreakCells = true;
            if (requirePhysicalGroundedSource
                    && (sourceAfterBreak == null || !sourceAfterBreak.hasGroundSupport())) {
                rejectedGroundedProofsWithoutSourceSupport++;
                return false;
            }
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

    private record FeetKey(int x, int feetY16, int z) {}
}
