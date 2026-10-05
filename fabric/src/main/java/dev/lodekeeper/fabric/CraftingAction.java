package dev.lodekeeper.fabric;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Manual grid placement works even for synchronized recipes absent from the unlocked recipe book. */
final class CraftingAction {
    private enum MovePurpose { OUTPUT, GRID_CONTENT }

    private final MinecraftClient client;
    private final PlayerActions actions;
    private final RecipeWork recipe;
    private final ItemStack expectedOutput;
    private final int targetCount;
    private final PlanStep step;
    private final List<Placement> placements = new ArrayList<>();
    private final Map<String, Integer> remainingMaterials = new HashMap<>();
    private final Map<Integer, List<ItemStack>> expectedGridContents = new HashMap<>();
    private final Map<Integer, List<ItemStack>> expectedGridRemainders = new HashMap<>();
    private ScreenHandler handler;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private MovePurpose movePurpose;
    private int placementIndex, cooldown;
    private boolean initialized, awaitingResult, drainGridPending, drainRequested, remaindersResolved;
    private record Placement(int gridSlot, int sourceSlot, Item item, Ingredient predicate, ItemStack inputStack, String budgetKey) {
        private Placement {
            inputStack = inputStack.copy();
        }
        @Override public ItemStack inputStack() { return inputStack.copy(); }
    }

