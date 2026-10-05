package dev.lodekeeper.fabric;

import net.minecraft.item.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

/** Version-specific Minecraft calls used by the shared client adapter. */
final class GameApi {
    record RecipeRef(String id, Recipe<?> recipe) {}
    record FoodInfo(int nutrition, float saturation, boolean safe) {}

    private GameApi() {}

    static double blockReach(net.minecraft.client.MinecraftClient client) { return client.interactionManager.getReachDistance(); }

    static Identifier identifier(String value) { return new Identifier(value); }

    static boolean canCombine(ItemStack first, ItemStack second) { return ItemStack.canCombine(first, second); }

    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getOutput(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookTime(); }

    static RecipeWork.RemainderResolver remainderResolver(Recipe<?> recipe) {
        return (handler, gridWidth, inputGrid) -> {
            CraftingInventory input = new CraftingInventory(handler, gridWidth, gridWidth);
            for (int slot = 0; slot < inputGrid.size(); slot++) input.setStack(slot, inputGrid.get(slot).copy());
            @SuppressWarnings("rawtypes") Recipe raw = recipe;
            return new ArrayList<>(raw.getRemainder(input));
        };
    }

    static List<RecipeRef> recipes(RecipeManager manager) {
        return manager.values().stream().map(recipe -> new RecipeRef(recipe.getId().toString(), recipe)).toList();
    }

    static FoodInfo food(ItemStack stack) {
        FoodComponent food = stack.getItem().getFoodComponent();
        return food == null ? null : new FoodInfo(food.getHunger(), food.getHunger() * food.getSaturationModifier() * 2, food.getStatusEffects().isEmpty());
    }
}
