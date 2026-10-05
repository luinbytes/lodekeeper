package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Bounded, deterministic backward planner for catalog-described item sources. */
public final class AcquisitionPlanner {
    private static final int MAX_REASONS = 32;

    public PlanResult plan(CatalogSnapshot catalog, InventorySnapshot inventory, ItemId target, int count) {
        return plan(catalog, inventory, target, count, PlannerLimits.DEFAULT);
    }

    public PlanResult plan(CatalogSnapshot catalog, InventorySnapshot inventory, ItemId target, int count, PlannerLimits limits) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(limits, "limits");
        long started = System.nanoTime();
        if (count < 1 || count > limits.maximumRequestedCount()) {
            return failure(target, count, BlockedReason.Code.INVALID_COUNT, "Requested count must be between 1 and " + limits.maximumRequestedCount(), 0, elapsed(started));
        }
        if (!catalog.knownItems().contains(target)) {
            return failure(target, count, BlockedReason.Code.UNKNOWN_ITEM, "Item is not present in the current catalog", 0, elapsed(started));
        }

        Search search = new Search(catalog, limits, started);
        State initial = new State(inventory, catalog);
        List<State> plans = search.satisfy(target, count, false, initial, Set.of(), 0, "requested target", -1);
        if (plans.isEmpty()) {
            List<BlockedReason> reasons = search.reasons();
            if (reasons.isEmpty()) {
                BlockedReason.Code code = search.limitCode == null ? BlockedReason.Code.NO_SOURCE : search.limitCode;
                reasons = List.of(new BlockedReason(code, target, "No bounded acquisition plan was found", List.of(target)));
            }
            return new PlanResult(target, count, List.of(), reasons, false, search.expanded, elapsed(started));
        }
        State best = plans.get(0);
        return new PlanResult(target, count, best.steps, List.of(), !search.truncated, search.expanded, elapsed(started));
    }

    private static PlanResult failure(ItemId target, int count, BlockedReason.Code code, String detail, int nodes, long nanos) {
        return new PlanResult(target, count, List.of(), List.of(new BlockedReason(code, target, detail, List.of(target))), false, nodes, nanos);
    }

    private static long elapsed(long started) { return Math.max(0, System.nanoTime() - started); }

    private static final class Search {
        private final CatalogSnapshot catalog;
        private final PlannerLimits limits;
        private final long deadline;
        private final LinkedHashSet<BlockedReason> failures = new LinkedHashSet<>();
        private int expanded;
        private boolean truncated;
        private BlockedReason.Code limitCode;
        private BlockedReason limitReason;

        private Search(CatalogSnapshot catalog, PlannerLimits limits, long started) {
            this.catalog = catalog;
            this.limits = limits;
            this.deadline = started + limits.maximumElapsedMillis() * 1_000_000L;
        }

        private List<State> satisfy(ItemId item, int count, boolean consume, State state, Set<ItemId> path, int depth, String purpose, int recipeSlot) {
            if (!visit(item, path, depth)) return List.of();
            if (count < 1 || count > limits.maximumRequestedCount()) {
                fail(BlockedReason.Code.STEP_LIMIT, item, "Required quantity exceeds planner limits", pathWith(path, item));
                return List.of();
            }
            int current = consume ? state.spendableCount(item) : state.count(item);
            if (current >= count) {
                State result = state.copy();
                if (consume) result.take(item, count);
                return List.of(result);
            }
            int missing = count - current;
            if (path.contains(item)) {
                fail(BlockedReason.Code.CYCLE, item, "Dependency cycle while obtaining " + item, pathWith(path, item));
                return List.of();
            }
            if (!catalog.knownItems().contains(item)) {
                fail(BlockedReason.Code.UNKNOWN_ITEM, item, "Required item is not present in the current catalog", pathWith(path, item));
                return List.of();
            }
            Set<ItemId> nextPath = with(path, item);
            List<State> produced = produce(item, missing, state, nextPath, depth + 1);
            var results = new ArrayList<State>();
            for (State candidate : produced) {
                int available = consume ? candidate.spendableCount(item) : candidate.count(item);
                if (available < count) {
                    fail(BlockedReason.Code.INVALID_CATALOG, item, "Source did not produce its declared amount", pathWith(path, item));
                    continue;
                }
                State result = candidate.copy();
                if (consume) result.take(item, count);
                results.add(result);
            }
            return trim(results);
        }

        private List<State> produce(ItemId item, int missing, State state, Set<ItemId> path, int depth) {
            if (!visit(item, path, depth)) return List.of();
            List<AcquisitionSource> sources = catalog.sourcesFor(item);
            if (sources.isEmpty()) {
                fail(BlockedReason.Code.NO_SOURCE, item, "No acquisition source is known for " + item, pathWith(path, item));
                return List.of();
            }
            var results = new ArrayList<State>();
            for (AcquisitionSource source : sources) {
                if (!visit(item, path, depth)) break;
                int operations;
                try {
                    operations = ceilDiv(missing, source.outputCount());
                } catch (ArithmeticException exception) {
                    fail(BlockedReason.Code.INVALID_CATALOG, item, "Invalid output quantity in source " + source.sourceId(), pathWith(path, item));
                    continue;
                }
                if (operations > limits.maximumRequestedCount()) {
                    fail(BlockedReason.Code.STEP_LIMIT, item, "Source operation count exceeds the planner limit", pathWith(path, item));
                    continue;
                }
                long outputLong = (long) operations * source.outputCount();
                if (outputLong > limits.maximumRequestedCount()) {
                    fail(BlockedReason.Code.STEP_LIMIT, item, "Source output exceeds the planner quantity limit", pathWith(path, item));
                    continue;
                }
                List<Prepared> prepared = List.of(new Prepared(state.copy(), List.of()));
                if (source instanceof CraftingSource crafting) {
                    prepared = prepareCrafting(prepared, crafting, operations, path, depth);
                } else if (source instanceof SmeltingSource smelting) {
                    prepared = prepareSmelting(prepared, smelting, operations, path, depth);
                }
                if (prepared.isEmpty()) continue;
                prepared = prepareRequirements(prepared, source.requirements(), operations, path, depth);
                for (Prepared candidate : prepared) {
                    if (candidate.state.steps.size() >= limits.maximumSteps()) {
                        fail(BlockedReason.Code.STEP_LIMIT, item, "Plan exceeds " + limits.maximumSteps() + " steps", pathWith(path, item));
                        continue;
                    }
                    int outputCount = (int) outputLong;
                    State completed = candidate.state.copy();
                    completed.add(source.output(), outputCount, catalog.maximumDurability(source.output()));
                    PlanStep step = makeStep(source, operations, outputCount, candidate.selected);
                    completed.steps.add(step);
                    completed.operations += operations;
                    if (source instanceof GatherSource) completed.gatherOperations += operations;
                    results.add(completed);
                }
            }
            if (results.isEmpty() && !truncated && sources.isEmpty()) {
                fail(BlockedReason.Code.NO_SOURCE, item, "No usable acquisition source is known for " + item, pathWith(path, item));
            }
            return trim(results);
        }

        private List<Prepared> prepareCrafting(List<Prepared> initial, CraftingSource source, int operations, Set<ItemId> path, int depth) {
            List<Prepared> prepared = initial;
            Map<Ingredient, List<Integer>> groups = new LinkedHashMap<>();
            for (int index = 0; index < source.slots().size(); index++) {
                RecipeSlot slot = source.slots().get(index);
                int recipeIndex = source.recipeType() == RecipeType.SHAPELESS ? index : slot.slotIndex();
                groups.computeIfAbsent(slot.ingredient(), ignored -> new ArrayList<>()).add(recipeIndex);
            }
            List<Map.Entry<Ingredient, List<Integer>>> orderedGroups = groups.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<Ingredient, List<Integer>> entry) -> expanded(entry.getKey(), path).size())
                            .thenComparingInt(entry -> entry.getValue().stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE)))
                    .toList();
            for (Map.Entry<Ingredient, List<Integer>> entry : orderedGroups) {
                Ingredient ingredient = entry.getKey();
                long total = (long) ingredient.count() * operations * entry.getValue().size();
                long perSlot = (long) ingredient.count() * operations;
                if (total > limits.maximumRequestedCount() || perSlot > limits.maximumRequestedCount()) {
                    fail(BlockedReason.Code.STEP_LIMIT, source.output(), "Recipe ingredient quantity exceeds planner limits", pathWith(path, source.output()));
                    return List.of();
                }
                List<Integer> slots = entry.getValue().stream().sorted().toList();
                prepared = chooseIngredientGroup(prepared, ingredient, (int) total, (int) perSlot, slots,
                        "recipe ingredient", path, depth);
                if (prepared.isEmpty()) return prepared;
            }
            return prepared;
        }

        private List<Prepared> chooseIngredientGroup(List<Prepared> initial, Ingredient ingredient, int total,
                                                     int perSlot, List<Integer> slots, String purpose,
                                                     Set<ItemId> path, int depth) {
            var next = new ArrayList<Prepared>();
            List<ItemId> alternatives = expanded(ingredient, path);
            int requiredUses = total / ingredient.count();
            for (Prepared candidate : initial) {
                // Allocate full ingredient-count chunks from held tag alternatives before sourcing more.
                int ingredientCount = ingredient.count();
                int remainingUses = requiredUses;
                var allocations = new LinkedHashMap<ItemId, Integer>();
                List<ItemId> stocked = alternatives.stream().filter(item -> candidate.state.spendableCount(item) >= ingredientCount)
                        .sorted(Comparator.comparingInt((ItemId item) -> candidate.state.spendableCount(item)).reversed().thenComparing(Comparator.naturalOrder()))
                        .limit(limits.maximumCandidatesPerBranch()).toList();
                if (alternatives.size() > limits.maximumCandidatesPerBranch()) truncated = true;
                for (ItemId item : stocked) {
                    int availableUses = candidate.state.spendableCount(item) / ingredientCount;
                    int uses = Math.min(remainingUses, availableUses);
                    if (uses > 0) {
                        allocations.put(item, uses * ingredientCount);
                        remainingUses -= uses;
                    }
                    if (remainingUses == 0) break;
                }

                List<Prepared> held = List.of(new Prepared(candidate.state.copy(), candidate.selected));
                for (Map.Entry<ItemId, Integer> allocation : allocations.entrySet()) {
                    var consumed = new ArrayList<Prepared>();
                    for (Prepared value : held) {
                        for (State ready : satisfy(allocation.getKey(), allocation.getValue(), true, value.state, path, depth + 1, purpose, -1)) {
                            consumed.add(new Prepared(ready, value.selected));
                        }
                    }
                    held = trimPrepared(consumed);
                    if (held.isEmpty()) break;
                }
                for (Prepared value : held) {
                    if (remainingUses == 0) {
                        next.add(withAllocatedRequirements(value, allocations, slots, perSlot, purpose));
                        continue;
                    }
                    int remainingCount = remainingUses * ingredientCount;
                    for (ItemId fallback : rankedAlternatives(ingredient, value.state, remainingUses * ingredientCount, true, path)) {
                        if (!visit(fallback, path, depth + 1)) break;
                        for (State ready : satisfy(fallback, remainingCount, true, value.state, path, depth + 1, purpose, -1)) {
                            var combined = new LinkedHashMap<>(allocations);
                            combined.merge(fallback, remainingCount, Integer::sum);
                            next.add(withAllocatedRequirements(new Prepared(ready, value.selected), combined, slots, perSlot, purpose));
                        }
                    }
                }
            }
            return trimPrepared(next);
        }

        private Prepared withAllocatedRequirements(Prepared prepared, Map<ItemId, Integer> allocation,
                                                   List<Integer> slots, int perSlot, String purpose) {
            var selected = new ArrayList<>(prepared.selected);
            int slotPosition = 0;
            int slotRemaining = perSlot;
            for (Map.Entry<ItemId, Integer> entry : allocation.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                int itemRemaining = entry.getValue();
                while (itemRemaining > 0 && slotPosition < slots.size()) {
                    int amount = Math.min(itemRemaining, slotRemaining);
                    selected.add(new SelectedItemRequirement(entry.getKey(), amount, true, purpose, slots.get(slotPosition)));
                    itemRemaining -= amount;
                    slotRemaining -= amount;
                    if (slotRemaining == 0) {
                        slotPosition++;
                        slotRemaining = perSlot;
                    }
                }
                if (itemRemaining > 0) throw new IllegalStateException("Ingredient allocation exceeds recipe slot capacity");
            }
            if (slotPosition != slots.size() || slotRemaining != perSlot) {
                throw new IllegalStateException("Ingredient allocation does not fill recipe slots");
            }
            return new Prepared(prepared.state, List.copyOf(selected));
        }

        private List<Prepared> prepareSmelting(List<Prepared> initial, SmeltingSource source, int operations, Set<ItemId> path, int depth) {
            int inputCount = multiplyCount(source.input().count(), operations, source.output(), path);
            List<Prepared> prepared = chooseIngredient(initial, source.input(), inputCount, true, "smelting input", -1, path, depth);
            if (prepared.isEmpty()) return prepared;
            long totalTicks;
            try {
                totalTicks = Math.multiplyExact(source.cookTicks(), (long) operations);
            } catch (ArithmeticException exception) {
                fail(BlockedReason.Code.STEP_LIMIT, source.output(), "Smelting fuel requirement exceeds the planner limit", pathWith(path, source.output()));
                return List.of();
            }
            var next = new ArrayList<Prepared>();
            for (Prepared candidate : prepared) {
                for (ItemSelector selector : source.fuels()) {
                    for (ItemId fuel : expand(selector, source.output(), path)) {
                        long burnTicks = catalog.fuelBurnTicks(fuel);
                        if (burnTicks < 1) {
                            fail(BlockedReason.Code.UNREACHABLE_REQUIREMENT, fuel, "Item is not registered as fuel: " + fuel, pathWith(path, fuel));
                            continue;
                        }
                        long neededLong = ceilDivLong(totalTicks, burnTicks);
                        if (neededLong > limits.maximumRequestedCount()) {
                            fail(BlockedReason.Code.STEP_LIMIT, fuel, "Fuel quantity exceeds planner limits", pathWith(path, fuel));
                            continue;
                        }
                        int needed = (int) neededLong;
                        List<State> supplied = satisfy(fuel, needed, true, candidate.state, path, depth + 1, "smelting fuel", -1);
                        for (State ready : supplied) {
                            var selected = new ArrayList<>(candidate.selected);
                            selected.add(new SelectedItemRequirement(fuel, needed, true, "smelting fuel", -1));
                            next.add(new Prepared(ready, List.copyOf(selected)));
                        }
                    }
                }
            }
            return trimPrepared(next);
        }

        private List<Prepared> prepareRequirements(List<Prepared> initial, List<Requirement> requirements, int operations, Set<ItemId> path, int depth) {
            List<Prepared> prepared = initial;
            for (Requirement requirement : requirements) {
                if (requirement instanceof ItemRequirement item) {
                    int amount = multiplyCount(item.ingredient().count(), operations, null, path);
                    prepared = chooseIngredient(prepared, item.ingredient(), amount, item.consume(), item.purpose(), -1, path, depth);
                } else if (requirement instanceof ToolRequirement tool) {
                    prepared = chooseTool(prepared, tool, path, depth);
                } else if (requirement instanceof StationRequirement station) {
                    prepared = ensureStation(prepared, station, path, depth);
                }
                if (prepared.isEmpty()) return prepared;
            }
            return prepared;
        }

        private List<Prepared> chooseIngredient(List<Prepared> initial, Ingredient ingredient, int amount, boolean consume,
                                                String purpose, int recipeSlot, Set<ItemId> path, int depth) {
            var next = new ArrayList<Prepared>();
            for (Prepared candidate : initial) {
                for (ItemId item : rankedAlternatives(ingredient, candidate.state, amount, consume, path)) {
                    if (!visit(item, path, depth + 1)) break;
                    for (State ready : satisfy(item, amount, consume, candidate.state, path, depth + 1, purpose, recipeSlot)) {
                        var selected = new ArrayList<>(candidate.selected);
                        selected.add(new SelectedItemRequirement(item, amount, consume, purpose, recipeSlot));
                        next.add(new Prepared(ready, List.copyOf(selected)));
                    }
                }
            }
            return trimPrepared(next);
        }

        private List<Prepared> chooseTool(List<Prepared> initial, ToolRequirement requirement, Set<ItemId> path, int depth) {
            var next = new ArrayList<Prepared>();
            for (Prepared candidate : initial) {
                List<ItemId> held = expanded(requirement.tools(), path).stream()
                        .filter(item -> candidate.state.count(item) > 0
                                && candidate.state.durability.getOrDefault(item, defaultDurability(item, catalog)) >= requirement.minimumDurability())
                        .sorted(Comparator.comparingInt((ItemId item) -> candidate.state.count(item)).reversed()
                                .thenComparing(Comparator.comparingInt((ItemId item) -> candidate.state.durability
                                        .getOrDefault(item, defaultDurability(item, catalog))).reversed())
                                .thenComparing(Comparator.naturalOrder()))
                        .limit(limits.maximumCandidatesPerBranch()).toList();
                List<ItemId> choices = held.isEmpty()
                        ? rankedAlternatives(requirement.tools(), candidate.state, 1, false, path)
                        : held;
                for (ItemId item : choices) {
                    if (!visit(item, path, depth + 1)) break;
                    for (State ready : ensureTool(item, requirement.minimumDurability(), candidate.state, path, depth + 1)) {
                        var selected = new ArrayList<>(candidate.selected);
                        selected.add(new SelectedToolRequirement(item, requirement.minimumDurability(), requirement.purpose()));
                        next.add(new Prepared(ready, List.copyOf(selected)));
                    }
                }
            }
            return trimPrepared(next);
        }

        private List<State> ensureTool(ItemId item, int minimumDurability, State state, Set<ItemId> path, int depth) {
            if (!visit(item, path, depth)) return List.of();
            int currentCount = state.count(item);
            int currentDurability = state.durability.getOrDefault(item, defaultDurability(item, catalog));
            if (currentCount > 0 && currentDurability >= minimumDurability) return List.of(state.copy());
            List<State> candidates;
            if (currentCount > 0) {
                if (path.contains(item)) {
                    fail(BlockedReason.Code.CYCLE, item, "Cannot replace a worn tool without a cycle", pathWith(path, item));
                    return List.of();
                }
                candidates = produce(item, 1, state, with(path, item), depth + 1);
            } else {
                candidates = satisfy(item, 1, false, state, path, depth + 1, "tool", -1);
            }
            var valid = new ArrayList<State>();
            for (State candidate : candidates) {
                int durability = candidate.durability.getOrDefault(item, defaultDurability(item, catalog));
                if (durability >= minimumDurability) valid.add(candidate);
                else fail(BlockedReason.Code.UNREACHABLE_REQUIREMENT, item, "Available item does not meet required tool durability", pathWith(path, item));
            }
            return trim(valid);
        }

        private List<Prepared> ensureStation(List<Prepared> initial, StationRequirement requirement, Set<ItemId> path, int depth) {
            var next = new ArrayList<Prepared>();
            for (Prepared candidate : initial) {
                if (candidate.state.stations.contains(requirement.station())) {
                    var selected = new ArrayList<>(candidate.selected);
                    selected.add(new SelectedStationRequirement(requirement.station(), requirement.purpose()));
                    next.add(new Prepared(candidate.state.copy(), List.copyOf(selected)));
                    continue;
                }
                for (State supplied : satisfy(requirement.placementItem(), 1, true, candidate.state, path, depth + 1, "station placement", -1)) {
                    if (supplied.steps.size() >= limits.maximumSteps()) {
                        fail(BlockedReason.Code.STEP_LIMIT, requirement.placementItem(), "Plan exceeds " + limits.maximumSteps() + " steps", pathWith(path, requirement.placementItem()));
                        continue;
                    }
                    State placed = supplied.copy();
                    placed.stations.add(requirement.station());
                    placed.steps.add(new PlanStep(
                            PlanKind.PLACE_STATION,
                            "place:" + requirement.station(),
                            null,
                            0,
                            1,
                            List.of(new SelectedItemRequirement(requirement.placementItem(), 1, true, "station placement", -1)),
                            List.of(),
                            null,
                            0,
                            0,
                            requirement.station(),
                            null,
                            Map.of()));
                    placed.operations++;
                    var selected = new ArrayList<>(candidate.selected);
                    selected.add(new SelectedStationRequirement(requirement.station(), requirement.purpose()));
                    next.add(new Prepared(placed, List.copyOf(selected)));
                }
            }
            return trimPrepared(next);
        }

        private List<ItemId> expanded(Ingredient ingredient, Set<ItemId> path) {
            var items = new TreeSet<ItemId>();
            for (ItemSelector selector : ingredient.alternatives()) items.addAll(expand(selector, null, path));
            return List.copyOf(items);
        }

        private List<ItemId> rankedAlternatives(Ingredient ingredient, State state, int amount, boolean consume, Set<ItemId> path) {
            List<ItemId> all = expanded(ingredient, path);
            Comparator<ItemId> order = Comparator
                    .comparingInt((ItemId item) -> availableCount(state, item, consume) >= amount ? 0
                            : availableCount(state, item, consume) > 0 && !catalog.sourcesFor(item).isEmpty() ? 1
                            : !catalog.sourcesFor(item).isEmpty() ? 2 : 3)
                    .thenComparing(Comparator.comparingInt((ItemId item) -> availableCount(state, item, consume)).reversed())
                    .thenComparing(Comparator.naturalOrder());
            List<ItemId> ranked = all.stream().sorted(order).limit(limits.maximumCandidatesPerBranch()).toList();
            if (all.size() > ranked.size()) truncated = true;
            return ranked;
        }

        private static int availableCount(State state, ItemId item, boolean consume) {
            return consume ? state.spendableCount(item) : state.count(item);
        }

        private List<ItemId> expand(ItemSelector selector, ItemId context, Set<ItemId> path) {
            List<ItemId> items = catalog.expand(selector);
            if (items.isEmpty()) {
                if (selector instanceof ItemSelector.Tag tag) {
                    fail(BlockedReason.Code.EMPTY_TAG, context, "Ingredient tag has no catalog members: #" + tag.tag(), path);
                } else {
                    ItemId item = ((ItemSelector.Exact) selector).item();
                    fail(BlockedReason.Code.UNKNOWN_ITEM, item, "Ingredient item is not present in the catalog: " + item, pathWith(path, item));
                }
            }
            return items;
        }

        private PlanStep makeStep(AcquisitionSource source, int operations, int outputCount, List<SelectedRequirement> selected) {
            if (source instanceof GatherSource gather) {
                return new PlanStep(PlanKind.GATHER, source.sourceId(), source.output(), outputCount, operations, selected,
                        gather.blocks(), null, 0, 0, null, null, Map.of());
            }
            if (source instanceof CraftingSource craft) {
                StationId station = selected.stream().filter(SelectedStationRequirement.class::isInstance)
                        .map(SelectedStationRequirement.class::cast).map(SelectedStationRequirement::station).findFirst().orElse(null);
                return new PlanStep(PlanKind.CRAFT, source.sourceId(), source.output(), outputCount, operations, selected,
                        List.of(), craft.recipeType(), craft.width(), craft.height(), station, null, Map.of());
            }
            if (source instanceof SmeltingSource smelt) {
                StationId station = selected.stream().filter(SelectedStationRequirement.class::isInstance)
                        .map(SelectedStationRequirement.class::cast).map(SelectedStationRequirement::station).findFirst().orElse(null);
                return new PlanStep(PlanKind.SMELT, source.sourceId(), source.output(), outputCount, operations, selected,
                        List.of(), null, 0, 0, station, null, Map.of("cook_ticks", Long.toString(smelt.cookTicks())));
            }
            if (source instanceof CustomSource custom) {
                StationId station = selected.stream().filter(SelectedStationRequirement.class::isInstance)
                        .map(SelectedStationRequirement.class::cast).map(SelectedStationRequirement::station).findFirst().orElse(null);
                return new PlanStep(PlanKind.CUSTOM, source.sourceId(), source.output(), outputCount, operations, selected,
                        List.of(), null, 0, 0, station, custom.sourceType(), custom.attributes());
            }
            return new PlanStep(PlanKind.CUSTOM, source.sourceId(), source.output(), outputCount, operations, selected,
                    List.of(), null, 0, 0, null, source.getClass().getName(), Map.of());
        }

        private int multiplyCount(int amount, int operations, ItemId item, Set<ItemId> path) {
            long multiplied = (long) amount * operations;
            if (multiplied > limits.maximumRequestedCount()) {
                fail(BlockedReason.Code.STEP_LIMIT, item, "Ingredient quantity exceeds planner limits", path);
                return limits.maximumRequestedCount() + 1;
            }
            return (int) multiplied;
        }

        private boolean visit(ItemId item, Set<ItemId> path, int depth) {
            if (depth > limits.maximumDepth()) {
                fail(BlockedReason.Code.DEPTH_LIMIT, item, "Dependency depth exceeds " + limits.maximumDepth(), pathWith(path, item));
                truncated = true;
                return false;
            }
            if (expanded >= limits.maximumExpandedNodes()) {
                setLimit(BlockedReason.Code.NODE_LIMIT, item, path);
                return false;
            }
            if (System.nanoTime() >= deadline) {
                setLimit(BlockedReason.Code.TIME_LIMIT, item, path);
                return false;
            }
            expanded++;
            return true;
        }

        private void setLimit(BlockedReason.Code code, ItemId item, Set<ItemId> path) {
            truncated = true;
            if (limitCode == null || code == BlockedReason.Code.TIME_LIMIT) limitCode = code;
            String detail = code == BlockedReason.Code.TIME_LIMIT ? "Planner time budget exhausted" : "Planner node budget exhausted";
            BlockedReason reason = new BlockedReason(code, item, detail, pathWith(path, item).stream().sorted().toList());
            if (limitReason == null || code == BlockedReason.Code.TIME_LIMIT) limitReason = reason;
            fail(code, item, detail, pathWith(path, item));
        }

        private void fail(BlockedReason.Code code, ItemId item, String detail, Iterable<ItemId> path) {
            if (failures.size() >= MAX_REASONS) return;
            var orderedPath = new ArrayList<ItemId>();
            path.forEach(orderedPath::add);
            orderedPath.sort(Comparator.naturalOrder());
            failures.add(new BlockedReason(code, item, detail, orderedPath));
        }

        private List<BlockedReason> reasons() {
            var ordered = new ArrayList<BlockedReason>();
            if (limitReason != null) ordered.add(limitReason);
            ordered.addAll(failures.stream().filter(reason -> !reason.equals(limitReason)).sorted(Comparator
                    .comparing((BlockedReason reason) -> reason.code().name())
                    .thenComparing(reason -> reason.item() == null ? "" : reason.item().toString())
                    .thenComparing(BlockedReason::detail)).toList());
            return ordered.stream().limit(MAX_REASONS).toList();
        }

        private List<State> trim(List<State> states) {
            if (states.size() <= 1) return states;
            if (states.size() > limits.maximumCandidatesPerBranch()) truncated = true;
            return states.stream().sorted(STATE_ORDER).limit(limits.maximumCandidatesPerBranch()).toList();
        }

        private List<Prepared> trimPrepared(List<Prepared> states) {
            if (states.size() <= 1) return states;
            if (states.size() > limits.maximumCandidatesPerBranch()) truncated = true;
            return states.stream().sorted(Comparator.comparing(Prepared::state, STATE_ORDER)).limit(limits.maximumCandidatesPerBranch()).toList();
        }
    }

    private static int ceilDiv(int amount, int divisor) {
        return (int) ceilDivLong(amount, divisor);
    }

    private static long ceilDivLong(long amount, long divisor) {
        if (amount < 0 || divisor < 1) throw new ArithmeticException("Invalid division");
        return amount == 0 ? 0 : 1 + ((amount - 1) / divisor);
    }

    private static Set<ItemId> with(Set<ItemId> values, ItemId item) {
        var copy = new LinkedHashSet<>(values);
        copy.add(item);
        return Set.copyOf(copy);
    }

    private static List<ItemId> pathWith(Set<ItemId> values, ItemId item) {
        var path = new ArrayList<>(values);
        if (!path.contains(item)) path.add(item);
        return path;
    }

    private static int defaultDurability(ItemId item, CatalogSnapshot catalog) {
        int maximum = catalog.maximumDurability(item);
        return maximum == 0 ? Integer.MAX_VALUE : maximum;
    }

    private static final Comparator<State> STATE_ORDER = Comparator
            .comparingLong((State state) -> state.gatherOperations)
            .thenComparingLong(state -> state.operations)
            .thenComparingInt(state -> state.steps.size())
            .thenComparing(State::tieKey);

    private record Prepared(State state, List<SelectedRequirement> selected) { }

    private static final class State {
        private final Map<ItemId, Integer> inventory;
        private final Map<ItemId, Integer> protectedHeld;
        private final Set<StationId> stations;
        private final Map<ItemId, Integer> durability;
        private final List<PlanStep> steps;
        private long operations;
        private long gatherOperations;

        private State(InventorySnapshot snapshot, CatalogSnapshot catalog) {
            inventory = new HashMap<>(snapshot.counts());
            protectedHeld = new HashMap<>(snapshot.protectedCounts());
            stations = new HashSet<>(snapshot.availableStations());
            durability = new HashMap<>(snapshot.remainingDurability());
            for (ItemId item : inventory.keySet()) durability.putIfAbsent(item, defaultDurability(item, catalog));
            steps = new ArrayList<>();
        }

        private State(State source) {
            inventory = new HashMap<>(source.inventory);
            protectedHeld = new HashMap<>(source.protectedHeld);
            stations = new HashSet<>(source.stations);
            durability = new HashMap<>(source.durability);
            steps = new ArrayList<>(source.steps);
            operations = source.operations;
            gatherOperations = source.gatherOperations;
        }

        private State copy() { return new State(this); }
        private int count(ItemId item) { return inventory.getOrDefault(item, 0); }
        private int spendableCount(ItemId item) { return count(item) - protectedHeld.getOrDefault(item, 0); }

        private void take(ItemId item, int amount) {
            int available = spendableCount(item);
            if (available < amount) throw new IllegalStateException("Planner inventory underflow for " + item);
            int left = count(item) - amount;
            if (left == 0) inventory.remove(item); else inventory.put(item, left);
        }

        private void add(ItemId item, int amount, int maximumDurability) {
            long total = (long) count(item) + amount;
            if (total > 1_000_000_000) throw new IllegalStateException("Planner inventory exceeded limit");
            inventory.put(item, (int) total);
            if (maximumDurability == 0) durability.put(item, Integer.MAX_VALUE);
            else durability.merge(item, maximumDurability, Math::max);
        }

        private String tieKey() {
            return steps.stream().map(step -> step.sourceId() + ":" + (step.output() == null ? "" : step.output()) + ":" + step.outputCount())
                    .reduce((left, right) -> left + "|" + right).orElse("");
        }
    }
}
