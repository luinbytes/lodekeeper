package dev.lodekeeper.fabric;

import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Manual grid placement works even for synchronized recipes absent from the unlocked recipe book. */
final class CraftingAction {
    private enum MovePurpose { OUTPUT, GRID_CONTENT }

    private final MinecraftClient client;
    private final PlayerActions actions;
    private final Recipe<?> recipe;
    private final int targetCount;
    private final PlanStep step;
    private final List<Placement> placements = new ArrayList<>();
    private final Map<String, Integer> remainingMaterials = new HashMap<>();
    private final Map<Integer, Set<Item>> expectedGridContents = new HashMap<>();
    private final Map<Integer, Set<Item>> expectedGridRemainders = new HashMap<>();
    private ScreenHandler handler;
    private SlotTransfer transfer;
    private VerifiedQuickMove quickMove;
    private MovePurpose movePurpose;
    private int placementIndex, cooldown;
    private boolean initialized, awaitingResult, drainGridPending, drainRequested;
    private record Placement(int gridSlot, Item item, String budgetKey) {}

    CraftingAction(MinecraftClient client, PlayerActions actions, Recipe<?> recipe, PlanStep step) {
        this.client = client;
        this.actions = actions;
        this.recipe = recipe;
        this.step = step;
        targetCount = actions.count(GameApi.result(recipe, client.world.getRegistryManager()).getItem()) + step.outputCount();
        step.requirements().stream().filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast)
                .filter(requirement -> requirement.purpose().equals("recipe ingredient"))
                .forEach(requirement -> remainingMaterials.merge(key(requirement.recipeSlot(), GameCatalog.item(requirement.item())), requirement.count(), Integer::sum));
    }

    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        var output = GameApi.result(recipe, client.world.getRegistryManager());
        if (cooldown-- > 0) return false;
        if (!initialized) initialize();
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Crafting container closed or changed");
        if (transfer == null && !handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");

        if (transfer != null) {
            if (transfer.tick()) {
                Placement placed = placements.get(placementIndex);
                remainingMaterials.compute(placed.budgetKey, (key, count) -> count - 1);
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
                    ItemStack remainingOutput = handler.getSlot(0).getStack();
                    if (!remainingOutput.isEmpty()) {
                        if (!remainingOutput.isOf(GameApi.result(recipe, client.world.getRegistryManager()).getItem())) {
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
            if (!actual.isOf(output.getItem())) throw new IllegalStateException("Crafting output disagrees with the planned recipe");
            quickMove = new VerifiedQuickMove(client, handler, 0, output.getItem(), "crafting output");
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

        boolean targetReached = actions.count(output.getItem()) >= targetCount;
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
        int source = -1;
        for (var slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36) continue;
            if (slot.getStack().isOf(placement.item)) { source = slot.id; break; }
        }
        if (source < 0) throw new IllegalStateException("Missing ingredient for " + step.sourceId());
        transfer = new SlotTransfer(client, handler, source, placement.gridSlot, 1);
        return false;
    }

    private void initialize() {
        handler = client.player.currentScreenHandler;
        if (!(handler instanceof PlayerScreenHandler || handler instanceof CraftingScreenHandler)) throw new IllegalStateException("Open the required crafting grid");
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied");
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        if (!recipe.fits(width, width)) throw new IllegalStateException("Recipe requires a crafting table");
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
        var ingredients = recipe.getIngredients();
        int recipeWidth = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : width;
        int shapelessIndex = 0;
        for (int index = 0; index < ingredients.size(); index++) {
            if (ingredients.get(index).isEmpty()) continue;
            int gridSlot = recipe instanceof ShapedRecipe ? 1 + index % recipeWidth + index / recipeWidth * width : 1 + shapelessIndex;
            int recipeIndex = recipe instanceof ShapedRecipe ? index : shapelessIndex;
            if (!(recipe instanceof ShapedRecipe)) shapelessIndex++;
            Item selected = null;
            String budgetKey = null;
            for (var requirement : step.requirements()) {
                if (!(requirement instanceof SelectedItemRequirement choice)
                        || !choice.purpose().equals("recipe ingredient") || choice.recipeSlot() != recipeIndex) continue;
                Item item = GameCatalog.item(choice.item());
                String candidateKey = key(recipeIndex, item);
                long alreadyForSlot = placements.stream().filter(placement -> placement.budgetKey.equals(candidateKey)).count();
                long alreadyForItem = placements.stream().filter(placement -> placement.item.equals(item)).count();
                if (!ingredients.get(index).test(new ItemStack(item))
                        || remainingMaterials.getOrDefault(candidateKey, 0) <= alreadyForSlot
                        || actions.count(item) <= alreadyForItem) continue;
                selected = item;
                budgetKey = candidateKey;
                break;
            }
            if (selected == null) throw new IllegalStateException("Planned ingredient no longer available for recipe");
            placements.add(new Placement(gridSlot, selected, budgetKey));
        }
    }

    private void rememberOwnedGridContents(Placement placement) {
        Set<Item> expected = expectedGridContents.computeIfAbsent(placement.gridSlot, ignored -> new HashSet<>());
        expected.add(placement.item);
        Item remainder = placement.item.getRecipeRemainder();
        if (remainder != null && remainder != Items.AIR) {
            expected.add(remainder);
            expectedGridRemainders.computeIfAbsent(placement.gridSlot, ignored -> new HashSet<>()).add(remainder);
        }
    }

    /** Returns true only after every known ingredient or recipe remainder is observed in inventory. */
    private boolean drainKnownGridContents(boolean allowIngredients) {
        int gridSlots = handler instanceof CraftingScreenHandler ? 9 : 4;
        for (int slot = 1; slot <= gridSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            Set<Item> allowed = (allowIngredients ? expectedGridContents : expectedGridRemainders).get(slot);
            if (allowed == null || !allowed.contains(stack.getItem()) || stack.getCount() != 1) {
                throw new IllegalStateException("Unexpected crafting grid contents; leaving the container open");
            }
            quickMove = new VerifiedQuickMove(client, handler, slot, stack.getItem(), "crafting remainder");
            movePurpose = MovePurpose.GRID_CONTENT;
            return false;
        }
        expectedGridContents.clear();
        expectedGridRemainders.clear();
        return true;
    }

    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
    void requestDrain() { drainRequested = true; }
}
