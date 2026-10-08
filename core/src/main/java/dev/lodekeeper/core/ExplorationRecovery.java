package dev.lodekeeper.core;

import java.util.Objects;
import java.util.Set;

/** Validates that a complete plan can justify exploring for an excluded gathering source. */
public final class ExplorationRecovery {
    private ExplorationRecovery() { }

    public static boolean isLogicalFailure(PlanResult filteredPlan) {
        Objects.requireNonNull(filteredPlan, "filteredPlan");
        return !filteredPlan.success() && !filteredPlan.blockedReasons().isEmpty()
                && filteredPlan.blockedReasons().stream().allMatch(reason -> switch (reason.code()) {
                    case NO_SOURCE, CYCLE, EMPTY_TAG, UNREACHABLE_REQUIREMENT, UNSUPPORTED_SOURCE -> true;
                    default -> false;
                });
    }

    public static boolean provesExploration(PlanResult filteredPlan, PlanResult fullPlan,
                                            CatalogSnapshot fullCatalog, Set<String> excludedGatherSourceIds) {
        Objects.requireNonNull(filteredPlan, "filteredPlan");
        Objects.requireNonNull(fullPlan, "fullPlan");
        Objects.requireNonNull(fullCatalog, "fullCatalog");
        Objects.requireNonNull(excludedGatherSourceIds, "excludedGatherSourceIds");
        if (!isLogicalFailure(filteredPlan) || !fullPlan.success() || excludedGatherSourceIds.isEmpty()
                || !filteredPlan.target().equals(fullPlan.target())
                || filteredPlan.requestedCount() != fullPlan.requestedCount()
                || fullPlan.steps().stream().anyMatch(step -> step.kind() == PlanKind.CUSTOM || step.kind() == PlanKind.NATIVE)) {
            return false;
        }
        return fullPlan.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER
                && excludedGatherSourceIds.contains(step.sourceId())
                && fullCatalog.sourcesFor(step.output()).stream().anyMatch(source ->
                        source instanceof GatherSource gather && gather.sourceId().equals(step.sourceId())));
    }
}
