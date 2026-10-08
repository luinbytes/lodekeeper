package dev.lodekeeper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProjectGatherBatchTest {
    private static final ItemId MATERIAL = ItemId.parse("test:material");
    private static final ItemId PICKAXE = ItemId.parse("test:pickaxe");
    private static final BlockId BLOCK = BlockId.parse("test:block");
    private static final TagId TOOLS = TagId.parse("test:tools");
    private static final AcquisitionPlanner PLANNER = new AcquisitionPlanner(() -> 0L);

    @Test
    void gathersSharedDemandTogetherAndAddsItToActualHeldStock() {
        GatherSource source = new GatherSource("test:gather", MATERIAL, 1, List.of(BLOCK));
        CatalogSnapshot catalog = catalog(source);
        InventorySnapshot inventory = new InventorySnapshot(Map.of(MATERIAL, 4));
        PlanStep first = gather(catalog, inventory, 6);
        PlanStep future = gather(catalog, new InventorySnapshot(Map.of()), 7);

        PlanStep batch = batch(catalog, inventory, List.of(first, future));

        assertEquals(9, batch.outputCount());
        assertEquals(9, batch.operationCount());
        assertEquals("test:gather", batch.sourceId());
    }

    @Test
    void validatesTaggedToolsUsingCurrentPhysicalWear() {
        GatherSource source = toolSource();
        CatalogSnapshot catalog = catalog(source);
        InventorySnapshot inventory = tools(12, false);
        PlanStep first = gather(catalog, inventory, 3);
        PlanStep future = gather(catalog, inventory, 6);

        PlanStep batch = batch(catalog, inventory, List.of(first, future));

        assertEquals(9, batch.operationCount());
        assertEquals(PICKAXE, ((SelectedToolRequirement) batch.requirements().get(0)).item());
    }

    @Test
    void doesNotSpendWearFromAToolThatOnlyExistsLaterInTheForecast() {
        CatalogSnapshot catalog = catalog(toolSource());
        InventorySnapshot actual = tools(4, false);
        PlanStep first = gather(catalog, actual, 2);
        PlanStep future = gather(catalog, tools(100, false), 8);

        assertSame(first, batch(catalog, actual, List.of(first, future)));
    }

    @Test
    void keepsMaterialForThePlannedToolUpgradeBeforeLaterBulkMining() {
        CatalogSnapshot catalog = catalog(toolSource());
        InventorySnapshot inventory = tools(100, false);
        PlanStep first = gather(catalog, inventory, 3);
        PlanStep later = gather(catalog, inventory, 32);
        PlanStep upgraded = new PlanStep(later.kind(), later.sourceId(), later.output(),
                later.outputCount(), later.operationCount(),
                List.of(new SelectedToolRequirement(ItemId.parse("test:better_pickaxe"), 2, "mine material")),
                later.candidateBlocks(), later.recipeType(), later.recipeWidth(), later.recipeHeight(),
                later.station(), later.customType(), later.attributes(), later.nativeWork());

        assertSame(first, batch(catalog, inventory, List.of(first, upgraded)));
    }

    @Test
    void excludesDifferentSourcesEvenWhenTheirOutputMatches() {
        GatherSource firstSource = new GatherSource("test:first", MATERIAL, 1, List.of(BLOCK));
        GatherSource otherSource = new GatherSource("test:other", MATERIAL, 1,
                List.of(BlockId.parse("test:other_block")));
        CatalogSnapshot catalog = catalog(firstSource, otherSource);
        InventorySnapshot inventory = new InventorySnapshot(Map.of());
        PlanStep first = gather(catalog(firstSource), inventory, 2);
        PlanStep future = gather(catalog(otherSource), inventory, 8);

        assertSame(first, batch(catalog, inventory, List.of(first, future)));
    }

    @Test
    void rejectsSilkTouchLotsForADropThatRequiresOrdinaryMining() {
        CatalogSnapshot catalog = catalog(toolSource());
        PlanStep first = gather(catalog, tools(100, false), 2);
        PlanStep future = gather(catalog, tools(100, false), 8);

        assertSame(first, batch(catalog, tools(100, true), List.of(first, future)));
    }

    @Test
    void preservesTheSmallerGatherWhenOnlyOneStackCanFit() {
        CatalogSnapshot catalog = catalog(new GatherSource("test:gather", MATERIAL, 1, List.of(BLOCK)));
        InventorySnapshot inventory = new InventorySnapshot(Map.of());
        PlanStep first = gather(catalog, inventory, 40);
        PlanStep future = gather(catalog, inventory, 40);

        assertSame(first, ProjectGatherBatch.firstStep(PLANNER, catalog, inventory,
                List.of(first, future), PlannerLimits.DEFAULT, PlanningPreferences.NONE, 64));
    }

    @Test
    void returnsTheOriginalGatherWhenOptionalBatchWorkExpires() {
        CatalogSnapshot catalog = catalog(new GatherSource("test:gather", MATERIAL, 1, List.of(BLOCK)));
        InventorySnapshot inventory = new InventorySnapshot(Map.of());
        PlanStep first = gather(catalog, inventory, 2);
        PlanStep future = gather(catalog, inventory, 8);
        var reads = new java.util.concurrent.atomic.AtomicInteger();

        assertSame(first, ProjectGatherBatch.firstStep(PLANNER, catalog, inventory,
                List.of(first, future), PlannerLimits.DEFAULT, PlanningPreferences.NONE, 64,
                () -> reads.getAndIncrement() == 0 ? 0L : 20_000_000L));
    }

    @Test
    void collectsCraftingMaterialAndEquivalentFuelTogetherBeforeLaterMining() {
        ItemId other = ItemId.parse("test:other_material");
        ItemId crafted = ItemId.parse("test:a_crafted");
        ItemId raw = ItemId.parse("test:raw");
        ItemId smelted = ItemId.parse("test:z_smelted");
        CatalogSnapshot catalog = supplyCatalog(other, crafted, raw, smelted, 200);
        InventorySnapshot inventory = new InventorySnapshot(Map.of(raw, 1));
        ProjectSpec project = supplyProject(crafted, smelted);
        PlanningPreferences preferences = new PlanningPreferences(Map.of("test:gather", 100, "test:other", 0));
        ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project, PlannerLimits.DEFAULT, preferences);
        assertTrue(original.success(), original.blockedReasons().toString());
        assertTrue(original.steps().stream().anyMatch(step -> step.sourceId().equals("test:other")));

        ProjectPlanResult consolidated = ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog,
                inventory, original, PlannerLimits.DEFAULT, preferences, Set.of(MATERIAL, other));
        PlanStep first = ProjectGatherBatch.firstStep(PLANNER, catalog, inventory, consolidated.steps(),
                PlannerLimits.DEFAULT, preferences, 64);

        assertTrue(consolidated.success());
        assertEquals(3, first.outputCount());
        assertTrue(consolidated.steps().stream().noneMatch(step -> step.sourceId().equals("test:other")));
        assertEquals(1, consolidated.steps().stream().filter(step -> step.kind() == PlanKind.SMELT).count());
    }

    @Test
    void keepsSpeciesSpecificProjectTargetsAndDifferentFuelEfficiencies() {
        ItemId other = ItemId.parse("test:other_material");
        ItemId crafted = ItemId.parse("test:a_crafted");
        ItemId raw = ItemId.parse("test:raw");
        ItemId smelted = ItemId.parse("test:z_smelted");
        InventorySnapshot inventory = new InventorySnapshot(Map.of(raw, 5));
        CatalogSnapshot catalog = supplyCatalog(other, crafted, raw, smelted, 1_000);
        PlanningPreferences preferences = new PlanningPreferences(Map.of("test:gather", 100, "test:other", 0));
        ProjectSpec project = new ProjectSpec("supplies", "Preserve fuel efficiency.",
                Map.of(crafted, 1, smelted, 5), ProjectSpec.Purpose.INVENTORY_GOALS);
        ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project, PlannerLimits.DEFAULT, preferences);
        assertTrue(original.success());
        assertSame(original, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                original, PlannerLimits.DEFAULT, preferences, Set.of(MATERIAL, other)));

        ProjectSpec exact = new ProjectSpec("specific", "Retain both distinct material targets.",
                Map.of(MATERIAL, 2, other, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
        ProjectPlanResult exactPlan = PLANNER.planProjectFast(catalog, inventory, exact,
                PlannerLimits.DEFAULT, preferences);
        assertTrue(exactPlan.success());
        assertSame(exactPlan, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                exactPlan, PlannerLimits.DEFAULT, preferences, Set.of(MATERIAL, other)));
    }

    @Test
    void countsNonfamilyFuelGathersAndKeepsAFullCatalogViewUsable() {
        ItemId other = ItemId.parse("test:other_material");
        ItemId crafted = ItemId.parse("test:a_crafted");
        ItemId raw = ItemId.parse("test:raw");
        ItemId smelted = ItemId.parse("test:z_smelted");
        ItemId coal = ItemId.parse("test:coal");
        CatalogSnapshot base = supplyCatalog(other, crafted, raw, smelted, 1_000);
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        base.itemDefinitions().values().forEach(builder::item);
        for (ItemId item : List.of(MATERIAL, other, crafted)) base.sourcesFor(item).forEach(builder::source);
        builder.item(coal, 0).source(new GatherSource("test:coal", coal, 1, List.of(BlockId.parse("test:coal_block"))))
                .source(new SmeltingSource("test:smelt", smelted, 1, Ingredient.of(raw),
                        List.of(ItemSelector.item(MATERIAL), ItemSelector.item(other), ItemSelector.item(coal)),
                        200, List.of(), Map.of(MATERIAL, 200L, other, 1_000L, coal, 200L)));
        for (int index = 0; index < 64; index++) {
            ItemId item = ItemId.parse("test:extra_" + index);
            builder.item(item, 0)
                    .source(new GatherSource("test:extra_a_" + index, item, 1, List.of(BLOCK)))
                    .source(new GatherSource("test:extra_b_" + index, item, 1, List.of(BLOCK)));
        }
        CatalogSnapshot catalog = builder.build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(raw, 2));
        ProjectSpec project = new ProjectSpec("supplies", "Do not add a coal excursion.",
                Map.of(crafted, 1, smelted, 2), ProjectSpec.Purpose.INVENTORY_GOALS);
        PlanningPreferences preferences = new PlanningPreferences(Map.of("test:gather", 100, "test:other", 0, "test:coal", 1));
        ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project, PlannerLimits.DEFAULT, preferences);
        assertTrue(original.success());
        assertTrue(original.steps().stream().anyMatch(step -> step.sourceId().equals("test:other")));
        assertSame(original, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                original, PlannerLimits.DEFAULT, preferences, Set.of(MATERIAL, other)));

        for (int index = 0; index < 64; index++)
            catalog = catalog.withOutputSources(ItemId.parse("test:extra_" + index), Set.of("test:extra_a_" + index));
        ProjectPlanResult viewPlan = PLANNER.planProjectFast(catalog, inventory, project, PlannerLimits.DEFAULT, preferences);
        assertTrue(viewPlan.success());
        assertSame(viewPlan, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                viewPlan, PlannerLimits.DEFAULT, preferences, Set.of(MATERIAL, other)));
    }

    @Test
    void promotesRequiredWoodBeforeOreWithoutChangingItsQuantitySpeciesOrTool() {
        ItemId ore = ItemId.parse("test:diamond");
        ItemId crafted = ItemId.parse("test:a_crafted");
        ItemId other = ItemId.parse("test:other_material");
        GatherSource wood = new GatherSource("test:birch", MATERIAL, 2, List.of(BLOCK),
                List.of(new ToolRequirement(Ingredient.tag(TOOLS), 2, "cut birch", 1)),
                Map.of("species", "birch"));
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(MATERIAL, 0).item(PICKAXE, 100)
                .tag(TOOLS, List.of(PICKAXE)).source(wood)
                .source(new GatherSource("test:oak", other, 1, List.of(BlockId.parse("test:oak"))))
                .source(new GatherSource("test:ore", ore, 1, List.of(BlockId.parse("test:ore"))))
                .source(new CraftingSource("test:craft", crafted, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(2, ore))), List.of())).build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(MATERIAL, 4, PICKAXE, 1), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of(PICKAXE, List.of(new InventoryToolLot(12, false))));
        ProjectSpec project = new ProjectSpec("wood", "Finish with six birch materials.",
                Map.of(crafted, 1, MATERIAL, 6), ProjectSpec.Purpose.INVENTORY_GOALS);
        ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);
        assertTrue(original.success(), original.blockedReasons().toString());
        assertEquals(List.of("test:ore", "test:craft", "test:birch"),
                original.steps().stream().map(PlanStep::sourceId).toList());

        ProjectPlanResult promoted = ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                original, PlannerLimits.DEFAULT, PlanningPreferences.NONE, Set.of(MATERIAL, other));

        assertEquals(List.of("test:birch", "test:ore", "test:craft"),
                promoted.steps().stream().map(PlanStep::sourceId).toList());
        PlanStep first = promoted.steps().get(0);
        assertEquals(MATERIAL, first.output());
        assertEquals(2, first.outputCount());
        assertEquals(1, first.operationCount());
        assertEquals(Map.of("species", "birch"), first.attributes());
        assertEquals(List.of(new SelectedToolRequirement(PICKAXE, 2, "cut birch")), first.requirements());
        assertSame(original.steps().get(2), first);
        assertSame(project, promoted.project());
    }

    @Test
    void leavesPlansWithoutWoodDemandOrWithALeadingCraftUnchanged() {
        ItemId ore = ItemId.parse("test:diamond");
        ItemId crafted = ItemId.parse("test:a_crafted");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(MATERIAL, 0)
                .source(new GatherSource("test:wood", MATERIAL, 1, List.of(BLOCK)))
                .source(new GatherSource("test:ore", ore, 1, List.of(BLOCK)))
                .source(new CraftingSource("test:craft", crafted, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(ore))), List.of())).build();
        InventorySnapshot inventory = new InventorySnapshot(Map.of(MATERIAL, 4, ore, 1));
        for (Map<ItemId, Integer> goals : List.of(Map.of(ore, 8), Map.of(crafted, 1, MATERIAL, 6))) {
            ProjectSpec project = new ProjectSpec("supplies", "Preserve this starting action.",
                    goals, ProjectSpec.Purpose.INVENTORY_GOALS);
            ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project,
                    PlannerLimits.DEFAULT, PlanningPreferences.NONE);
            assertTrue(original.success(), original.blockedReasons().toString());

            assertSame(original, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                    original, PlannerLimits.DEFAULT, PlanningPreferences.NONE, Set.of(MATERIAL)));
        }
    }

    @Test
    void declinesWoodWithConsumableOrStationPrerequisitesEvenWhenTheyAreHeld() {
        ItemId ore = ItemId.parse("test:diamond");
        StationId station = StationId.parse("test:workbench");
        InventorySnapshot inventory = new InventorySnapshot(Map.of(PICKAXE, 3), Set.of(station), Map.of());
        for (Requirement prerequisite : List.of(new ItemRequirement(Ingredient.of(PICKAXE), true, "supplies"),
                new StationRequirement(station, PICKAXE, "workbench"))) {
            CatalogSnapshot catalog = catalog(new GatherSource("test:wood", MATERIAL, 1, List.of(BLOCK),
                            List.of(prerequisite)),
                    new GatherSource("test:ore", ore, 1, List.of(BLOCK)));
            ProjectSpec project = new ProjectSpec("supplies", "Gather ore and wood.",
                    Map.of(ore, 1, MATERIAL, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
            ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project,
                    PlannerLimits.DEFAULT, PlanningPreferences.NONE);
            assertTrue(original.success(), original.blockedReasons().toString());
            assertEquals("test:ore", original.steps().get(0).sourceId());

            assertSame(original, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                    original, PlannerLimits.DEFAULT, PlanningPreferences.NONE, Set.of(MATERIAL)));
        }
    }

    @Test
    void declinesTheEarliestWoodWhenItsToolIsMissingWornOrUnsuitable() {
        ItemId ore = ItemId.parse("test:diamond");
        ItemId other = ItemId.parse("test:other_material");
        CatalogSnapshot catalog = catalog(toolSource(), new GatherSource("test:ore", ore, 1, List.of(BLOCK)),
                new GatherSource("test:other", other, 1, List.of(BLOCK)));
        ProjectSpec project = new ProjectSpec("supplies", "Retain forecast tool dependencies.",
                Map.of(ore, 1, MATERIAL, 3, other, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
        ProjectPlanResult original = PLANNER.planProjectFast(catalog, tools(100, false), project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);
        assertTrue(original.success(), original.blockedReasons().toString());
        assertEquals(List.of("test:ore", "test:gather", "test:other"),
                original.steps().stream().map(PlanStep::sourceId).toList());

        for (InventorySnapshot actual : List.of(new InventorySnapshot(Map.of()), tools(2, false), tools(100, true)))
            assertSame(original, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, actual,
                    original, PlannerLimits.DEFAULT, PlanningPreferences.NONE, Set.of(MATERIAL, other)));
    }

    @Test
    void doesNotSubstituteAnotherHeldToolForTheSelectedWoodTool() {
        ItemId ore = ItemId.parse("test:diamond");
        ItemId otherTool = ItemId.parse("test:other_pickaxe");
        CatalogSnapshot catalog = CatalogSnapshot.builder().item(MATERIAL, 0).item(PICKAXE, 100)
                .item(otherTool, 100).tag(TOOLS, List.of(PICKAXE, otherTool)).source(toolSource())
                .source(new GatherSource("test:ore", ore, 1, List.of(BLOCK))).build();
        ProjectSpec project = new ProjectSpec("supplies", "Preserve the selected tool.",
                Map.of(ore, 1, MATERIAL, 3), ProjectSpec.Purpose.INVENTORY_GOALS);
        ProjectPlanResult original = PLANNER.planProjectFast(catalog, tools(100, false), project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);
        assertTrue(original.success(), original.blockedReasons().toString());
        InventorySnapshot actual = new InventorySnapshot(Map.of(otherTool, 1), Set.of(), Map.of(), Map.of(),
                Map.of(), Map.of(otherTool, List.of(new InventoryToolLot(100, false))));

        assertSame(original, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, actual,
                original, PlannerLimits.DEFAULT, PlanningPreferences.NONE, Set.of(MATERIAL)));
    }

    @Test
    void preservesThePlanWhenMaterialProofExhaustsNodeOrCountBounds() {
        ItemId ore = ItemId.parse("test:diamond");
        CatalogSnapshot catalog = catalog(toolSource(), new GatherSource("test:ore", ore, 1, List.of(BLOCK)));
        InventorySnapshot inventory = tools(100, false);
        ProjectSpec project = new ProjectSpec("supplies", "Keep bounded optional planning.",
                Map.of(ore, 1, MATERIAL, 3), ProjectSpec.Purpose.INVENTORY_GOALS);
        ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE);
        assertTrue(original.success(), original.blockedReasons().toString());

        for (PlannerLimits limits : List.of(new PlannerLimits(48, 1, 20, 12, 4_096, 1_000_000),
                new PlannerLimits(48, 8_000, 20, 12, 4_096, 2)))
            assertSame(original, ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                    original, limits, PlanningPreferences.NONE, Set.of(MATERIAL)));
    }

    @Test
    void batchesOnlyBeforeADifferentGatherSourceOutputAttributesOrToolRequirement() {
        CatalogSnapshot catalog = catalog(toolSource());
        InventorySnapshot inventory = tools(100, false);
        PlanStep first = gather(catalog, inventory, 2);
        PlanStep matching = gather(catalog, inventory, 3);
        PlanStep later = gather(catalog, inventory, 32);
        List<PlanStep> barriers = List.of(
                new PlanStep(first.kind(), "test:wood", first.output(), 1, 1, first.requirements(),
                        first.candidateBlocks(), null, 0, 0, null, null, first.attributes(), first.nativeWork()),
                new PlanStep(first.kind(), first.sourceId(), ItemId.parse("test:other_material"), 1, 1,
                        first.requirements(), first.candidateBlocks(), null, 0, 0, null, null, first.attributes(), first.nativeWork()),
                new PlanStep(first.kind(), first.sourceId(), first.output(), 1, 1, first.requirements(),
                        first.candidateBlocks(), null, 0, 0, null, null, Map.of("species", "other"), first.nativeWork()),
                new PlanStep(first.kind(), first.sourceId(), first.output(), 1, 1,
                        List.of(new SelectedToolRequirement(PICKAXE, 3, "mine material")),
                        first.candidateBlocks(), null, 0, 0, null, null, first.attributes(), first.nativeWork()));

        for (PlanStep barrier : barriers) {
            PlanStep batch = batch(catalog, inventory, List.of(first, matching, barrier, later));

            assertEquals(5, batch.outputCount());
            assertEquals(5, batch.operationCount());
            assertEquals("test:gather", batch.sourceId());
        }
    }

    @Test
    void restoresWoodPriorityAfterSpeciesReplanningAndKeepsItWhenConsolidationFails() {
        ItemId ore = ItemId.parse("test:0_ore");
        ItemId other = ItemId.parse("test:other_material");
        ItemId crafted = ItemId.parse("test:a_crafted");
        ItemId raw = ItemId.parse("test:raw");
        ItemId smelted = ItemId.parse("test:z_smelted");
        PlanningPreferences preferences = new PlanningPreferences(Map.of("test:gather", 100, "test:other", 0));
        for (long fuelTicks : List.of(200L, 1_000L)) {
            CatalogSnapshot base = supplyCatalog(other, crafted, raw, smelted, fuelTicks);
            CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
            base.itemDefinitions().values().forEach(builder::item);
            for (ItemId item : List.of(MATERIAL, other, crafted, smelted))
                base.sourcesFor(item).forEach(builder::source);
            CatalogSnapshot catalog = builder.source(new GatherSource("test:ore", ore, 1, List.of(BLOCK))).build();
            int smeltCount = fuelTicks == 200L ? 1 : 5;
            InventorySnapshot inventory = new InventorySnapshot(Map.of(raw, smeltCount));
            ProjectSpec project = new ProjectSpec("supplies", "Required wood precedes ore.",
                    Map.of(ore, 1, crafted, 1, smelted, smeltCount), ProjectSpec.Purpose.INVENTORY_GOALS);
            ProjectPlanResult original = PLANNER.planProjectFast(catalog, inventory, project,
                    PlannerLimits.DEFAULT, preferences);
            assertTrue(original.success(), original.blockedReasons().toString());
            assertEquals("test:ore", original.steps().get(0).sourceId());
            assertEquals(1, original.steps().stream().filter(step -> step.sourceId().equals("test:other")).count());

            ProjectPlanResult consolidated = ProjectGatherBatch.consolidateInitialMaterial(PLANNER, catalog, inventory,
                    original, PlannerLimits.DEFAULT, preferences, Set.of(MATERIAL, other));

            assertEquals("test:gather", consolidated.steps().get(0).sourceId());
            assertEquals(2, consolidated.steps().get(0).outputCount());
            assertEquals(fuelTicks == 200L ? 0 : 1,
                    consolidated.steps().stream().filter(step -> step.sourceId().equals("test:other")).count());
            assertEquals("test:ore", consolidated.steps().get(1).sourceId());
            assertEquals(2, ProjectGatherBatch.firstStep(PLANNER, catalog, inventory, consolidated.steps(),
                    PlannerLimits.DEFAULT, preferences, 64).outputCount());
        }
    }

    private static CatalogSnapshot supplyCatalog(ItemId other, ItemId crafted, ItemId raw,
                                                 ItemId smelted, long otherFuelTicks) {
        return CatalogSnapshot.builder().item(MATERIAL, 0).item(other, 0).item(crafted, 0).item(raw, 0).item(smelted, 0)
                .source(new GatherSource("test:gather", MATERIAL, 1, List.of(BLOCK)))
                .source(new GatherSource("test:other", other, 1, List.of(BlockId.parse("test:other_block"))))
                .source(new CraftingSource("test:craft", crafted, 1, RecipeType.SHAPELESS, 0, 0,
                        List.of(new RecipeSlot(-1, Ingredient.of(2, MATERIAL))), List.of()))
                .source(new SmeltingSource("test:smelt", smelted, 1, Ingredient.of(raw),
                        List.of(ItemSelector.item(MATERIAL), ItemSelector.item(other)), 200, List.of(),
                        Map.of(MATERIAL, 200L, other, otherFuelTicks))).build();
    }

    private static ProjectSpec supplyProject(ItemId crafted, ItemId smelted) {
        return new ProjectSpec("supplies", "Crafting and fuel share a portable material family.",
                Map.of(crafted, 1, smelted, 1), ProjectSpec.Purpose.INVENTORY_GOALS);
    }

    private static GatherSource toolSource() {
        return new GatherSource("test:gather", MATERIAL, 1, List.of(BLOCK),
                List.of(new ToolRequirement(Ingredient.tag(TOOLS), 2, "mine material", 1)));
    }

    private static CatalogSnapshot catalog(GatherSource... sources) {
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder().item(MATERIAL, 0).item(PICKAXE, 100)
                .tag(TOOLS, List.of(PICKAXE));
        for (GatherSource source : sources) builder.source(source);
        return builder.build();
    }

    private static InventorySnapshot tools(int durability, boolean silkTouch) {
        return new InventorySnapshot(Map.of(PICKAXE, 1), Set.of(), Map.of(), Map.of(), Map.of(),
                Map.of(PICKAXE, List.of(new InventoryToolLot(durability, silkTouch))));
    }

    private static PlanStep gather(CatalogSnapshot catalog, InventorySnapshot inventory, int target) {
        PlanResult plan = PLANNER.planFast(catalog, inventory, MATERIAL, target);
        assertTrue(plan.success(), plan.blockedReasons().toString());
        assertEquals(1, plan.steps().size());
        return plan.steps().get(0);
    }

    private static PlanStep batch(CatalogSnapshot catalog, InventorySnapshot inventory, List<PlanStep> steps) {
        return ProjectGatherBatch.firstStep(PLANNER, catalog, inventory, steps,
                PlannerLimits.DEFAULT, PlanningPreferences.NONE, 10_000, () -> 0L);
    }
}
