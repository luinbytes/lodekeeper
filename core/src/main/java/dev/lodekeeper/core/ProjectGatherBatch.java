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
