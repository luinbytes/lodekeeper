package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

/** Minecraft 1.20.2–1.20.4 names for the shared client adapter's narrow compatibility surface. */
final class GameApi {
    record RecipeRef(String id, Recipe<?> recipe) {}
    record FoodInfo(int nutrition, float saturation, boolean safe) {}

    private GameApi() {}

    static double blockReach(MinecraftClient client) { return client.interactionManager.getReachDistance(); }

    static Identifier identifier(String value) { return new Identifier(value); }

    static boolean canCombine(ItemStack first, ItemStack second) { return ItemStack.canCombine(first, second); }

    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getResult(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookingTime(); }

    static RecipeWork.RemainderResolver remainderResolver(Recipe<?> recipe) {
        return (handler, gridWidth, inputGrid) -> {
            CraftingInventory input = new CraftingInventory(handler, gridWidth, gridWidth);
            for (int slot = 0; slot < inputGrid.size(); slot++) input.setStack(slot, inputGrid.get(slot).copy());
            @SuppressWarnings("rawtypes") Recipe raw = recipe;
            return new ArrayList<>(raw.getRemainder(input));
        };
    }

    static List<RecipeRef> recipes(RecipeManager manager) {
        return manager.values().stream().map((RecipeEntry<?> entry) -> new RecipeRef(entry.id().toString(), entry.value())).toList();
    }

    static FoodInfo food(ItemStack stack) {
        FoodComponent food = stack.getItem().getFoodComponent();
        return food == null ? null : new FoodInfo(food.getHunger(), food.getHunger() * food.getSaturationModifier() * 2, food.getStatusEffects().isEmpty());
    }
}
