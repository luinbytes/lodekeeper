package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import java.util.ArrayList;
import java.util.List;

/** Manual grid placement works even for synchronized recipes absent from the unlocked recipe book. */
final class CraftingAction {
    private final MinecraftClient client;
    private final PlayerActions actions;
    private final Recipe<?> recipe;
    private final int targetCount;
    private final List<Placement> placements = new ArrayList<>();
    private ScreenHandler handler;
    private SlotTransfer transfer;
    private int placementIndex, cooldown;
    private boolean initialized, awaitingResult;
    private record Placement(int gridSlot, net.minecraft.recipe.Ingredient ingredient) {}
    CraftingAction(MinecraftClient client, PlayerActions actions, Recipe<?> recipe, int desiredIncrease) {
        this.client = client; this.actions = actions; this.recipe = recipe;
        targetCount = actions.count(recipe.getOutput(client.world.getRegistryManager()).getItem()) + desiredIncrease;
    }
    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        var output = recipe.getOutput(client.world.getRegistryManager());
        if (cooldown-- > 0) return false;
        if (actions.count(output.getItem()) >= targetCount) return true;
        if (!initialized) initialize();
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Crafting container closed or changed");
        if (transfer != null) {
            if (transfer.tick()) { transfer = null; placementIndex++; }
            return false;
        }
        if (awaitingResult) {
            ItemStack actual = handler.getSlot(0).getStack();
            if (actual.isEmpty()) return false;
            if (!actual.isOf(output.getItem())) throw new IllegalStateException("Crafting output disagrees with the planned recipe");
            client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
            cooldown = 8; awaitingResult = false; placementIndex = 0;
            return false;
        }
        // Only clear leftovers produced by our completed crafting cycle, never the user's initial grid.
        if (placementIndex == 0) {
            int gridSlots = handler instanceof CraftingScreenHandler ? 9 : 4;
            for (int i = 1; i <= gridSlots; i++) if (!handler.getSlot(i).getStack().isEmpty()) {
                client.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, client.player);
                cooldown = 3; return false;
            }
        }
        if (placementIndex == placements.size()) { awaitingResult = true; cooldown = 3; return false; }
        Placement placement = placements.get(placementIndex);
        int source = -1;
        for (var slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36) continue;
            if (placement.ingredient.test(slot.getStack())) { source = slot.id; break; }
        }
        if (source < 0) throw new IllegalStateException("Missing ingredient for " + recipe.getId());
        transfer = new SlotTransfer(client, handler, source, placement.gridSlot, 1);
        return false;
    }
    private void initialize() {
        handler = client.player.currentScreenHandler;
        if (!(handler instanceof PlayerScreenHandler || handler instanceof CraftingScreenHandler)) throw new IllegalStateException("Open the required crafting grid");
        if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied");
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        if (!recipe.fits(width, width)) throw new IllegalStateException("Recipe requires a crafting table");
        for (int i = 1; i <= width * width; i++) if (!handler.getSlot(i).getStack().isEmpty()) throw new IllegalStateException("Crafting grid contains your items; clear it before automation");
        var ingredients = recipe.getIngredients();
        int recipeWidth = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : width;
        int sequential = 0;
        for (int i = 0; i < ingredients.size(); i++) {
            if (ingredients.get(i).isEmpty()) continue;
            int slot = recipe instanceof ShapedRecipe ? 1 + i % recipeWidth + i / recipeWidth * width : 1 + sequential++;
            placements.add(new Placement(slot, ingredients.get(i)));
        }
        initialized = true;
    }
    void cancel() { if (transfer != null) transfer.recover(); }
}
