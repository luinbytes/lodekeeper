package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class PathEdgeValidatorTest {
    @Test
    void changedSweptHazardIsRejectedFromThePlayersCurrentFeet() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step destination = step(1, 0, 0, Path.Movement.WALK);
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(1, 0, 0).fullSupport = true;

        assertTrue(edge(terrain, source, destination, 0.5, 0, 0.5, true, true));

        // An unrelated observed revision is accepted when the live edge remains clear.
        terrain.revision++;
        assertTrue(edge(terrain, source, destination, 0.68, 0.06, 0.5, false, true));

        // A watched intermediate cell then changes while the player is already partway through.
        terrain.revision++;
        terrain.sweepClear = false;
        assertFalse(edge(terrain, source, destination, 0.82, 0.08, 0.5, false, true));
        assertEquals(0.82, terrain.lastFromX, 0.0001);
        assertEquals(0.08, terrain.lastFromY, 0.0001);
    }

    @Test
    void supportLossCannotBeReplacedByWaterForAWalkEdge() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step destination = step(1, 0, 0, Path.Movement.WALK);
        terrain.stance(0, 0, 0).fullSupport = true;
        StanceProbe endpoint = terrain.stance(1, 0, 0);
        endpoint.water = true;
        endpoint.fullSupport = false;

        assertFalse(edge(terrain, source, destination, 0.5, 0, 0.5, true, true));

        terrain.revision++;
        destination = step(1, 0, 0, Path.Movement.SWIM);
        assertTrue(edge(terrain, source, destination, 0.5, 0, 0.5, true, true));
    }

    @Test
    void continuousMediumFeetKeepLegacyCellProbeAndActualBodySafety() {
        VolumeTerrain waterTerrain = new VolumeTerrain();
        waterTerrain.stance(0, 0, 0).water = true;
        waterTerrain.stance(1, 0, 0).water = true;

        assertTrue(PathEdgeValidator.isCurrentMotionSafe(waterTerrain, Path.Movement.SWIM,
                0.5, 0.2, 0.5, true, new StanceProbe(), new StanceProbe()));
        Path.Step start = step(0, 0, 0, Path.Movement.START);
        Path.Step swim = step(1, 0, 0, Path.Movement.SWIM);
        for (double height : new double[] {0.0625, 0.125, 0.2, 0.5}) {
            assertTrue(edge(waterTerrain, start, swim, 0.5, height, 0.5, true, true),
                    "continuous media must stay valid both on and between exact sixteenths");
            assertTrue(PathEdgeValidator.isCurrentMotionSafe(waterTerrain, Path.Movement.SWIM,
                    0.5, height, 0.5, true, new StanceProbe(), new StanceProbe()));
            assertTrue(PathEdgeValidator.isCurrentStanceSafe(waterTerrain, start,
                    0.5, height, 0.5, new StanceProbe(), new StanceProbe()),
                    "continuous medium obstruction actions retain actual body validation");
        }

        waterTerrain.hazards.add(new Box(0.4, 0.1, 0.4, 0.6, 0.8, 0.6));
        assertFalse(PathEdgeValidator.isCurrentMotionSafe(waterTerrain, Path.Movement.SWIM,
                0.5, 0.2, 0.5, true, new StanceProbe(), new StanceProbe()),
                "the actual player box still rejects a nearby hazard at continuous Y");
        assertFalse(edge(waterTerrain, start, swim, 0.5, 0.2, 0.5, true, true),
                "the medium-cell fallback must not skip the actual edge sweep");
        assertFalse(PathEdgeValidator.isCurrentStanceSafe(waterTerrain, start,
                0.5, 0.2, 0.5, new StanceProbe(), new StanceProbe()));

        VolumeTerrain climbTerrain = new VolumeTerrain();
        climbTerrain.stance(0, 0, 0).climbable = true;
        assertTrue(PathEdgeValidator.isCurrentMotionSafe(climbTerrain, Path.Movement.CLIMB,
                0.5, 0.2, 0.5, true, new StanceProbe(), new StanceProbe()));
        climbTerrain.obstacles.add(new Box(0.4, 0.1, 0.4, 0.6, 0.8, 0.6));
        assertFalse(PathEdgeValidator.isCurrentMotionSafe(climbTerrain, Path.Movement.CLIMB,
                0.5, 0.2, 0.5, true, new StanceProbe(), new StanceProbe()),
                "the actual player box still rejects a body obstruction while climbing");
    }

    @Test
    void partialSourceSupportCannotLaunchStrictMovementsAfterTerrainChanges() {
        for (Path.Movement movement : new Path.Movement[] {
                Path.Movement.JUMP, Path.Movement.DROP, Path.Movement.PARKOUR, Path.Movement.BRIDGE}) {
            FakeTerrain terrain = new FakeTerrain();
            Path.Step source = step(0, 0, 0, Path.Movement.START);
            int landingY = movement == Path.Movement.JUMP ? 1 : movement == Path.Movement.DROP ? -1 : 0;
            Path.Step destination = step(1, landingY, 0, movement);
            terrain.stance(0, 0, 0).fullSupport = true;
            terrain.stance(1, landingY, 0).fullSupport = true;
            assertTrue(edge(terrain, source, destination, 0.5, 0, 0.5, true, true));

            StanceProbe changedSource = terrain.stance(0, 0, 0);
            changedSource.fullSupport = false;
            changedSource.surfaceSupport = true;
            assertFalse(edge(terrain, source, destination, 0.5, 0, 0.5, true, true),
                    "partial support cannot replace the full launch surface for " + movement);
            assertFalse(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                    0.5, 0, 0.5, 0.5, 0, 0.5, true, true,
                    new StanceProbe(), new StanceProbe()));
        }
    }

    @Test
    void parkourPermissionAndSweptLoadedStateAreCheckedLive() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step destination = step(3, 0, 0, Path.Movement.PARKOUR);
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(3, 0, 0).fullSupport = true;

        assertFalse(edge(terrain, source, destination, 0.5, 0, 0.5, true, false));
        assertTrue(edge(terrain, source, destination, 0.5, 0, 0.5, true, true));

        terrain.revision++;
        terrain.sweepLoaded = false;
        assertFalse(edge(terrain, source, destination, 1.6, 0.4, 0.5, false, true));
        assertEquals(1.35, terrain.lastArc, 0.0001);
    }

    @Test
    void midairParkourRevisionChecksRemainingOriginalArcUnderLowCeiling() {
        VolumeTerrain terrain = new VolumeTerrain();
        Path.Step source = step(0, 64, 0, Path.Movement.START);
        Path.Step destination = step(3, 64, 0, Path.Movement.PARKOUR);
        terrain.stance(3, 64, 0).fullSupport = true;
        terrain.obstacles.add(new Box(0, 67.5, 0, 4, 68.5, 1));

        assertTrue(terrain.isMotionClear(0.5, 64, 0.5, 3.5, 64, 0.5,
                1.35, new StanceProbe()));
        assertFalse(terrain.isMotionClear(2, 65.2, 0.5, 3.5, 64, 0.5,
                1.35, new StanceProbe()), "restarting a full arc midair creates a phantom second jump");
        assertTrue(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                0.5, 64, 0.5, 2, 65.2, 0.5, false, true,
                new StanceProbe(), new StanceProbe()));
    }

    @Test
    void midairContinuationIgnoresPastHazardsButRejectsHazardsAhead() {
        VolumeTerrain terrain = new VolumeTerrain();
        Path.Step source = step(0, 64, 0, Path.Movement.START);
        Path.Step destination = step(3, 64, 0, Path.Movement.PARKOUR);
        terrain.stance(3, 64, 0).fullSupport = true;
        terrain.hazards.add(new Box(0, 66, 0, 1.2, 67, 1));

        assertTrue(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                0.5, 64, 0.5, 2, 65.2, 0.5, false, true,
                new StanceProbe(), new StanceProbe()), "terrain behind projected progress is no longer in the trajectory");

        terrain.hazards.clear();
        terrain.hazards.add(new Box(2.4, 66, 0, 2.6, 66.5, 1));
        assertFalse(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                0.5, 64, 0.5, 2, 65.2, 0.5, false, true,
                new StanceProbe(), new StanceProbe()), "a collision in the remaining swept volume must stop input");
    }

    @Test
    void routeCorridorRejectsCorrectionOffAOneBlockLedge() {
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step destination = step(1, 0, 0, Path.Movement.WALK);

        assertTrue(PathEdgeValidator.isWithinEdgeCorridor(source, destination, 0.9, 0, 0.5));
        // A one-block platform at z=0 ends at z=1. At z=1.31 the 0.6-wide player box is
        // completely beyond its edge, despite terrain revisions remaining unchanged.
        assertFalse(PathEdgeValidator.isWithinEdgeCorridor(source, destination, 0.9, 0, 1.31));
    }

    @Test
    void routeCorridorUsesCapturedFractionalStartWithoutWideningCorrectionBounds() {
        Path.Step source = step(12, 64, 1, Path.Movement.START);
        Path.Step destination = step(12, 64, 2, Path.Movement.WALK);
        double startX = 12.270697567;
        double startY = 64.0;
        double startZ = 1.285615921;

        assertFalse(PathEdgeValidator.isWithinEdgeCorridor(source, destination, startX, startY, startZ),
                "the compatibility overload remains centered on the source block");
        assertTrue(PathEdgeValidator.isWithinEdgeCorridor(source, destination,
                startX, startY, startZ, startX, startY, startZ),
                "the newly captured actual start is progress zero even when it is off the block center");
        assertTrue(PathEdgeValidator.isWithinEdgeCorridor(source, destination,
                startX, startY, startZ, startX, startY, 1.6));
        assertFalse(PathEdgeValidator.isWithinEdgeCorridor(source, destination,
                startX, startY, startZ, startX + 0.8, startY, startZ),
                "a real lateral correction still exceeds the existing corridor bound");
    }

    @Test
    void pointProbeRejectsAdjacentHazardAfterSmallCorrectionWithoutTerrainRevision() {
        VolumeTerrain terrain = new VolumeTerrain();
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step destination = step(1, 0, 0, Path.Movement.WALK);
        terrain.stance(0, 0, 0).fullSupport = true;
        terrain.stance(0, 0, 1).fullSupport = true;
        terrain.hazards.add(new Box(0.8, 0, 1, 1.8, 1.8, 2)); // Adjacent lava volume.

        assertTrue(PathEdgeValidator.isWithinEdgeCorridor(source, destination, 0.9, 0, 1.15),
                "the small correction is within the coarse route corridor");
        assertTrue(PathEdgeValidator.isCurrentMotionSafe(terrain, Path.Movement.WALK,
                0.9, 0, 0.5, true, new StanceProbe(), new StanceProbe()));
        assertFalse(PathEdgeValidator.isCurrentMotionSafe(terrain, Path.Movement.WALK,
                0.9, 0, 1.15, true, new StanceProbe(), new StanceProbe()),
                "the player's current body now overlaps the adjacent hazard despite unchanged terrain revision");
    }

    @Test
    void pointProbeRequiresSupportOnlyWhileGrounded() {
        VolumeTerrain terrain = new VolumeTerrain();
        terrain.stance(0, 0, 0).fullSupport = false;

        assertFalse(PathEdgeValidator.isCurrentMotionSafe(terrain, Path.Movement.WALK,
                0.5, 0, 0.5, true, new StanceProbe(), new StanceProbe()));
        assertTrue(PathEdgeValidator.isCurrentMotionSafe(terrain, Path.Movement.WALK,
                0.5, 0, 0.5, false, new StanceProbe(), new StanceProbe()));
    }

    @Test
    void groundedLiveValidationKeepsNegativeSixteenthHeightAndRejectsOffGridFeet() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step16(0, -1, 0, Path.Movement.START);
        Path.Step destination = step16(1, -1, 0, Path.Movement.WALK);
        terrain.stance16(0, -1, 0).surfaceSupport = true;
        terrain.stance16(1, -1, 0).surfaceSupport = true;

        assertTrue(edge(terrain, source, destination, 0.51, -1.0 / 16.0, 0.5, true, true));
        assertEquals(-1, terrain.lastCurrentFeetY16,
                "live support queries must preserve the exact negative sixteenth height");
        assertEquals(0.51, terrain.lastCurrentFeetX, 0.0,
                "the live support adapter receives actual X rather than a centered replacement");
        assertEquals(1, terrain.groundedSweepChecks);

        assertFalse(edge(terrain, source, destination, 0.51, -0.055, 0.5, true, true),
                "feet too far from the sixteenth grid must not be floored into a grounded stance");
    }

    @Test
    void defaultTerrainSweepOverloadAllowsNullSourceProbe() {
        Terrain terrain = new VolumeTerrain();
        assertTrue(terrain.isMotionClear(0.5, 0, 0.5, 1.5, 0, 0.5,
                0, null, new StanceProbe()));
    }

    @Test
    void plannedBreakAndBridgeAreValidatedOnlyAfterTheirWorldActionsComplete() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step brokenDestination = step(1, 0, 0, Path.Movement.WALK);
        terrain.stance(0, 0, 0).fullSupport = true;
        StanceProbe broken = terrain.stance(1, 0, 0);
        broken.fullSupport = true;
        broken.bodyClear = false;
        broken.breakCount = 1;
        BreakTarget obstruction = broken.breakTargets[0];
        obstruction.x = 1; obstruction.y = 1; obstruction.z = 0;
        obstruction.stateToken = 77; obstruction.cost = 20;
        Action breakAction = new Action(Action.Type.BREAK_BLOCK, 1, 1, 0, 77);
        assertTrue(PathEdgeValidator.isBreakActionSafe(terrain, source, brokenDestination,
                breakAction, new StanceProbe()));
        terrain.revision++;
        broken.hazard = true;
        assertFalse(PathEdgeValidator.isBreakActionSafe(terrain, source, brokenDestination,
                breakAction, new StanceProbe()));
        broken.hazard = false;
        assertFalse(edge(terrain, source, brokenDestination, 0.5, 0, 0.5, true, true));

        // The executor has observed the requested block become air; its own revision is accepted
        // after a fresh sweep rather than causing a blanket route restart.
        terrain.revision++;
        broken.bodyClear = true;
        broken.breakCount = 0;
        assertTrue(edge(terrain, source, brokenDestination, 0.5, 0, 0.5, true, true));

        Path.Step bridgeDestination = step(1, 0, 0, Path.Movement.BRIDGE);
        broken.fullSupport = false;
        broken.water = true;
        assertFalse(edge(terrain, source, bridgeDestination, 0.5, 0, 0.5, true, true));

        // Only after the placed block is observed may this bridge stance pass its support policy.
        terrain.revision++;
        broken.fullSupport = true;
        broken.water = false;
        assertTrue(edge(terrain, source, bridgeDestination, 0.5, 0, 0.5, true, true));
    }

    @Test
    void newRouteMustStartSupportedButAnActiveMidairEdgeMayContinue() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step destination = step(1, 0, 0, Path.Movement.WALK);
        StanceProbe start = terrain.stance(0, 0, 0);
        start.fullSupport = true;
        terrain.stance(1, 0, 0).fullSupport = true;
        assertTrue(edge(terrain, source, destination, 0.5, 0, 0.5, true, true));

        terrain.revision++;
        start.fullSupport = false;
        assertFalse(edge(terrain, source, destination, 0.5, 0, 0.5, true, true));
        assertFalse(edge(terrain, source, destination, 0.5, 0, 0.5, true, false, true));

        // Mid-edge revision checks start from the live airborne feet position and do not demand
        // support under the original start node.
        assertTrue(edge(terrain, source, destination, 0.8, 0.4, 0.5, false, true));
    }

    @Test
    void bridgePlacementIsRejectedWhenItsSupportOrLandingPolicyChanges() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step(0, 0, 0, Path.Movement.START);
        Path.Step destination = step(1, 0, 0, Path.Movement.BRIDGE);
        Action place = new Action(Action.Type.PLACE_BLOCK, 1, -1, 0, 42);
        terrain.stance(0, 0, 0).fullSupport = true;
        StanceProbe landing = terrain.stance(1, 0, 0);

        assertTrue(PathEdgeValidator.isBridgeActionSafe(terrain, source, destination,
                place, new StanceProbe(), new StanceProbe()));

        terrain.revision++;
        landing.hazard = true;
        assertFalse(PathEdgeValidator.isBridgeActionSafe(terrain, source, destination,
                place, new StanceProbe(), new StanceProbe()));
        landing.hazard = false;
        terrain.stance(0, 0, 0).fullSupport = false;
        assertFalse(PathEdgeValidator.isBridgeActionSafe(terrain, source, destination,
                place, new StanceProbe(), new StanceProbe()));
    }

    private static boolean edge(Terrain terrain, Path.Step source, Path.Step destination,
                                double x, double y, double z, boolean checkSource, boolean allowParkour) {
        return edge(terrain, source, destination, x, y, z, checkSource, checkSource, allowParkour);
    }

    private static boolean edge(Terrain terrain, Path.Step source, Path.Step destination,
                                double x, double y, double z, boolean checkSource,
                                boolean requireSource, boolean allowParkour) {
        return PathEdgeValidator.isSafeEdge(terrain, source, destination, x, y, z,
                checkSource, requireSource, allowParkour, new StanceProbe(), new StanceProbe());
    }

    private static Path.Step step(int x, int y, int z, Path.Movement movement) {
        return new Path.Step(x, y, z, movement, new Action[0]);
    }

    private static Path.Step step16(int x, int feetY16, int z, Path.Movement movement) {
        return Path.Step.atFeetY16(x, feetY16, z, movement, new Action[0]);
    }

    @Test
    void jumpContinuationLiftsBeforeForwardMotionAndBoundsRemainingRise() {
        FakeTerrain terrain = new FakeTerrain();
        Path.Step source = step(0, 64, 0, Path.Movement.START);
        Path.Step destination = step(1, 65, 0, Path.Movement.JUMP);
        terrain.stance(1, 65, 0).fullSupport = true;
        assertTrue(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                .5, 64, .5, .7, 64.42, .5, false, true, new StanceProbe(), new StanceProbe()));
        assertEquals(.7, terrain.lastToX, 1e-9);
        assertEquals(65.35, terrain.lastToY, 1e-9);
        assertTrue(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                .5, 64, .5, .7, 65.2, .5, false, true, new StanceProbe(), new StanceProbe()));
        assertEquals(65.35, terrain.lastToY, 1e-9);
        assertEquals(1e-12, terrain.lastArc, 1e-15);
        assertEquals(1.5, terrain.lastToX, 1e-9);
        terrain.sweepClear = false;
        assertFalse(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                .5, 64, .5, .7, 64.42, .5, false, true, new StanceProbe(), new StanceProbe()));

        VolumeTerrain volume = new VolumeTerrain();
        volume.stance(1, 65, 0).fullSupport = true;
        volume.obstacles.add(new Box(0, 67.3, 0, 2, 68, 1));
        assertFalse(volume.isMotionClear(.7, 65.2, .5, 1.5, 65, .5, .85, new StanceProbe()));
        assertTrue(PathEdgeValidator.isSafeContinuation(volume, source, destination,
                .5, 64, .5, .7, 65.2, .5, false, true, new StanceProbe(), new StanceProbe()));

        VolumeTerrain apexCeiling = new VolumeTerrain();
        apexCeiling.stance(1, 65, 0).fullSupport = true;
        apexCeiling.obstacles.add(new Box(1.1, 67.1, 0, 1.12, 67.12, 1));
        assertTrue(apexCeiling.isMotionClear(.7, 65.2, .5, 1.5, 65, .5, .15, new StanceProbe()),
                "a residual sinusoid can miss the true apex ahead of the current column");
        assertFalse(PathEdgeValidator.isSafeContinuation(apexCeiling, source, destination,
                .5, 64, .5, .7, 65.2, .5, false, true, new StanceProbe(), new StanceProbe()),
                "the full height envelope must reject the ahead-only ceiling near apex");

        VolumeTerrain lowObstacle = new VolumeTerrain();
        lowObstacle.stance(1, 65, 0).fullSupport = true;
        lowObstacle.obstacles.add(new Box(1.1, 65.01, 0, 1.12, 65.02, 1));
        assertTrue(lowObstacle.isMotionClear(.7, 65.05, .5, 1.5, 65, .5, .3, new StanceProbe()),
                "a higher modeled arc can miss a thin obstruction near actual feet");
        assertFalse(PathEdgeValidator.isSafeContinuation(lowObstacle, source, destination,
                .5, 64, .5, .7, 65.05, .5, false, true, new StanceProbe(), new StanceProbe()),
                "forward release must also prove the lower actual-position chord");
    }

    @Test
    void jumpLiftProofDoesNotAuthorizeForwardIntoAnObstacleAtTheSameRevision() {
        VolumeTerrain terrain = new VolumeTerrain();
        Path.Step source = step(0, 64, 0, Path.Movement.START);
        Path.Step destination = step(1, 65, 0, Path.Movement.JUMP);
        terrain.stance(1, 65, 0).fullSupport = true;
        assertTrue(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                .5, 64, .5, .7, 65.1, .5, false, true, new StanceProbe(), new StanceProbe()));
        // Terrain changes during lift, ahead of the player's still-stationary body.
        terrain.obstacles.add(new Box(1.1, 65.6, 0, 1.4, 67.5, 1));
        long revision = terrain.revision();
        assertTrue(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                .5, 64, .5, .7, 64.42, .5, false, true, new StanceProbe(), new StanceProbe()));
        // No second revision occurs at the height where the executor releases forward.
        assertEquals(revision, terrain.revision());
        assertFalse(PathEdgeValidator.isSafeContinuation(terrain, source, destination,
                .5, 64, .5, .7, 65.1, .5, false, true, new StanceProbe(), new StanceProbe()));
    }

    @Test
    void idleWalkSettlementRequiresNearbyRealSupportAndClearVolume() {
        FakeTerrain terrain = new FakeTerrain();
        for (int y16 = 6; y16 < 16; y16++) terrain.stance16(0, y16, 0);
        StanceProbe lower = terrain.stance16(0, 15, 0);
        lower.surfaceSupport = true;
        assertFalse(PathEdgeValidator.isCurrentMotionSafe(terrain, Path.Movement.WALK,
                .5, 1, .5, true, new StanceProbe(), new StanceProbe().clear()));
        assertTrue(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        lower.hazard = true;
        assertFalse(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        lower.hazard = false;
        terrain.sweepClear = false;
        assertFalse(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        terrain.sweepClear = true;
        terrain.verticalSweepClear = false;
        assertFalse(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        terrain.verticalSweepClear = true;
        lower.bodyClear = false;
        assertFalse(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        lower.bodyClear = true;
        lower.breakCount = 1;
        assertFalse(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        lower.breakCount = 0;
        lower.surfaceSupport = false;
        terrain.stance16(0, 7, 0).surfaceSupport = true;
        assertTrue(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        terrain.stance16(0, 7, 0).surfaceSupport = false;
        terrain.stance16(0, 6, 0).surfaceSupport = true;
        assertFalse(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
        lower.loaded = false;
        assertFalse(PathEdgeValidator.isSafeWalkSettlement(terrain, .5, 1, .5,
                new StanceProbe(), new StanceProbe().clear()));
    }

    private static final class FakeTerrain implements Terrain {
        private final Map<Long, StanceProbe> stances = new HashMap<>();
        private final Map<FeetKey, StanceProbe> stances16 = new HashMap<>();
        private boolean sweepClear = true;
        private boolean sweepLoaded = true;
        private boolean verticalSweepClear = true;
        private boolean bridgePlaceable = true;
        private double lastFromX, lastFromY, lastArc, lastToX, lastToY;
        private double lastCurrentFeetX;
        private int lastCurrentFeetY16, groundedSweepChecks;
        private long revision;

        StanceProbe stance(int x, int y, int z) {
            return stances.computeIfAbsent(Position.pack(x, y, z), ignored -> {
                StanceProbe probe = new StanceProbe();
                probe.loaded = true;
                probe.bodyClear = true;
                probe.hazard = false;
                return probe;
            });
        }

        StanceProbe stance16(int x, int feetY16, int z) {
            return stances16.computeIfAbsent(new FeetKey(x, feetY16, z), ignored -> {
                StanceProbe probe = new StanceProbe();
                probe.loaded = true;
                probe.bodyClear = true;
                probe.hazard = false;
                return probe;
            });
        }

        @Override public boolean probeStance16(int x, int feetY16, int z, StanceProbe out) {
            StanceProbe stored = stances16.get(new FeetKey(x, feetY16, z));
            if (stored != null) { out.copyFrom(stored); return out.loaded; }
            if (Math.floorMod(feetY16, 16) == 0) {
                probeStance(x, Math.floorDiv(feetY16, 16), z, out);
                return out.loaded;
            }
            out.clear();
            return false;
        }

        @Override public boolean probeCurrentStance(double feetX, int feetY16, double feetZ,
                                                    StanceProbe out) {
            lastCurrentFeetX = feetX;
            lastCurrentFeetY16 = feetY16;
            return probeStance16((int) Math.floor(feetX), feetY16,
                    (int) Math.floor(feetZ), out);
        }

        @Override public boolean isGroundedWalkClear(double fromX, int fromFeetY16, double fromZ,
                                                     double toX, int toFeetY16, double toZ,
                                                     StanceProbe sourceAfterBreak,
                                                     StanceProbe destinationAfterBreak) {
            groundedSweepChecks++;
            return sweepClear && sweepLoaded;
        }

        @Override public void probeStance(int x, int y, int z, StanceProbe out) {
            StanceProbe stored = stances.get(Position.pack(x, y, z));
            if (stored == null) { out.clear(); return; }
            out.copyFrom(stored);
        }

        @Override public boolean isMotionClear(double fromX, double fromY, double fromZ,
                                               double toX, double toY, double toZ,
                                               double arcHeight, StanceProbe destinationAfterBreak) {
            lastFromX = fromX;
            lastFromY = fromY;
            lastToX = toX; lastToY = toY;
            lastArc = arcHeight;
            return sweepClear && sweepLoaded && (fromY == toY || verticalSweepClear);
        }

        @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe destination, int index) {
            return index >= 0 && index < destination.breakCount;
        }

        @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz,
                                                    int blockItemToken, boolean supportWasPlanned) {
            return bridgePlaceable;
        }

        @Override public long revision() { return revision; }
    }

    private record FeetKey(int x, int feetY16, int z) {}

    private static final class VolumeTerrain implements Terrain {
        private static final double HALF_WIDTH = 0.3;
        private static final double BODY_HEIGHT = 1.8;
        private final Map<Long, StanceProbe> stances = new HashMap<>();
        private final List<Box> obstacles = new ArrayList<>();
        private final List<Box> hazards = new ArrayList<>();

        StanceProbe stance(int x, int y, int z) {
            return stances.computeIfAbsent(Position.pack(x, y, z), ignored -> {
                StanceProbe probe = new StanceProbe();
                probe.loaded = true;
                probe.bodyClear = true;
                return probe;
            });
        }

        @Override public void probeStance(int x, int y, int z, StanceProbe out) {
            StanceProbe stored = stances.get(Position.pack(x, y, z));
            if (stored == null) { out.clear(); return; }
            out.copyFrom(stored);
        }

        @Override public boolean isMotionClear(double fromX, double fromY, double fromZ,
                                               double toX, double toY, double toZ,
                                               double arcHeight, StanceProbe destinationAfterBreak) {
            double length = Math.sqrt(square(toX - fromX) + square(toY - fromY) + square(toZ - fromZ));
            int samples = Math.max(1, (int) Math.ceil(length / 0.02));
            for (int i = 0; i <= samples; i++) {
                double t = (double) i / samples;
                double x = fromX + (toX - fromX) * t;
                double feetY = fromY + (toY - fromY) * t + Math.sin(Math.PI * t) * arcHeight;
                double z = fromZ + (toZ - fromZ) * t;
                for (Box obstacle : obstacles) if (intersects(obstacle, x, feetY, z)) return false;
                for (Box hazard : hazards) if (intersects(hazard, x, feetY, z)) return false;
            }
            return true;
        }

        private static boolean intersects(Box box, double x, double feetY, double z) {
            return x + HALF_WIDTH > box.minX && x - HALF_WIDTH < box.maxX
                    && feetY + BODY_HEIGHT > box.minY && feetY < box.maxY
                    && z + HALF_WIDTH > box.minZ && z - HALF_WIDTH < box.maxZ;
        }

        @Override public boolean canBreakFrom(int x, int y, int z, StanceProbe destination, int index) {
            return false;
        }

        @Override public boolean canPlaceBridgeFrom(int x, int y, int z, int bx, int by, int bz,
                                                    int blockItemToken, boolean supportWasPlanned) {
            return false;
        }

        private static double square(double value) { return value * value; }
    }

    private record Box(double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {}
}
