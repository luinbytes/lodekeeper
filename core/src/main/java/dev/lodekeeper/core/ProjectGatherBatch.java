package dev.lodekeeper.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Combines matching project gathers only when the current inventory can perform them together. */
public final class ProjectGatherBatch {
    private ProjectGatherBatch() { }

    public static ProjectPlanResult consolidateInitialMaterial(AcquisitionPlanner planner, CatalogSnapshot catalog,
                                                               InventorySnapshot inventory, ProjectPlanResult original,
                                                               PlannerLimits limits, PlanningPreferences preferences,
                                                               Set<ItemId> materialFamily) {
        if (!original.success() || original.steps().isEmpty()) return original;
        PlanStep first = original.steps().get(0);
        if (first.kind() != PlanKind.GATHER || !materialFamily.contains(first.output())) return original;
        boolean mixed = original.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER
                && materialFamily.contains(step.output()) && !step.sourceId().equals(first.sourceId()));
        if (!mixed) return original;

        Set<ItemId> outputs = new HashSet<>();
        for (GatherSource source : catalog.gatherSources())
            if (materialFamily.contains(source.output())) outputs.add(source.output());
        if (outputs.size() > 64) return original;
        CatalogSnapshot restricted = catalog;
        try {
            for (ItemId output : outputs) {
                List<AcquisitionSource> sources = catalog.sourcesFor(output).stream()
                        .filter(source -> !(source instanceof GatherSource) || source.sourceId().equals(first.sourceId()))
                        .toList();
                restricted = restricted.withOutputSources(output, sources);
            }
        } catch (IllegalArgumentException | IllegalStateException viewCapacity) {
            return original;
        }
        ProjectPlanResult consolidated = planner.planProjectFast(restricted, inventory, original.project(), limits, preferences);
        if (!consolidated.success() || consolidated.steps().isEmpty()
                || !consolidated.steps().get(0).sourceId().equals(first.sourceId())
                || gatherOperations(consolidated.steps()) > gatherOperations(original.steps()))
            return original;
        return new ProjectPlanResult(original.project(), consolidated.steps(), List.of(), false,
                Math.addExact(original.expandedNodes(), consolidated.expandedNodes()),
                Math.addExact(original.elapsedNanos(), consolidated.elapsedNanos()));
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

    private static Set<ItemId> selectedTools(PlanStep step) {
        Set<ItemId> tools = new HashSet<>();
        for (SelectedRequirement requirement : step.requirements())
            if (requirement instanceof SelectedToolRequirement tool) tools.add(tool.item());
        return tools;
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

        Set<ItemId> currentTools = selectedTools(first);
        long quantity = 0;
        for (PlanStep step : steps) {
            if (clock.getAsLong() >= deadline) return first;
            if (step.kind() == PlanKind.GATHER && step.sourceId().equals(first.sourceId())
                    && step.output().equals(first.output()) && step.attributes().equals(first.attributes())) {
                if (!selectedTools(step).equals(currentTools)) break;
                quantity += step.outputCount();
            }
        }
        if (quantity <= first.outputCount() || quantity > additionalCapacity) return first;
        long target = inventory.count(first.output()) + quantity;
        if (target > limits.maximumRequestedCount()) return first;

        CatalogSnapshot.Builder restricted = CatalogSnapshot.builder().source(source);
        Set<ItemId> neededItems = new HashSet<>(inventory.counts().keySet());
        neededItems.add(first.output());
        for (ItemId item : neededItems) {
            if (clock.getAsLong() >= deadline) return first;
            ItemDefinition definition = catalog.itemDefinitions().get(item);
            if (definition != null) restricted.item(definition);
        }
        Set<TagId> copiedTags = new HashSet<>();
        for (Requirement requirement : source.requirements()) {
            if (clock.getAsLong() >= deadline) return first;
            Ingredient ingredient = requirement instanceof ToolRequirement tool ? tool.tools()
                    : requirement instanceof ItemRequirement material ? material.ingredient() : null;
            if (ingredient == null) continue;
            for (ItemSelector selector : ingredient.alternatives()) {
                if (clock.getAsLong() >= deadline) return first;
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
        int remainingMillis = (int) ((deadline - clock.getAsLong()) / 1_000_000L);
        if (remainingMillis < 1) return first;
        PlannerLimits proofLimits = new PlannerLimits(limits.maximumDepth(), limits.maximumExpandedNodes(),
                Math.min(remainingMillis, limits.maximumElapsedMillis()), limits.maximumCandidatesPerBranch(),
                limits.maximumSteps(), limits.maximumRequestedCount());
        PlanResult proof = planner.planFast(proofCatalog, inventory, first.output(), (int) target,
                proofLimits, preferences);
        if (!proof.success() || proof.steps().size() != 1) return first;
        PlanStep batch = proof.steps().get(0);
        return batch.kind() == PlanKind.GATHER && batch.sourceId().equals(first.sourceId())
                && batch.output().equals(first.output()) && batch.attributes().equals(first.attributes())
                ? batch : first;
    }
}
