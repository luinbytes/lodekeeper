package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

/** Version-specific Minecraft calls used by the shared client adapter. */
final class GameApi {
    record RecipeRef(String id, Recipe<?> recipe) {}
    record FoodInfo(int nutrition, float saturation, boolean safe) {}

    private GameApi() {}

    static double blockReach(net.minecraft.client.MinecraftClient client) { return client.interactionManager.getReachDistance(); }

    static Identifier identifier(String value) { return new Identifier(value); }

    static boolean canCombine(ItemStack first, ItemStack second) { return ItemStack.canCombine(first, second); }

    /** Unknown legacy damageable implementations cannot disclose their mining wear. */
    static int blockBreakWear(ItemStack stack) {
        if (!stack.isDamageable()) return 0;
        Class<?> type = stack.getItem().getClass();
        if (type == SwordItem.class) return 2;
        if (type == ShearsItem.class || type == PickaxeItem.class || type == AxeItem.class
                || type == ShovelItem.class || type == HoeItem.class) return 1;
        return -1;
    }

    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getOutput(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookTime(); }

    static RecipeWork.RemainderResolver remainderResolver(Recipe<?> recipe) {
        return (handler, gridWidth, inputGrid) -> {
            CraftingInventory input = new CraftingInventory(handler, gridWidth, gridWidth);
            for (int slot = 0; slot < inputGrid.size(); slot++) input.setStack(slot, inputGrid.get(slot).copy());
            @SuppressWarnings("rawtypes") Recipe raw = recipe;
            return CompletableFuture.completedFuture(new ArrayList<>(raw.getRemainder(input)));
        };
    }

    static List<RecipeRef> recipes(RecipeManager manager) {
        return manager.values().stream().map(recipe -> new RecipeRef(recipe.getId().toString(), recipe)).toList();
    }

    static Object recipeProviderIdentity(net.minecraft.client.MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static void loadRecipes(net.minecraft.client.MinecraftClient client, Consumer<RecipeCatalogSnapshot> publish) {
        if (client.world == null) { publish.accept(RecipeCatalogSnapshot.empty()); return; }
        Map<String, RecipeWork> works = new TreeMap<>();
        List<String> unsupported = new ArrayList<>();
        var registries = client.world.getRegistryManager();
        for (RecipeRef entry : recipes(client.world.getRecipeManager()).stream().sorted(java.util.Comparator.comparing(RecipeRef::id)).toList()) {
            Recipe<?> recipe = entry.recipe();
            ItemStack output = result(recipe, registries);
            if (output.isEmpty()) continue;
            try {
                if (recipe instanceof ShapedRecipe shaped) {
                    List<RecipeWork.Input> inputs = new ArrayList<>();
                    List<Ingredient> ingredients = recipe.getIngredients();
                    for (int slot = 0; slot < ingredients.size(); slot++) {
                        if (!ingredients.get(slot).isEmpty()) inputs.add(new RecipeWork.Input(slot, ingredients.get(slot)));
                    }
                    if (!inputs.isEmpty()) works.put(entry.id(), new RecipeWork(RecipeWork.Kind.SHAPED_CRAFTING,
                            output, shaped.getWidth(), shaped.getHeight(), inputs, 0, remainderResolver(recipe)));
                } else if (recipe instanceof ShapelessRecipe) {
                    List<RecipeWork.Input> inputs = new ArrayList<>();
                    int slot = 0;
                    for (Ingredient ingredient : recipe.getIngredients()) {
                        if (!ingredient.isEmpty()) inputs.add(new RecipeWork.Input(slot++, ingredient));
                    }
                    if (!inputs.isEmpty()) works.put(entry.id(), new RecipeWork(RecipeWork.Kind.SHAPELESS_CRAFTING,
                            output, 0, 0, inputs, 0, remainderResolver(recipe)));
                } else if (recipe instanceof AbstractCookingRecipe cooking && recipe.getType() == net.minecraft.recipe.RecipeType.SMELTING) {
                    List<Ingredient> ingredients = recipe.getIngredients();
                    if (ingredients.size() == 1 && !ingredients.get(0).isEmpty()) {
                        works.put(entry.id(), new RecipeWork(RecipeWork.Kind.SMELTING, output, 0, 0,
                                List.of(new RecipeWork.Input(-1, ingredients.get(0))), cookingTime(cooking), null));
                    }
                } else {
                    unsupported.add(entry.id() + " (" + Registries.RECIPE_SERIALIZER.getId(recipe.getSerializer()) + ")");
                }
            } catch (IllegalArgumentException exception) {
                unsupported.add(entry.id() + ": " + (exception.getMessage() == null ? "invalid recipe" : exception.getMessage()));
            }
        }
        Map<ItemId, Long> fuel = new HashMap<>();
        Map<Item, Integer> burnTimes = AbstractFurnaceBlockEntity.createFuelTimeMap();
        burnTimes.forEach((item, ticks) -> { if (ticks != null && ticks > 0) fuel.put(GameCatalog.id(item), ticks.longValue()); });
        publish.accept(new RecipeCatalogSnapshot(works, unsupported, fuel));
    }

    static dev.lodekeeper.core.Ingredient ingredient(Ingredient ingredient) {
        List<ItemId> choices = Arrays.stream(ingredient.getMatchingStacks()).filter(stack -> !stack.isEmpty())
                .map(stack -> GameCatalog.id(stack.getItem())).distinct().sorted().toList();
        return dev.lodekeeper.core.Ingredient.choices(choices, 1);
    }

    static FoodInfo food(ItemStack stack) {
        FoodComponent food = stack.getItem().getFoodComponent();
        return food == null ? null : new FoodInfo(food.getHunger(), food.getHunger() * food.getSaturationModifier() * 2, food.getStatusEffects().isEmpty());
    }
}
