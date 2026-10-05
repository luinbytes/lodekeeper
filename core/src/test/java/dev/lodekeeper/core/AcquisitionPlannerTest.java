package dev.lodekeeper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AcquisitionPlannerTest {
    private static final ItemId LOG = ItemId.parse("minecraft:oak_log");
    private static final ItemId PLANKS = ItemId.parse("minecraft:oak_planks");
    private static final ItemId STICKS = ItemId.parse("minecraft:stick");

    @Test
    void reservesInventoryAndPlansOnlyMissingQuantity() {
        CatalogSnapshot catalog = CatalogSnapshot.builder()
                .item(LOG, 0, "wood")
                .source(new GatherSource("gather:oak", LOG, 1, List.of(BlockId.parse("minecraft:oak_log"))))
                .build();

        PlanResult result = new AcquisitionPlanner().plan(catalog, new InventorySnapshot(Map.of(LOG, 60)), LOG, 64);

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

        PlanResult result = new AcquisitionPlanner().plan(catalog, new InventorySnapshot(Map.of(PLANKS, 4)), STICKS, 8);

        assertTrue(result.success());
        assertEquals(1, result.steps().size());
        PlanStep craft = result.steps().get(0);
        assertEquals(2, craft.operationCount());
        assertEquals(8, craft.outputCount());
        assertEquals(2, craft.requirements().size());
        assertEquals(4, craft.requirements().stream().map(SelectedItemRequirement.class::cast).mapToInt(SelectedItemRequirement::count).sum());
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

        PlanResult result = new AcquisitionPlanner().plan(catalog, inventory, iron, 3);

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

        PlanResult result = new AcquisitionPlanner().plan(catalog, new InventorySnapshot(Map.of()), a, 1);

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

        PlanResult result = new AcquisitionPlanner().plan(builder.build(), new InventorySnapshot(mixedInventory), table, 1);

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
    void projectCatalogIsStableAndProvidersCannotSilentlyOverride() {
        ProjectCatalog catalog = ProjectCatalog.standard();
        assertEquals(List.of("expedition", "farming_supplies", "gear_iron", "gear_stone", "mining_trip", "shelter_supplies"), catalog.names());
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

        PlanResult protectedResult = new AcquisitionPlanner().plan(catalog, protectedLog, STICKS, 4);
        assertTrue(protectedResult.success());
        assertTrue(protectedResult.steps().stream().anyMatch(step -> step.kind() == PlanKind.GATHER && step.output().equals(LOG)));
        assertEquals(1, protectedLog.count(LOG));
        assertEquals(1, protectedLog.protectedCounts().get(LOG));

        InventorySnapshot oneArgument = new InventorySnapshot(Map.of(LOG, 1));
        InventorySnapshot legacyThreeArgument = new InventorySnapshot(Map.of(LOG, 1), Set.of(), Map.of());
        assertTrue(oneArgument.protectedCounts().isEmpty());
        assertTrue(legacyThreeArgument.protectedCounts().isEmpty());
        PlanResult legacyResult = new AcquisitionPlanner().plan(catalog, oneArgument, STICKS, 4);
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

        PlanResult materialPlan = new AcquisitionPlanner().plan(materialCatalog, partialProtected, widget, 1);
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
        PlanResult repeatedPlan = new AcquisitionPlanner().plan(repeatedConsumption, twoConsumptionSnapshot, widget, 1);
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

        PlanResult miningPlan = new AcquisitionPlanner().plan(miningCatalog, protectedPickaxe, ore, 1);
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

        PlanResult result = new AcquisitionPlanner().plan(builder.build(),
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

        PlanResult result = new AcquisitionPlanner().plan(builder.build(), new InventorySnapshot(Map.of()), target, 1,
                new PlannerLimits(48, 99, 25, 64, 4_096, 1_000_000));

        assertFalse(result.success());
        assertTrue(result.blockedReasons().size() <= 32);
        assertEquals(BlockedReason.Code.NODE_LIMIT, result.blockedReasons().get(0).code());
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
