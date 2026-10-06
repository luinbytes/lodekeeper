package dev.lodekeeper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AcquisitionPlannerTest {
    private static final ItemId LOG = ItemId.parse("minecraft:oak_log");
    private static final ItemId PLANKS = ItemId.parse("minecraft:oak_planks");
    private static final ItemId STICKS = ItemId.parse("minecraft:stick");

    // Quantity and inventory checks use a stationary clock, independently of CI scheduling.
    private static AcquisitionPlanner planner() { return new AcquisitionPlanner(() -> 0L); }

    @Test
    void storedOnlyItemRetrievesExactCountsAndNeverRepeatsExhaustedStock() {
        ItemId relic = ItemId.parse("test:stored_only_relic");
        StoredItemSource stock = new StoredItemSource("storage:relic", relic, 5, "chest-slot-4", 17);
        CatalogSnapshot catalog = CatalogSnapshot.builder().source(stock).build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());

        PlanResult three = planner().plan(catalog, empty, relic, 3);
        PlanResult five = planner().planFast(catalog, empty, relic, 5);
        PlanResult six = planner().planFast(catalog, empty, relic, 6);

        assertTrue(three.success(), three.blockedReasons().toString());
        assertEquals(List.of(PlanKind.CUSTOM), three.steps().stream().map(PlanStep::kind).toList());
        assertEquals(List.of(3), three.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals("storage", three.steps().get(0).customType());
        assertEquals(Map.of("stockReference", "chest-slot-4", "stockGeneration", "17", "observedCount", "5"),
                three.steps().get(0).attributes());
        assertThrows(UnsupportedOperationException.class, () -> three.steps().get(0).attributes().put("observedCount", "99"));
        assertTrue(five.success(), five.blockedReasons().toString());
        assertEquals(List.of(5), five.steps().stream().map(PlanStep::outputCount).toList());
        assertFalse(six.success());
        assertEquals(List.of(), six.steps());
        assertTrue(six.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.NO_SOURCE));
        assertEquals(List.of(stock), catalog.sourcesFor(relic));
        assertEquals(List.of(stock), catalog.storedSources());
        PlannerLimits twoNodes = new PlannerLimits(48, 2, 25, 12, 4_096, 1_000_000);
        PlanResult bounded = planner().plan(catalog, empty, relic, 1, twoNodes);
        assertFalse(bounded.success());
        assertEquals(2, bounded.expandedNodes());
        assertEquals(BlockedReason.Code.NODE_LIMIT, bounded.blockedReasons().get(0).code());
    }

    @Test
    void partialStoredSupplyGathersOnlyTheResidualDeficitAndRespectsStepLimits() {
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(LOG, 0)
                .source(new StoredItemSource("storage:logs", LOG, 2, "chest-slot-0", 3))
                .source(new GatherSource("gather:logs", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        PlanResult result = planner().planFast(catalog, empty, LOG, 5);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("storage:logs", "gather:logs"), result.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(2, 3), result.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals(3, result.steps().get(1).operationCount());
        PlannerLimits oneStep = new PlannerLimits(48, 20_000, 25, 12, 1, 1_000_000);
        PlanResult limited = planner().plan(catalog, empty, LOG, 5, oneStep);
        assertTrue(limited.success(), limited.blockedReasons().toString());
        assertEquals(List.of("gather:logs"), limited.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(5), limited.steps().stream().map(PlanStep::outputCount).toList());
        CatalogSnapshot twoLots = CatalogSnapshot.builder().item(LOG, 0)
                .source(new StoredItemSource("storage:a_logs", LOG, 2, "slot-a", 1))
                .source(new StoredItemSource("storage:b_logs", LOG, 3, "slot-b", 1))
                .source(new GatherSource("gather:logs", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .build();
        PlanResult twoLotFallback = planner().planFast(twoLots, empty, LOG, 5, oneStep);
        assertTrue(twoLotFallback.success(), twoLotFallback.blockedReasons().toString());
        assertEquals(List.of("gather:logs"), twoLotFallback.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(5), twoLotFallback.steps().stream().map(PlanStep::outputCount).toList());
    }

    @Test
    void storedSupplyFromTwoPhysicalLotsStopsAtTheRequestedCount() {
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .source(new StoredItemSource("storage:a_logs", LOG, 2, "slot-a", 1))
                .source(new StoredItemSource("storage:b_logs", LOG, 3, "slot-b", 1)).build();
        PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of()), LOG, 4);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("storage:a_logs", "storage:b_logs"), result.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1, 3), result.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals("2", result.steps().get(0).attributes().get("observedCount"));
        assertEquals("3", result.steps().get(1).attributes().get("observedCount"));
        assertEquals(1, 2 - result.steps().get(0).outputCount());
    }

    @Test
    void storedLotAlternativesKeepTheSingleRetrievalThatFitsTheStepBudget() {
        ItemId relic = ItemId.parse("test:stored_only_relic");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .source(new StoredItemSource("storage:a_small", relic, 1, "small-slot", 1))
                .source(new StoredItemSource("storage:z_large", relic, 2, "large-slot", 1)).build();
        PlannerLimits oneStep = new PlannerLimits(48, 20_000, 25, 12, 1, 1_000_000);
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        for (PlanResult result : List.of(planner().plan(catalog, empty, relic, 2, oneStep),
                planner().planFast(catalog, empty, relic, 2, oneStep))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(List.of("storage:z_large"), result.steps().stream().map(PlanStep::sourceId).toList());
            assertEquals(List.of(2), result.steps().stream().map(PlanStep::outputCount).toList());
        }
    }

    @Test
    void storedLotAlternativesPreserveTheUsableToolForALaterRequirement() {
        ItemId pick = ItemId.parse("test:stored_pick");
        ItemId goal = ItemId.parse("test:consumed_pick_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 100).item(goal, 0)
                .source(new StoredItemSource("storage:a_good", pick, 1, "good-slot", 1,
                        new InventoryToolLot(100, false)))
                .source(new StoredItemSource("storage:z_unknown", pick, 1, "unknown-slot", 1))
                .source(new CraftingSource("craft:consumed_pick_goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(pick))),
                        List.of(new ToolRequirement(Ingredient.of(pick), 2, "later tool", 1)))).build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        for (PlanResult result : List.of(planner().plan(catalog, empty, goal, 1),
                planner().planFast(catalog, empty, goal, 1))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(List.of("storage:z_unknown", "storage:a_good", "craft:consumed_pick_goal"),
                    result.steps().stream().map(PlanStep::sourceId).toList());
            assertEquals(List.of(1, 1, 1), result.steps().stream().map(PlanStep::outputCount).toList());
            assertEquals(List.of(new SelectedItemRequirement(pick, 1, true, "recipe ingredient", 0),
                            new SelectedToolRequirement(pick, 2, "later tool")),
                    result.steps().get(2).requirements());
        }
    }

    @Test
    void storedItemLotsCanConsumeOneKnownCopyAndKeepAnotherForTheLaterTool() {
        ItemId pick = ItemId.parse("test:partial_stored_pick");
        ItemId goal = ItemId.parse("test:partial_consumed_pick_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 100).item(goal, 0)
                .source(new StoredItemSource("storage:a_good", pick, 2, "good-slot", 1,
                        new InventoryToolLot(100, false)))
                .source(new StoredItemSource("storage:z_unknown", pick, 1, "unknown-slot", 1))
                .source(new CraftingSource("craft:partial_consumed_pick_goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(2, pick))),
                        List.of(new ToolRequirement(Ingredient.of(pick), 2, "later tool", 1)))).build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        for (PlanResult result : List.of(planner().plan(catalog, empty, goal, 1),
                planner().planFast(catalog, empty, goal, 1))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(List.of("storage:a_good", "storage:z_unknown", "storage:a_good", "craft:partial_consumed_pick_goal"),
                    result.steps().stream().map(PlanStep::sourceId).toList());
            assertEquals(List.of(1, 1, 1, 1), result.steps().stream().map(PlanStep::outputCount).toList());
            assertEquals(List.of(new SelectedItemRequirement(pick, 2, true, "recipe ingredient", 0),
                            new SelectedToolRequirement(pick, 2, "later tool")), result.steps().get(3).requirements());
            assertEquals("100", result.steps().get(0).attributes().get("remainingDurability"));
            assertFalse(result.steps().get(1).attributes().containsKey("remainingDurability"));
            assertEquals("100", result.steps().get(2).attributes().get("remainingDurability"));
        }
    }

    @Test
    void storedToolLotsCanMixWornCopiesAndKeepOneFreshStrongCopyForTheLaterRequirement() {
        ItemId pick = ItemId.parse("test:partial_finite_pick");
        ItemId ore = ItemId.parse("test:partial_finite_ore");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 4).item(ore, 0)
                .source(new StoredItemSource("storage:a_strong", pick, 2, "strong-slot", 1,
                        new InventoryToolLot(4, false)))
                .source(new StoredItemSource("storage:z_weak", pick, 1, "weak-slot", 1,
                        new InventoryToolLot(3, false)))
                .source(new GatherSource("gather:partial_finite_ore", ore, 1, List.of(BlockId.parse("test:ore")),
                        List.of(new ToolRequirement(Ingredient.of(pick), 2, "first tool", 1),
                                new ToolRequirement(Ingredient.of(pick), 4, "later fresh tool", 0)))).build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        for (PlanResult result : List.of(planner().plan(catalog, empty, ore, 5),
                planner().planFast(catalog, empty, ore, 5))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(List.of("storage:a_strong", "storage:z_weak", "storage:a_strong", "gather:partial_finite_ore"),
                    result.steps().stream().map(PlanStep::sourceId).toList());
            assertEquals(List.of(1, 1, 1, 5), result.steps().stream().map(PlanStep::outputCount).toList());
            assertEquals(List.of("4", "3", "4"), result.steps().subList(0, 3).stream()
                    .map(step -> step.attributes().get("remainingDurability")).toList());
            assertEquals(List.of(new SelectedToolRequirement(pick, 2, "first tool"),
                            new SelectedToolRequirement(pick, 4, "later fresh tool")), result.steps().get(3).requirements());
        }
        assertFalse(planner().plan(catalog, empty, ore, 6).success());
        assertFalse(planner().planFast(catalog, empty, ore, 6).success());
    }

    @Test
    void storedToolLotsCanSkipTheStrongerCopyToFitTheWholeGather() {
        ItemId pick = ItemId.parse("test:finite_pick");
        ItemId ore = ItemId.parse("test:finite_ore");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 4).item(ore, 0)
                .source(new StoredItemSource("storage:high", pick, 1, "high-slot", 1,
                        new InventoryToolLot(4, false)))
                .source(new StoredItemSource("storage:lower", pick, 3, "lower-slot", 1,
                        new InventoryToolLot(3, false)))
                .source(new GatherSource("gather:finite_ore", ore, 1, List.of(BlockId.parse("test:ore")),
                        List.of(new ToolRequirement(Ingredient.of(pick), 2, "mine", 1)))).build();
        PlannerLimits twoSteps = new PlannerLimits(48, 20_000, 25, 12, 2, 1_000_000);
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        for (PlanResult result : List.of(planner().plan(catalog, empty, ore, 5, twoSteps),
                planner().planFast(catalog, empty, ore, 5, twoSteps))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(List.of("storage:lower", "gather:finite_ore"),
                    result.steps().stream().map(PlanStep::sourceId).toList());
            assertEquals(List.of(3, 5), result.steps().stream().map(PlanStep::outputCount).toList());
            assertEquals(5, result.steps().get(1).operationCount());
            assertEquals(List.of(new SelectedToolRequirement(pick, 2, "mine")), result.steps().get(1).requirements());
        }
    }

    @Test
    void storageAliasesAndChangedObservationsCannotMultiplyOnePhysicalBudget() {
        StoredItemSource observed = new StoredItemSource("storage:original", LOG, 2, "same-chest-slot", 1);
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().source(observed);
        assertThrows(IllegalArgumentException.class, () -> builder.source(
                new StoredItemSource("storage:alias", LOG, 2, "same-chest-slot", 1)));
        assertThrows(IllegalArgumentException.class, () -> builder.source(
                new StoredItemSource("storage:alias", LOG, 4, "same-chest-slot", 2)));
        CatalogSnapshot catalog = builder.build();
        assertThrows(IllegalArgumentException.class, () -> catalog.withOutputSources(LOG,
                List.of(new StoredItemSource("storage:original", LOG, 4, "same-chest-slot", 1))));
        PlanResult exact = planner().planFast(catalog, new InventorySnapshot(Map.of()), LOG, 2);
        assertTrue(exact.success(), exact.blockedReasons().toString());
        assertEquals(List.of(2), exact.steps().stream().map(PlanStep::outputCount).toList());
        assertFalse(planner().planFast(catalog, new InventorySnapshot(Map.of()), LOG, 3).success());
        CatalogSnapshot excluded = catalog.withOutputSources(LOG, Set.of());
        assertFalse(planner().planFast(excluded, new InventorySnapshot(Map.of()), LOG, 1).success());
    }

    @Test
    void sharedProjectIngredientsDebitStorageOnceAcrossBothGoals() {
        ItemId first = ItemId.parse("test:a_stock_goal");
        ItemId second = ItemId.parse("test:b_stock_goal");
        ProjectSpec project = new ProjectSpec("finite_stock", "Both goals consume two logs.",
                Map.of(first, 1, second, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
        for (int storedCount : new int[]{3, 4}) {
            CatalogSnapshot catalog = CatalogSnapshot.builder().item(first, 0).item(second, 0)
                    .source(new StoredItemSource("storage:shared_logs", LOG, storedCount, "shared-slot", 1))
                    .source(new CraftingSource("craft:first_stock_goal", first, 1, RecipeType.SHAPELESS, 0, 0,
                            List.of(new RecipeSlot(-1, Ingredient.of(2, LOG))), List.of()))
                    .source(new CraftingSource("craft:second_stock_goal", second, 1, RecipeType.SHAPELESS, 0, 0,
                            List.of(new RecipeSlot(-1, Ingredient.of(2, LOG))), List.of())).build();
            ProjectPlanResult result = planner().planProjectFast(catalog, new InventorySnapshot(Map.of()), project,
                    PlannerLimits.DEFAULT, PlanningPreferences.NONE);
            if (storedCount == 3) {
                assertFalse(result.success());
                assertEquals(List.of(), result.steps());
                assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.NO_SOURCE));
            } else {
                assertTrue(result.success(), result.blockedReasons().toString());
                assertEquals(List.of("storage:shared_logs", "craft:first_stock_goal", "storage:shared_logs", "craft:second_stock_goal"),
                        result.steps().stream().map(PlanStep::sourceId).toList());
                assertEquals(List.of(2, 1, 2, 1), result.steps().stream().map(PlanStep::outputCount).toList());
                assertEquals(0, 4 - result.steps().stream().filter(step -> step.customType() != null)
                        .mapToInt(PlanStep::outputCount).sum());
            }
        }
    }

    @Test
    void protectedHeldMaterialIsPreservedWhenStorageSuppliesTheRecipe() {
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(PLANKS, 0)
                .source(new StoredItemSource("storage:logs", LOG, 1, "slot-1", 1))
                .source(new CraftingSource("craft:planks", PLANKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(LOG))), List.of())).build();
        InventorySnapshot protectedHeld = new InventorySnapshot(Map.of(LOG, 1), Set.of(), Map.of(), Map.of(LOG, 1));
        PlanResult result = planner().planFast(catalog, protectedHeld, PLANKS, 4);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("storage:logs", "craft:planks"), result.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1, 4), result.steps().stream().map(PlanStep::outputCount).toList());
        assertFalse(planner().planFast(catalog, protectedHeld, PLANKS, 8).success());
        assertEquals(1, protectedHeld.count(LOG));
    }

    @Test
    void recipeAlternativesUseAvailableStorageBeforeGatheringAndCanSplitStoredLots() {
        ItemId birch = ItemId.parse("minecraft:birch_log");
        ItemId goal = ItemId.parse("test:wood_bundle");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(goal, 0).item(LOG, 0).item(birch, 0)
                .source(new GatherSource("gather:oak", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .source(new StoredItemSource("storage:birch", birch, 2, "birch-slot", 1))
                .source(new StoredItemSource("storage:oak", LOG, 1, "oak-slot", 1))
                .source(new CraftingSource("craft:bundle", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(LOG, birch)),
                                new RecipeSlot(-1, Ingredient.of(LOG, birch)),
                                new RecipeSlot(-1, Ingredient.of(LOG, birch))), List.of())).build();
        PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of()), goal, 1);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("storage:birch", "storage:oak", "craft:bundle"),
                result.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(2, 1, 1), result.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals(List.of(birch, birch, LOG), result.steps().get(2).requirements().stream()
                .map(SelectedItemRequirement.class::cast).map(SelectedItemRequirement::item).toList());
    }

    @Test
    void mixedStoredTagMembersPreserveTheExactMaterialNeededLater() {
        ItemId a = ItemId.parse("test:mix_a");
        ItemId b = ItemId.parse("test:mix_b");
        ItemId c = ItemId.parse("test:mix_c");
        ItemId goal = ItemId.parse("test:mixed_stock_goal");
        TagId choices = TagId.parse("test:stock_choices");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(goal, 0).tag(choices, Set.of(a, b, c))
                .source(new StoredItemSource("storage:a", a, 2, "slot-a", 1))
                .source(new StoredItemSource("storage:b", b, 1, "slot-b", 1))
                .source(new StoredItemSource("storage:c", c, 1, "slot-c", 1))
                .source(new CraftingSource("craft:mixed_stock_goal", goal, 1, RecipeType.SHAPED, 2, 1,
                        List.of(new RecipeSlot(0, Ingredient.tag(choices)), new RecipeSlot(1, Ingredient.tag(choices))),
                        List.of(new ItemRequirement(Ingredient.of(2, a), true, "later exact material")))).build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        for (PlanResult result : List.of(planner().plan(catalog, empty, goal, 1),
                planner().planFast(catalog, empty, goal, 1))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(List.of(new SelectedItemRequirement(b, 1, true, "recipe ingredient", 0),
                            new SelectedItemRequirement(c, 1, true, "recipe ingredient", 1),
                            new SelectedItemRequirement(a, 2, true, "later exact material", -1)),
                    result.steps().get(result.steps().size() - 1).requirements());
            assertEquals(Map.of(a, 2, b, 1, c, 1), result.steps().stream()
                    .filter(step -> step.kind() == PlanKind.CUSTOM).collect(java.util.stream.Collectors.toMap(
                            PlanStep::output, PlanStep::outputCount, Integer::sum)));
        }
    }

    @Test
    void mixedStoredTagMembersCanUsePartOfTheLargestLotWithoutSpendingProtectedHeldStock() {
        ItemId a = ItemId.parse("test:partial_a");
        ItemId b = ItemId.parse("test:partial_b");
        ItemId goal = ItemId.parse("test:partial_stock_goal");
        TagId choices = TagId.parse("test:partial_choices");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(goal, 0).tag(choices, Set.of(a, b))
                .source(new StoredItemSource("storage:a", a, 2, "slot-a", 1))
                .source(new StoredItemSource("storage:b", b, 1, "slot-b", 1))
                .source(new CraftingSource("craft:partial_stock_goal", goal, 1, RecipeType.SHAPED, 2, 1,
                        List.of(new RecipeSlot(0, Ingredient.tag(choices)), new RecipeSlot(1, Ingredient.tag(choices))),
                        List.of(new ItemRequirement(Ingredient.of(a), true, "later exact material")))).build();
        for (InventorySnapshot inventory : List.of(new InventorySnapshot(Map.of()),
                new InventorySnapshot(Map.of(a, 1), Set.of(), Map.of(), Map.of(a, 1)))) {
            for (PlanResult result : List.of(planner().plan(catalog, inventory, goal, 1),
                    planner().planFast(catalog, inventory, goal, 1))) {
                assertTrue(result.success(), result.blockedReasons().toString());
                assertEquals(List.of(new SelectedItemRequirement(a, 1, true, "recipe ingredient", 0),
                                new SelectedItemRequirement(b, 1, true, "recipe ingredient", 1),
                                new SelectedItemRequirement(a, 1, true, "later exact material", -1)),
                        result.steps().get(result.steps().size() - 1).requirements());
                assertEquals(Map.of(a, 2, b, 1), result.steps().stream()
                        .filter(step -> step.kind() == PlanKind.CUSTOM).collect(java.util.stream.Collectors.toMap(
                                PlanStep::output, PlanStep::outputCount, Integer::sum)));
            }
            assertFalse(planner().plan(catalog, inventory, goal, 2).success());
            assertFalse(planner().planFast(catalog, inventory, goal, 2).success());
        }
    }

    @Test
    void bulkStoredTagAllocationsAreDeterministicAndReportTheQuantityBranchCap() {
        ItemId a = ItemId.parse("test:bulk_a");
        ItemId b = ItemId.parse("test:bulk_b");
        ItemId goal = ItemId.parse("test:bulk_stock_goal");
        TagId choices = TagId.parse("test:bulk_choices");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(goal, 0).tag(choices, Set.of(a, b))
                .source(new StoredItemSource("storage:a", a, 1_000_000, "slot-a", 1))
                .source(new StoredItemSource("storage:b", b, 1_000_000, "slot-b", 1))
                .source(new CraftingSource("craft:bulk_stock_goal", goal, 1, RecipeType.SHAPED, 2, 1,
                        List.of(new RecipeSlot(0, Ingredient.tag(choices)), new RecipeSlot(1, Ingredient.tag(choices))),
                        List.of())).build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        PlanResult full = planner().plan(catalog, empty, goal, 500_000);
        PlanResult fast = planner().planFast(catalog, empty, goal, 500_000);
        for (PlanResult result : List.of(full, fast)) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertFalse(result.optimal());
            assertTrue(result.expandedNodes() <= PlannerLimits.DEFAULT.maximumExpandedNodes());
            assertEquals(2, result.steps().size());
            assertEquals(1_000_000, result.steps().get(0).outputCount());
            assertEquals(List.of(new SelectedItemRequirement(a, 500_000, true, "recipe ingredient", 0),
                            new SelectedItemRequirement(a, 500_000, true, "recipe ingredient", 1)),
                    result.steps().get(1).requirements());
        }
        assertEquals(full.steps(), planner().plan(catalog, empty, goal, 500_000).steps());
        assertEquals(fast.steps(), planner().planFast(catalog, empty, goal, 500_000).steps());
        PlannerLimits capped = new PlannerLimits(48, 20, 25, 12, 4_096, 1_000_000);
        for (PlanResult result : List.of(planner().plan(catalog, empty, goal, 500_000, capped),
                planner().planFast(catalog, empty, goal, 500_000, capped))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertFalse(result.optimal());
            assertEquals(20, result.expandedNodes());
            assertEquals(full.steps(), result.steps());
        }
    }

    @Test
    void groupedRecipeAlternativesCanSkipStoredMaterialThroughLaterSourceRequirements() {
        ItemId stored = ItemId.parse("test:a_stored_ingredient");
        ItemId gathered = ItemId.parse("test:b_gathered_ingredient");
        ItemId extra = ItemId.parse("test:c_extra_ingredient");
        ItemId goal = ItemId.parse("test:bounded_recipe_goal");
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        for (boolean needsExtra : new boolean[]{false, true}) {
            CatalogSnapshot catalog = CatalogSnapshot.builder().item(goal, 0).item(gathered, 0).item(extra, 0)
                    .source(new StoredItemSource("storage:ingredient_a", stored, 1, "ingredient-slot", 1))
                    .source(new GatherSource("gather:ingredient_b", gathered, 1, List.of(BlockId.parse("test:ingredient_block"))))
                    .source(new GatherSource("gather:ingredient_c", extra, 1, List.of(BlockId.parse("test:extra_block"))))
                    .source(new CraftingSource("craft:bounded_goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                            List.of(new RecipeSlot(-1, Ingredient.of(stored, gathered)),
                                    new RecipeSlot(-1, Ingredient.of(stored, gathered))),
                            needsExtra ? List.of(new ItemRequirement(Ingredient.of(extra), true, "extra")) : List.of())).build();
            PlannerLimits limits = new PlannerLimits(48, 8_000, 25, 12, needsExtra ? 3 : 2, 1_000_000);
            for (PlanResult result : List.of(planner().plan(catalog, empty, goal, 1, limits),
                    planner().planFast(catalog, empty, goal, 1, limits))) {
                assertTrue(result.success(), result.blockedReasons().toString());
                assertEquals(needsExtra ? List.of("gather:ingredient_b", "gather:ingredient_c", "craft:bounded_goal")
                                : List.of("gather:ingredient_b", "craft:bounded_goal"),
                        result.steps().stream().map(PlanStep::sourceId).toList());
                assertEquals(needsExtra ? List.of(2, 1, 1) : List.of(2, 1),
                        result.steps().stream().map(PlanStep::outputCount).toList());
                assertEquals(2, result.steps().get(0).operationCount());
                PlanStep craft = result.steps().get(result.steps().size() - 1);
                assertEquals(needsExtra ? List.of(
                                new SelectedItemRequirement(gathered, 1, true, "recipe ingredient", 0),
                                new SelectedItemRequirement(gathered, 1, true, "recipe ingredient", 1),
                                new SelectedItemRequirement(extra, 1, true, "extra", -1))
                                : List.of(new SelectedItemRequirement(gathered, 1, true, "recipe ingredient", 0),
                                new SelectedItemRequirement(gathered, 1, true, "recipe ingredient", 1)), craft.requirements());
            }
        }
    }

    @Test
    void storedToolRetrievalSelectsKnownUsableLotsAndKeepsActualWearCapacity() {
        ItemId pick = ItemId.parse("test:stored_pick");
        ItemId ore = ItemId.parse("test:stored_tool_ore");
        ToolRequirement tool = new ToolRequirement(Ingredient.of(pick), 2, "mine", 1);
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 100).item(ore, 0)
                .source(new StoredItemSource("storage:a_unknown", pick, 1, "unknown-slot", 1))
                .source(new StoredItemSource("storage:b_silk", pick, 1, "silk-slot", 1, new InventoryToolLot(100, true)))
                .source(new StoredItemSource("storage:c_worn", pick, 1, "worn-slot", 1, new InventoryToolLot(1, false)))
                .source(new StoredItemSource("storage:d_usable", pick, 2, "usable-slot", 1, new InventoryToolLot(3, false)))
                .source(new GatherSource("mine:ore", ore, 1, List.of(BlockId.parse("test:ore")), List.of(tool))).build();
        InventorySnapshot heldWorn = new InventorySnapshot(Map.of(pick, 1), Set.of(), Map.of(pick, 1), Map.of(pick, 1));
        PlanResult four = planner().planFast(catalog, heldWorn, ore, 4);
        PlanResult five = planner().planFast(catalog, heldWorn, ore, 5);

        assertTrue(four.success(), four.blockedReasons().toString());
        assertEquals(List.of("storage:d_usable", "mine:ore"), four.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(2, 4), four.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals("3", four.steps().get(0).attributes().get("remainingDurability"));
        assertEquals("false", four.steps().get(0).attributes().get("silkTouch"));
        assertFalse(five.success());
    }

    @Test
    void silkTouchStorageNeedsCompatibleHarvestAndUnknownDurabilityCannotSupplyATool() {
        ItemId pick = ItemId.parse("test:stored_pick");
        ItemId raw = ItemId.parse("test:raw_ore");
        ItemId block = ItemId.parse("test:ore_block");
        ToolRequirement tool = new ToolRequirement(Ingredient.of(pick), 2, "mine", 1);
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 100).item(raw, 0).item(block, 0)
                .source(new StoredItemSource("storage:silk", pick, 1, "silk-slot", 1, new InventoryToolLot(4, true)))
                .source(new StoredItemSource("storage:unknown", pick, 10, "unknown-slot", 1))
                .source(new GatherSource("mine:raw", raw, 1, List.of(BlockId.parse("test:ore")), List.of(tool)))
                .source(new GatherSource("mine:block", block, 1, List.of(BlockId.parse("test:ore")),
                        List.of(tool), Map.of("silkTouchCompatible", "true"))).build();
        InventorySnapshot empty = new InventorySnapshot(Map.of());
        PlanResult compatible = planner().planFast(catalog, empty, block, 3);

        assertTrue(compatible.success(), compatible.blockedReasons().toString());
        assertEquals(List.of("storage:silk", "mine:block"), compatible.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1, 3), compatible.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals("true", compatible.steps().get(0).attributes().get("silkTouch"));
        assertFalse(planner().planFast(catalog, empty, raw, 1).success());
        assertFalse(planner().planFast(catalog, empty, block, 4).success());
    }

    @Test
    void minimumOnlyToolRequirementSkipsInsufficientStoredDurability() {
        ItemId pick = ItemId.parse("test:stored_pick");
        ItemId goal = ItemId.parse("test:custom_tool_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 100).item(goal, 0)
                .source(new StoredItemSource("storage:a_worn", pick, 1, "worn-slot", 1, new InventoryToolLot(4, false)))
                .source(new StoredItemSource("storage:z_usable", pick, 1, "usable-slot", 1, new InventoryToolLot(6, false)))
                .source(new CustomSource("custom:goal", "tool_action", goal, 1,
                        List.of(new ToolRequirement(Ingredient.of(pick), 5, "use tool")), Map.of())).build();
        PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of()), goal, 1);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("storage:z_usable", "custom:goal"), result.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals("6", result.steps().get(0).attributes().get("remainingDurability"));
    }

    @Test
    void storedDamagedToolAndCraftedReplacementSupplyOnlyTheirRealCombinedCapacity() {
        ItemId pick = ItemId.parse("test:stored_pick");
        ItemId ore = ItemId.parse("test:stored_tool_ore");
        ItemId material = ItemId.parse("test:pick_material");
        ToolRequirement tool = new ToolRequirement(Ingredient.of(pick), 2, "mine", 1);
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 6).item(ore, 0).item(material, 0)
                .source(new StoredItemSource("storage:worn", pick, 1, "worn-slot", 1, new InventoryToolLot(3, false)))
                .source(new CraftingSource("craft:pick", pick, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(material))), List.of()))
                .source(new GatherSource("mine:ore", ore, 1, List.of(BlockId.parse("test:ore")), List.of(tool))).build();
        InventorySnapshot oneCraft = new InventorySnapshot(Map.of(material, 1));
        PlanResult seven = planner().planFast(catalog, oneCraft, ore, 7);

        assertTrue(seven.success(), seven.blockedReasons().toString());
        assertEquals(List.of("storage:worn", "craft:pick", "mine:ore"), seven.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1, 1, 7), seven.steps().stream().map(PlanStep::outputCount).toList());
        assertFalse(planner().planFast(catalog, oneCraft, ore, 8).success());
        PlannerLimits twoSteps = new PlannerLimits(48, 8_000, 25, 12, 2, 1_000_000);
        PlanResult craftOnlyFallback = planner().planFast(catalog, oneCraft, ore, 5, twoSteps);
        assertTrue(craftOnlyFallback.success(), craftOnlyFallback.blockedReasons().toString());
        assertEquals(List.of("craft:pick", "mine:ore"), craftOnlyFallback.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1, 5), craftOnlyFallback.steps().stream().map(PlanStep::outputCount).toList());
    }

    @Test
    void observedStoredToolsRequireDurableDefinitionsButMayExceedRegistryDurability() {
        ItemId pick = ItemId.parse("test:component_pick");
        ItemId ore = ItemId.parse("test:component_ore");
        StoredItemSource unsafe = new StoredItemSource("storage:undefined_pick", pick, 1, "undefined-slot", 1,
                new InventoryToolLot(1, true));
        ToolRequirement tool = new ToolRequirement(Ingredient.of(pick), 2, "mine", 1);
        CatalogSnapshot.Builder missing = CatalogSnapshot.builder().item(ore, 0).source(unsafe)
                .source(new GatherSource("mine:component_ore", ore, 1, List.of(BlockId.parse("test:ore")), List.of(tool)));
        assertThrows(IllegalArgumentException.class, missing::build);
        assertThrows(IllegalArgumentException.class, () -> CatalogSnapshot.builder().item(pick, 0).source(unsafe).build());
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 3).item(ore, 0)
                .source(new StoredItemSource("storage:component_pick", pick, 1, "component-slot", 2,
                        new InventoryToolLot(7, false)))
                .source(new GatherSource("mine:component_ore", ore, 1, List.of(BlockId.parse("test:ore")), List.of(tool)))
                .build();
        PlanResult six = planner().planFast(catalog, new InventorySnapshot(Map.of()), ore, 6);
        assertTrue(six.success(), six.blockedReasons().toString());
        assertEquals(List.of("storage:component_pick", "mine:component_ore"), six.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1, 6), six.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals("7", six.steps().get(0).attributes().get("remainingDurability"));
        assertFalse(planner().planFast(catalog, new InventorySnapshot(Map.of()), ore, 7).success());
    }

    @Test
    void consumedStoredToolCannotLeaveAnAssumedUsableProtectedHeldStack() {
        ItemId pick = ItemId.parse("test:recipe_pick");
        ItemId goal = ItemId.parse("test:recipe_tool_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 100).item(goal, 0)
                .source(new StoredItemSource("storage:recipe_pick", pick, 1, "recipe-pick-slot", 1,
                        new InventoryToolLot(100, false)))
                .source(new CraftingSource("craft:tool_goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(pick))),
                        List.of(new ToolRequirement(Ingredient.of(pick), 2, "use remaining pick", 1)))).build();
        InventorySnapshot wornProtected = new InventorySnapshot(Map.of(pick, 1), Set.of(), Map.of(pick, 1), Map.of(pick, 1));
        InventorySnapshot unknownProtected = new InventorySnapshot(Map.of(pick, 1), Set.of(), Map.of(), Map.of(pick, 1));
        InventorySnapshot identicalProtected = new InventorySnapshot(Map.of(pick, 1), Set.of(), Map.of(pick, 100), Map.of(pick, 1));
        PlanResult worn = planner().planFast(catalog, wornProtected, goal, 1);
        PlanResult unknown = planner().planFast(catalog, unknownProtected, goal, 1);
        PlanResult identical = planner().planFast(catalog, identicalProtected, goal, 1);

        assertFalse(worn.success());
        assertEquals(List.of(), worn.steps());
        assertFalse(unknown.success());
        assertEquals(List.of(), unknown.steps());
        assertTrue(identical.success(), identical.blockedReasons().toString());
        assertEquals(List.of("storage:recipe_pick", "craft:tool_goal"), identical.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1, 1), identical.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals(List.of(new SelectedItemRequirement(pick, 1, true, "recipe ingredient", 0),
                        new SelectedToolRequirement(pick, 2, "use remaining pick")), identical.steps().get(1).requirements());
    }

    @Test
    void consumingToolsWithUnknownCopiesRetainsOnlyGuaranteedKnownSurvivors() {
        ItemId pick = ItemId.parse("test:recipe_pick");
        ItemId goal = ItemId.parse("test:recipe_tool_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pick, 100).item(goal, 0)
                .source(new CraftingSource("craft:tool_goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(pick))),
                        List.of(new ToolRequirement(Ingredient.of(pick), 2, "use remaining pick", 1)))).build();
        InventorySnapshot oneKnownOneUnknown = new InventorySnapshot(Map.of(pick, 2), Set.of(), Map.of(pick, 100),
                Map.of(pick, 1), Map.of(pick, List.of(100)));
        InventorySnapshot twoKnownOneUnknown = new InventorySnapshot(Map.of(pick, 3), Set.of(), Map.of(pick, 100),
                Map.of(pick, 1), Map.of(pick, List.of(100, 100)));
        PlanResult ambiguous = planner().planFast(catalog, oneKnownOneUnknown, goal, 1);
        PlanResult guaranteed = planner().planFast(catalog, twoKnownOneUnknown, goal, 1);

        assertFalse(ambiguous.success());
        assertEquals(List.of(), ambiguous.steps());
        assertTrue(guaranteed.success(), guaranteed.blockedReasons().toString());
        assertEquals(List.of("craft:tool_goal"), guaranteed.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(1), guaranteed.steps().stream().map(PlanStep::outputCount).toList());
        assertEquals(List.of(new SelectedItemRequirement(pick, 1, true, "recipe ingredient", 0),
                        new SelectedToolRequirement(pick, 2, "use remaining pick")), guaranteed.steps().get(0).requirements());
    }

    @Test
    void elapsedBudgetRejectsTheExactDeadlineWithoutTimingFunctionalChecks() {
        CatalogSnapshot catalog = woodToSticksCatalog();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(LOG, 1));
        long deadline = PlannerLimits.DEFAULT.maximumElapsedMillis() * 1_000_000L;
        for (long observed : new long[]{deadline - 1, deadline}) {
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            AcquisitionPlanner planner = new AcquisitionPlanner(() -> reads.getAndIncrement() == 0 ? 0L : observed);
            PlanResult result = planner.plan(catalog, inventory, LOG, 1);
            if (observed < deadline) {
                assertTrue(result.success());
                assertEquals(observed, result.elapsedNanos());
            } else {
                assertFalse(result.success());
                assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.TIME_LIMIT));
                assertEquals(0, result.expandedNodes());
            }
        }
    }

    @Test
    void explorationRecoveryProvesAnExcludedGatherPathDespiteOtherImpossibleRecipeBranches() {
        ItemId goal = ItemId.parse("test:iron_goal");
        ItemId rawIron = ItemId.parse("test:raw_iron");
        ItemId chainmail = ItemId.parse("test:chainmail");
        String ironGatherId = "test:gather_raw_iron";
        CatalogSnapshot full = recoveryCatalog(goal, rawIron, chainmail, true);
        CatalogSnapshot filtered = recoveryCatalog(goal, rawIron, chainmail, false);
        InventorySnapshot inventory = new InventorySnapshot(Map.of());
        PlanResult filteredPlan = planner().planFast(filtered, inventory, goal, 1);
        PlanResult fullPlan = planner().planFast(full, inventory, goal, 1);

        assertFalse(filteredPlan.success());
        assertTrue(filteredPlan.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.NO_SOURCE));
        assertTrue(fullPlan.success());
        assertTrue(fullPlan.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER && step.sourceId().equals(ironGatherId)));
        assertTrue(ExplorationRecovery.provesExploration(filteredPlan, fullPlan, full, Set.of(ironGatherId)));
    }

    @Test
    void explorationRecoveryRejectsIncompleteUnrelatedOrCustomPlans() {
        ItemId goal = ItemId.parse("test:iron_goal");
        ItemId rawIron = ItemId.parse("test:raw_iron");
        ItemId chainmail = ItemId.parse("test:chainmail");
        String ironGatherId = "test:gather_raw_iron";
        CatalogSnapshot filtered = recoveryCatalog(goal, rawIron, chainmail, false);
        CatalogSnapshot full = recoveryCatalog(goal, rawIron, chainmail, true);
        InventorySnapshot inventory = new InventorySnapshot(Map.of());
        PlanResult filteredPlan = planner().planFast(filtered, inventory, goal, 1);
        PlanResult fullPlan = planner().planFast(full, inventory, goal, 1);

        assertFalse(ExplorationRecovery.provesExploration(filteredPlan, filteredPlan, full, Set.of(ironGatherId)));

        CatalogSnapshot unrelatedGatherCatalog = CatalogSnapshot.builder()
                .item(goal, 0).item(rawIron, 0).item(chainmail, 0)
                .item(ItemId.parse("test:wood"), 0)
                .source(new CraftingSource("goal-from-wood", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(ItemId.parse("test:wood")))), List.of()))
                .source(new GatherSource(ironGatherId, rawIron, 1, List.of(BlockId.parse("test:iron_ore"))))
                .source(new GatherSource("test:gather_wood", ItemId.parse("test:wood"), 1, List.of(BlockId.parse("test:wood_block"))))
                .build();
        PlanResult unrelatedGatherPlan = planner().planFast(unrelatedGatherCatalog, inventory, goal, 1);
        assertTrue(unrelatedGatherPlan.success());
        assertFalse(unrelatedGatherPlan.steps().stream().anyMatch(step -> step.sourceId().equals(ironGatherId)));
        assertFalse(ExplorationRecovery.provesExploration(filteredPlan, unrelatedGatherPlan,
                unrelatedGatherCatalog, Set.of(ironGatherId)));
        assertFalse(ExplorationRecovery.provesExploration(filteredPlan, fullPlan, full, Set.of("test:goal-from-iron")));

        CatalogSnapshot customCatalog = CatalogSnapshot.builder()
                .item(goal, 0).item(rawIron, 0).item(chainmail, 0)
                .source(new GatherSource(ironGatherId, rawIron, 1, List.of(BlockId.parse("test:iron_ore"))))
                .source(new CustomSource("test:custom_goal", "test:custom", goal, 1,
                        List.of(new ItemRequirement(Ingredient.of(rawIron), true, "material")), Map.of()))
                .build();
        PlanResult customPlan = planner().planFast(customCatalog, inventory, goal, 1);
        assertTrue(customPlan.success());
        assertTrue(customPlan.steps().stream().anyMatch(step -> step.kind() == PlanKind.CUSTOM));
        assertTrue(customPlan.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER && step.sourceId().equals(ironGatherId)));
        assertFalse(ExplorationRecovery.provesExploration(filteredPlan, customPlan, customCatalog, Set.of(ironGatherId)));

        PlanResult mismatchedTarget = new PlanResult(ItemId.parse("test:other_goal"), 1,
                fullPlan.steps(), List.of(), false, fullPlan.expandedNodes(), fullPlan.elapsedNanos());
        PlanResult mismatchedCount = new PlanResult(goal, 2,
                fullPlan.steps(), List.of(), false, fullPlan.expandedNodes(), fullPlan.elapsedNanos());
        assertFalse(ExplorationRecovery.provesExploration(filteredPlan, mismatchedTarget, full, Set.of(ironGatherId)));
        assertFalse(ExplorationRecovery.provesExploration(filteredPlan, mismatchedCount, full, Set.of(ironGatherId)));
    }

    @Test
    void explorationRecoveryDoesNotRunForUnknownInvalidCountOrPlannerLimits() {
        ItemId goal = ItemId.parse("test:iron_goal");
        ItemId rawIron = ItemId.parse("test:raw_iron");
        ItemId chainmail = ItemId.parse("test:chainmail");
        String ironGatherId = "test:gather_raw_iron";
        CatalogSnapshot full = recoveryCatalog(goal, rawIron, chainmail, true);
        CatalogSnapshot filtered = recoveryCatalog(goal, rawIron, chainmail, false);
        InventorySnapshot inventory = new InventorySnapshot(Map.of());
        PlanResult fullPlan = planner().planFast(full, inventory, goal, 1);

        for (BlockedReason.Code code : List.of(BlockedReason.Code.UNKNOWN_ITEM, BlockedReason.Code.INVALID_COUNT,
                BlockedReason.Code.TIME_LIMIT, BlockedReason.Code.NODE_LIMIT,
                BlockedReason.Code.DEPTH_LIMIT, BlockedReason.Code.STEP_LIMIT)) {
            PlanResult failure = new PlanResult(goal, 1, List.of(),
                    List.of(new BlockedReason(code, goal, "bounded planner failure", List.of(goal))), false, 0, 0);
            assertFalse(ExplorationRecovery.isLogicalFailure(failure), code.toString());
            assertFalse(ExplorationRecovery.provesExploration(failure, fullPlan, full, Set.of(ironGatherId)), code.toString());
        }
    }

    private static CatalogSnapshot recoveryCatalog(ItemId goal, ItemId rawIron, ItemId chainmail, boolean includeIronGather) {
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(goal, 0).item(rawIron, 0).item(chainmail, 0)
                .source(new CraftingSource("test:goal_from_iron", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(rawIron))), List.of()))
                .source(new CraftingSource("test:goal_from_chainmail", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(chainmail))), List.of()));
        if (includeIronGather) {
            builder.source(new GatherSource("test:gather_raw_iron", rawIron, 1, List.of(BlockId.parse("test:iron_ore"))));
        }
        return builder.build();
    }

    @Test
    void reservesInventoryAndPlansOnlyMissingQuantity() {
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(LOG, 0, "wood")
                .source(new GatherSource("gather:oak", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .build();

        PlanResult result = planner().plan(catalog, new InventorySnapshot(Map.of(LOG, 60)), LOG, 64);

        assertTrue(result.success());
        assertEquals(1, result.steps().size());
        assertEquals(4, result.steps().get(0).outputCount());
        assertEquals(4, result.steps().get(0).operationCount());
    }

    @Test
    void combinesRepeatedRecipeSlotsAndCraftOperations() {
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(PLANKS, 0)
                .item(STICKS, 0)
                .source(new CraftingSource("stick-recipe", STICKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(PLANKS)), new RecipeSlot(-1, Ingredient.of(PLANKS))), List.of()))
                .build();

        PlanResult result = planner().plan(catalog, new InventorySnapshot(Map.of(PLANKS, 4)), STICKS, 8);

        assertTrue(result.success());
        assertEquals(1, result.steps().size());
        PlanStep craft = result.steps().get(0);
        assertEquals(2, craft.operationCount());
        assertEquals(8, craft.outputCount());
        assertEquals(2, craft.requirements().size());
        assertEquals(4, craft.requirements().stream().map(SelectedItemRequirement.class::cast).mapToInt(SelectedItemRequirement::count).sum());
    }

    @Test
    void stonecuttingRoundsOutputBatchesAndPreservesProtectedMaterials() {
        ItemId stone = ItemId.parse("minecraft:stone"), slabs = ItemId.parse("minecraft:stone_slab");
        ItemId cutter = ItemId.parse("minecraft:stonecutter");
        StationId station = StationId.parse("minecraft:stonecutter");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(stone, 0).item(slabs, 0).item(cutter, 0)
                .source(new CraftingSource("stonecutting:slabs", slabs, 2, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(stone))),
                        List.of(new StationRequirement(station, cutter, "use station")))).build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(stone, 40, slabs, 2, cutter, 1),
                Set.of(), Map.of(), Map.of(stone, 4));
        PlanResult result = planner().planFast(catalog, inventory, slabs, 73);
        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of(PlanKind.PLACE_STATION, PlanKind.CRAFT), result.steps().stream().map(PlanStep::kind).toList());
        PlanStep craft = result.steps().get(1);
        assertEquals(station, craft.station());
        assertEquals(36, craft.operationCount());
        assertEquals(72, craft.outputCount());
        List<SelectedItemRequirement> materials = craft.requirements().stream()
                .filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast).toList();
        assertEquals(1, materials.size());
        assertEquals(stone, materials.get(0).item());
        assertEquals(36, materials.get(0).count());
        assertEquals(0, materials.get(0).recipeSlot());
        assertTrue(materials.get(0).consumed());
        assertFalse(planner().planFast(catalog, inventory, slabs, 75).success());
    }

    @Test
    void reservesHeldItemsForLessFlexibleShapelessIngredientsAcrossCraftCycles() {
        ItemId a = ItemId.parse("test:a");
        ItemId b = ItemId.parse("test:b");
        ItemId output = ItemId.parse("test:output");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(a, 0)
                .item(b, 0)
                .item(output, 0)
                .source(new CraftingSource("overlapping-shapeless", output, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.choices(List.of(a, b), 1)),
                                new RecipeSlot(-1, Ingredient.of(a))), List.of()))
                .build();

        PlanResult result = planner().plan(catalog, new InventorySnapshot(Map.of(a, 2, b, 2)), output, 2);

        assertTrue(result.success());
        PlanStep craft = result.steps().stream().filter(step -> step.kind() == PlanKind.CRAFT).findFirst().orElseThrow();
        assertEquals(2, craft.operationCount());
        List<SelectedItemRequirement> ingredients = craft.requirements().stream()
                .filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("recipe ingredient")).toList();
        assertEquals(Set.of(new SelectedItemRequirement(b, 2, true, "recipe ingredient", 0),
                new SelectedItemRequirement(a, 2, true, "recipe ingredient", 1)), Set.copyOf(ingredients));
    }

    @Test
    void computesFuelForTheWholeSmeltingBatch() {
        ItemId rawIron = ItemId.parse("minecraft:raw_iron");
        ItemId iron = ItemId.parse("minecraft:iron_ingot");
        ItemId coal = ItemId.parse("minecraft:coal");
        ItemId furnaceItem = ItemId.parse("minecraft:furnace");
        StationId furnace = StationId.parse("minecraft:furnace");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(rawIron, 0)
                .item(iron, 0)
                .item(coal, 0, 1_600)
                .item(furnaceItem, 0)
                .source(new GatherSource("raw-iron", rawIron, 1, List.of(BlockId.parse("minecraft:iron_ore"))))
                .source(new SmeltingSource("iron-smelt", iron, 1, Ingredient.of(rawIron),
                        List.of(ItemSelector.item(coal)), 200,
                        List.of(new StationRequirement(furnace, furnaceItem, "furnace"))))
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(coal, 1), Set.of(furnace), Map.of());

        PlanResult result = planner().plan(catalog, inventory, iron, 3);

        assertTrue(result.success());
        PlanStep smelt = result.steps().stream().filter(step -> step.kind() == PlanKind.SMELT).findFirst().orElseThrow();
        assertEquals(3, smelt.operationCount());
        assertEquals(3, smelt.outputCount());
        SelectedItemRequirement fuel = smelt.requirements().stream().filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast).filter(requirement -> requirement.purpose().equals("smelting fuel"))
                .findFirst().orElseThrow();
        assertEquals(coal, fuel.item());
        assertEquals(1, fuel.count());
    }

    @Test
    void convertsHeldLogsIntoFuelAndKeepsHeldCoalAheadOfCrafting() {
        ItemId raw = ItemId.parse("test:raw");
        ItemId cooked = ItemId.parse("test:cooked");
        ItemId coal = ItemId.parse("test:coal");
        ItemId strippedLog = ItemId.parse("minecraft:stripped_oak_log");
        ItemId wood = ItemId.parse("minecraft:oak_wood");
        ItemId strippedWood = ItemId.parse("minecraft:stripped_oak_wood");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(raw, 0).item(cooked, 0)
                .item(LOG, 0, 300).item(strippedLog, 0, 300).item(wood, 0, 300).item(strippedWood, 0, 300)
                .item(PLANKS, 0, 300).item(coal, 0, 1_600)
                .source(new CraftingSource("craft:planks", PLANKS, 4, RecipeType.SHAPED, 1, 1,
                        List.of(new RecipeSlot(0, Ingredient.choices(List.of(LOG, strippedLog, wood, strippedWood), 1))), List.of()))
                .source(new SmeltingSource("smelt", cooked, 1, Ingredient.of(raw),
                        List.of(ItemSelector.item(LOG), ItemSelector.item(strippedLog), ItemSelector.item(wood),
                                ItemSelector.item(strippedWood), ItemSelector.item(PLANKS), ItemSelector.item(coal)), 200, List.of()))
                .build();
        for (PlanningPreferences preferences : List.of(PlanningPreferences.NONE,
                new PlanningPreferences(Map.of("craft:planks", 100)))) {
            PlanResult converted = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 3, LOG, 2)),
                    cooked, 3, PlannerLimits.DEFAULT, preferences);
            assertTrue(converted.success(), converted.blockedReasons().toString());
            assertEquals(List.of(PlanKind.CRAFT, PlanKind.SMELT), converted.steps().stream().map(PlanStep::kind).toList());
            assertEquals(4, converted.steps().get(0).outputCount());
            assertEquals(List.of(new SelectedItemRequirement(LOG, 1, true, "recipe ingredient", 0)),
                    converted.steps().get(0).requirements());
            assertEquals(PLANKS, selectedFuel(converted).item());
            assertEquals(2, selectedFuel(converted).count());

            PlanResult stocked = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 3, LOG, 2, coal, 1)),
                    cooked, 3, PlannerLimits.DEFAULT, preferences);
            assertTrue(stocked.success());
            assertEquals(List.of(PlanKind.SMELT), stocked.steps().stream().map(PlanStep::kind).toList());
            assertEquals(coal, selectedFuel(stocked).item());
            assertEquals(1, selectedFuel(stocked).count());
        }
    }

    @Test
    void fuelConversionRequiresAnUnambiguousRecipeAndAvailableStation() {
        ItemId raw = ItemId.parse("test:raw");
        ItemId cooked = ItemId.parse("test:cooked");
        StationId table = StationId.parse("test:table");
        ItemId tableItem = ItemId.parse("test:table");
        for (String scenario : List.of("competing_recipe", "missing_station", "effective_capacity")) {
            List<Requirement> requirements = scenario.equals("missing_station")
                    ? List.of(new StationRequirement(table, tableItem, "crafting table")) : List.of();
            CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(raw, 0).item(cooked, 0)
                    .item(LOG, 0, 300).item(PLANKS, 0, 300).item(tableItem, 0)
                    .source(new CraftingSource("craft:planks", PLANKS, 4, RecipeType.SHAPED, 1, 1,
                            List.of(new RecipeSlot(0, Ingredient.of(LOG))), requirements));
            if (scenario.equals("competing_recipe")) builder.source(new CraftingSource("craft:other_planks", PLANKS, 2,
                    RecipeType.SHAPED, 1, 1, List.of(new RecipeSlot(0, Ingredient.of(LOG))), List.of()));
            Map<ItemId, Long> capacities = Map.of(LOG, 300L, PLANKS, scenario.equals("effective_capacity") ? 50L : 300L);
            CatalogSnapshot catalog = builder.source(new SmeltingSource("smelt", cooked, 1, Ingredient.of(raw),
                    List.of(ItemSelector.item(LOG), ItemSelector.item(PLANKS)), 200, List.of(), capacities)).build();
            PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 3, LOG, 2)), cooked, 3);
            assertTrue(result.success(), scenario + result.blockedReasons());
            assertEquals(List.of(PlanKind.SMELT), result.steps().stream().map(PlanStep::kind).toList(), scenario);
            assertEquals(LOG, selectedFuel(result).item(), scenario);
            assertEquals(2, selectedFuel(result).count(), scenario);
        }
    }

    @Test
    void heldFuelConversionAvoidsMiningPlanksAndLeavesWoodForLaterTools() {
        ItemId raw = ItemId.parse("test:raw");
        ItemId ingot = ItemId.parse("test:ingot");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(raw, 0).item(ingot, 0)
                .item(LOG, 0, 300).item(PLANKS, 0, 300).item(STICKS, 0)
                .source(new GatherSource("gather:planks", PLANKS, 1, List.of(BlockId.parse("test:planks"))))
                .source(new CraftingSource("craft:planks", PLANKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(LOG))), List.of()))
                .source(new CraftingSource("craft:sticks", STICKS, 4, RecipeType.SHAPED, 1, 2,
                        List.of(new RecipeSlot(0, Ingredient.of(PLANKS)), new RecipeSlot(1, Ingredient.of(PLANKS))), List.of()))
                .source(new SmeltingSource("smelt", ingot, 1, Ingredient.of(raw),
                        List.of(ItemSelector.item(LOG), ItemSelector.item(PLANKS)), 200, List.of()))
                .build();
        for (int heldPlanks : List.of(1, 3)) {
            for (PlanningPreferences preferences : List.of(PlanningPreferences.NONE,
                    new PlanningPreferences(Map.of("gather:planks", 0, "craft:planks", 100)))) {
                InventorySnapshot stock = new InventorySnapshot(Map.of(raw, 3, LOG, 2, PLANKS, heldPlanks));
                PlanResult fuel = planner().planFast(catalog, stock, ingot, 3, PlannerLimits.DEFAULT, preferences);
                assertTrue(fuel.success(), fuel.blockedReasons().toString());
                assertEquals(heldPlanks < 2 ? List.of("craft:planks", "smelt") : List.of("smelt"),
                        fuel.steps().stream().map(PlanStep::sourceId).toList());
                assertEquals(PLANKS, selectedFuel(fuel).item());
                assertEquals(2, selectedFuel(fuel).count());
                Map<ItemId, Integer> remaining = new HashMap<>(stock.counts());
                for (PlanStep step : fuel.steps()) {
                    for (SelectedRequirement requirement : step.requirements())
                        if (requirement instanceof SelectedItemRequirement item && item.consumed())
                            remaining.merge(item.item(), -item.count(), Integer::sum);
                    remaining.merge(step.output(), step.outputCount(), Integer::sum);
                }
                remaining.entrySet().removeIf(entry -> entry.getValue() == 0);
                assertEquals(heldPlanks < 2 ? 1 : 2, remaining.get(LOG));
                assertEquals(heldPlanks + (heldPlanks < 2 ? 4 : 0) - 2, remaining.get(PLANKS));
                PlanResult handles = planner().plan(catalog, new InventorySnapshot(remaining), STICKS, 8);
                assertTrue(handles.success(), handles.blockedReasons().toString());
                assertTrue(handles.steps().stream().noneMatch(step -> step.kind() == PlanKind.GATHER));
                assertEquals(1, handles.steps().stream().filter(step -> step.sourceId().equals("craft:planks"))
                        .mapToInt(PlanStep::operationCount).sum());
            }
        }
    }

    @Test
    void fuelConversionRespectsProtectedAndRoundedIngredientQuantities() {
        ItemId raw = ItemId.parse("test:raw");
        ItemId cooked = ItemId.parse("test:cooked");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(raw, 0).item(cooked, 0)
                .item(LOG, 0, 100).item(PLANKS, 0, 300)
                .source(new CraftingSource("craft:planks", PLANKS, 3, RecipeType.SHAPED, 1, 1,
                        List.of(new RecipeSlot(0, Ingredient.of(2, LOG))), List.of()))
                .source(new SmeltingSource("smelt", cooked, 1, Ingredient.of(raw),
                        List.of(ItemSelector.item(LOG), ItemSelector.item(PLANKS)), 200, List.of()))
                .build();
        InventorySnapshot protectedStock = new InventorySnapshot(Map.of(raw, 4, LOG, 3), Set.of(), Map.of(), Map.of(LOG, 1));
        PlanResult sufficient = planner().planFast(catalog, protectedStock, cooked, 4);
        assertTrue(sufficient.success(), sufficient.blockedReasons().toString());
        assertEquals(3, sufficient.steps().get(0).outputCount());
        assertEquals(List.of(new SelectedItemRequirement(LOG, 2, true, "recipe ingredient", 0)),
                sufficient.steps().get(0).requirements());
        assertEquals(PLANKS, selectedFuel(sufficient).item());
        assertEquals(3, selectedFuel(sufficient).count());
        PlanResult insufficient = planner().planFast(catalog,
                new InventorySnapshot(Map.of(raw, 4, LOG, 2), Set.of(), Map.of(), Map.of(LOG, 1)), cooked, 4);
        assertFalse(insufficient.success());
    }

    @Test
    void storedStationRecipeUsesHeldMaterialsWithoutOvercountingOrProtectedStock() {
        ItemId furnace = ItemId.parse("minecraft:furnace");
        ItemId cobble = ItemId.parse("minecraft:cobblestone");
        ItemId deepCobble = ItemId.parse("minecraft:cobbled_deepslate");
        ItemId tableItem = ItemId.parse("minecraft:crafting_table");
        StationId table = StationId.parse("minecraft:crafting_table");
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(furnace, 0).item(cobble, 0)
                .item(deepCobble, 0).item(tableItem, 0);
        Ingredient stones = Ingredient.choices(List.of(cobble, deepCobble), 1);
        List<RecipeSlot> slots = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new RecipeSlot(-1, stones)).toList();
        CatalogSnapshot catalog = builder.source(new CraftingSource("furnace", furnace, 1,
                RecipeType.SHAPELESS, 0, 0, slots, List.of(new StationRequirement(table, tableItem, "craft")))).build();
        assertTrue(StoredCrafting.canSupplyOne(catalog,
                new InventorySnapshot(Map.of(cobble, 4, deepCobble, 4, tableItem, 1)), furnace, () -> 0L));
        assertTrue(StoredCrafting.canSupplyOne(catalog,
                new InventorySnapshot(Map.of(cobble, 8), Set.of(table), Map.of()), furnace, () -> 0L));
        assertFalse(StoredCrafting.canSupplyOne(catalog,
                new InventorySnapshot(Map.of(cobble, 7, tableItem, 1)), furnace, () -> 0L));
        assertFalse(StoredCrafting.canSupplyOne(catalog,
                new InventorySnapshot(Map.of(cobble, 8)), furnace, () -> 0L));
        assertFalse(StoredCrafting.canSupplyOne(catalog,
                new InventorySnapshot(Map.of(cobble, 8, tableItem, 1), Set.of(), Map.of(), Map.of(cobble, 1)),
                furnace, () -> 0L));
        assertTrue(StoredCrafting.canSupplyOne(catalog, new InventorySnapshot(Map.of(furnace, 1)), furnace, () -> 0L));
        assertFalse(StoredCrafting.canSupplyOne(catalog,
                new InventorySnapshot(Map.of(furnace, 1), Set.of(), Map.of(), Map.of(furnace, 1)), furnace, () -> 0L));
    }

    @Test
    void storedStationProofCannotSpendTheSameCarriedStationAsAnIngredient() {
        ItemId output = ItemId.parse("test:station");
        ItemId tableItem = ItemId.parse("test:table");
        StationId table = StationId.parse("test:table");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(output, 0).item(tableItem, 0)
                .source(new CraftingSource("station", output, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(tableItem))),
                        List.of(new StationRequirement(table, tableItem, "craft")))).build();
        assertFalse(StoredCrafting.canSupplyOne(catalog, new InventorySnapshot(Map.of(tableItem, 1)), output, () -> 0L));
        assertTrue(StoredCrafting.canSupplyOne(catalog, new InventorySnapshot(Map.of(tableItem, 2)), output, () -> 0L));
        assertTrue(StoredCrafting.canSupplyOne(catalog,
                new InventorySnapshot(Map.of(tableItem, 1), Set.of(table), Map.of()), output, () -> 0L));
    }

    @Test
    void storedStationProofFallsBackOnAmbiguousSourcesAndExpiredBudget() {
        ItemId output = ItemId.parse("test:station");
        CraftingSource craft = new CraftingSource("station", output, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.of(LOG))), List.of());
        CatalogSnapshot simple = CatalogSnapshot.builder().item(output, 0).item(LOG, 0).source(craft).build();
        InventorySnapshot stock = new InventorySnapshot(Map.of(LOG, 1));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(StoredCrafting.canSupplyOne(simple, stock, output,
                () -> reads.getAndIncrement() == 0 ? 0L : 1_000_000L));
        CatalogSnapshot ambiguous = CatalogSnapshot.builder().item(output, 0).item(LOG, 0).source(craft)
                .source(new GatherSource("mine:station", output, 1, List.of(BlockId.parse("test:station")))).build();
        assertFalse(StoredCrafting.canSupplyOne(ambiguous, stock, output, () -> 0L));
    }

    @Test
    void stationSpecificFuelProgressControlsTheWholeBatchAndKeepsLegacyCapacity() {
        ItemId coal = ItemId.parse("test:coal");
        ItemId output = ItemId.parse("test:smelted");
        ItemId raw = ItemId.parse("test:raw");
        SmeltingSource olderFastStation = new SmeltingSource("test:smelt", output, 1, Ingredient.of(raw),
                List.of(ItemSelector.item(coal)), 100, List.of(), Map.of(coal, 800L));
        PlanResult olderFastPlan = planFuelBatch(olderFastStation, 9, 1_600, 2, false, Map.of());
        assertEquals(2, selectedFuel(olderFastPlan).count());

        // The unchanged seven-argument constructor retains catalog capacity (1,600 ticks).
        SmeltingSource ordinaryFurnace = new SmeltingSource("test:smelt", output, 1, Ingredient.of(raw),
                List.of(ItemSelector.item(coal)), 200, List.of());
        PlanResult ordinaryPlan = planFuelBatch(ordinaryFurnace, 9, 1_600, 2, false, Map.of());
        assertEquals(2, selectedFuel(ordinaryPlan).count());

        // A modern provider's effective context can differ from the catalog burn duration.
        SmeltingSource modernCustomFuel = new SmeltingSource("test:smelt", output, 1, Ingredient.of(raw),
                List.of(ItemSelector.item(coal)), 200, List.of(), Map.of(coal, 400L));
        PlanResult modernPlan = planFuelBatch(modernCustomFuel, 3, 1_600, 2, false, Map.of());
        assertEquals(2, selectedFuel(modernPlan).count());
    }

    @Test
    void modernCookingCapacityAccountsForCeilingAndContinuouslySuppliedShortFuel() {
        long capacity = CookingFuelCapacity.progressTicks(400, 200, 1.5f);
        assertEquals(597, capacity); // Three native 134-tick recipes need more than one 400-tick fuel.
        ItemId coal = ItemId.parse("test:coal");
        SmeltingSource source = new SmeltingSource("test:rounded_cooking", ItemId.parse("test:smelted"), 1,
                Ingredient.of(ItemId.parse("test:raw")), List.of(ItemSelector.item(coal)), 200,
                List.of(), Map.of(coal, capacity));
        assertEquals(2, selectedFuel(planFuelBatch(source, 3, 600, 2, false, Map.of())).count());
        assertEquals(100, CookingFuelCapacity.progressTicks(100, 200, 1));
        assertEquals(0, CookingFuelCapacity.progressTicks(100, 200, 1, false));
        assertEquals(400, CookingFuelCapacity.progressTicks(400, 200, 1.5f, false));
        assertEquals(3, selectedFuel(planFuelBatch(new SmeltingSource("test:discontinuous",
                ItemId.parse("test:smelted"), 1, Ingredient.of(ItemId.parse("test:raw")),
                List.of(ItemSelector.item(coal)), 200, List.of(),
                Map.of(coal, CookingFuelCapacity.progressTicks(400, 200, 1.5f, false))),
                5, 600, 3, false, Map.of())).count());
        assertEquals(294, CookingFuelCapacity.progressTicks(100, 100, 3));
        assertEquals(0, CookingFuelCapacity.progressTicks(Long.MAX_VALUE, 200, 1));
        assertEquals(0, CookingFuelCapacity.progressTicks(100, 200, Float.NaN));
        assertEquals(0, CookingFuelCapacity.progressTicks(100, 200, 0));
    }

    @Test
    void fastCookingPrefersAnAvailableStationAndThenShorterDeclaredDuration() {
        ItemId raw = ItemId.parse("test:raw_food"), cooked = ItemId.parse("test:cooked_food");
        ItemId coal = ItemId.parse("test:coal"), furnaceItem = ItemId.parse("minecraft:furnace");
        ItemId smokerItem = ItemId.parse("minecraft:smoker");
        StationId furnace = StationId.parse("minecraft:furnace"), smoker = StationId.parse("minecraft:smoker");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(raw, 0).item(cooked, 0).item(coal, 0, 1600)
                .item(furnaceItem, 0).item(smokerItem, 0)
                .source(new SmeltingSource("a:furnace", cooked, 1, Ingredient.of(raw), List.of(ItemSelector.item(coal)),
                        200, List.of(new StationRequirement(furnace, furnaceItem, "cook")), Map.of(coal, 1600L)))
                .source(new SmeltingSource("z:smoker", cooked, 1, Ingredient.of(raw), List.of(ItemSelector.item(coal)),
                        100, List.of(new StationRequirement(smoker, smokerItem, "cook")), Map.of(coal, 800L)))
                .build();
        for (Set<StationId> stations : List.of(Set.of(smoker), Set.of(smoker, furnace))) {
            InventorySnapshot inventory = new InventorySnapshot(Map.of(raw, 8, coal, 1, furnaceItem, 1), stations, Map.of());
            PlanResult result = planner().planFast(catalog, inventory, cooked, 8);
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(1, result.steps().size());
            assertEquals("z:smoker", result.steps().get(0).sourceId());
            assertEquals(1, selectedFuel(result).count());
        }
        PlanResult heldSmoker = planner().planFast(catalog,
                new InventorySnapshot(Map.of(raw, 8, coal, 1, smokerItem, 1), Set.of(), Map.of()), cooked, 8);
        assertTrue(heldSmoker.success(), heldSmoker.blockedReasons().toString());
        assertEquals(smoker, heldSmoker.steps().get(0).station());
        assertEquals(PlanKind.PLACE_STATION, heldSmoker.steps().get(0).kind());
        PlanResult reservedSmoker = planner().planFast(catalog,
                new InventorySnapshot(Map.of(raw, 8, coal, 1, smokerItem, 1), Set.of(furnace), Map.of(),
                        Map.of(smokerItem, 1)), cooked, 8);
        assertTrue(reservedSmoker.success(), reservedSmoker.blockedReasons().toString());
        assertEquals("a:furnace", reservedSmoker.steps().get(0).sourceId());
    }

    @Test
    void outputScopedSourceRestrictionSelectsAndNarrowsOnlyThatOutput() {
        ItemId chosenRaw = ItemId.parse("test:chosen_raw_food");
        ItemId otherRaw = ItemId.parse("test:other_raw_food");
        ItemId cooked = ItemId.parse("test:restricted_cooked_food");
        ItemId coal = ItemId.parse("test:restriction_coal");
        SmeltingSource broadChoice = new SmeltingSource("smelt:chosen_recipe", cooked, 1,
                Ingredient.choices(List.of(chosenRaw, otherRaw), 1), List.of(ItemSelector.item(coal)), 200, List.of());
        SmeltingSource cheaper = new SmeltingSource("smelt:cheaper_recipe", cooked, 1,
                Ingredient.of(otherRaw), List.of(ItemSelector.item(coal)), 100, List.of());
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(chosenRaw, 0).item(otherRaw, 0).item(cooked, 0).item(coal, 0, 1_600)
                .source(new GatherSource("gather:chosen_raw", chosenRaw, 1,
                        List.of(BlockId.parse("test:chosen_raw_block"))))
                .source(new GatherSource("gather:other_raw", otherRaw, 1,
                        List.of(BlockId.parse("test:other_raw_block"))))
                .source(broadChoice)
                .source(cheaper)
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(coal, 1));

        PlanResult unfiltered = planner().planFast(catalog, inventory, cooked, 1);
        assertTrue(unfiltered.success(), unfiltered.blockedReasons().toString());
        assertEquals("smelt:cheaper_recipe", unfiltered.steps().get(unfiltered.steps().size() - 1).sourceId());

        CatalogSnapshot idRestricted = catalog.withOutputSources(cooked, Set.of(broadChoice.sourceId()));
        CatalogSnapshot cannotReenable = idRestricted.withOutputSources(cooked, List.of(cheaper));
        assertTrue(cannotReenable.sourcesFor(cooked).isEmpty());
        SmeltingSource narrowedChoice = new SmeltingSource(broadChoice.sourceId(), cooked, broadChoice.outputCount(),
                Ingredient.of(chosenRaw), broadChoice.fuels(), broadChoice.cookTicks(), broadChoice.requirements(),
                broadChoice.fuelProgressTicks());
        CatalogSnapshot restricted = idRestricted.withOutputSources(cooked, List.of(narrowedChoice));
        assertEquals(List.of(narrowedChoice), restricted.sourcesFor(cooked));
        assertEquals(narrowedChoice, restricted.sourceById(broadChoice.sourceId()));
        assertNull(restricted.sourceById(cheaper.sourceId()));
        assertTrue(restricted.hasRecipeSource(cooked));
        assertEquals(catalog.gatherSources(), restricted.gatherSources());
        assertEquals(List.of(new GatherSource("gather:chosen_raw", chosenRaw, 1,
                List.of(BlockId.parse("test:chosen_raw_block")))), restricted.sourcesFor(chosenRaw));
        CatalogSnapshot gatherRestricted = catalog.withOutputSources(chosenRaw, Set.of());
        assertEquals(List.of("gather:other_raw"), gatherRestricted.gatherSources().stream()
                .map(GatherSource::sourceId).toList());
        assertEquals(2, catalog.gatherSources().size());

        PlanResult selected = planner().planFast(restricted, inventory, cooked, 1);
        assertTrue(selected.success(), selected.blockedReasons().toString());
        assertEquals(List.of("gather:chosen_raw", "smelt:chosen_recipe"),
                selected.steps().stream().map(PlanStep::sourceId).toList());

        CatalogSnapshot empty = catalog.withOutputSources(cooked, Set.of());
        assertTrue(empty.sourcesFor(cooked).isEmpty());
        assertFalse(empty.hasRecipeSource(cooked));
        assertFalse(planner().planFast(empty, inventory, cooked, 1).success());
        assertEquals(2, catalog.sourcesFor(cooked).size());
        PlanResult originalAgain = planner().planFast(catalog, inventory, cooked, 1);
        assertTrue(originalAgain.success(), originalAgain.blockedReasons().toString());
        assertEquals("smelt:cheaper_recipe", originalAgain.steps().get(originalAgain.steps().size() - 1).sourceId());
    }

    @Test
    void nearbyCraftedFuelOutranksGatherFuelWithoutBeatingUsableStock() {
        ItemId raw = ItemId.parse("test:raw_ore");
        ItemId output = ItemId.parse("test:ranked_ingot");
        ItemId coal = ItemId.parse("test:coal");
        ItemId oakLog = ItemId.parse("test:oak_log");
        ItemId oakPlanks = ItemId.parse("test:oak_planks");
        SmeltingSource smelting = new SmeltingSource("smelt:ranked_ingot", output, 1, Ingredient.of(raw),
                List.of(ItemSelector.item(coal), ItemSelector.item(oakPlanks)), 100, List.of(),
                Map.of(coal, 100L, oakPlanks, 100L));
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(raw, 0).item(output, 0).item(coal, 0).item(oakLog, 0).item(oakPlanks, 0)
                .source(new GatherSource("gather:coal", coal, 1, List.of(BlockId.parse("test:coal_ore"))))
                .source(new GatherSource("gather:oak_log", oakLog, 1, List.of(BlockId.parse("test:oak_log"))))
                .source(new CraftingSource("craft:oak_planks", oakPlanks, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(oakLog))), List.of()))
                .source(smelting)
                .build();
        PlanningPreferences preferences = new PlanningPreferences(Map.of(
                "gather:coal", 50,
                "gather:oak_log", 1));

        PlanResult legacy = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 1)), output, 1);
        PlanResult nearbyFuel = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 1)), output, 1,
                PlannerLimits.DEFAULT, preferences);
        PlanResult heldCoal = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 1, coal, 1)), output, 1,
                PlannerLimits.DEFAULT, preferences);
        PlanResult partialCoal = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 2, coal, 1)), output, 2,
                PlannerLimits.DEFAULT, preferences);
        InventorySnapshot protectedCoalInventory = new InventorySnapshot(Map.of(raw, 1, coal, 1), Set.of(), Map.of(),
                Map.of(coal, 1));
        PlanResult protectedCoal = planner().planFast(catalog, protectedCoalInventory, output, 1,
                PlannerLimits.DEFAULT, preferences);

        assertTrue(legacy.success(), legacy.blockedReasons().toString());
        assertEquals(coal, selectedFuel(legacy).item());
        assertTrue(nearbyFuel.success(), nearbyFuel.blockedReasons().toString());
        assertEquals(oakPlanks, selectedFuel(nearbyFuel).item());
        assertTrue(nearbyFuel.steps().stream().anyMatch(step -> step.sourceId().equals("gather:oak_log")));
        assertFalse(nearbyFuel.steps().stream().anyMatch(step -> step.sourceId().equals("gather:coal")));
        assertTrue(heldCoal.success(), heldCoal.blockedReasons().toString());
        assertEquals(coal, selectedFuel(heldCoal).item());
        assertTrue(partialCoal.success(), partialCoal.blockedReasons().toString());
        assertEquals(coal, selectedFuel(partialCoal).item());
        assertEquals(2, selectedFuel(partialCoal).count());
        assertTrue(protectedCoal.success(), protectedCoal.blockedReasons().toString());
        assertEquals(oakPlanks, selectedFuel(protectedCoal).item());
        assertEquals(1, protectedCoalInventory.count(coal));
        assertEquals(1, protectedCoalInventory.protectedCounts().get(coal));
    }

    @Test
    void preferredCyclicFuelDoesNotHideCoalFallback() {
        ItemId raw = ItemId.parse("test:cycle_raw");
        ItemId output = ItemId.parse("test:cycle_smelted");
        ItemId coal = ItemId.parse("test:cycle_coal");
        ItemId cycleFuel = ItemId.parse("test:cycle_fuel");
        SmeltingSource smelting = new SmeltingSource("smelt:cycle_target", output, 1, Ingredient.of(raw),
                List.of(ItemSelector.item(cycleFuel), ItemSelector.item(coal)), 100, List.of(),
                Map.of(cycleFuel, 100L, coal, 100L));
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(raw, 0).item(output, 0).item(coal, 0).item(cycleFuel, 0)
                .source(new GatherSource("gather:cycle_coal", coal, 1, List.of(BlockId.parse("test:cycle_coal_ore"))))
                .source(new CraftingSource("craft:cycle_fuel", cycleFuel, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(output))), List.of()))
                .source(smelting)
                .build();
        PlanningPreferences preferences = new PlanningPreferences(Map.of(
                "craft:cycle_fuel", 0,
                "gather:cycle_coal", 50));

        PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of(raw, 1)), output, 1,
                PlannerLimits.DEFAULT, preferences);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(coal, selectedFuel(result).item());
        assertTrue(result.steps().stream().anyMatch(step -> step.sourceId().equals("gather:cycle_coal")));
        assertFalse(result.steps().stream().anyMatch(step -> step.sourceId().equals("craft:cycle_fuel")));
    }

    @Test
    void explicitFuelCapacityMapDoesNotFallBackToGlobalFuelMetadata() {
        ItemId coal = ItemId.parse("test:coal");
        ItemId otherFuel = ItemId.parse("test:other_fuel");
        SmeltingSource source = new SmeltingSource("test:smelt", ItemId.parse("test:smelted"), 1,
                Ingredient.of(ItemId.parse("test:raw")), List.of(ItemSelector.item(coal)), 200,
                List.of(), Map.of(otherFuel, 400L));

        PlanResult result = planFuelBatch(source, 3, 1_600, 3, false, Map.of());

        assertFalse(result.success());
    }

    @Test
    void completeLargeFuelCatalogUsesHeldCoalBeyondTheFormerCutoff() {
        ItemId raw = ItemId.parse("test:raw");
        ItemId output = ItemId.parse("test:smelted");
        ItemId coal = ItemId.parse("test:coal");
        var fuels = new java.util.ArrayList<ItemSelector>();
        Map<ItemId, Long> capacities = new HashMap<>();
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(raw, 0).item(output, 0);
        for (int index = 0; index < 511; index++) {
            ItemId fuel = ItemId.parse("test:fuel_" + index);
            fuels.add(ItemSelector.item(fuel));
            capacities.put(fuel, 200L);
            builder.item(fuel, 0, 200).source(new GatherSource("gather:fuel_" + index, fuel, 1,
                    List.of(BlockId.parse("test:fuel_block_" + index))));
        }
        fuels.add(ItemSelector.item(coal));
        capacities.put(coal, 1_600L);
        builder.item(coal, 0, 1_600).source(new SmeltingSource("test:smelt", output, 1,
                Ingredient.of(raw), fuels, 200, List.of(), capacities));
        CatalogSnapshot catalog = builder.build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(raw, 1, coal, 1));

        for (PlanResult result : List.of(planner().plan(catalog, inventory, output, 1),
                planner().planFast(catalog, inventory, output, 1))) {
            assertTrue(result.success(), result.blockedReasons().toString());
            assertEquals(coal, selectedFuel(result).item());
            assertEquals(1, selectedFuel(result).count());
            assertEquals(List.of(PlanKind.SMELT), result.steps().stream().map(PlanStep::kind).toList());
        }
    }

    @Test
    void protectedFuelMustBeReplacedBeforeItCanSatisfySmelting() {
        ItemId coal = ItemId.parse("test:coal");
        SmeltingSource source = new SmeltingSource("test:smelt", ItemId.parse("test:smelted"), 1,
                Ingredient.of(ItemId.parse("test:raw")), List.of(ItemSelector.item(coal)), 100,
                List.of(), Map.of(coal, 800L));

        PlanResult result = planFuelBatch(source, 9, 1_600, 1, true, Map.of(coal, 1));

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(2, result.steps().stream().filter(step -> step.kind() == PlanKind.GATHER && step.output().equals(coal))
                .mapToInt(PlanStep::outputCount).sum());
        assertEquals(2, selectedFuel(result).count());
    }

    @Test
    void fuelProgressMapIsDefensivelyCopiedImmutableAndBounded() {
        ItemId coal = ItemId.parse("test:coal");
        ItemId raw = ItemId.parse("test:raw");
        ItemId output = ItemId.parse("test:smelted");
        Map<ItemId, Long> capacities = new HashMap<>();
        capacities.put(coal, 400L);
        SmeltingSource source = new SmeltingSource("test:smelt", output, 1, Ingredient.of(raw),
                List.of(ItemSelector.item(coal)), 200, List.of(), capacities);
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(coal, 0, 1_600).build();

        capacities.put(coal, 900L);
        assertEquals(400L, source.effectiveFuelTicks(catalog, coal));
        assertEquals(400L, source.fuelProgressTicks().get(coal));
        assertThrows(UnsupportedOperationException.class, () -> source.fuelProgressTicks().put(coal, 900L));

        assertThrows(IllegalArgumentException.class, () -> fuelSource(Map.of(coal, 0L)));
        assertThrows(IllegalArgumentException.class, () -> fuelSource(Map.of(coal, 1_000_000_001L)));
        Map<ItemId, Long> nullKey = new HashMap<>();
        nullKey.put(null, 100L);
        assertThrows(IllegalArgumentException.class, () -> fuelSource(nullKey));
        Map<ItemId, Long> nullValue = new HashMap<>();
        nullValue.put(coal, null);
        assertThrows(IllegalArgumentException.class, () -> fuelSource(nullValue));
        Map<ItemId, Long> excessive = new HashMap<>();
        for (int index = 0; index < 513; index++) {
            excessive.put(ItemId.parse("test:fuel_" + index), 1L);
        }
        assertThrows(IllegalArgumentException.class, () -> fuelSource(excessive));
        List<ItemSelector> excessiveSelectors = excessive.keySet().stream().map(ItemSelector::item).toList();
        assertThrows(IllegalArgumentException.class, () -> new SmeltingSource("test:smelt", output, 1,
                Ingredient.of(raw), excessiveSelectors, 200, List.of()));
    }

    private static SmeltingSource fuelSource(Map<ItemId, Long> capacities) {
        return new SmeltingSource("test:smelt", ItemId.parse("test:smelted"), 1,
                Ingredient.of(ItemId.parse("test:raw")), List.of(ItemSelector.item(ItemId.parse("test:coal"))),
                200, List.of(), capacities);
    }

    private static PlanResult planFuelBatch(SmeltingSource source, int operations, long globalFuelTicks,
                                            int heldFuel, boolean gatherFuel, Map<ItemId, Integer> protectedFuel) {
        ItemId raw = ItemId.parse("test:raw");
        ItemId fuel = ItemId.parse("test:coal");
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder()
                .item(raw, 0)
                .item(source.output(), 0)
                .item(fuel, 0, globalFuelTicks)
                .source(source);
        if (gatherFuel) {
            builder.source(new GatherSource("test:gather_coal", fuel, 1, List.of(BlockId.parse("test:coal_ore"))));
        }
        CatalogSnapshot catalog = builder.build();
        InventorySnapshot inventory = new InventorySnapshot(
                Map.of(raw, operations, fuel, heldFuel), Set.of(), Map.of(), protectedFuel);
        return planner().plan(catalog, inventory, source.output(), operations);
    }

    private static SelectedItemRequirement selectedFuel(PlanResult result) {
        assertTrue(result.success(), result.blockedReasons().toString());
        return result.steps().stream().filter(step -> step.kind() == PlanKind.SMELT)
                .flatMap(step -> step.requirements().stream())
                .filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("smelting fuel"))
                .findFirst().orElseThrow();
    }

    @Test
    void reportsDependencyCycles() {
        ItemId a = ItemId.parse("test:a");
        ItemId b = ItemId.parse("test:b");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(a, 0)
                .item(b, 0)
                .source(new CraftingSource("a-from-b", a, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(b))), List.of()))
                .source(new CraftingSource("b-from-a", b, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(a))), List.of()))
                .build();

        PlanResult result = planner().plan(catalog, new InventorySnapshot(Map.of()), a, 1);

        assertFalse(result.success());
        assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.CYCLE));
    }

    @Test
    void usesMixedHeldTagAlternativesAcrossRecipeSlots() {
        TagId planks = TagId.parse("minecraft:planks");
        List<ItemId> variants = List.of(
                ItemId.parse("minecraft:oak_planks"),
                ItemId.parse("minecraft:birch_planks"),
                ItemId.parse("minecraft:spruce_planks"),
                ItemId.parse("minecraft:jungle_planks"));
        ItemId table = ItemId.parse("minecraft:crafting_table");
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(table, 0).tag(planks, variants);
        variants.forEach(item -> builder.item(item, 0));
        Ingredient tagIngredient = Ingredient.tag(planks);
        builder.source(new CraftingSource("mixed-plank-table", table, 1, RecipeType.SHAPED, 2, 2,
                List.of(new RecipeSlot(0, tagIngredient), new RecipeSlot(1, tagIngredient),
                        new RecipeSlot(2, tagIngredient), new RecipeSlot(3, tagIngredient)), List.of()));
        Map<ItemId, Integer> mixedInventory = Map.of(variants.get(0), 1, variants.get(1), 1, variants.get(2), 1, variants.get(3), 1);

        PlanResult result = planner().plan(builder.build(), new InventorySnapshot(mixedInventory), table, 1);

        assertTrue(result.success());
        assertEquals(1, result.steps().size());
        List<SelectedItemRequirement> selections = result.steps().get(0).requirements().stream()
                .filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast).toList();
        assertEquals(Set.of(0, 1, 2, 3), selections.stream().map(SelectedItemRequirement::recipeSlot).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.copyOf(variants), selections.stream().map(SelectedItemRequirement::item).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void parsesBoundedClientCommandBodies() {
        CommandParser parser = new CommandParser();
        CommandParser.ParseResult result = parser.parse("get \"diamond boots\" 64");

        assertTrue(result.success());
        CommandParser.GetCommand get = (CommandParser.GetCommand) result.command();
        assertEquals("diamond boots", get.item());
        assertEquals(64, get.count());
        assertFalse(parser.parse("get diamond_boots 1000001").success());
        assertFalse(parser.parse("pause extra").success());
    }

    @Test
    void clientPrefixHelpDoesNotInterceptUnrelatedChat() {
        CommandParser parser = new CommandParser();
        assertEquals("", CommandParser.clientCommandBody("!lk", "!lk "));
        assertEquals("", CommandParser.clientCommandBody("!lk   ", "!lk ").trim());
        assertEquals("status", CommandParser.clientCommandBody("!lk status", "!lk "));
        assertNull(CommandParser.clientCommandBody("!lkfoo", "!lk "));
        assertNull(CommandParser.clientCommandBody("hello !lk", "!lk "));
        assertNull(CommandParser.clientCommandBody("custom", "custom:"));
        assertEquals("status", CommandParser.clientCommandBody("custom:status", "custom:"));
        assertNull(CommandParser.clientCommandBody("!lk", "!lk\t\tstatus"));
        assertTrue(parser.parse("").command() instanceof CommandParser.HelpCommand);
        assertTrue(parser.parse("help").command() instanceof CommandParser.HelpCommand);
        assertTrue(parser.parse("?").command() instanceof CommandParser.HelpCommand);
        assertFalse(parser.parse("help extra").success());
    }

    @Test
    void projectCatalogIsStableAndProvidersCannotSilentlyOverride() {
        ProjectCatalog catalog = ProjectCatalog.standard();
        assertEquals(List.of("expedition", "farming_supplies", "gear_diamond", "gear_iron", "gear_stone", "mining_trip", "shelter_supplies"), catalog.names());
        assertEquals(Map.of(
                ItemId.parse("minecraft:diamond_pickaxe"), 1, ItemId.parse("minecraft:diamond_axe"), 1,
                ItemId.parse("minecraft:diamond_shovel"), 1, ItemId.parse("minecraft:diamond_hoe"), 1,
                ItemId.parse("minecraft:diamond_sword"), 1, ItemId.parse("minecraft:diamond_helmet"), 1,
                ItemId.parse("minecraft:diamond_chestplate"), 1, ItemId.parse("minecraft:diamond_leggings"), 1,
                ItemId.parse("minecraft:diamond_boots"), 1), catalog.require("gear_diamond").goals());
        ProjectSpec shelter = catalog.require("SHELTER_SUPPLIES");
        assertEquals(ProjectSpec.Purpose.SUPPLIES_ONLY, shelter.purpose());
        assertTrue(shelter.description().contains("does not build or place"));
        assertTrue(shelter.goals().containsKey(ItemId.parse("minecraft:white_bed")));
        assertFalse(shelter.goals().containsKey(ItemId.parse("minecraft:bed")));
        assertTrue(catalog.require("expedition").goals().containsKey(ItemId.parse("minecraft:oak_boat")));
        assertThrows(UnsupportedOperationException.class, () -> shelter.goals().put(LOG, 1));

        ProjectSpec collision = new ProjectSpec("gear_iron", "Replacement", Map.of(LOG, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
        assertThrows(IllegalArgumentException.class, () -> ProjectCatalog.builder().registerProvider("addon", List.of(collision)));

        ProjectCatalog.Builder builder = ProjectCatalog.emptyBuilder();
        ProjectSpec original = new ProjectSpec("extra", "Original", Map.of(LOG, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
        ProjectSpec replacement = new ProjectSpec("extra", "Explicitly replaced", Map.of(PLANKS, 4), ProjectSpec.Purpose.INVENTORY_GOALS);
        builder.registerProvider("addon", List.of(original));
        builder.replaceProvider("addon", List.of(replacement));
        assertEquals(replacement, builder.build().require("extra"));
        assertThrows(IllegalArgumentException.class, () -> builder.registerProvider("other", List.of(original)));
        assertThrows(IllegalArgumentException.class, () -> builder.replaceProvider("missing", List.of(original)));
    }

    @Test
    void projectPlanSharesYieldedInventoryAcrossRecipeGoals() {
        ItemId material = ItemId.parse("test:shared_material");
        ItemId firstGoal = ItemId.parse("test:first_goal");
        ItemId secondGoal = ItemId.parse("test:second_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(material, 0).item(firstGoal, 0).item(secondGoal, 0)
                .source(new GatherSource("gather:shared_material", material, 3,
                        List.of(BlockId.parse("test:material_ore"))))
                .source(new CraftingSource("craft:first_goal", firstGoal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(4, material))), List.of()))
                .source(new CraftingSource("craft:second_goal", secondGoal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(2, material))), List.of()))
                .build();
        ProjectSpec project = new ProjectSpec("shared_yield", "Two recipes share yielded materials.",
                Map.of(firstGoal, 1, secondGoal, 1), ProjectSpec.Purpose.INVENTORY_GOALS);

        ProjectPlanResult result = planner().planProjectFast(catalog, new InventorySnapshot(Map.of()), project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("gather:shared_material", "craft:first_goal", "craft:second_goal"),
                result.steps().stream().map(PlanStep::sourceId).toList());
        PlanStep gather = result.steps().get(0);
        assertEquals(2, gather.operationCount());
        assertEquals(6, gather.outputCount());
    }

    @Test
    void projectPlanKeepsCompletedGoalStockForLaterRecipes() {
        ItemId material = ItemId.parse("test:a_project_material");
        ItemId target = ItemId.parse("test:z_project_target");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(material, 0).item(target, 0)
                .source(new GatherSource("gather:project_material", material, 1,
                        List.of(BlockId.parse("test:material_block"))))
                .source(new CraftingSource("craft:project_target", target, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(material))), List.of()))
                .build();
        ProjectSpec project = new ProjectSpec("retain_goal", "Keep the material goal while crafting.",
                Map.of(material, 1, target, 1), ProjectSpec.Purpose.INVENTORY_GOALS);

        ProjectPlanResult result = planner().planProjectFast(catalog, new InventorySnapshot(Map.of()), project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(2, result.steps().stream().filter(step -> step.sourceId().equals("gather:project_material"))
                .mapToInt(PlanStep::outputCount).sum());
        assertEquals(List.of("gather:project_material", "gather:project_material", "craft:project_target"),
                result.steps().stream().map(PlanStep::sourceId).toList());
    }

    @Test
    void projectPlanReservesHeldStockForLaterGoalBeforePlanningEarlierGoal() {
        ItemId target = ItemId.parse("test:a_target");
        ItemId futureGoal = ItemId.parse("test:z_future_material");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(target, 0).item(futureGoal, 0)
                .source(new GatherSource("gather:future_material", futureGoal, 1,
                        List.of(BlockId.parse("test:future_material_block"))))
                .source(new CraftingSource("craft:target", target, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(futureGoal))), List.of()))
                .build();
        ProjectSpec project = new ProjectSpec("reserve_future", "Keep held stock for its project goal.",
                Map.of(target, 1, futureGoal, 1), ProjectSpec.Purpose.INVENTORY_GOALS);

        ProjectPlanResult result = planner().planProjectFast(catalog,
                new InventorySnapshot(Map.of(futureGoal, 1)), project, PlannerLimits.DEFAULT,
                PlanningPreferences.NONE);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("gather:future_material", "craft:target"),
                result.steps().stream().map(PlanStep::sourceId).toList());
    }

    @Test
    void projectPlanSharesPhysicalToolWearAcrossGoals() {
        ItemId pickaxe = ItemId.parse("test:project_pickaxe");
        ItemId firstDrop = ItemId.parse("test:a_first_drop");
        ItemId secondDrop = ItemId.parse("test:b_second_drop");
        ToolRequirement tool = new ToolRequirement(Ingredient.of(pickaxe), 2, "mine", 1);
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(pickaxe, 4).item(firstDrop, 0).item(secondDrop, 0)
                .source(new GatherSource("gather:first_drop", firstDrop, 1,
                        List.of(BlockId.parse("test:first_ore")), List.of(tool)))
                .source(new GatherSource("gather:second_drop", secondDrop, 1,
                        List.of(BlockId.parse("test:second_ore")), List.of(tool)))
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(pickaxe, 1), Set.of(), Map.of(pickaxe, 4),
                Map.of(), Map.of(pickaxe, List.of(4)),
                Map.of(pickaxe, List.of(new InventoryToolLot(4, false))));
        ProjectSpec project = new ProjectSpec("shared_tool_wear", "Two goals share one physical pickaxe.",
                Map.of(firstDrop, 2, secondDrop, 2), ProjectSpec.Purpose.INVENTORY_GOALS);

        ProjectPlanResult result = planner().planProjectFast(catalog, inventory, project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);

        assertFalse(result.success());
        assertTrue(planner().planFast(catalog, inventory, firstDrop, 2).success());
        assertTrue(planner().planFast(catalog, inventory, secondDrop, 2).success());
    }

    @Test
    void failedProjectPlanLeavesSingleGoalPlanningAvailable() {
        ItemId feasible = ItemId.parse("test:a_feasible_goal");
        ItemId blocked = ItemId.parse("test:z_blocked_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(feasible, 0).item(blocked, 0)
                .source(new GatherSource("gather:feasible", feasible, 1,
                        List.of(BlockId.parse("test:feasible_block"))))
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of());
        ProjectSpec project = new ProjectSpec("partial_fallback", "One goal has no source.",
                Map.of(feasible, 1, blocked, 1), ProjectSpec.Purpose.INVENTORY_GOALS);

        ProjectPlanResult result = planner().planProjectFast(catalog, inventory, project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);

        assertFalse(result.success());
        assertTrue(result.steps().isEmpty());
        assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.item().equals(blocked)));
        assertTrue(planner().planFast(catalog, inventory, feasible, 1).success());
    }

    @Test
    void projectGoalsShareOneExpandedNodeBudget() {
        ItemId first = ItemId.parse("test:a_project_goal");
        ItemId second = ItemId.parse("test:b_project_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(first, 0).item(second, 0)
                .source(new GatherSource("gather:first_project_goal", first, 1,
                        List.of(BlockId.parse("test:first_goal_block"))))
                .source(new GatherSource("gather:second_project_goal", second, 1,
                        List.of(BlockId.parse("test:second_goal_block"))))
                .build();
        ProjectSpec project = new ProjectSpec("shared_node_budget", "Two goals use one planner budget.",
                Map.of(first, 1, second, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
        // One simple gather root takes three visits; the remaining shared budget starts, but
        // cannot finish, the second root.
        PlannerLimits limits = new PlannerLimits(48, 4, 25, 12, 4_096, 1_000_000);

        ProjectPlanResult result = planner().planProjectFast(catalog, new InventorySnapshot(Map.of()), project,
                limits, PlanningPreferences.NONE);

        assertFalse(result.success());
        assertEquals(4, result.expandedNodes());
        assertEquals(BlockedReason.Code.NODE_LIMIT, result.blockedReasons().get(0).code());
    }

    @Test
    void projectPlanBacktracksWhenGreedyGoalChoiceBlocksLaterGoal() {
        ItemId firstGoal = ItemId.parse("test:a_project_goal");
        ItemId secondGoal = ItemId.parse("test:b_project_goal");
        ItemId shared = ItemId.parse("test:shared_input");
        ItemId alternate = ItemId.parse("test:alternate_input");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(firstGoal, 0).item(secondGoal, 0).item(shared, 0).item(alternate, 0)
                .source(new CraftingSource("craft:a_goal_from_a_shared", firstGoal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(shared))), List.of()))
                .source(new CraftingSource("craft:a_goal_from_z_alternate", firstGoal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(alternate))), List.of()))
                .source(new CraftingSource("craft:b_goal_from_shared", secondGoal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(shared))), List.of()))
                .build();
        ProjectSpec project = new ProjectSpec("project_backtrack", "Preserve shared stock for a later goal.",
                Map.of(firstGoal, 1, secondGoal, 1), ProjectSpec.Purpose.INVENTORY_GOALS);

        ProjectPlanResult result = planner().planProjectFast(catalog,
                new InventorySnapshot(Map.of(shared, 1, alternate, 1)), project, PlannerLimits.DEFAULT,
                PlanningPreferences.NONE);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("craft:a_goal_from_z_alternate", "craft:b_goal_from_shared"),
                result.steps().stream().map(PlanStep::sourceId).toList());
    }

    @Test
    void projectPlanFindsFeasibleWoodToolProgressionAcrossNineGoals() {
        ItemId woodenPickaxe = ItemId.parse("test:z_wooden_pickaxe");
        ItemId cobblestone = ItemId.parse("test:cobblestone");
        List<ItemId> gear = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> ItemId.parse("test:a_gear_" + index)).toList();
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder()
                .item(LOG, 0).item(PLANKS, 0).item(STICKS, 0).item(woodenPickaxe, 59).item(cobblestone, 0);
        gear.forEach(item -> builder.item(item, 0));
        ToolRequirement pickaxe = new ToolRequirement(Ingredient.of(woodenPickaxe), 2, "mine", 1);
        builder.source(new GatherSource("gather:oak_log", LOG, 1, List.of(BlockId.parse("test:oak_tree"))))
                .source(new CraftingSource("craft:planks", PLANKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(LOG))), List.of()))
                .source(new CraftingSource("craft:sticks", STICKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(2, PLANKS))), List.of()))
                .source(new CraftingSource("craft:wooden_pickaxe", woodenPickaxe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(3, PLANKS)),
                                new RecipeSlot(-1, Ingredient.of(2, STICKS))), List.of()))
                .source(new GatherSource("mine:cobblestone", cobblestone, 4,
                        List.of(BlockId.parse("test:stone")), List.of(pickaxe)));
        for (int index = 0; index < gear.size(); index++) {
            builder.source(new CraftingSource("craft:gear_" + index, gear.get(index), 1, RecipeType.SHAPELESS, 0, 0,
                    List.of(new RecipeSlot(-1, Ingredient.of(cobblestone)),
                            new RecipeSlot(-1, Ingredient.of(STICKS))), List.of()));
        }
        CatalogSnapshot catalog = builder.build();
        Map<ItemId, Integer> goals = new HashMap<>();
        goals.put(woodenPickaxe, 1);
        gear.forEach(item -> goals.put(item, 1));
        ProjectSpec project = new ProjectSpec("wood_tool_loadout", "Craft a tool and eight gear goals.",
                goals, ProjectSpec.Purpose.INVENTORY_GOALS);

        ProjectPlanResult result = planner().planProjectFast(catalog, new InventorySnapshot(Map.of(LOG, 10)),
                project, PlannerLimits.DEFAULT, PlanningPreferences.NONE);

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(9, result.project().goals().size());
        assertTrue(result.steps().stream().anyMatch(step -> step.sourceId().equals("craft:wooden_pickaxe")));
        assertEquals(8, result.steps().stream().filter(step -> step.sourceId().startsWith("craft:gear_")).count());
        int pickaxeIndex = java.util.stream.IntStream.range(0, result.steps().size())
                .filter(index -> result.steps().get(index).sourceId().equals("craft:wooden_pickaxe"))
                .findFirst().orElseThrow();
        int miningIndex = java.util.stream.IntStream.range(0, result.steps().size())
                .filter(index -> result.steps().get(index).sourceId().equals("mine:cobblestone"))
                .findFirst().orElseThrow();
        assertTrue(pickaxeIndex < miningIndex);
    }

    @Test
    void parsesProjectAndMaintainedCommandsWithCountCaps() {
        CommandParser parser = new CommandParser();

        assertEquals(new CommandParser.ProjectCommand("gear_iron"), parser.parse("project GEAR_IRON").command());
        assertTrue(parser.parse("projects").command() instanceof CommandParser.ProjectsCommand);
        assertEquals(new CommandParser.MaintainCommand("oak_log", 64), parser.parse("maintain oak_log 64").command());
        assertEquals("all", ((CommandParser.UnmaintainCommand) parser.parse("unmaintain ALL").command()).item());
        assertTrue(parser.parse("unmaintain minecraft:oak_log").success());
        assertTrue(parser.parse("maintained").command() instanceof CommandParser.MaintainedCommand);
        assertFalse(parser.parse("project invalid/name").success());
        assertFalse(parser.parse("maintain oak_log 0").success());
        assertFalse(parser.parse("maintain oak_log 1000001").success());
        assertFalse(parser.parse("maintained extra").success());
    }

    @Test
    void maintainedDemandReservesQuantitiesAndRefillsFromObservedProgress() {
        MaintainedDemandModel model = new MaintainedDemandModel();
        model.maintain(LOG, 8, 5);

        List<MaintainedDemandModel.MaintenanceRequest> initial = model.onInventoryChanged(Map.of(LOG, 2));
        assertEquals(1, initial.size());
        MaintainedDemandModel.MaintenanceRequest first = initial.get(0);
        assertEquals(8, first.targetCount());
        assertEquals(6, first.deficitCount());
        assertEquals(Map.of(LOG, 2), model.reservedCounts());
        assertEquals(Map.of(), model.reservedCountsFor(LOG));
        assertEquals(Map.of(LOG, 6), model.activeOutputReservations());
        assertThrows(IllegalArgumentException.class, () -> model.complete(first.taskId(), Map.of(LOG, -1)));
        assertEquals(first, model.activeRequests().get(0));

        MaintainedDemandModel.Status queued = model.statuses().get(0);
        assertEquals(2, queued.actualCount());
        assertEquals(6, queued.shortfallCount());
        assertEquals(6, queued.activeReservedCount());
        assertEquals(0, queued.deficitCount());
        assertEquals(MaintainedDemandModel.State.QUEUED, queued.state());

        List<MaintainedDemandModel.MaintenanceRequest> followup = model.complete(first.taskId(), Map.of(LOG, 4));
        assertEquals(1, followup.size());
        assertEquals(8, followup.get(0).targetCount());
        assertEquals(4, followup.get(0).deficitCount());
        assertEquals(4, model.reservedCounts().get(LOG));

        assertTrue(model.complete(followup.get(0).taskId(), Map.of(LOG, 8)).isEmpty());
        MaintainedDemandModel.Status satisfied = model.statuses().get(0);
        assertEquals(8, satisfied.actualCount());
        assertEquals(0, satisfied.shortfallCount());
        assertEquals(MaintainedDemandModel.State.SATISFIED, satisfied.state());
    }

    @Test
    void maintainedDemandUsesHysteresisAndBlocksRepeatedFailuresUntilInventoryChanges() {
        MaintainedDemandModel model = new MaintainedDemandModel();
        model.maintain(LOG, 8, 3);
        assertTrue(model.onInventoryChanged(Map.of(LOG, 4)).isEmpty());
        assertEquals(MaintainedDemandModel.State.HOLDING_ABOVE_REFILL_POINT, model.statuses().get(0).state());

        MaintainedDemandModel.MaintenanceRequest request = model.onInventoryChanged(Map.of(LOG, 3)).get(0);
        assertTrue(model.fail(request.taskId(), Map.of(LOG, 3)).isEmpty());
        assertEquals(MaintainedDemandModel.State.BLOCKED_UNTIL_INVENTORY_CHANGE, model.statuses().get(0).state());
        assertTrue(model.onInventoryChanged(Map.of(LOG, 3)).isEmpty());

        List<MaintainedDemandModel.MaintenanceRequest> retry = model.onInventoryChanged(Map.of(LOG, 3, STICKS, 1));
        assertEquals(1, retry.size());
        assertEquals(5, retry.get(0).deficitCount());
        assertEquals(MaintainedDemandModel.State.QUEUED, model.statuses().get(0).state());
    }

    @Test
    void maintainedDemandBoundsTargetsAndActiveRequestsAndCancelsOnlyItsOwnJobs() {
        MaintainedDemandModel model = new MaintainedDemandModel();
        for (int index = 0; index < MaintainedDemandModel.MAX_TARGETS; index++) {
            model.maintain(ItemId.parse("test:item_" + index), 1);
        }
        assertThrows(IllegalArgumentException.class, () -> model.maintain(ItemId.parse("test:overflow"), 1));

        List<MaintainedDemandModel.MaintenanceRequest> requests = model.onInventoryChanged(Map.of());
        assertEquals(MaintainedDemandModel.MAX_ACTIVE_TASKS, requests.size());
        assertEquals(MaintainedDemandModel.MAX_ACTIVE_TASKS, model.activeRequests().size());
        assertTrue(model.onInventoryChanged(Map.of()).isEmpty());

        ItemId one = requests.get(0).item();
        assertEquals(List.of(requests.get(0).taskId()), model.unmaintain(one));
        assertEquals(MaintainedDemandModel.MAX_ACTIVE_TASKS - 1, model.activeRequests().size());
        assertEquals(MaintainedDemandModel.MAX_ACTIVE_TASKS - 1, model.unmaintainAll().size());
        assertTrue(model.statuses().isEmpty());
    }

    @Test
    void protectedLogsAreNotSpentToCraftSticksButLegacySnapshotsRemainSpendable() {
        CatalogSnapshot catalog = woodToSticksCatalog();
        InventorySnapshot protectedLog = new InventorySnapshot(Map.of(LOG, 1), Set.of(), Map.of(), Map.of(LOG, 1));

        PlanResult protectedResult = planner().plan(catalog, protectedLog, STICKS, 4);
        assertTrue(protectedResult.success());
        assertTrue(protectedResult.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER && step.output().equals(LOG)));
        assertEquals(1, protectedLog.count(LOG));
        assertEquals(1, protectedLog.protectedCounts().get(LOG));

        InventorySnapshot oneArgument = new InventorySnapshot(Map.of(LOG, 1));
        InventorySnapshot legacyThreeArgument = new InventorySnapshot(Map.of(LOG, 1), Set.of(), Map.of());
        assertTrue(oneArgument.protectedCounts().isEmpty());
        assertTrue(legacyThreeArgument.protectedCounts().isEmpty());
        PlanResult legacyResult = planner().plan(catalog, oneArgument, STICKS, 4);
        assertTrue(legacyResult.success());
        assertFalse(legacyResult.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER && step.output().equals(LOG)));
        assertThrows(IllegalArgumentException.class,
                () -> new InventorySnapshot(Map.of(LOG, 1), Set.of(), Map.of(), Map.of(LOG, 2)));
    }

    @Test
    void protectedToolsRemainReusableAndOnlyUnprotectedMaterialsCanBeConsumed() {
        ItemId widget = ItemId.parse("test:widget");
        CatalogSnapshot materialCatalog = CatalogSnapshot.builder()
                .item(LOG, 0)
                .item(widget, 0)
                .source(new GatherSource("extra-logs", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .source(new CraftingSource("logs-to-widget", widget, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(4, LOG))), List.of()))
                .build();
        InventorySnapshot partialProtected = new InventorySnapshot(Map.of(LOG, 6), Set.of(), Map.of(), Map.of(LOG, 4));

        PlanResult materialPlan = planner().plan(materialCatalog, partialProtected, widget, 1);
        assertTrue(materialPlan.success());
        PlanStep gather = materialPlan.steps().stream().filter(step -> step.kind() == PlanKind.GATHER && step.output().equals(LOG)).findFirst().orElseThrow();
        assertEquals(2, gather.outputCount());
        SelectedItemRequirement logIngredient = materialPlan.steps().stream().filter(step -> step.output().equals(widget))
                .flatMap(step -> step.requirements().stream()).filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast).findFirst().orElseThrow();
        assertEquals(4, logIngredient.count());
        assertEquals(4, partialProtected.protectedCounts().get(LOG));

        CatalogSnapshot repeatedConsumption = CatalogSnapshot.builder()
                .item(LOG, 0)
                .item(widget, 0)
                .source(new GatherSource("unused-extra-logs", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .source(new CraftingSource("consume-in-two-groups", widget, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(LOG)), new RecipeSlot(-1, Ingredient.of(2, LOG))), List.of()))
                .build();
        InventorySnapshot twoConsumptionSnapshot = new InventorySnapshot(Map.of(LOG, 5), Set.of(), Map.of(), Map.of(LOG, 2));
        PlanResult repeatedPlan = planner().plan(repeatedConsumption, twoConsumptionSnapshot, widget, 1);
        assertTrue(repeatedPlan.success());
        assertFalse(repeatedPlan.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER));
        assertEquals(5, twoConsumptionSnapshot.count(LOG));
        assertEquals(2, twoConsumptionSnapshot.protectedCounts().get(LOG));

        ItemId pickaxe = ItemId.parse("minecraft:iron_pickaxe");
        ItemId ore = ItemId.parse("test:ore");
        CatalogSnapshot miningCatalog = CatalogSnapshot.builder()
                .item(pickaxe, 250)
                .item(ore, 0)
                .source(new GatherSource("mine-ore", ore, 1, List.of(BlockId.parse("minecraft:iron_ore")),
                        List.of(new ToolRequirement(Ingredient.of(pickaxe), 20, "mining pickaxe"))))
                .build();
        InventorySnapshot protectedPickaxe = new InventorySnapshot(Map.of(pickaxe, 1), Set.of(), Map.of(pickaxe, 100), Map.of(pickaxe, 1));

        PlanResult miningPlan = planner().plan(miningCatalog, protectedPickaxe, ore, 1);
        assertTrue(miningPlan.success());
        assertEquals(1, miningPlan.steps().size());
        assertEquals(PlanKind.GATHER, miningPlan.steps().get(0).kind());
        assertTrue(miningPlan.steps().get(0).requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).anyMatch(requirement -> requirement.item().equals(pickaxe)));
    }

    @Test
    void heldStonePickKeepsFurnacePlanFeasibleWithinNodeBudgetDespiteToolAlternatives() {
        ItemId furnace = ItemId.parse("minecraft:furnace");
        ItemId cobblestone = ItemId.parse("minecraft:cobblestone");
        List<ItemId> picks = List.of(
                ItemId.parse("minecraft:wooden_pickaxe"),
                ItemId.parse("minecraft:stone_pickaxe"),
                ItemId.parse("minecraft:iron_pickaxe"),
                ItemId.parse("minecraft:diamond_pickaxe"),
                ItemId.parse("minecraft:netherite_pickaxe"));
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(cobblestone, 0);
        for (int index = 0; index < picks.size(); index++) {
            ItemId pick = picks.get(index);
            ItemId material = ItemId.parse("test:pick_material_" + index);
            builder.item(pick, 250).item(material, 0)
                    .source(new GatherSource("gather:pick_material_" + index, material, 1,
                            List.of(BlockId.parse("test:pick_ore_" + index))))
                    .source(new CraftingSource("craft:pick_" + index, pick, 1, RecipeType.SHAPELESS, 0, 0,
                            List.of(new RecipeSlot(-1, Ingredient.of(material))), List.of()));
        }
        builder.source(new CraftingSource("craft:furnace", furnace, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.of(8, cobblestone))),
                List.of(new ToolRequirement(Ingredient.choices(picks, 1), 1, "furnace crafting pickaxe"))));

        PlanResult result = planner().plan(builder.build(),
                new InventorySnapshot(Map.of(cobblestone, 8, picks.get(1), 1), Set.of(), Map.of(picks.get(1), 100)),
                furnace, 1, new PlannerLimits(48, 6, 25, 12, 4_096, 1_000_000));

        assertTrue(result.success());
        assertTrue(result.optimal());
        assertEquals(6, result.expandedNodes());
        assertEquals(1, result.steps().size());
        assertEquals(furnace, result.steps().get(0).output());
        assertTrue(result.steps().get(0).requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).anyMatch(requirement -> requirement.item().equals(picks.get(1))));
    }

    @Test
    void miningWearUsesActualKnownChargesAndCombinesSeparateToolStacks() {
        ItemId pickaxe = ItemId.parse("minecraft:stone_pickaxe");
        ItemId ore = ItemId.parse("test:stone_ore");
        assertEquals(0, new ToolRequirement(Ingredient.of(pickaxe), 8, "custom harvest").wearPerOperation());
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(pickaxe, 131)
                .item(ore, 0)
                .source(new GatherSource("mine:stone_ore", ore, 1, List.of(BlockId.parse("test:stone_ore")),
                        List.of(new ToolRequirement(Ingredient.of(pickaxe), 2, "mine ore", 1))))
                .build();

        InventorySnapshot sevenUses = new InventorySnapshot(Map.of(pickaxe, 1), Set.of(), Map.of(pickaxe, 7), Map.of(),
                Map.of(pickaxe, List.of(7)));
        assertEquals(List.of(new InventoryToolLot(7, false)), sevenUses.toolLots().get(pickaxe));
        PlanResult smallBatch = planner().plan(catalog, sevenUses, ore, 6);
        assertTrue(smallBatch.success());
        assertEquals(6, smallBatch.steps().get(0).operationCount());
        assertFalse(planner().plan(catalog, sevenUses, ore, 7).success());

        InventorySnapshot twoWornPicks = new InventorySnapshot(Map.of(pickaxe, 2), Set.of(), Map.of(pickaxe, 2), Map.of(),
                Map.of(pickaxe, List.of(2, 2)));
        PlanResult twoToolBatch = planner().plan(catalog, twoWornPicks, ore, 2);
        assertTrue(twoToolBatch.success());
        assertFalse(planner().plan(catalog, twoWornPicks, ore, 3).success());

        // A legacy max-only snapshot proves one lot, never one lot per counted tool.
        InventorySnapshot legacyMaximum = new InventorySnapshot(Map.of(pickaxe, 2), Set.of(), Map.of(pickaxe, 2));
        assertEquals(List.of(2), legacyMaximum.durabilityLots().get(pickaxe));
        assertEquals(List.of(new InventoryToolLot(2, false)), legacyMaximum.toolLots().get(pickaxe));
        assertFalse(planner().plan(catalog, legacyMaximum, ore, 2).success());
        assertThrows(IllegalArgumentException.class, () -> new InventorySnapshot(Map.of(pickaxe, 1), Set.of(),
                Map.of(pickaxe, 2), Map.of(), Map.of(pickaxe, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new InventorySnapshot(Map.of(pickaxe, 1), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(pickaxe, List.of(
                        new InventoryToolLot(1, false), new InventoryToolLot(1, true)))));
        assertThrows(IllegalArgumentException.class, () -> new InventorySnapshot(Map.of(pickaxe, 37), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(pickaxe,
                        java.util.Collections.nCopies(37, new InventoryToolLot(1, false)))));
        assertThrows(IllegalArgumentException.class, () -> new InventoryToolLot(10_000_001, true));

        // A counts-only snapshot does not claim an unknown damageable tool is full.
        assertFalse(planner().plan(catalog, new InventorySnapshot(Map.of(pickaxe, 1)), ore, 1).success());
    }

    @Test
    void silkTouchToolRequiresExplicitCompatibleGatherAndCanBeReplacedWithCraftedOrdinaryTool() {
        ItemId pickaxe = ItemId.parse("minecraft:diamond_pickaxe");
        ItemId diamond = ItemId.parse("minecraft:diamond");
        ItemId sticks = ItemId.parse("minecraft:stick");
        ItemId obsidian = ItemId.parse("minecraft:obsidian");
        ItemId rawIron = ItemId.parse("minecraft:raw_iron");
        ToolRequirement pickRequirement = new ToolRequirement(Ingredient.of(pickaxe), 2, "mine", 1);
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(pickaxe, 1561).item(diamond, 0).item(sticks, 0).item(obsidian, 0).item(rawIron, 0)
                .source(new GatherSource("mine:obsidian", obsidian, 1, List.of(BlockId.parse("minecraft:obsidian")),
                        List.of(pickRequirement), Map.of("silkTouchCompatible", "true")))
                .source(new GatherSource("mine:raw_iron", rawIron, 1, List.of(BlockId.parse("minecraft:iron_ore")),
                        List.of(pickRequirement)))
                .source(new CraftingSource("craft:diamond_pickaxe", pickaxe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(3, diamond)),
                                new RecipeSlot(-1, Ingredient.of(2, sticks))), List.of()))
                .build();
        InventorySnapshot silkOnly = new InventorySnapshot(Map.of(pickaxe, 1), Set.of(), Map.of(pickaxe, 20), Map.of(),
                Map.of(pickaxe, List.of(20)), Map.of(pickaxe, List.of(new InventoryToolLot(20, true))));

        PlanResult obsidianPlan = planner().planFast(catalog, silkOnly, obsidian, 1);
        PlanResult rawIronWithoutMaterials = planner().planFast(catalog, silkOnly, rawIron, 1);
        InventorySnapshot craftableOrdinaryPick = new InventorySnapshot(
                Map.of(pickaxe, 1, diamond, 3, sticks, 2), Set.of(), Map.of(pickaxe, 20), Map.of(),
                Map.of(pickaxe, List.of(20)), Map.of(pickaxe, List.of(new InventoryToolLot(20, true))));
        PlanResult rawIronWithMaterials = planner().planFast(catalog, craftableOrdinaryPick, rawIron, 1);

        assertTrue(obsidianPlan.success(), obsidianPlan.blockedReasons().toString());
        assertEquals("true", obsidianPlan.steps().get(0).attributes().get("silkTouchCompatible"));
        assertFalse(rawIronWithoutMaterials.success());
        assertTrue(rawIronWithMaterials.success(), rawIronWithMaterials.blockedReasons().toString());
        assertTrue(rawIronWithMaterials.steps().stream().anyMatch(step -> step.sourceId().equals("craft:diamond_pickaxe")));
    }

    @Test
    void harvestWearCapacitySeparatesSilkTouchLotsAndDebitsCompatibleLotsAcrossOperations() {
        ItemId pickaxe = ItemId.parse("minecraft:diamond_pickaxe");
        ItemId obsidian = ItemId.parse("minecraft:obsidian");
        ItemId rawIron = ItemId.parse("minecraft:raw_iron");
        ToolRequirement pickRequirement = new ToolRequirement(Ingredient.of(pickaxe), 2, "mine", 1);
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(pickaxe, 1561).item(obsidian, 0).item(rawIron, 0)
                .source(new GatherSource("mine:obsidian", obsidian, 1, List.of(BlockId.parse("minecraft:obsidian")),
                        List.of(pickRequirement), Map.of("silkTouchCompatible", "true")))
                .source(new GatherSource("mine:raw_iron", rawIron, 1, List.of(BlockId.parse("minecraft:iron_ore")),
                        List.of(pickRequirement)))
                .build();
        InventorySnapshot mixedTools = new InventorySnapshot(Map.of(pickaxe, 2), Set.of(), Map.of(pickaxe, 4), Map.of(),
                Map.of(pickaxe, List.of(3, 4)), Map.of(pickaxe, List.of(
                        new InventoryToolLot(4, false), new InventoryToolLot(3, true))));

        PlanResult ordinaryCapacity = planner().planFast(catalog, mixedTools, rawIron, 3);
        PlanResult ordinaryOverCapacity = planner().planFast(catalog, mixedTools, rawIron, 4);
        PlanResult compatibleCapacity = planner().planFast(catalog, mixedTools, obsidian, 5);
        PlanResult compatibleOverCapacity = planner().planFast(catalog, mixedTools, obsidian, 6);

        assertTrue(ordinaryCapacity.success(), ordinaryCapacity.blockedReasons().toString());
        assertEquals(3, ordinaryCapacity.steps().get(0).operationCount());
        assertFalse(ordinaryOverCapacity.success());
        assertTrue(compatibleCapacity.success(), compatibleCapacity.blockedReasons().toString());
        assertEquals(5, compatibleCapacity.steps().get(0).operationCount());
        assertFalse(compatibleOverCapacity.success());
    }

    @Test
    void largeFuelCatalogPrioritizesHeldFuelWithinTheNodeBudget() {
        ItemId raw = ItemId.parse("test:raw_ore");
        ItemId output = ItemId.parse("test:ingot");
        ItemId heldFuel = ItemId.parse("test:zz_held_fuel");
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(raw, 0).item(output, 0).item(heldFuel, 0, 300);
        var fuels = new java.util.ArrayList<ItemSelector>();
        for (int index = 0; index < 255; index++) {
            ItemId unavailable = ItemId.parse("test:unused_fuel_" + index);
            builder.item(unavailable, 0, 300);
            fuels.add(ItemSelector.item(unavailable));
        }
        fuels.add(ItemSelector.item(heldFuel));
        builder.source(new SmeltingSource("smelt:ingot", output, 1, Ingredient.of(raw), fuels, 200, List.of()));
        PlannerLimits limits = new PlannerLimits(48, 40, 20, 12, 4096, 1000);
        PlanResult result = planner().plan(builder.build(), new InventorySnapshot(Map.of(raw, 1, heldFuel, 1)), output, 1, limits);
        assertTrue(result.success(), result.blockedReasons().toString());
        assertFalse(result.optimal());
        assertTrue(result.expandedNodes() <= 40);
        assertTrue(result.steps().get(0).requirements().stream().filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast).anyMatch(requirement -> requirement.item().equals(heldFuel)));
    }

    @Test
    void replacementToolsAccountForTheirOwnMiningMaterials() {
        ItemId pickaxe = ItemId.parse("minecraft:stone_pickaxe");
        ItemId cobble = ItemId.parse("minecraft:cobblestone");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(pickaxe, 131).item(cobble, 0).item(STICKS, 0)
                .source(new GatherSource("mine:stone", cobble, 1, List.of(BlockId.parse("minecraft:stone")),
                        List.of(new ToolRequirement(Ingredient.of(pickaxe), 2, "mine stone", 1))))
                .source(new CraftingSource("craft:stone_pickaxe", pickaxe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(3, cobble)), new RecipeSlot(-1, Ingredient.of(2, STICKS))), List.of()))
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(pickaxe, 1, STICKS, 4), Set.of(),
                Map.of(pickaxe, 5), Map.of(), Map.of(pickaxe, List.of(5)));
        PlanResult result = planner().plan(catalog, inventory, cobble, 132);
        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(2, result.steps().stream().filter(step -> pickaxe.equals(step.output())).mapToInt(PlanStep::outputCount).sum());
        assertEquals(138, result.steps().stream().filter(step -> cobble.equals(step.output())).mapToInt(PlanStep::outputCount).sum());
        assertEquals(List.of(5), inventory.durabilityLots().get(pickaxe));
    }

    @Test
    void insufficientBatchWearPlansReplacementToolsBeforeMining() {
        ItemId pickaxe = ItemId.parse("test:short_lived_pick");
        ItemId material = ItemId.parse("test:pick_material");
        ItemId ore = ItemId.parse("test:large_ore_batch");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(pickaxe, 20)
                .item(material, 0)
                .item(ore, 0)
                .source(new GatherSource("mine:large_batch", ore, 1, List.of(BlockId.parse("test:large_ore")),
                        List.of(new ToolRequirement(Ingredient.of(pickaxe), 2, "mine batch", 1))))
                .source(new CraftingSource("craft:short_lived_pick", pickaxe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(material))), List.of()))
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(pickaxe, 1, material, 4), Set.of(),
                Map.of(pickaxe, 2), Map.of(pickaxe, 1), Map.of(pickaxe, List.of(2)));

        PlanResult result = planner().plan(catalog, inventory, ore, 64);

        assertTrue(result.success());
        PlanStep craft = result.steps().stream().filter(step -> pickaxe.equals(step.output())).findFirst().orElseThrow();
        PlanStep gather = result.steps().stream().filter(step -> ore.equals(step.output())).findFirst().orElseThrow();
        assertEquals(4, craft.outputCount());
        assertEquals(64, gather.operationCount());
        assertTrue(result.steps().indexOf(craft) < result.steps().indexOf(gather));
        assertEquals(1, inventory.protectedCounts().get(pickaxe));
        assertEquals(List.of(2), inventory.durabilityLots().get(pickaxe));
    }

    @Test
    void plannedWearCarriesAcrossGatherStepsAndProtectedCraftingMaterials() {
        ItemId pickaxe = ItemId.parse("test:two_use_pick");
        ItemId material = ItemId.parse("test:pick_wood");
        ItemId firstOre = ItemId.parse("test:first_ore");
        ItemId secondOre = ItemId.parse("test:second_ore");
        ItemId output = ItemId.parse("test:combined_ore");
        ToolRequirement pickRequirement = new ToolRequirement(Ingredient.of(pickaxe), 2, "mine", 1);
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(pickaxe, 2)
                .item(material, 0)
                .item(firstOre, 0)
                .item(secondOre, 0)
                .item(output, 0)
                .source(new GatherSource("mine:first_ore", firstOre, 1, List.of(BlockId.parse("test:first_ore")), List.of(pickRequirement)))
                .source(new GatherSource("mine:second_ore", secondOre, 1, List.of(BlockId.parse("test:second_ore")), List.of(pickRequirement)))
                .source(new GatherSource("gather:pick_wood", material, 1, List.of(BlockId.parse("test:pick_wood"))))
                .source(new CraftingSource("craft:two_use_pick", pickaxe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(material))), List.of()))
                .source(new CraftingSource("craft:combined_ore", output, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(firstOre)), new RecipeSlot(-1, Ingredient.of(secondOre))), List.of()))
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(pickaxe, 1, material, 1), Set.of(),
                Map.of(pickaxe, 2), Map.of(material, 1), Map.of(pickaxe, List.of(2)));

        PlanResult result = planner().plan(catalog, inventory, output, 1);

        assertTrue(result.success());
        int first = indexOfSource(result, "mine:first_ore");
        int materialGather = indexOfSource(result, "gather:pick_wood");
        int pickCraft = indexOfSource(result, "craft:two_use_pick");
        int second = indexOfSource(result, "mine:second_ore");
        assertTrue(first >= 0 && first < materialGather);
        assertTrue(materialGather < pickCraft && pickCraft < second);
        assertEquals(1, inventory.protectedCounts().get(material));
        assertEquals(List.of(2), inventory.durabilityLots().get(pickaxe));
    }

    private static int indexOfSource(PlanResult result, String sourceId) {
        for (int index = 0; index < result.steps().size(); index++) {
            if (result.steps().get(index).sourceId().equals(sourceId)) return index;
        }
        return -1;
    }

    @Test
    void boundedFailureReasonSurvivesMoreThanTheDiagnosticCap() {
        ItemId target = ItemId.parse("test:limited_target");
        List<ItemId> alternatives = new java.util.ArrayList<>();
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(target, 0);
        for (int index = 0; index < 64; index++) {
            ItemId alternative = ItemId.parse("test:missing_" + index);
            alternatives.add(alternative);
            builder.item(alternative, 0);
        }
        builder.source(new CraftingSource("limited:target", target, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.choices(alternatives, 1))), List.of()));

        PlanResult result = planner().plan(builder.build(), new InventorySnapshot(Map.of()), target, 1,
                new PlannerLimits(48, 99, 25, 64, 4_096, 1_000_000));

        assertFalse(result.success());
        assertTrue(result.blockedReasons().size() <= 32);
        assertEquals(BlockedReason.Code.NODE_LIMIT, result.blockedReasons().get(0).code());
    }

    @Test
    void fastPlanBootstrapsDiamondBootsWithoutExpandingEveryWoodAndToolChoice() {
        ItemId cobble = ItemId.parse("minecraft:cobblestone"), coal = ItemId.parse("minecraft:coal");
        ItemId rawIron = ItemId.parse("minecraft:raw_iron"), iron = ItemId.parse("minecraft:iron_ingot");
        ItemId diamond = ItemId.parse("minecraft:diamond"), boots = ItemId.parse("minecraft:diamond_boots");
        ItemId table = ItemId.parse("minecraft:crafting_table"), furnace = ItemId.parse("minecraft:furnace");
        ItemId wooden = ItemId.parse("minecraft:wooden_pickaxe"), stone = ItemId.parse("minecraft:stone_pickaxe");
        ItemId ironPick = ItemId.parse("minecraft:iron_pickaxe"), diamondPick = ItemId.parse("minecraft:diamond_pickaxe");
        StationRequirement tableRequirement = new StationRequirement(StationId.parse(table.toString()), table, "craft");
        StationRequirement furnaceRequirement = new StationRequirement(StationId.parse(furnace.toString()), furnace, "smelt");
        TagId plankTag = TagId.parse("test:many_planks");
        var builder = CatalogSnapshot.builder().item(STICKS, 0).item(cobble, 0).item(coal, 0, 1600)
                .item(rawIron, 0).item(iron, 0).item(diamond, 0).item(boots, 0).item(table, 0).item(furnace, 0)
                .item(wooden, 59).item(stone, 131).item(ironPick, 250).item(diamondPick, 1561);
        var planks = new java.util.ArrayList<ItemId>();
        for (int index = 0; index < 24; index++) {
            ItemId log = ItemId.parse("test:log_" + index), plank = ItemId.parse("test:plank_" + index);
            planks.add(plank);
            builder.item(log, 0).item(plank, 0)
                .source(new GatherSource("gather:log_" + index, log, 1, List.of(BlockId.parse(log.toString()))))
                .source(new CraftingSource("craft:planks_" + index, plank, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(log))), List.of()));
        }
        builder.tag(plankTag, planks);
        builder.source(new CraftingSource("craft:sticks", STICKS, 4, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.tag(plankTag, 2))), List.of()));
        builder.source(new CraftingSource("craft:table", table, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.tag(plankTag, 4))), List.of()));
        for (var entry : Map.of(wooden, Ingredient.tag(plankTag, 3), stone, Ingredient.of(3, cobble),
                ironPick, Ingredient.of(3, iron), diamondPick, Ingredient.of(3, diamond)).entrySet()) {
            builder.source(new CraftingSource("craft:" + entry.getKey().path(), entry.getKey(), 1, RecipeType.SHAPELESS, 0, 0,
                    List.of(new RecipeSlot(-1, entry.getValue()), new RecipeSlot(-1, Ingredient.of(2, STICKS))),
                    List.of(tableRequirement)));
        }
        builder.source(new GatherSource("gather:cobble", cobble, 1, List.of(BlockId.parse("minecraft:stone")),
                List.of(new ToolRequirement(Ingredient.of(wooden, stone, ironPick, diamondPick), 2, "mine", 1))));
        builder.source(new GatherSource("gather:raw_iron", rawIron, 1, List.of(BlockId.parse("minecraft:iron_ore")),
                List.of(new ToolRequirement(Ingredient.of(stone, ironPick, diamondPick), 2, "mine", 1))));
        builder.source(new GatherSource("gather:coal", coal, 1, List.of(BlockId.parse("minecraft:coal_ore")),
                List.of(new ToolRequirement(Ingredient.of(wooden, stone, ironPick, diamondPick), 2, "mine", 1))));
        builder.source(new GatherSource("gather:diamond", diamond, 1, List.of(BlockId.parse("minecraft:diamond_ore")),
                List.of(new ToolRequirement(Ingredient.of(ironPick, diamondPick), 2, "mine", 1))));
        builder.source(new CraftingSource("craft:furnace", furnace, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.of(8, cobble))), List.of(tableRequirement)));
        builder.source(new SmeltingSource("smelt:iron", iron, 1, Ingredient.of(rawIron),
                List.of(ItemSelector.item(coal)), 200, List.of(furnaceRequirement)));
        ItemId ironBlock = ItemId.parse("minecraft:iron_block");
        builder.item(ironBlock, 0)
                .source(new CraftingSource("craft:iron_ingot_from_block", iron, 9, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(ironBlock))), List.of()))
                .source(new CraftingSource("craft:iron_block", ironBlock, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(9, iron))), List.of(tableRequirement)));
        builder.source(new CraftingSource("craft:boots", boots, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.of(4, diamond))), List.of(tableRequirement)));
        PlanResult result = planner().planFast(builder.build(), new InventorySnapshot(Map.of()), boots, 1,
                new PlannerLimits(48, 300, 20, 12, 4096, 1_000_000));
        assertTrue(result.success(), result.blockedReasons().toString());
        assertFalse(result.optimal());
        assertEquals(4, result.steps().stream().filter(step -> step.sourceId().equals("gather:diamond"))
                .mapToInt(PlanStep::outputCount).sum());
        assertEquals(3, result.steps().stream().filter(step -> step.sourceId().equals("smelt:iron"))
                .mapToInt(PlanStep::outputCount).sum());
        assertTrue(indexOfSource(result, "craft:wooden_pickaxe") < indexOfSource(result, "craft:stone_pickaxe"));
        assertTrue(indexOfSource(result, "craft:stone_pickaxe") < indexOfSource(result, "craft:iron_pickaxe"));
        assertTrue(indexOfSource(result, "craft:iron_pickaxe") < indexOfSource(result, "gather:diamond"));
        assertTrue(result.expandedNodes() < 300);
    }

    @Test
    void bootstrapOrderingAvoidsStationRecipeThatConsumesTheCurrentOutput() {
        ItemId rawIron = ItemId.parse("minecraft:raw_iron");
        ItemId iron = ItemId.parse("minecraft:iron_ingot");
        ItemId coal = ItemId.parse("minecraft:coal");
        ItemId cobble = ItemId.parse("minecraft:cobblestone");
        ItemId smoothStone = ItemId.parse("minecraft:smooth_stone");
        ItemId table = ItemId.parse("minecraft:crafting_table");
        ItemId furnace = ItemId.parse("minecraft:furnace");
        ItemId blastFurnace = ItemId.parse("minecraft:blast_furnace");
        ItemId pickaxe = ItemId.parse("minecraft:iron_pickaxe");
        StationId tableStation = StationId.parse("minecraft:crafting_table");
        StationId furnaceStation = StationId.parse("minecraft:furnace");
        StationId blastStation = StationId.parse("minecraft:blast_furnace");
        StationRequirement tableRequirement = new StationRequirement(tableStation, table, "craft");
        StationRequirement furnaceRequirement = new StationRequirement(furnaceStation, furnace, "smelt");
        StationRequirement blastRequirement = new StationRequirement(blastStation, blastFurnace, "smelt");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(rawIron, 0).item(iron, 0).item(coal, 0, 1600).item(cobble, 0).item(smoothStone, 0)
                .item(table, 0).item(furnace, 0).item(blastFurnace, 0).item(pickaxe, 250).item(STICKS, 0)
                .source(new GatherSource("gather:raw_iron", rawIron, 1, List.of(BlockId.parse("minecraft:iron_ore"))))
                .source(new CraftingSource("craft:furnace", furnace, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(8, cobble))), List.of(tableRequirement)))
                .source(new CraftingSource("craft:blast_furnace", blastFurnace, 1, RecipeType.SHAPED, 3, 3,
                        List.of(new RecipeSlot(0, Ingredient.of(iron)), new RecipeSlot(1, Ingredient.of(iron)),
                                new RecipeSlot(2, Ingredient.of(iron)), new RecipeSlot(3, Ingredient.of(iron)),
                                new RecipeSlot(4, Ingredient.of(furnace)), new RecipeSlot(5, Ingredient.of(iron)),
                                new RecipeSlot(6, Ingredient.of(smoothStone)), new RecipeSlot(7, Ingredient.of(smoothStone)),
                                new RecipeSlot(8, Ingredient.of(smoothStone))), List.of(tableRequirement)))
                .source(new SmeltingSource("smelt:blast_iron", iron, 1, Ingredient.of(rawIron),
                        List.of(ItemSelector.item(coal)), 100, List.of(blastRequirement)))
                .source(new SmeltingSource("smelt:furnace_iron", iron, 1, Ingredient.of(rawIron),
                        List.of(ItemSelector.item(coal)), 200, List.of(furnaceRequirement)))
                .source(new CraftingSource("craft:iron_pickaxe", pickaxe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(3, iron)), new RecipeSlot(-1, Ingredient.of(2, STICKS))),
                        List.of(tableRequirement)))
                .build();
        Map<ItemId, Integer> stocked = Map.of(rawIron, 3, coal, 1, cobble, 8, smoothStone, 3, STICKS, 2);

        PlanResult furnaceBootstrap = planner().planFast(catalog,
                new InventorySnapshot(stocked, Set.of(tableStation), Map.of()), pickaxe, 1);

        assertTrue(furnaceBootstrap.success(), furnaceBootstrap.blockedReasons().toString());
        assertTrue(furnaceBootstrap.steps().stream().anyMatch(step -> step.sourceId().equals("smelt:furnace_iron")));
        assertFalse(furnaceBootstrap.steps().stream().anyMatch(step -> step.sourceId().equals("smelt:blast_iron")));
        assertTrue(furnaceBootstrap.expandedNodes() < 1_024);

        var heldBlastStock = new HashMap<>(stocked);
        heldBlastStock.put(blastFurnace, 1);
        PlanResult heldBlast = planner().planFast(catalog,
                new InventorySnapshot(heldBlastStock, Set.of(tableStation), Map.of()), pickaxe, 1);
        assertTrue(heldBlast.success(), heldBlast.blockedReasons().toString());
        assertTrue(heldBlast.steps().stream().anyMatch(step -> step.sourceId().equals("smelt:blast_iron")));

        PlanResult placedBlast = planner().planFast(catalog,
                new InventorySnapshot(stocked, Set.of(tableStation, blastStation), Map.of()), pickaxe, 1);
        assertTrue(placedBlast.success(), placedBlast.blockedReasons().toString());
        assertTrue(placedBlast.steps().stream().anyMatch(step -> step.sourceId().equals("smelt:blast_iron")));
    }

    @Test
    void fastPlanRestartsFromOriginalInventoryAfterGreedyResourceConflict() {
        ItemId a = ItemId.parse("test:a"), b = ItemId.parse("test:b");
        ItemId intermediate = ItemId.parse("test:intermediate"), target = ItemId.parse("test:target");
        var builder = CatalogSnapshot.builder().item(a, 0).item(b, 0).item(intermediate, 0).item(target, 0);
        for (ItemId input : List.of(a, b)) builder.source(new CraftingSource("craft:" + input.path(), intermediate, 1,
                RecipeType.SHAPELESS, 0, 0, List.of(new RecipeSlot(-1, Ingredient.of(input))), List.of()));
        builder.source(new CraftingSource("craft:target", target, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.of(intermediate)), new RecipeSlot(-1, Ingredient.of(a))), List.of()));
        InventorySnapshot inventory = new InventorySnapshot(Map.of(a, 1, b, 1));
        CatalogSnapshot catalog = builder.build();
        PlanResult result = planner().planFast(catalog, inventory, target, 1);
        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("craft:b", "craft:target"), result.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(Map.of(a, 1, b, 1), inventory.counts());
        assertTrue(result.blockedReasons().isEmpty());
        PlanResult beamOnly = planner().plan(catalog, inventory, target, 1);
        assertTrue(result.expandedNodes() > beamOnly.expandedNodes());
        int insufficientCombinedBudget = result.expandedNodes() - 1;
        PlanResult capped = planner().planFast(catalog, inventory, target, 1,
                new PlannerLimits(48, insufficientCombinedBudget, 20, 12, 4096, 1_000_000));
        assertFalse(capped.success());
        assertEquals(insufficientCombinedBudget, capped.expandedNodes());
        assertEquals(BlockedReason.Code.NODE_LIMIT, capped.blockedReasons().get(0).code());
    }

    @Test
    void fastPlanCraftsStoredWoodBeforeFollowingPreferredGatherSources() {
        CatalogSnapshot catalog = competingWoodCatalog();
        PlanningPreferences preferences = new PlanningPreferences(Map.of(
                "gather:acacia_log", 1, "gather:oak_log", 100));
        InventorySnapshot inventory = new InventorySnapshot(Map.of(LOG, 2));

        PlanResult stored = planner().planFast(catalog, inventory, STICKS, 4,
                PlannerLimits.DEFAULT, preferences);
        assertTrue(stored.success(), stored.blockedReasons().toString());
        assertEquals(List.of("craft:oak_planks", "craft:sticks"),
                stored.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(4, stored.steps().get(1).outputCount());
        assertEquals(List.of(new SelectedItemRequirement(LOG, 1, true, "recipe ingredient", 0)),
                stored.steps().get(0).requirements());
        assertEquals(Map.of(LOG, 2), inventory.counts());

        InventorySnapshot partlyProtected = new InventorySnapshot(Map.of(LOG, 2), Set.of(), Map.of(), Map.of(LOG, 1));
        PlanResult oneSpendable = planner().planFast(catalog, partlyProtected, STICKS, 4,
                PlannerLimits.DEFAULT, preferences);
        assertTrue(oneSpendable.success(), oneSpendable.blockedReasons().toString());
        assertEquals(List.of("craft:oak_planks", "craft:sticks"),
                oneSpendable.steps().stream().map(PlanStep::sourceId).toList());

        InventorySnapshot protectedLogs = new InventorySnapshot(Map.of(LOG, 2), Set.of(), Map.of(), Map.of(LOG, 2));
        PlanResult needsGathering = planner().planFast(catalog, protectedLogs, STICKS, 4,
                PlannerLimits.DEFAULT, preferences);
        assertTrue(needsGathering.success(), needsGathering.blockedReasons().toString());
        assertEquals(List.of("gather:acacia_log", "craft:acacia_planks", "craft:sticks"),
                needsGathering.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(Map.of(LOG, 2), protectedLogs.protectedCounts());
    }

    @Test
    void storedMaterialSeedBacktracksWhenDerivativesCompeteForTheSameStock() {
        ItemId a = ItemId.parse("test:a"), b = ItemId.parse("test:b");
        ItemId intermediate = ItemId.parse("test:intermediate"), target = ItemId.parse("test:target");
        var builder = CatalogSnapshot.builder().item(a, 0).item(b, 0).item(intermediate, 0).item(target, 0);
        for (ItemId input : List.of(a, b)) {
            builder.source(new GatherSource("gather:" + input.path(), input, 1,
                    List.of(BlockId.parse("test:" + input.path()))));
            builder.source(new CraftingSource("craft:" + input.path(), intermediate, 1,
                    RecipeType.SHAPELESS, 0, 0, List.of(new RecipeSlot(-1, Ingredient.of(input))), List.of()));
        }
        builder.source(new CraftingSource("craft:target", target, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.of(intermediate)), new RecipeSlot(-1, Ingredient.of(a))), List.of()));
        InventorySnapshot inventory = new InventorySnapshot(Map.of(a, 1, b, 1));

        PlanResult result = planner().planFast(builder.build(), inventory, target, 1);
        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(List.of("craft:b", "craft:target"), result.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(List.of(new SelectedItemRequirement(b, 1, true, "recipe ingredient", 0)),
                result.steps().get(0).requirements());
        assertEquals(Map.of(a, 1, b, 1), inventory.counts());
    }

    @Test
    void storedMaterialSeedSharesTheGlobalNodeAndElapsedBudgets() {
        CatalogSnapshot catalog = competingWoodCatalog();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(LOG, 2));
        PlannerLimits limits = new PlannerLimits(48, 8, 4, 12, 4096, 1_000_000);
        PlanResult capped = planner().planFast(catalog, inventory, STICKS, 4, limits);
        assertFalse(capped.success());
        assertEquals(8, capped.expandedNodes());
        assertEquals(BlockedReason.Code.NODE_LIMIT, capped.blockedReasons().get(0).code());

        var reads = new java.util.concurrent.atomic.AtomicInteger();
        AcquisitionPlanner timed = new AcquisitionPlanner(() -> reads.getAndIncrement() <= 2 ? 0L : 4_000_000L);
        PlanResult expired = timed.planFast(catalog, inventory, STICKS, 4, limits);
        assertFalse(expired.success());
        assertTrue(expired.expandedNodes() > 0);
        assertTrue(expired.expandedNodes() <= limits.maximumExpandedNodes());
        assertEquals(4_000_000L, expired.elapsedNanos());
        assertEquals(BlockedReason.Code.TIME_LIMIT, expired.blockedReasons().get(0).code());

        CatalogSnapshot gatherOnly = CatalogSnapshot.builder().item(LOG, 0)
                .source(new GatherSource("gather:oak_log", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .build();
        PlanResult empty = planner().planFast(gatherOnly, new InventorySnapshot(Map.of()), LOG, 1, limits);
        assertTrue(empty.success(), empty.blockedReasons().toString());
        assertEquals(List.of("gather:oak_log"), empty.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(3, empty.expandedNodes());
    }

    private static CatalogSnapshot competingWoodCatalog() {
        ItemId acaciaLog = ItemId.parse("minecraft:acacia_log");
        ItemId acaciaPlanks = ItemId.parse("minecraft:acacia_planks");
        TagId planks = TagId.parse("minecraft:planks");
        return CatalogSnapshot.builder().item(LOG, 0).item(PLANKS, 0).item(STICKS, 0)
                .item(acaciaLog, 0).item(acaciaPlanks, 0).tag(planks, Set.of(PLANKS, acaciaPlanks))
                .source(new GatherSource("gather:oak_log", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .source(new GatherSource("gather:acacia_log", acaciaLog, 1, List.of(BlockId.parse("minecraft:acacia_log"))))
                .source(new CraftingSource("craft:oak_planks", PLANKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(LOG))), List.of()))
                .source(new CraftingSource("craft:acacia_planks", acaciaPlanks, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(acaciaLog))), List.of()))
                .source(new CraftingSource("craft:sticks", STICKS, 4, RecipeType.SHAPED, 1, 2,
                        List.of(new RecipeSlot(0, Ingredient.tag(planks, 1)),
                                new RecipeSlot(1, Ingredient.tag(planks, 1))), List.of()))
                .build();
    }

    @Test
    void sourcePreferencesBreakIngredientTiesButEmptyPreferencesPreserveLegacyChoice() {
        ItemId deepslate = ItemId.parse("minecraft:cobbled_deepslate");
        ItemId cobblestone = ItemId.parse("minecraft:cobblestone");
        ItemId pickaxe = ItemId.parse("minecraft:stone_pickaxe");
        CatalogSnapshot catalog = stonePickPreferenceCatalog();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(STICKS, 2));

        PlanResult legacy = planner().planFast(catalog, inventory, pickaxe, 1);
        PlanResult explicitNone = planner().planFast(catalog, inventory, pickaxe, 1,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);
        assertTrue(legacy.success(), legacy.blockedReasons().toString());
        assertEquals(deepslate, selectedRecipeItem(legacy, Set.of(deepslate, cobblestone)));
        assertEquals(legacy.steps().stream().map(PlanStep::sourceId).toList(),
                explicitNone.steps().stream().map(PlanStep::sourceId).toList());
        assertEquals(selectedRecipeItem(legacy, Set.of(deepslate, cobblestone)),
                selectedRecipeItem(explicitNone, Set.of(deepslate, cobblestone)));

        PlanResult preferred = planner().planFast(catalog, inventory, pickaxe, 1,
                PlannerLimits.DEFAULT, new PlanningPreferences(Map.of(
                        "gather:cobbled_deepslate", 20,
                        "gather:cobblestone", 1)));
        assertTrue(preferred.success(), preferred.blockedReasons().toString());
        assertEquals(cobblestone, selectedRecipeItem(preferred, Set.of(deepslate, cobblestone)));
    }

    @Test
    void craftedIngredientPreferencePropagatesFromObservedGatherInputs() {
        ItemId goal = ItemId.parse("test:preference_goal");
        ItemId oakLog = ItemId.parse("test:oak_log");
        ItemId acaciaLog = ItemId.parse("test:acacia_log");
        ItemId oakPlanks = ItemId.parse("test:oak_planks");
        ItemId acaciaPlanks = ItemId.parse("test:acacia_planks");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(goal, 0).item(oakLog, 0).item(acaciaLog, 0).item(oakPlanks, 0).item(acaciaPlanks, 0)
                .source(new GatherSource("gather:oak_log", oakLog, 1, List.of(BlockId.parse("test:oak_log"))))
                .source(new GatherSource("gather:acacia_log", acaciaLog, 1, List.of(BlockId.parse("test:acacia_log"))))
                .source(new CraftingSource("craft:oak_planks", oakPlanks, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(oakLog))), List.of()))
                .source(new CraftingSource("craft:acacia_planks", acaciaPlanks, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(acaciaLog))), List.of()))
                .source(new CraftingSource("craft:goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.choices(List.of(acaciaPlanks, oakPlanks), 1))), List.of()))
                .build();

        PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of()), goal, 1,
                PlannerLimits.DEFAULT, new PlanningPreferences(Map.of(
                        "gather:oak_log", 1,
                        "gather:acacia_log", 20)));

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(oakPlanks, selectedRecipeItem(result, Set.of(oakPlanks, acaciaPlanks)));
        assertTrue(result.steps().stream().anyMatch(step -> step.sourceId().equals("gather:oak_log")));
        assertFalse(result.steps().stream().anyMatch(step -> step.sourceId().equals("gather:acacia_log")));
    }

    @Test
    void observedRecipeInputsStillRankCraftedAlternativesAfterDirectSeedLimit() {
        ItemId goal = ItemId.parse("test:bounded_preference_goal");
        ItemId oakLog = ItemId.parse("test:bounded_oak_log");
        ItemId acaciaLog = ItemId.parse("test:bounded_acacia_log");
        ItemId oakPlanks = ItemId.parse("test:bounded_oak_planks");
        ItemId acaciaPlanks = ItemId.parse("test:bounded_acacia_planks");
        var builder = CatalogSnapshot.builder()
                .item(goal, 0).item(oakLog, 0).item(acaciaLog, 0).item(oakPlanks, 0).item(acaciaPlanks, 0)
                .source(new GatherSource("gather:bounded_oak_log", oakLog, 1, List.of(BlockId.parse("test:bounded_oak_log"))))
                .source(new GatherSource("gather:bounded_acacia_log", acaciaLog, 1, List.of(BlockId.parse("test:bounded_acacia_log"))))
                .source(new CraftingSource("craft:bounded_oak_planks", oakPlanks, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(oakLog))), List.of()))
                .source(new CraftingSource("craft:bounded_acacia_planks", acaciaPlanks, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(acaciaLog))), List.of()))
                .source(new CraftingSource("craft:bounded_goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.choices(List.of(acaciaPlanks, oakPlanks), 1))), List.of()));
        var ranks = new HashMap<String, Integer>();
        ranks.put("gather:bounded_oak_log", 1);
        ranks.put("gather:bounded_acacia_log", 1_000);
        for (int index = 0; index < 512; index++) {
            String suffix = index < 10 ? "00" + index : index < 100 ? "0" + index : Integer.toString(index);
            ItemId filler = ItemId.parse("test:bounded_filler_" + suffix);
            builder.item(filler, 0).source(new GatherSource("gather:bounded_filler_" + suffix, filler, 1,
                    List.of(BlockId.parse("test:bounded_filler_" + suffix))));
            ranks.put("gather:bounded_filler_" + suffix, index + 2);
        }

        CatalogSnapshot catalog = builder.build();
        PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of()), goal, 1,
                PlannerLimits.DEFAULT, new PlanningPreferences(ranks));

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(oakPlanks, selectedRecipeItem(result, Set.of(oakPlanks, acaciaPlanks)));
        assertTrue(result.steps().stream().anyMatch(step -> step.sourceId().equals("gather:bounded_oak_log")));
        assertFalse(result.steps().stream().anyMatch(step -> step.sourceId().equals("gather:bounded_acacia_log")));
    }

    @Test
    void hintedCyclicSourceDoesNotPruneFeasibleFallback() {
        ItemId goal = ItemId.parse("test:cycle_goal");
        ItemId cycleInput = ItemId.parse("test:cycle_input");
        ItemId raw = ItemId.parse("test:cycle_raw");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(goal, 0).item(cycleInput, 0).item(raw, 0)
                .source(new CraftingSource("craft:a_preferred_cycle", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(cycleInput))), List.of()))
                .source(new CraftingSource("craft:b_cycle_back", cycleInput, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(goal))), List.of()))
                .source(new GatherSource("gather:cycle_raw", raw, 1, List.of(BlockId.parse("test:cycle_raw"))))
                .source(new CraftingSource("craft:z_feasible", goal, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(raw))), List.of()))
                .build();

        PlanResult result = planner().planFast(catalog, new InventorySnapshot(Map.of()), goal, 1,
                PlannerLimits.DEFAULT, new PlanningPreferences(Map.of(
                        "craft:a_preferred_cycle", 0,
                        "craft:z_feasible", 5)));

        assertTrue(result.success(), result.blockedReasons().toString());
        assertTrue(result.steps().stream().anyMatch(step -> step.sourceId().equals("craft:z_feasible")));
        assertFalse(result.steps().stream().anyMatch(step -> step.sourceId().equals("craft:a_preferred_cycle")));
    }

    @Test
    void sourcePreferencesCannotSpendProtectedHeldIngredientStock() {
        ItemId cobble = ItemId.parse("minecraft:cobblestone");
        ItemId deepslate = ItemId.parse("minecraft:cobbled_deepslate");
        ItemId pickaxe = ItemId.parse("minecraft:stone_pickaxe");
        CatalogSnapshot catalog = stonePickPreferenceCatalog();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(cobble, 3, deepslate, 3, STICKS, 2),
                Set.of(), Map.of(), Map.of(cobble, 3));

        PlanResult result = planner().planFast(catalog, inventory, pickaxe, 1,
                PlannerLimits.DEFAULT, new PlanningPreferences(Map.of(
                        "gather:cobblestone", 0,
                        "gather:cobbled_deepslate", 100)));

        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(deepslate, selectedRecipeItem(result, Set.of(deepslate, cobble)));
        assertEquals(3, inventory.count(cobble));
        assertEquals(3, inventory.protectedCounts().get(cobble));
    }

    @Test
    void planningPreferencesSnapshotRanksAndRejectInvalidBounds() {
        Map<String, Integer> mutableRanks = new HashMap<>();
        mutableRanks.put("gather:near", 7);
        PlanningPreferences preferences = new PlanningPreferences(mutableRanks);
        mutableRanks.put("gather:near", 8);
        assertEquals(7, preferences.sourceRanks().get("gather:near"));
        assertThrows(UnsupportedOperationException.class, () -> preferences.sourceRanks().put("gather:far", 9));
        assertThrows(IllegalArgumentException.class, () -> new PlanningPreferences(Map.of("gather:negative", -1)));
        assertThrows(IllegalArgumentException.class, () -> new PlanningPreferences(
                Map.of("gather:too_high", PlanningPreferences.MAX_RANK + 1)));
    }

    @Test
    void preferenceSnapshotStopsAtThePlannerDeadlineBeforeSourceOrdering() {
        ItemId goal = ItemId.parse("test:preference_deadline_goal");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(goal, 0)
                .source(new GatherSource("gather:preference_deadline_goal", goal, 1,
                        List.of(BlockId.parse("test:preference_deadline_goal"))))
                .build();
        long deadline = 1_000_000L;
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        AcquisitionPlanner timedPlanner = new AcquisitionPlanner(() ->
                reads.getAndIncrement() < 5 ? 0L : deadline);

        PlanResult result = timedPlanner.planFast(catalog, new InventorySnapshot(Map.of()), goal, 1,
                new PlannerLimits(48, 300, 1, 12, 4_096, 1_000_000),
                new PlanningPreferences(Map.of("gather:preference_deadline_goal", 1)));

        assertFalse(result.success());
        assertTrue(result.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.TIME_LIMIT));
        assertEquals(2, result.expandedNodes());
    }

    @Test
    void preferenceRankingRemainsDeterministicWhenSnapshotCapsAreReached() {
        ItemId goal = ItemId.parse("test:many_choice_goal");
        var builder = CatalogSnapshot.builder().item(goal, 0);
        var choices = new java.util.ArrayList<ItemId>();
        var ranks = new HashMap<String, Integer>();
        for (int index = 0; index < 600; index++) {
            String suffix = index < 10 ? "00" + index : index < 100 ? "0" + index : Integer.toString(index);
            ItemId option = ItemId.parse("test:option_" + suffix);
            choices.add(option);
            builder.item(option, 0).source(new GatherSource("gather:option_" + suffix, option, 1,
                    List.of(BlockId.parse("test:option_" + suffix))));
            ranks.put("gather:option_" + suffix, 600 - index);
        }
        builder.source(new CraftingSource("craft:many_choice_goal", goal, 1, RecipeType.SHAPELESS, 0, 0,
                List.of(new RecipeSlot(-1, Ingredient.choices(choices, 1))), List.of()));
        CatalogSnapshot catalog = builder.build();
        PlanningPreferences preferences = new PlanningPreferences(ranks);

        PlanResult first = planner().planFast(catalog, new InventorySnapshot(Map.of()), goal, 1,
                PlannerLimits.DEFAULT, preferences);
        PlanResult second = planner().planFast(catalog, new InventorySnapshot(Map.of()), goal, 1,
                PlannerLimits.DEFAULT, preferences);

        assertTrue(first.success(), first.blockedReasons().toString());
        assertTrue(second.success(), second.blockedReasons().toString());
        assertTrue(first.steps().stream().anyMatch(step -> step.sourceId().equals("gather:option_599")));
        assertEquals(first.steps().stream().map(PlanStep::sourceId).toList(),
                second.steps().stream().map(PlanStep::sourceId).toList());
    }

    private static CatalogSnapshot stonePickPreferenceCatalog() {
        ItemId deepslate = ItemId.parse("minecraft:cobbled_deepslate");
        ItemId cobblestone = ItemId.parse("minecraft:cobblestone");
        ItemId pickaxe = ItemId.parse("minecraft:stone_pickaxe");
        Ingredient stone = Ingredient.choices(List.of(deepslate, cobblestone), 1);
        return CatalogSnapshot.builder()
                .item(deepslate, 0).item(cobblestone, 0).item(STICKS, 0).item(pickaxe, 131)
                .source(new GatherSource("gather:cobbled_deepslate", deepslate, 1,
                        List.of(BlockId.parse("minecraft:cobbled_deepslate"))))
                .source(new GatherSource("gather:cobblestone", cobblestone, 1,
                        List.of(BlockId.parse("minecraft:cobblestone"))))
                .source(new CraftingSource("craft:stone_pickaxe", pickaxe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, stone), new RecipeSlot(-1, stone), new RecipeSlot(-1, stone),
                                new RecipeSlot(-1, Ingredient.of(2, STICKS))), List.of()))
                .build();
    }

    private static ItemId selectedRecipeItem(PlanResult result, Set<ItemId> candidates) {
        return result.steps().stream().filter(step -> step.kind() == PlanKind.CRAFT)
                .flatMap(step -> step.requirements().stream())
                .filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("recipe ingredient"))
                .map(SelectedItemRequirement::item)
                .filter(candidates::contains)
                .findFirst().orElseThrow();
    }

    @Test
    void fastPlanFallbackSharesTheOriginalTimeAndNodeBudgets() {
        CatalogSnapshot catalog = woodToSticksCatalog();
        for (long observed : new long[]{15_000_000L, 19_999_999L, 20_000_000L}) {
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var planner = new AcquisitionPlanner(() -> reads.getAndIncrement() == 0 ? 0L : observed);
            PlanResult result = planner.planFast(catalog, new InventorySnapshot(Map.of(LOG, 1)), LOG, 1);
            assertEquals(observed < 20_000_000L, result.success());
            assertEquals(observed, result.elapsedNanos());
            assertTrue(result.expandedNodes() <= PlannerLimits.DEFAULT.maximumExpandedNodes());
            if (!result.success()) assertEquals(BlockedReason.Code.TIME_LIMIT, result.blockedReasons().get(0).code());
        }
        PlanResult bounded = planner().planFast(catalog, new InventorySnapshot(Map.of()), STICKS, 4,
                new PlannerLimits(48, 6, 20, 12, 4096, 1_000_000));
        assertFalse(bounded.success());
        assertEquals(6, bounded.expandedNodes());
        assertEquals(BlockedReason.Code.NODE_LIMIT, bounded.blockedReasons().get(0).code());
    }

    @Test
    void harvestDemandUsesExplicitWornLotsAndCapsAdditionalCopies() {
        ItemId axe = ItemId.parse("test:axe");
        InventorySnapshot wornAndUnknown = new InventorySnapshot(Map.of(axe, 3), Set.of(), Map.of(), Map.of(),
                Map.of(axe, List.of(59, 1)));

        HarvestInvestment.ToolDemand demand = HarvestInvestment.additionalDemand(
                wornAndUnknown, axe, 100, 59, 1, 2, 2).orElseThrow();

        assertEquals(3, demand.heldCount());
        assertEquals(1, demand.additionalCount()); // Only one retained slot remains.
        assertEquals(4, demand.targetCount());
        assertEquals(58, demand.usableHeldCapacity()); // The worn and unreported copies add no capacity.
        assertEquals(58, demand.addedSafeCapacity());
        assertTrue(HarvestInvestment.additionalDemand(wornAndUnknown, axe, 58, 59, 1, 2, 2).isEmpty());

        InventorySnapshot oneKnownCopy = new InventorySnapshot(Map.of(axe, 1), Set.of(), Map.of(axe, 59));
        HarvestInvestment.ToolDemand capped = HarvestInvestment.additionalDemand(
                oneKnownCopy, axe, 500, 59, 1, 2, 2).orElseThrow();
        assertEquals(2, capped.additionalCount());
        assertEquals(3, capped.targetCount());
        assertEquals(116, capped.addedSafeCapacity());
        assertTrue(HarvestInvestment.additionalDemand(
                new InventorySnapshot(Map.of(axe, 1), Set.of(), Map.of(axe, 59), Map.of(axe, 1)),
                axe, 100, 59, 1, 2, 2).isEmpty());
        assertTrue(HarvestInvestment.additionalDemand(oneKnownCopy, axe, 1_000_001, 59, 1, 2, 2).isEmpty());
        assertTrue(HarvestInvestment.additionalDemand(oneKnownCopy, axe, 100, 0, 1, 2, 2).isEmpty());
        assertTrue(HarvestInvestment.additionalDemand(oneKnownCopy, axe, 100, 59, 0, 2, 2).isEmpty());
        assertTrue(HarvestInvestment.additionalDemand(oneKnownCopy, axe, 100, 59, 1, 1, 2).isEmpty());
        assertTrue(HarvestInvestment.additionalDemand(oneKnownCopy, axe, 100, 59, 1_000_001, 1_000_002, 2).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HarvestInvestment.ToolDemand(
                axe, 1, 1, 2, 100, 40_000_001, 1));
    }

    @Test
    void harvestInvestmentPricesTheWholeToolBootstrapAgainstTheMatchedPlan() {
        ItemId log = ItemId.parse("test:local_log");
        ItemId plank = ItemId.parse("test:plank");
        ItemId table = ItemId.parse("test:table");
        ItemId axe = ItemId.parse("test:axe");
        StationId craftingTable = StationId.parse("test:crafting_table");
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(log, 0).item(plank, 0).item(table, 0).item(axe, 59)
                .source(new GatherSource("local:logs", log, 1, List.of(BlockId.parse("test:log_block"))))
                .source(new CraftingSource("craft:planks", plank, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(log))), List.of()))
                .source(new CraftingSource("craft:table", table, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(4, plank))), List.of()))
                .source(new CraftingSource("craft:axe", axe, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(3, plank))),
                        List.of(new StationRequirement(craftingTable, table, "crafting"))))
                .build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(axe, 1), Set.of(), Map.of(axe, 59));
        HarvestInvestment.ToolDemand demand = HarvestInvestment.additionalDemand(
                inventory, axe, 100, 59, 1, 2, 1).orElseThrow();
        PlanResult ordinary = planner().planFast(catalog, new InventorySnapshot(Map.of()), log, 1);
        PlanResult investment = planner().planFast(catalog, inventory, axe, demand.targetCount());

        assertTrue(ordinary.success());
        assertTrue(investment.success(), investment.blockedReasons().toString());
        assertTrue(investment.steps().stream().anyMatch(step -> step.kind() == PlanKind.PLACE_STATION));
        HarvestInvestment.Decision decision = HarvestInvestment.approve(ordinary, investment, demand,
                Set.of(log), Set.of(craftingTable), new HarvestInvestment.TickEstimates(55, 10, 5, 7, 8));

        assertTrue(decision.approved(), decision.reason());
        assertEquals(47, decision.estimatedCostTicks()); // 2 logs, 4 craft operations, and one station placement.
        assertEquals(8, decision.estimatedNetSavingTicks());
        PlanResult mismatched = new PlanResult(axe, demand.targetCount() + 1, investment.steps(),
                List.of(), investment.optimal(), investment.expandedNodes(), investment.elapsedNanos());
        assertFalse(HarvestInvestment.approve(ordinary, mismatched, demand,
                Set.of(log), Set.of(craftingTable), new HarvestInvestment.TickEstimates(55, 10, 5, 7, 8)).approved());
    }

    @Test
    void harvestInvestmentRejectsUnsafeAuxiliaryStepsAndIncompleteOrdinaryPlans() {
        ItemId axe = ItemId.parse("test:axe");
        ItemId log = ItemId.parse("test:local_log");
        ItemId remoteOre = ItemId.parse("test:remote_ore");
        StationId unsupported = StationId.parse("test:unsupported_station");
        InventorySnapshot inventory = new InventorySnapshot(Map.of(axe, 1), Set.of(), Map.of(axe, 59));
        HarvestInvestment.ToolDemand demand = HarvestInvestment.additionalDemand(
                inventory, axe, 100, 59, 1, 2, 1).orElseThrow();
        PlanResult ordinary = simpleSuccessfulPlan(log, 1, List.of(gatherStep(log, 1)));
        HarvestInvestment.TickEstimates estimates = new HarvestInvestment.TickEstimates(100, 1, 1, 1, 1);
        List<PlanStep> unsafeSteps = List.of(
                new PlanStep(PlanKind.CUSTOM, "custom:bootstrap", log, 1, 1, List.of(), List.of(),
                        null, 0, 0, null, "custom", Map.of()),
                new PlanStep(PlanKind.SMELT, "smelt:bootstrap", log, 1, 1, List.of(), List.of(),
                        null, 0, 0, null, null, Map.of()),
                new PlanStep(PlanKind.CRAFT, "craft:unsupported_station", log, 1, 1, List.of(), List.of(),
                        RecipeType.SHAPELESS, 0, 0, unsupported, null, Map.of()),
                gatherStep(remoteOre, 1),
                stationPlacementStep(unsupported));
        for (PlanStep unsafe : unsafeSteps) {
            PlanResult investment = simpleSuccessfulPlan(axe, demand.targetCount(), List.of(unsafe));
            assertFalse(HarvestInvestment.approve(ordinary, investment, demand,
                    Set.of(log), Set.of(), estimates).approved(), unsafe.kind().toString());
        }
        PlanResult incompleteOrdinary = new PlanResult(log, 1, List.of(),
                List.of(new BlockedReason(BlockedReason.Code.NO_SOURCE, log, "incomplete", List.of(log))), false, 0, 0);
        PlanResult safeInvestment = simpleSuccessfulPlan(axe, demand.targetCount(), List.of(gatherStep(log, 1)));
        assertFalse(HarvestInvestment.approve(incompleteOrdinary, safeInvestment, demand,
                Set.of(log), Set.of(), estimates).approved());
        PlanResult emptyInvestment = simpleSuccessfulPlan(axe, demand.targetCount(), List.of());
        assertFalse(HarvestInvestment.approve(ordinary, emptyInvestment, demand,
                Set.of(log), Set.of(), estimates).approved());
    }

    @Test
    void harvestInvestmentRejectsBenefitBelowFullCostAndMinimumSaving() {
        ItemId axe = ItemId.parse("test:axe");
        ItemId log = ItemId.parse("test:local_log");
        InventorySnapshot inventory = new InventorySnapshot(Map.of(axe, 1), Set.of(), Map.of(axe, 59));
        HarvestInvestment.ToolDemand demand = HarvestInvestment.additionalDemand(
                inventory, axe, 100, 59, 1, 2, 1).orElseThrow();
        PlanResult ordinary = simpleSuccessfulPlan(log, 1, List.of(gatherStep(log, 1)));
        PlanResult investment = simpleSuccessfulPlan(axe, demand.targetCount(), List.of(gatherStep(log, 2)));

        HarvestInvestment.Decision decision = HarvestInvestment.approve(ordinary, investment, demand,
                Set.of(log), Set.of(), new HarvestInvestment.TickEstimates(20, 6, 0, 0, 9));

        assertFalse(decision.approved());
        assertEquals(12, decision.estimatedCostTicks());
        assertEquals(8, decision.estimatedNetSavingTicks());
    }

    @Test
    void harvestInvestmentPricesHeldGoalStockWithoutReservingBootstrapMaterials() {
        ItemId axe = ItemId.parse("test:axe");
        ItemId birch = ItemId.parse("test:birch_log");
        PlanStep craft = new PlanStep(PlanKind.CRAFT, "craft:bootstrap", PLANKS, 12, 3,
                List.of(new SelectedItemRequirement(LOG, 3, true, "ingredient", 0),
                        new SelectedItemRequirement(birch, 1, false, "reserved tool", -1)),
                List.of(), RecipeType.SHAPELESS, 0, 0, null, null, Map.of());
        Set<ItemId> logs = Set.of(LOG, birch);
        PlanResult bootstrap = simpleSuccessfulPlan(axe, 2, List.of(gatherStep(LOG, 3), craft, stationPlacementStep(StationId.parse("minecraft:crafting_table"))));
        assertEquals(1000, HarvestInvestment.adjustedBenefitForGoalStock(bootstrap, logs, 1000, 80));
        PlanResult afterGather = simpleSuccessfulPlan(axe, 2, List.of(craft));
        assertEquals(760, HarvestInvestment.adjustedBenefitForGoalStock(afterGather, logs, 1000, 80));
        PlanResult mixedLogs = simpleSuccessfulPlan(axe, 2, List.of(gatherStep(birch, 2), craft));
        assertEquals(920, HarvestInvestment.adjustedBenefitForGoalStock(mixedLogs, logs, 1000, 80));
        assertEquals(0, HarvestInvestment.adjustedBenefitForGoalStock(afterGather, logs, 200, 80));
        assertEquals(0, HarvestInvestment.adjustedBenefitForGoalStock(afterGather, logs, 1000, Long.MAX_VALUE));
        PlanResult incomplete = new PlanResult(axe, 2, List.of(craft),
                List.of(new BlockedReason(BlockedReason.Code.NO_SOURCE, axe, "missing", List.of(axe))), false, 0, 0);
        assertEquals(0, HarvestInvestment.adjustedBenefitForGoalStock(incomplete, logs, 1000, 80));
    }

    private static PlanResult simpleSuccessfulPlan(ItemId target, int count, List<PlanStep> steps) {
        return new PlanResult(target, count, steps, List.of(), false, 0, 0);
    }

    private static PlanStep gatherStep(ItemId output, int operations) {
        return new PlanStep(PlanKind.GATHER, "gather:" + output.path(), output, operations, operations,
                List.of(), List.of(BlockId.parse("test:local_block")), null, 0, 0, null, null, Map.of());
    }

    private static PlanStep stationPlacementStep(StationId station) {
        return new PlanStep(PlanKind.PLACE_STATION, "place:" + station, null, 0, 1,
                List.of(), List.of(), null, 0, 0, station, null, Map.of());
    }

    private static CatalogSnapshot woodToSticksCatalog() {
        return CatalogSnapshot.builder()
                .item(LOG, 0)
                .item(PLANKS, 0)
                .item(STICKS, 0)
                .source(new GatherSource("gather:oak_log", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .source(new CraftingSource("craft:oak_planks", PLANKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(LOG))), List.of()))
                .source(new CraftingSource("craft:sticks", STICKS, 4, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(2, PLANKS))), List.of()))
                .build();
    }
}
