package dev.lodekeeper.core;

import java.util.List;

/** Immutable planner result with enough metrics to expose bounded-search behavior. */
public record PlanResult(
        ItemId target,
        int requestedCount,
        List<PlanStep> steps,
        List<BlockedReason> blockedReasons,
        boolean optimal,
        int expandedNodes,
        long elapsedNanos
) {
    public PlanResult {
        steps = List.copyOf(steps);
        blockedReasons = List.copyOf(blockedReasons);
        if (expandedNodes < 0 || elapsedNanos < 0) throw new IllegalArgumentException("Invalid planner metrics");
    }

    public boolean success() { return blockedReasons.isEmpty(); }
}
