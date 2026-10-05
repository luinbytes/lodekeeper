package dev.lodekeeper.core;

/** Hard caps applied before catalog-driven search. Elapsed limits are clamped to 25ms. */
public record PlannerLimits(
        int maximumDepth,
        int maximumExpandedNodes,
        int maximumElapsedMillis,
        int maximumCandidatesPerBranch,
        int maximumSteps,
        int maximumRequestedCount
) {
    public static final PlannerLimits DEFAULT = new PlannerLimits(48, 8_000, 20, 12, 4_096, 1_000_000);

    public PlannerLimits {
        if (maximumDepth < 1 || maximumDepth > 128) throw new IllegalArgumentException("maximumDepth out of range");
        if (maximumExpandedNodes < 1 || maximumExpandedNodes > 100_000) throw new IllegalArgumentException("maximumExpandedNodes out of range");
        if (maximumElapsedMillis < 1 || maximumElapsedMillis > 25) throw new IllegalArgumentException("maximumElapsedMillis must be 1..25");
        if (maximumCandidatesPerBranch < 1 || maximumCandidatesPerBranch > 64) throw new IllegalArgumentException("maximumCandidatesPerBranch out of range");
        if (maximumSteps < 1 || maximumSteps > 100_000) throw new IllegalArgumentException("maximumSteps out of range");
        if (maximumRequestedCount < 1 || maximumRequestedCount > 1_000_000_000) throw new IllegalArgumentException("maximumRequestedCount out of range");
    }
}
