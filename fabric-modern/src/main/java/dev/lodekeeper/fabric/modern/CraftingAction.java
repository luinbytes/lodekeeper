package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Places only planner-selected ingredients into an empty player or crafting-table grid. */
final class CraftingAction {
    private enum MovePurpose { OUTPUT, GRID_CONTENT }

    private record Placement(int menuSlot, Item item, String budgetKey) {}

    private final Minecraft client;
    private final PlayerActions actions;
    private final GameCatalog.RecipeWork recipe;
    private final ItemStack expectedOutput;
    private final int targetCount;
    private final PlanStep step;
    private final List<Placement> placements = new ArrayList<>();
    private final Map<String, Integer> remainingMaterials = new HashMap<>();
    private final Map<Integer, Set<Item>> expectedGridContents = new HashMap<>();
    private final Map<Integer, Set<Item>> expectedGridRemainders = new HashMap<>();
    private AbstractContainerMenu menu;
    private AbstractCraftingMenu craftingMenu;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private MovePurpose movePurpose;
    private int placementIndex, cooldown;
    private boolean initialized, awaitingResult, drainGridPending, drainRequested;

    CraftingAction(Minecraft client, PlayerActions actions, GameCatalog.RecipeWork recipe, PlanStep step) {
        this.client = client;
        this.actions = actions;
        this.recipe = recipe;
        this.step = step;
        expectedOutput = recipe.resultStack();
        targetCount = actions.count(expectedOutput.getItem()) + step.outputCount();
        step.requirements().stream().filter(SelectedItemRequirement.class::isInstance)
                .map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("recipe ingredient"))
                .forEach(requirement -> remainingMaterials.merge(key(requirement.recipeSlot(), GameCatalog.item(requirement.item())), requirement.count(), Integer::sum));
    }

    boolean tick() {
        if (client.player == null || client.gameMode == null) throw new IllegalStateException("No player");
        if (cooldown-- > 0) return false;
        if (!initialized) initialize();
        if (client.player.containerMenu != menu) throw new IllegalStateException("Crafting container closed or changed");
        if (transfer == null && !menu.getCarried().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");
        if (transfer != null) {
            if (transfer.tick()) {
                Placement placed = placements.get(placementIndex);
                remainingMaterials.compute(placed.budgetKey(), (ignored, count) -> count - 1);
                rememberOwnedGridContents(placed);
                transfer = null;
                placementIndex++;
            }
            return false;
        }
        if (quickMove != null) {
            if (quickMove.tick()) {
                MovePurpose completedPurpose = movePurpose;
                quickMove = null;
                movePurpose = null;
                if (completedPurpose == MovePurpose.OUTPUT) {
                    ItemStack remainingOutput = craftingMenu.getResultSlot().getItem();
                    if (!remainingOutput.isEmpty()) {
                        if (!same(remainingOutput, expectedOutput)) {
                            throw new IllegalStateException("Unexpected item remained in the crafting output slot; leaving the container open");
                        }
                        quickMove = new VerifiedQuickMove(client, menu, menu.slots.indexOf(craftingMenu.getResultSlot()),
                                expectedOutput.getItem(), "crafting output");
                        movePurpose = MovePurpose.OUTPUT;
                        return false;
                    }
                    awaitingResult = false;
                    placements.clear();
                    placementIndex = 0;
                    drainGridPending = true;
                }
            }
            return false;
        }
        if (awaitingResult) {
            ItemStack actual = craftingMenu.getResultSlot().getItem();
            if (actual.isEmpty()) return false;
            if (!same(actual, expectedOutput)) throw new IllegalStateException("Crafting result disagrees with the planned recipe");
            quickMove = new VerifiedQuickMove(client, menu, menu.slots.indexOf(craftingMenu.getResultSlot()),
                    expectedOutput.getItem(), "crafting output");
            movePurpose = MovePurpose.OUTPUT;
            return false;
        }

        if (drainRequested) {
            if (drainGridPending) {
                if (!drainKnownGridContents(false)) return false;
                drainGridPending = false;
                return true;
            }
            if (!placements.isEmpty() && placementIndex == placements.size()) {
                awaitingResult = true;
                return false;
            }
            if (!drainKnownGridContents(true)) return false;
            return true;
        }

        boolean targetReached = actions.count(expectedOutput.getItem()) >= targetCount;
        if (targetReached || drainGridPending) {
            if (!drainKnownGridContents(targetReached && !drainGridPending)) return false;
            drainGridPending = false;
            if (targetReached) return true;
        }
        if (placements.isEmpty()) buildPlacements();
        if (placementIndex == placements.size()) {
            awaitingResult = true;
            cooldown = 3;
            return false;
        }
        Placement placement = placements.get(placementIndex);
        int source = source(placement.item());
        transfer = new SlotTransfer(client, menu, source, placement.menuSlot(), 1);
        return false;
    }

    private void initialize() {
        menu = client.player.containerMenu;
        if (!(menu instanceof AbstractCraftingMenu crafting)) throw new IllegalStateException("Open the required crafting grid");
        craftingMenu = crafting;
        int gridSize = craftingMenu.getInputGridSlots().size();
        if (gridSize != 4 && gridSize != 9) throw new IllegalStateException("This crafting menu does not expose a supported 2×2 or 3×3 grid");
        if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Cursor is occupied");
        for (Slot slot : craftingMenu.getInputGridSlots()) {
            if (!slot.getItem().isEmpty()) throw new IllegalStateException("Crafting grid contains your items; clear it before automation");
        }
        int gridWidth = gridSize == 9 ? 3 : 2;
        if (recipe.type() == dev.lodekeeper.core.RecipeType.SHAPED &&
                (recipe.width() > gridWidth || recipe.height() > gridWidth)) {
            throw new IllegalStateException("Recipe does not fit this crafting grid");
        }
        initialized = true;
    }

    private void buildPlacements() {
        int gridWidth = craftingMenu.getInputGridSlots().size() == 9 ? 3 : 2;
        for (Slot slot : craftingMenu.getInputGridSlots()) {
            if (!slot.getItem().isEmpty()) throw new IllegalStateException("Unexpected crafting grid contents; leaving the container open");
        }
        for (GameCatalog.RecipeInput input : recipe.ingredients()) {
            int gridIndex;
            if (recipe.type() == dev.lodekeeper.core.RecipeType.SHAPED) {
                int recipeRow = input.recipeSlot() / recipe.width();
                int recipeColumn = input.recipeSlot() % recipe.width();
                int rowOffset = Math.max(0, (gridWidth - recipe.height()) / 2);
                int columnOffset = Math.max(0, (gridWidth - recipe.width()) / 2);
                gridIndex = (recipeRow + rowOffset) * gridWidth + recipeColumn + columnOffset;
            } else {
                gridIndex = input.recipeSlot();
            }
            if (gridIndex < 0 || gridIndex >= craftingMenu.getInputGridSlots().size()) throw new IllegalStateException("Recipe does not fit the open grid");
            Item selected = null;
            String selectedKey = null;
            for (SelectedItemRequirement requirement : step.requirements().stream()
                    .filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast)
                    .filter(candidate -> candidate.purpose().equals("recipe ingredient") && candidate.recipeSlot() == input.recipeSlot()).toList()) {
                Item item = GameCatalog.item(requirement.item());
                String candidateKey = key(input.recipeSlot(), item);
                long alreadyPlaced = placements.stream().filter(placement -> placement.budgetKey().equals(candidateKey)).count();
                long alreadyPlacedForItem = placements.stream().filter(placement -> placement.item().equals(item)).count();
                if (!input.ingredient().test(new ItemStack(item))
                        || remainingMaterials.getOrDefault(candidateKey, 0) <= alreadyPlaced
                        || actions.count(item) <= alreadyPlacedForItem) continue;
                selected = item;
                selectedKey = candidateKey;
                break;
            }
            if (selected == null) throw new IllegalStateException("Planned ingredient is missing or no longer matches the known recipe");
            Slot target = craftingMenu.getInputGridSlots().get(gridIndex);
            if (!target.getItem().isEmpty()) throw new IllegalStateException("Crafting grid changed during automation");
            placements.add(new Placement(menu.slots.indexOf(target), selected, selectedKey));
        }
        if (placements.isEmpty()) throw new IllegalStateException("Known recipe has no placeable ingredients");
    }

    private void rememberOwnedGridContents(Placement placement) {
        Set<Item> expected = expectedGridContents.computeIfAbsent(placement.menuSlot(), ignored -> new HashSet<>());
        expected.add(placement.item());
        var remainderTemplate = placement.item().getCraftingRemainder();
        ItemStack remainder = remainderTemplate == null ? ItemStack.EMPTY : remainderTemplate.create();
        if (remainder != null && !remainder.isEmpty()) {
            expected.add(remainder.getItem());
            expectedGridRemainders.computeIfAbsent(placement.menuSlot(), ignored -> new HashSet<>()).add(remainder.getItem());
        }
    }

    /** Returns true only after known ingredients or recipe remainders are observed in inventory. */
    private boolean drainKnownGridContents(boolean allowIngredients) {
        for (Slot slot : craftingMenu.getInputGridSlots()) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            int menuSlot = menu.slots.indexOf(slot);
            Set<Item> allowed = (allowIngredients ? expectedGridContents : expectedGridRemainders).get(menuSlot);
            if (allowed == null || !allowed.contains(stack.getItem()) || stack.getCount() != 1) {
                throw new IllegalStateException("Unexpected crafting grid contents; leaving the container open");
            }
            quickMove = new VerifiedQuickMove(client, menu, menuSlot, stack.getItem(), "crafting remainder");
            movePurpose = MovePurpose.GRID_CONTENT;
            return false;
        }
        expectedGridContents.clear();
        expectedGridRemainders.clear();
        return true;
    }

    private int source(Item item) {
        Inventory inventory = client.player.getInventory();
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            if (slot.container == inventory && slot.getContainerSlot() < 36 && slot.getItem().is(item)) return index;
        }
        throw new IllegalStateException("Missing ingredient " + GameCatalog.id(item));
    }

    private static String key(int recipeSlot, Item item) {
        return recipeSlot + ":" + BuiltInRegistries.ITEM.getKey(item);
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }

    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
    void requestDrain() { drainRequested = true; }
}
