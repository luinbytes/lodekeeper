package dev.lodekeeper.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
}
