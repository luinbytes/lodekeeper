package dev.lodekeeper.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Prioritizes required material and combines gathers proven against the current inventory. */
public final class ProjectGatherBatch {
    private ProjectGatherBatch() { }

    public static ProjectPlanResult consolidateInitialMaterial(AcquisitionPlanner planner, CatalogSnapshot catalog,
                                                               InventorySnapshot inventory, ProjectPlanResult original,
                                                               PlannerLimits limits, PlanningPreferences preferences,
                                                               Set<ItemId> materialFamily) {
        if (!original.success() || original.steps().isEmpty()) return original;
        LongSupplier clock = System::nanoTime;
        long deadline = clock.getAsLong() + limits.maximumElapsedMillis() * 1_000_000L;
        int initialNodes = original.expandedNodes();
        original = promoteExecutableMaterial(planner, catalog, inventory, original, limits,
                preferences, materialFamily, deadline, clock);
        PlanStep first = original.steps().get(0);
        if (first.kind() != PlanKind.GATHER || !materialFamily.contains(first.output())) return original;
        boolean mixed = original.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER
                && materialFamily.contains(step.output()) && !step.sourceId().equals(first.sourceId()));
        if (!mixed) return original;

        Set<ItemId> outputs = new HashSet<>();
        for (GatherSource source : catalog.gatherSources()) {
            if (clock.getAsLong() >= deadline) return original;
            if (materialFamily.contains(source.output())) outputs.add(source.output());
        }
        if (outputs.size() > 64) return original;
        CatalogSnapshot restricted = catalog;
        try {
            for (ItemId output : outputs) {
                if (clock.getAsLong() >= deadline) return original;
                List<AcquisitionSource> sources = catalog.sourcesFor(output).stream()
                        .filter(source -> !(source instanceof GatherSource) || source.sourceId().equals(first.sourceId()))
                        .toList();
                restricted = restricted.withOutputSources(output, sources);
            }
        } catch (IllegalArgumentException | IllegalStateException viewCapacity) {
            return original;
        }
        PlannerLimits remaining = remainingLimits(limits, deadline, clock,
                original.expandedNodes() - initialNodes);
        if (remaining == null) return original;
        ProjectPlanResult consolidated = planner.planProjectFast(restricted, inventory, original.project(), remaining, preferences);
        if (clock.getAsLong() >= deadline || !consolidated.success() || consolidated.steps().isEmpty()) return original;
        consolidated = new ProjectPlanResult(original.project(), consolidated.steps(), List.of(), false,
                Math.addExact(original.expandedNodes(), consolidated.expandedNodes()),
                Math.addExact(original.elapsedNanos(), consolidated.elapsedNanos()));
        remaining = remainingLimits(limits, deadline, clock, consolidated.expandedNodes() - initialNodes);
        if (remaining != null)
            consolidated = promoteExecutableMaterial(planner, restricted, inventory, consolidated, remaining,
                    preferences, materialFamily, deadline, clock);
        if (!consolidated.steps().get(0).sourceId().equals(first.sourceId())
                || gatherOperations(consolidated.steps()) > gatherOperations(original.steps()))
            return original;
        return consolidated;
    }

    private static ProjectPlanResult promoteExecutableMaterial(AcquisitionPlanner planner, CatalogSnapshot catalog,
                                                               InventorySnapshot inventory, ProjectPlanResult plan,
                                                               PlannerLimits limits, PlanningPreferences preferences,
                                                               Set<ItemId> materialFamily, long deadline, LongSupplier clock) {
        PlanStep first = plan.steps().get(0);
        if (first.kind() != PlanKind.GATHER || materialFamily.contains(first.output())) return plan;
        for (int index = 1; index < plan.steps().size(); index++) {
            if (clock.getAsLong() >= deadline) return plan;
            PlanStep selected = plan.steps().get(index);
            if (selected.kind() != PlanKind.GATHER || !materialFamily.contains(selected.output())) continue;
            AcquisitionSource source = catalog.sourceById(selected.sourceId());
            if (!(source instanceof GatherSource) || source.requirements().stream().anyMatch(requirement ->
                    requirement instanceof StationRequirement
                            || requirement instanceof ItemRequirement item && item.consume())) return plan;
            PlanResult proof = proveHeldGather(planner, catalog, inventory, selected, selected.outputCount(),
                    limits, preferences, deadline, clock);
            if (proof == null || !selected.equals(proof.steps().get(0))) return plan;
            List<PlanStep> promoted = new ArrayList<>(plan.steps());
            promoted.remove(index);
            promoted.add(0, selected);
            return new ProjectPlanResult(plan.project(), promoted, List.of(), false,
                    Math.addExact(plan.expandedNodes(), proof.expandedNodes()),
                    Math.addExact(plan.elapsedNanos(), proof.elapsedNanos()));
        }
        return plan;
    }

    private static long gatherOperations(List<PlanStep> steps) {
        return steps.stream().filter(step -> step.kind() == PlanKind.GATHER)
                .mapToLong(PlanStep::operationCount).sum();
    }

    public static PlanStep firstStep(AcquisitionPlanner planner, CatalogSnapshot catalog,
                                     InventorySnapshot inventory, List<PlanStep> steps,
                                     PlannerLimits limits, PlanningPreferences preferences,
                                     int additionalCapacity) {
        return firstStep(planner, catalog, inventory, steps, limits, preferences, additionalCapacity, System::nanoTime);
    }

    private static List<SelectedToolRequirement> selectedTools(PlanStep step) {
        return step.requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).toList();
    }

    static PlanStep firstStep(AcquisitionPlanner planner, CatalogSnapshot catalog,
                              InventorySnapshot inventory, List<PlanStep> steps,
                              PlannerLimits limits, PlanningPreferences preferences,
                              int additionalCapacity, LongSupplier clock) {
        if (steps.isEmpty()) throw new IllegalArgumentException("Project plan has no steps");
        PlanStep first = steps.get(0);
        if (first.kind() != PlanKind.GATHER) return first;
        long deadline = clock.getAsLong() + limits.maximumElapsedMillis() * 1_000_000L;
        AcquisitionSource source = catalog.sourceById(first.sourceId());
        if (!(source instanceof GatherSource)) return first;
        if (inventory.counts().size() > 64) return first;

        List<SelectedToolRequirement> currentTools = selectedTools(first);
        long quantity = 0;
        for (PlanStep step : steps) {
            if (clock.getAsLong() >= deadline) return first;
            if (step.kind() != PlanKind.GATHER) continue;
            if (!step.sourceId().equals(first.sourceId()) || !step.output().equals(first.output())
                    || !step.attributes().equals(first.attributes()) || !selectedTools(step).equals(currentTools)) break;
            quantity += step.outputCount();
        }
        if (quantity <= first.outputCount() || quantity > additionalCapacity) return first;
        PlanResult proof = proveHeldGather(planner, catalog, inventory, first, quantity,
                limits, preferences, deadline, clock);
        return proof == null ? first : proof.steps().get(0);
    }

    private static PlanResult proveHeldGather(AcquisitionPlanner planner, CatalogSnapshot catalog,
                                              InventorySnapshot inventory, PlanStep selected, long outputCount,
                                              PlannerLimits limits, PlanningPreferences preferences,
                                              long deadline, LongSupplier clock) {
        AcquisitionSource source = catalog.sourceById(selected.sourceId());
        if (!(source instanceof GatherSource) || inventory.counts().size() > 64) return null;
        long target = inventory.count(selected.output()) + outputCount;
        if (target > limits.maximumRequestedCount()) return null;

        CatalogSnapshot.Builder restricted = CatalogSnapshot.builder().source(source);
        Set<ItemId> neededItems = new HashSet<>(inventory.counts().keySet());
        neededItems.add(selected.output());
        for (ItemId item : neededItems) {
            if (clock.getAsLong() >= deadline) return null;
            ItemDefinition definition = catalog.itemDefinitions().get(item);
            if (definition != null) restricted.item(definition);
        }
        Set<TagId> copiedTags = new HashSet<>();
        for (Requirement requirement : source.requirements()) {
            if (clock.getAsLong() >= deadline) return null;
            Ingredient ingredient = requirement instanceof ToolRequirement tool ? tool.tools()
                    : requirement instanceof ItemRequirement material ? material.ingredient() : null;
            if (ingredient == null) continue;
            for (ItemSelector selector : ingredient.alternatives()) {
                if (clock.getAsLong() >= deadline) return null;
                if (selector instanceof ItemSelector.Tag tag && copiedTags.add(tag.tag())) {
                    List<ItemId> members = catalog.itemsIn(tag.tag());
                    List<ItemId> heldMembers = new ArrayList<>();
                    for (ItemId held : neededItems)
                        if (Collections.binarySearch(members, held) >= 0) heldMembers.add(held);
                    restricted.tag(tag.tag(), heldMembers);
                }
            }
        }
        CatalogSnapshot proofCatalog = restricted.build();
        PlannerLimits proofLimits = remainingLimits(limits, deadline, clock, 0);
        if (proofLimits == null) return null;
        PlanResult proof = planner.planFast(proofCatalog, inventory, selected.output(), (int) target,
                proofLimits, preferences);
        if (clock.getAsLong() >= deadline || !proof.success() || proof.steps().size() != 1) return null;
        PlanStep batch = proof.steps().get(0);
        return batch.kind() == PlanKind.GATHER && batch.sourceId().equals(selected.sourceId())
                && batch.output().equals(selected.output()) && batch.outputCount() == outputCount
                && batch.attributes().equals(selected.attributes()) && selectedTools(batch).equals(selectedTools(selected))
                ? proof : null;
    }

    private static PlannerLimits remainingLimits(PlannerLimits limits, long deadline, LongSupplier clock, int spentNodes) {
        int remainingMillis = (int) ((deadline - clock.getAsLong()) / 1_000_000L);
        int remainingNodes = limits.maximumExpandedNodes() - spentNodes;
        if (remainingMillis < 1 || remainingNodes < 1) return null;
        return new PlannerLimits(limits.maximumDepth(), remainingNodes,
                Math.min(remainingMillis, limits.maximumElapsedMillis()), limits.maximumCandidatesPerBranch(),
                limits.maximumSteps(), limits.maximumRequestedCount());
    }
}
