package dev.lodekeeper.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherCandidatesTest {
    private static final ItemId DIAMOND = ItemId.parse("minecraft:diamond");
    private static final ItemId PICK = ItemId.parse("minecraft:iron_pickaxe");
    private static final BlockId NORMAL = BlockId.parse("minecraft:diamond_ore");
    private static final BlockId DEEP = BlockId.parse("minecraft:deepslate_diamond_ore");
    private static final GatherSource ORIGINAL = source("test:ordinary", NORMAL, 1, 1, Map.of());

    @Test
    void aBulkDiamondPlanCanMineOrdinaryAndDeepslateOreWithTheSameQuantity() {
        CatalogSnapshot catalog = catalog(ORIGINAL, source("test:deep", DEEP, 1, 1, Map.of()));
        PlanStep step = plan(catalog);

        assertEquals(Set.of(NORMAL, DEEP), Set.copyOf(GatherCandidates.forStep(catalog, step, Set.of())));
        assertEquals(32, step.outputCount());
        assertEquals(32, step.operationCount());
    }

    @Test
    void excludesSourcesThatConsumeMoreToolWearThanThePlanReserves() {
        CatalogSnapshot catalog = catalog(ORIGINAL, source("test:deep", DEEP, 1, 2, Map.of()));
        assertEquals(List.of(NORMAL), GatherCandidates.forStep(catalog, plan(catalog), Set.of()));
    }

    @Test
    void excludesDifferentDropYieldsAndHarvestAttributes() {
        CatalogSnapshot catalog = catalog(ORIGINAL, source("test:deep", DEEP, 2, 1, Map.of()),
                source("test:silk", BlockId.parse("test:silk_ore"), 1, 1, Map.of("silkTouchCompatible", "true")));
        assertEquals(List.of(NORMAL), GatherCandidates.forStep(catalog, plan(catalog), Set.of()));
    }

    @Test
    void honorsSourcesRejectedByTheActiveRequest() {
        CatalogSnapshot catalog = catalog(ORIGINAL, source("test:deep", DEEP, 1, 1, Map.of()));
        assertEquals(List.of(NORMAL), GatherCandidates.forStep(catalog, plan(catalog), Set.of("test:deep")));
    }

    @Test
    void acceptsCustomBlocksWithTheSameRegisteredHarvestContract() {
        BlockId custom = BlockId.parse("example:diamond_bearing_rock");
        CatalogSnapshot catalog = catalog(ORIGINAL, source("example:harvest", custom, 1, 1, Map.of()));
        assertEquals(Set.of(NORMAL, custom), Set.copyOf(GatherCandidates.forStep(catalog, plan(catalog), Set.of())));
    }

    @Test
    void boundsLargeCustomOreFamilies() {
        CatalogSnapshot.Builder builder = builder().source(ORIGINAL);
        for (int i = 0; i < 300; i++) builder.source(source("test:ore_" + i, BlockId.parse("test:ore_" + i), 1, 1, Map.of()));
        CatalogSnapshot catalog = builder.build();
        List<BlockId> blocks = GatherCandidates.forStep(catalog, plan(catalog), Set.of());
        assertTrue(blocks.contains(NORMAL));
        assertEquals(256, blocks.size());
    }

    private static GatherSource source(String id, BlockId block, int count, int wear, Map<String, String> attributes) {
        return new GatherSource(id, DIAMOND, count, List.of(block),
                List.of(new ToolRequirement(Ingredient.of(PICK), wear + 1, "harvest " + block, wear)), attributes);
    }

    private static CatalogSnapshot.Builder builder() {
        return CatalogSnapshot.builder().item(DIAMOND, 0).item(PICK, 250);
    }

    private static CatalogSnapshot catalog(GatherSource... sources) {
        CatalogSnapshot.Builder builder = builder();
        for (GatherSource source : sources) builder.source(source);
        return builder.build();
    }

    private static PlanStep plan(CatalogSnapshot catalog) {
        InventorySnapshot inventory = new InventorySnapshot(Map.of(PICK, 1), Set.of(), Map.of(), Map.of(), Map.of(),
                Map.of(PICK, List.of(new InventoryToolLot(100, false))));
        PlanResult result = new AcquisitionPlanner(() -> 0L).planFast(
                catalog.withOutputSources(DIAMOND, List.of(ORIGINAL)), inventory, DIAMOND, 32);
        assertTrue(result.success(), result.blockedReasons().toString());
        assertEquals(1, result.steps().size());
        return result.steps().get(0);
    }
}