    CraftingAction(MinecraftClient client, PlayerActions actions, RecipeWork recipe, PlanStep step) {
        this.client = client;
        this.actions = actions;
        this.recipe = recipe;
        if (recipe.kind() != RecipeWork.Kind.SHAPED_CRAFTING && recipe.kind() != RecipeWork.Kind.SHAPELESS_CRAFTING) {
            throw new IllegalArgumentException("Crafting action received non-crafting recipe work");
        }
        expectedOutput = recipe.outputPerOperation();
        this.step = step;
        targetCount = actions.count(expectedOutput.getItem()) + step.outputCount();
        step.requirements().stream().filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("recipe ingredient"))
                .forEach(requirement -> remainingMaterials.merge(key(requirement.recipeSlot(), GameCatalog.item(requirement.item())), requirement.count(), Integer::sum));
    }

    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        Item output = expectedOutput.getItem();
        if (cooldown-- > 0) return false;
        if (!initialized) initialize();
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Crafting container closed or changed");
        if (transfer == null && !handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");

        if (transfer != null) {
            if (transfer.tick()) {
                Placement placed = placements.get(placementIndex);
                ItemStack gridStack = handler.getSlot(placed.gridSlot()).getStack();
                if (gridStack.getCount() != 1 || !GameApi.canCombine(gridStack, placed.inputStack())
                        || !placed.predicate().test(gridStack)) {
                    throw new IllegalStateException("Crafting grid input changed or no longer matches the planned recipe; leaving the container open");
                }
                remainingMaterials.compute(placed.budgetKey, (key, count) -> count - 1);
                rememberOwnedGridContents(placed.gridSlot(), gridStack);
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
                    ItemStack remainingOutput = handler.getSlot(0).getStack();
                    if (!remainingOutput.isEmpty()) {
                        if (!GameApi.canCombine(remainingOutput, expectedOutput)
                                || remainingOutput.getCount() > expectedOutput.getCount()) {
                            throw new IllegalStateException("Unexpected item remained in the crafting output slot; leaving the container open");
                        }
                        quickMove = new VerifiedQuickMove(client, handler, 0, remainingOutput.getItem(), "crafting output");
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
            ItemStack actual = handler.getSlot(0).getStack();
            if (actual.isEmpty()) return false;
            if (!GameApi.canCombine(actual, expectedOutput) || actual.getCount() != expectedOutput.getCount()) {
                throw new IllegalStateException("Crafting output disagrees with the planned recipe; leaving the container open");
            }
            quickMove = new VerifiedQuickMove(client, handler, 0, output, "crafting output");
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
                resolveExpectedRemainders();
                awaitingResult = true;
                return false;
            }
            if (!drainKnownGridContents(true)) return false;
            return true;
        }

        boolean targetReached = actions.count(output) >= targetCount;
        if (targetReached || drainGridPending) {
            if (!drainKnownGridContents(targetReached && !drainGridPending)) return false;
            drainGridPending = false;
            if (targetReached) return true;
        }

        if (placements.isEmpty()) buildPlacements();
        if (placementIndex == placements.size()) {
            resolveExpectedRemainders();
            awaitingResult = true;
            cooldown = 3;
            return false;
        }
        Placement placement = placements.get(placementIndex);
        ItemStack currentInput = handler.getSlot(placement.sourceSlot()).getStack();
        if (currentInput.isEmpty() || !currentInput.isOf(placement.item())
                || !GameApi.canCombine(currentInput, placement.inputStack()) || !placement.predicate().test(currentInput)) {
            throw new IllegalStateException("The selected inventory stack no longer matches the planned recipe input; no mismatching item was consumed");
        }
        transfer = new SlotTransfer(client, handler, placement.sourceSlot(), placement.gridSlot(), 1);
        return false;
    }

    private void initialize() {
        handler = client.player.currentScreenHandler;
        if (!(handler instanceof PlayerScreenHandler || handler instanceof CraftingScreenHandler)) throw new IllegalStateException("Open the required crafting grid");
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied");
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        if (recipe.kind() == RecipeWork.Kind.SHAPED_CRAFTING && (recipe.width() > width || recipe.height() > width)) {
            throw new IllegalStateException("Recipe requires a crafting table");
        }
        for (int index = 1; index <= width * width; index++) {
            if (!handler.getSlot(index).getStack().isEmpty()) throw new IllegalStateException("Crafting grid contains your items; clear it before automation");
        }
        initialized = true;
    }

    private static String key(int slot, Item item) {
        return slot + ":" + net.minecraft.registry.Registries.ITEM.getId(item);
    }

    private void buildPlacements() {
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        for (int slot = 1; slot <= width * width; slot++) {
            if (!handler.getSlot(slot).getStack().isEmpty()) {
                throw new IllegalStateException("Unexpected crafting grid contents; leaving the container open");
            }
        }
        for (RecipeWork.Input input : recipe.inputs()) {
            int gridIndex = recipe.gridIndex(input, width);
            int gridSlot = 1 + gridIndex;
            if (gridIndex < 0 || gridIndex >= width * width) throw new IllegalStateException("Recipe does not fit the open crafting grid");
            Item selected = null;
            int sourceSlot = -1;
            ItemStack selectedStack = null;
            String budgetKey = null;
            for (var requirement : step.requirements()) {
                if (!(requirement instanceof SelectedItemRequirement choice)
                        || !choice.purpose().equals("recipe ingredient") || choice.recipeSlot() != input.slot()) continue;
                Item item = GameCatalog.item(choice.item());
                String candidateKey = key(input.slot(), item);
                long alreadyForSlot = placements.stream().filter(placement -> placement.budgetKey.equals(candidateKey)).count();
                long alreadyForItem = placements.stream().filter(placement -> placement.item.equals(item)).count();
                if (remainingMaterials.getOrDefault(candidateKey, 0) <= alreadyForSlot
                        || actions.count(item) <= alreadyForItem) continue;
                var source = findAvailableInput(item, input.predicate());
                if (source == null) continue;
                selected = item;
                sourceSlot = source.slotId();
                selectedStack = source.stack();
                budgetKey = candidateKey;
                break;
            }
            if (selected == null) throw new IllegalStateException("Planned ingredient no longer matches an actual inventory stack for " + step.sourceId() + "; the planner tracks item IDs, so component-specific choices are blocked safely");
            placements.add(new Placement(gridSlot, sourceSlot, selected, input.predicate(), selectedStack, budgetKey));
        }
        if (placements.isEmpty()) throw new IllegalStateException("Known recipe has no placeable ingredients");
    }

    private record AvailableInput(int slotId, ItemStack stack) {}

    private AvailableInput findAvailableInput(Item item, Ingredient predicate) {
        for (var slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !stack.isOf(item) || !predicate.test(stack)) continue;
            long reserved = placements.stream().filter(placement -> placement.sourceSlot() == slot.id).count();
            if (stack.getCount() > reserved) return new AvailableInput(slot.id, stack.copyWithCount(1));
        }
        return null;
    }

    private void rememberOwnedGridContents(int gridSlot, ItemStack input) {
        addExpected(expectedGridContents, gridSlot, input);
    }

    private static void addExpected(Map<Integer, List<ItemStack>> expected, int gridSlot, ItemStack stack) {
        List<ItemStack> values = expected.computeIfAbsent(gridSlot, ignored -> new ArrayList<>());
        if (values.stream().noneMatch(existing -> GameApi.canCombine(existing, stack) && existing.getCount() == stack.getCount())) {
            values.add(stack.copy());
        }
    }

    private void resolveExpectedRemainders() {
        if (remaindersResolved) return;
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        int gridSlots = width * width;
        List<ItemStack> grid = new ArrayList<>(gridSlots);
        for (int index = 0; index < gridSlots; index++) grid.add(handler.getSlot(index + 1).getStack().copy());
        List<ItemStack> remainders = recipe.remainderResolver().resolve(handler, width, List.copyOf(grid));
        if (remainders == null || remainders.size() != gridSlots) {
            throw new IllegalStateException("Recipe remainder prediction did not cover the complete crafting grid; leaving the container open");
        }
        expectedGridRemainders.clear();
        for (int index = 0; index < remainders.size(); index++) {
            ItemStack remainder = remainders.get(index);
            if (remainder == null) throw new IllegalStateException("Recipe remainder prediction returned unknown contents; leaving the container open");
            if (!remainder.isEmpty()) addExpected(expectedGridRemainders, index + 1, remainder);
        }
        remaindersResolved = true;
    }

    /** Returns true only after every known ingredient or recipe remainder is observed in inventory. */
    private boolean drainKnownGridContents(boolean allowIngredients) {
        int gridSlots = handler instanceof CraftingScreenHandler ? 9 : 4;
        for (int slot = 1; slot <= gridSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            List<ItemStack> allowed = (allowIngredients ? expectedGridContents : expectedGridRemainders).get(slot);
            if (allowed == null || allowed.stream().noneMatch(expected -> GameApi.canCombine(stack, expected)
                    && stack.getCount() == expected.getCount())) {
                throw new IllegalStateException("Unexpected crafting grid contents; leaving the container open");
            }
            quickMove = new VerifiedQuickMove(client, handler, slot, stack.getItem(), "crafting remainder");
            movePurpose = MovePurpose.GRID_CONTENT;
            return false;
        }
        expectedGridContents.clear();
        expectedGridRemainders.clear();
        remaindersResolved = false;
        return true;
    }

    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
    void requestDrain() { drainRequested = true; }
}
