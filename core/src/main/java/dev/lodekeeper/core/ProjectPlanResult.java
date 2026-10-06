package dev.lodekeeper.core;

import java.util.List;
import java.util.Objects;

/** Immutable bounded-search result for all inventory goals in one project. */
public record ProjectPlanResult(
        ProjectSpec project,
        List<PlanStep> steps,
        List<BlockedReason> blockedReasons,
        boolean optimal,
        int expandedNodes,
        long elapsedNanos
) {
    public ProjectPlanResult {
        Objects.requireNonNull(project, "project");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        blockedReasons = List.copyOf(Objects.requireNonNull(blockedReasons, "blockedReasons"));
        if (expandedNodes < 0 || elapsedNanos < 0) throw new IllegalArgumentException("Invalid planner metrics");
    }

    public boolean success() { return blockedReasons.isEmpty(); }
}
