package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.SelectedItemRequirement;
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
    private final PlanStep step;
    private final List<Placement> placements = new ArrayList<>();
    private final java.util.Map<String, Integer> remainingMaterials = new java.util.HashMap<>();
    private ScreenHandler handler;
    private SlotTransfer transfer;
    private int placementIndex, cooldown;
    private boolean initialized, awaitingResult;
    private record Placement(int gridSlot, net.minecraft.item.Item item, String budgetKey) {}
    CraftingAction(MinecraftClient client, PlayerActions actions, Recipe<?> recipe, PlanStep step) {
        this.client = client; this.actions = actions; this.recipe = recipe; this.step = step;
        targetCount = actions.count(recipe.getOutput(client.world.getRegistryManager()).getItem()) + step.outputCount();
        step.requirements().stream().filter(SelectedItemRequirement.class::isInstance).map(SelectedItemRequirement.class::cast)
            .filter(r -> r.purpose().equals("recipe ingredient")).forEach(r -> remainingMaterials.merge(key(r.recipeSlot(), GameCatalog.item(r.item())), r.count(), Integer::sum));
    }
    boolean tick() {
        if (client.player == null || client.interactionManager == null) throw new IllegalStateException("No player");
        var output = recipe.getOutput(client.world.getRegistryManager());
        if (cooldown-- > 0) return false;
        if (actions.count(output.getItem()) >= targetCount) return true;
        if (!initialized) initialize();
        if (client.player.currentScreenHandler != handler) throw new IllegalStateException("Crafting container closed or changed");
        if (transfer != null) {
            if (transfer.tick()) { remainingMaterials.compute(placements.get(placementIndex).budgetKey, (k, count) -> count - 1); transfer = null; placementIndex++; }
            return false;
        }
        if (awaitingResult) {
            ItemStack actual = handler.getSlot(0).getStack();
            if (actual.isEmpty()) return false;
            if (!actual.isOf(output.getItem())) throw new IllegalStateException("Crafting output disagrees with the planned recipe");
            client.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, client.player);
            cooldown = 8; awaitingResult = false; placementIndex = 0; placements.clear();
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
        if (placements.isEmpty()) buildPlacements();
        if (placementIndex == placements.size()) { awaitingResult = true; cooldown = 3; return false; }
        Placement placement = placements.get(placementIndex);
        int source = -1;
        for (var slot : handler.slots) {
            if (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36) continue;
            if (slot.getStack().isOf(placement.item)) { source = slot.id; break; }
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
        initialized = true;
    }
    private static String key(int slot, net.minecraft.item.Item item) { return slot + ":" + net.minecraft.registry.Registries.ITEM.getId(item); }
    private void buildPlacements() {
        int width = handler instanceof CraftingScreenHandler ? 3 : 2;
        var ingredients = recipe.getIngredients();
        int recipeWidth = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : width;
        int sequential = 0;
        for (int i = 0; i < ingredients.size(); i++) {
            if (ingredients.get(i).isEmpty()) continue;
            int slot = recipe instanceof ShapedRecipe ? 1 + i % recipeWidth + i / recipeWidth * width : 1 + sequential++;
            int recipeIndex = recipe instanceof ShapedRecipe ? i : -1;
            net.minecraft.item.Item selected = null;
            String budgetKey = null;
            for (var requirement : step.requirements()) {
                if (!(requirement instanceof SelectedItemRequirement choice) || !choice.purpose().equals("recipe ingredient") || choice.recipeSlot() != recipeIndex) continue;
                var item = GameCatalog.item(choice.item());
                String candidateKey = key(recipeIndex, item);
                long already = placements.stream().filter(p -> p.budgetKey.equals(candidateKey)).count();
                if (!ingredients.get(i).test(new ItemStack(item)) || remainingMaterials.getOrDefault(candidateKey, 0) <= already || actions.count(item) <= already) continue;
                selected = item; budgetKey = candidateKey; break;
            }
            if (selected == null) throw new IllegalStateException("Planned ingredient no longer available for recipe");
            placements.add(new Placement(slot, selected, budgetKey));
        }
    }
    void pause() { if (transfer != null) transfer.recover(); }
    void cancel() { pause(); }
}
