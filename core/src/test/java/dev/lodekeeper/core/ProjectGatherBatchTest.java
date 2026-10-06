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
                later.station(), later.customType(), later.attributes());

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
