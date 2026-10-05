package dev.lodekeeper.fabric;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Identifier;
import java.util.List;

/** Minecraft 1.21.1 names for the shared client adapter's narrow compatibility surface. */
final class GameApi {
    record RecipeRef(String id, Recipe<?> recipe) {}
    record FoodInfo(int nutrition, float saturation, boolean safe) {}

    private GameApi() {}

    static double blockReach(net.minecraft.client.MinecraftClient client) { return client.player.getBlockInteractionRange(); }

    static Identifier identifier(String value) {
        int separator = value.indexOf(':');
        return separator < 0 ? Identifier.of("minecraft", value)
                : Identifier.of(value.substring(0, separator), value.substring(separator + 1));
    }

    static boolean canCombine(ItemStack first, ItemStack second) { return ItemStack.areItemsAndComponentsEqual(first, second); }

    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getResult(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookingTime(); }

    static List<RecipeRef> recipes(RecipeManager manager) {
        return manager.values().stream().map((RecipeEntry<?> entry) -> new RecipeRef(entry.id().toString(), entry.value())).toList();
    }

    static FoodInfo food(ItemStack stack) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        return food == null ? null : new FoodInfo(food.nutrition(), food.saturation(), food.effects().isEmpty());
    }
}
