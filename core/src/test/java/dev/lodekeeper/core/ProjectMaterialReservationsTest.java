package dev.lodekeeper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProjectMaterialReservationsTest {
    private static final ItemId LOG = ItemId.parse("minecraft:oak_log");
    private static final ItemId STICK = ItemId.parse("minecraft:stick");
    private static final ItemId COAL = ItemId.parse("minecraft:coal");
    private static final ItemId PICKAXE = ItemId.parse("minecraft:iron_pickaxe");
    private static final ItemId OUTPUT = ItemId.parse("test:output");
    private static final ProjectSpec PROJECT = new ProjectSpec("supplies", "Keep remaining project materials.",
            Map.of(OUTPUT, 1), ProjectSpec.Purpose.INVENTORY_GOALS);

    @Test
    void addsCraftingDemandToProtectedProjectGoalsAndLeavesSurplusFuelSpendable() {
        InventorySnapshot stock = new InventorySnapshot(Map.of(LOG, 12, COAL, 5), Set.of(), Map.of(), Map.of(LOG, 8));

        InventorySnapshot optional = protect(stock, step(item(LOG, 2, true)));

        assertEquals(10, optional.protectedCounts().get(LOG));
        assertEquals(2, optional.spendableCount(LOG));
        assertEquals(5, optional.spendableCount(COAL));
        assertEquals(8, stock.protectedCounts().get(LOG));
        assertEquals(4, stock.spendableCount(LOG));
    }

    @Test
    void keepsHeldSticksForAFutureToolRecipe() {
        InventorySnapshot stock = new InventorySnapshot(Map.of(STICK, 2, LOG, 10), Set.of(), Map.of(), Map.of(LOG, 8));

        InventorySnapshot optional = protect(stock, step(item(STICK, 2, true), item(LOG, 2, true)));

        assertEquals(0, optional.spendableCount(STICK));
        assertEquals(10, optional.protectedCounts().get(LOG));
    }

    @Test
    void reservesReusableRequirementsOnceAndAddsTheirLargestCountToConsumption() {
        InventorySnapshot stock = new InventorySnapshot(Map.of(STICK, 20, PICKAXE, 3));
        PlanStep first = step(item(STICK, 4, true), item(STICK, 2, false),
                new SelectedToolRequirement(PICKAXE, 5, "mine"));
        PlanStep second = step(item(STICK, 3, false), new SelectedToolRequirement(STICK, 0, "held item"),
                new SelectedToolRequirement(PICKAXE, 10, "mine again"));

        InventorySnapshot optional = protect(stock, first, second);

        assertEquals(7, optional.protectedCounts().get(STICK));
        assertEquals(13, optional.spendableCount(STICK));
        assertEquals(1, optional.protectedCounts().get(PICKAXE));
        assertEquals(2, optional.spendableCount(PICKAXE));
    }

    @Test
    void clampsDemandToPhysicalStockWithoutIntegerOverflowOrForecastItems() {
        InventorySnapshot stock = new InventorySnapshot(Map.of(LOG, 1_000_000_000), Set.of(), Map.of(),
                Map.of(LOG, 999_999_999));

        InventorySnapshot optional = protect(stock,
                step(item(LOG, Integer.MAX_VALUE, true), item(STICK, Integer.MAX_VALUE, true)),
                step(item(LOG, Integer.MAX_VALUE, true), item(LOG, Integer.MAX_VALUE, false)));

        assertEquals(1_000_000_000, optional.protectedCounts().get(LOG));
        assertEquals(0, optional.spendableCount(LOG));
        assertEquals(Map.of(LOG, 1_000_000_000), optional.counts());
        assertEquals(Map.of(LOG, 1_000_000_000), optional.protectedCounts());
    }

    @Test
    void retainsStationAndPhysicalToolLotInformation() {
        StationId furnace = StationId.parse("minecraft:furnace");
        InventorySnapshot stock = new InventorySnapshot(Map.of(PICKAXE, 2, LOG, 10), Set.of(furnace),
                Map.of(), Map.of(LOG, 8), Map.of(),
                Map.of(PICKAXE, List.of(new InventoryToolLot(7, false), new InventoryToolLot(90, true))));

        InventorySnapshot optional = protect(stock, step(new SelectedToolRequirement(PICKAXE, 5, "mine")));

        assertEquals(stock.counts(), optional.counts());
        assertEquals(Set.of(furnace), optional.availableStations());
        assertEquals(Map.of(PICKAXE, 90), optional.remainingDurability());
        assertEquals(Map.of(PICKAXE, List.of(7, 90)), optional.durabilityLots());
        assertEquals(Map.of(PICKAXE, List.of(new InventoryToolLot(7, false), new InventoryToolLot(90, true))),
                optional.toolLots());
        assertEquals(Map.of(LOG, 8, PICKAXE, 1), optional.protectedCounts());
    }

    @Test
    void rebuildsReservationsFromTheLatestRemainingPlan() {
        InventorySnapshot stock = new InventorySnapshot(Map.of(LOG, 12), Set.of(), Map.of(), Map.of(LOG, 8));

        assertEquals(10, protect(stock, step(item(LOG, 2, true))).protectedCounts().get(LOG));
        assertEquals(8, protect(stock).protectedCounts().get(LOG));
        assertEquals(4, protect(stock).spendableCount(LOG));
    }

    @Test
    void rejectsPartialFailedPlansAndOversizedPlans() {
        InventorySnapshot stock = new InventorySnapshot(Map.of(LOG, 12));
        ProjectPlanResult failed = new ProjectPlanResult(PROJECT, List.of(step(item(LOG, 2, true))),
                List.of(new BlockedReason(BlockedReason.Code.NO_SOURCE, OUTPUT, "Cannot finish project", List.of())),
                false, 0, 0);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ProjectMaterialReservations.protectOptionalWork(stock, failed));
        assertTrue(failure.getMessage().contains("failed project plan"));
        ProjectPlanResult excessive = new ProjectPlanResult(PROJECT,
                Collections.nCopies(100_001, step(item(LOG, 1, true))), List.of(), false, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> ProjectMaterialReservations.protectOptionalWork(stock, excessive));
    }

    private static InventorySnapshot protect(InventorySnapshot stock, PlanStep... steps) {
        return ProjectMaterialReservations.protectOptionalWork(stock,
                new ProjectPlanResult(PROJECT, List.of(steps), List.of(), false, 0, 0));
    }

    private static SelectedItemRequirement item(ItemId item, int count, boolean consumed) {
        return new SelectedItemRequirement(item, count, consumed, "remaining project", -1);
    }

    private static PlanStep step(SelectedRequirement... requirements) {
        return new PlanStep(PlanKind.CRAFT, "test:craft", OUTPUT, 1, 1, List.of(requirements), List.of(),
                RecipeType.SHAPELESS, 0, 0, null, null, Map.of());
    }
}
