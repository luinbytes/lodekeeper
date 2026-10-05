package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.Item;
import net.minecraft.component.type.ToolComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Minecraft 1.20.5–1.20.6 names for the shared client adapter's narrow compatibility surface. */
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

    /** Reads the effective per-block wear from this stack's tool component. */
    static int blockBreakWear(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.item.ShearsItem) {
            if (stack.getItem().getClass() != net.minecraft.item.ShearsItem.class) return -1;
            var shearTool = stack.get(DataComponentTypes.TOOL);
            return shearTool == null ? 1 : Math.max(1, shearTool.damagePerBlock());
        }
        ToolComponent tool = stack.get(DataComponentTypes.TOOL);
        return tool == null ? (stack.isDamageable() ? -1 : 0) : tool.damagePerBlock();
    }

    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getResult(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookingTime(); }

    static RecipeWork.RemainderResolver remainderResolver(Recipe<?> recipe) {
        return (handler, gridWidth, inputGrid) -> {
            CraftingInventory input = new CraftingInventory(handler, gridWidth, gridWidth);
            for (int slot = 0; slot < inputGrid.size(); slot++) input.setStack(slot, inputGrid.get(slot).copy());
            @SuppressWarnings("rawtypes") Recipe raw = recipe;
            return CompletableFuture.completedFuture(new ArrayList<>(raw.getRemainder(input)));
        };
    }

    static List<RecipeRef> recipes(RecipeManager manager) {
        return manager.values().stream().map((RecipeEntry<?> entry) -> new RecipeRef(entry.id().toString(), entry.value())).toList();
    }

    static Object recipeProviderIdentity(net.minecraft.client.MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static void loadRecipes(net.minecraft.client.MinecraftClient client, Consumer<RecipeCatalogSnapshot> publish) {
        if (client.world == null) { publish.accept(RecipeCatalogSnapshot.empty()); return; }
        RecipeManager manager = client.world.getRecipeManager();
        DynamicRegistryManager registries = client.world.getRegistryManager();
        Map<String, RecipeWork> works = new TreeMap<>();
        List<String> unsupported = new ArrayList<>();
        for (RecipeRef entry : recipes(manager).stream().sorted(Comparator.comparing(RecipeRef::id)).toList()) {
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
                } else if (recipe instanceof AbstractCookingRecipe cooking && recipe.getType() == RecipeType.SMELTING) {
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
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        return food == null ? null : new FoodInfo(food.nutrition(), food.saturation(), food.effects().isEmpty());
    }
}
