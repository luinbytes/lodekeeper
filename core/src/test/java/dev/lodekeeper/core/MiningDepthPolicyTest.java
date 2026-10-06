package dev.lodekeeper.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MiningDepthPolicyTest {
    @Test
    void keepsTheFirstPickaxeAndThresholdRequestsAtNearestOre() {
        assertEquals(MiningDepthPolicy.Kind.NEAREST, policy(3).kind());
        assertEquals(MiningDepthPolicy.Kind.NEAREST, policy(8).kind());
        assertEquals(MiningDepthPolicy.Kind.BULK_DIAMOND, policy(9).kind());
    }

    @Test
    void bulkRequestDescendsToTheRichDepthBeforeMiningAboveTheBand() {
        MiningDepthPolicy policy = policy(32);

        assertEquals(-55, policy.desiredY());
        assertEquals(-40, policy.maximumY());
        assertTrue(policy.shouldDescend(16));
        assertTrue(policy.shouldDescend(-39));
        assertFalse(policy.shouldDescend(-40));
        assertFalse(policy.shouldDescend(-55));
    }

    @Test
    void keepsAStricterNativeCeilingAndChoosesADepthInsideIt() {
        MiningDepthPolicy policy = MiningDepthPolicy.select(32, true, true, -64, -58);

        assertEquals(-58, policy.maximumY());
        assertEquals(-58, policy.desiredY());
        assertTrue(policy.shouldDescend(-55));
    }

    @Test
    void keepsNearestPolicyForOtherOutputsExplorationOptOutAndIncompatibleWorlds() {
        assertEquals(MiningDepthPolicy.Kind.NEAREST,
                MiningDepthPolicy.select(32, false, true, -64, 256).kind());
        assertEquals(MiningDepthPolicy.Kind.NEAREST,
                MiningDepthPolicy.select(32, true, false, -64, 256).kind());
        assertEquals(MiningDepthPolicy.Kind.NEAREST,
                MiningDepthPolicy.select(32, true, true, 0, 256).kind());
    }

    @Test
    void doesNotChooseADescendingGoalBelowTheWorldFloor() {
        MiningDepthPolicy policy = MiningDepthPolicy.select(32, true, true, -64, -65);

        assertEquals(MiningDepthPolicy.Kind.NEAREST, policy.kind());
        assertEquals(-65, policy.maximumY());
        assertFalse(policy.shouldDescend(16));
    }

    @Test
    void appliesAStricterLiveCeilingWithoutRaisingTheLatchedDepth() {
        MiningDepthPolicy policy = policy(32);

        assertEquals(-58, policy.effectiveMaximumY(-58));
        assertEquals(-58, policy.effectiveDesiredY(-58));
        assertEquals(-40, policy.effectiveMaximumY(256));
        assertEquals(-55, policy.effectiveDesiredY(256));
        assertEquals(-65, policy.effectiveDesiredY(-65));
    }

    private static MiningDepthPolicy policy(int missingCount) {
        return MiningDepthPolicy.select(missingCount, true, true, -64, 256);
    }
}
