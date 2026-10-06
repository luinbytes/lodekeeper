package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.StationId;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ShovelItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ShearsItem;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

/** Minecraft 1.20.2–1.20.4 names for the shared client adapter's narrow compatibility surface. */
final class GameApi {
    record RecipeRef(String id, Recipe<?> recipe) {}
    record FoodInfo(int nutrition, float saturation, boolean safe) {}

    private GameApi() {}

    static double blockReach(MinecraftClient client) { return client.interactionManager.getReachDistance(); }

    static Identifier identifier(String value) { return new Identifier(value); }

    static boolean canCombine(ItemStack first, ItemStack second) { return ItemStack.canCombine(first, second); }

    static boolean hasSilkTouch(ItemStack stack) {
        return EnchantmentHelper.getLevel(Enchantments.SILK_TOUCH, stack) > 0;
    }

    /** Unknown legacy damageable implementations cannot disclose their mining wear. */
    static int blockBreakWear(ItemStack stack) {
        if (!stack.isDamageable()) return 0;
        Class<?> type = stack.getItem().getClass();
        if (type == SwordItem.class) return 2;
        if (type == ShearsItem.class || type == PickaxeItem.class || type == AxeItem.class
                || type == ShovelItem.class || type == HoeItem.class) return 1;
        return -1;
    }

    static boolean isSword(ItemStack stack) { return stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS); }

    static boolean isAxe(ItemStack stack) { return stack.isIn(net.minecraft.registry.tag.ItemTags.AXES); }

    /** Vanilla swords lose 1 durability and axes lose 2 when their native attack hook succeeds. */
    static int attackWear(ItemStack stack) {
        if (!stack.isDamageable()) return 0;
        Class<?> type = stack.getItem().getClass();
        if (type == SwordItem.class) return 1;
        if (type == AxeItem.class) return 2;
        return -1;
    }


    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getResult(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookingTime(); }

    private static StationId cookingStation(AbstractCookingRecipe recipe) {
        if (recipe.getType() == net.minecraft.recipe.RecipeType.SMELTING) return StationId.parse("minecraft:furnace");
        if (recipe.getType() == net.minecraft.recipe.RecipeType.SMOKING) return StationId.parse("minecraft:smoker");
        if (recipe.getType() == net.minecraft.recipe.RecipeType.BLASTING) return StationId.parse("minecraft:blast_furnace");
        return null;
    }

    static long cookingFuelProgressTicks(Item fuel, StationId station, long rawBurnTicks) {
        if (fuel == null || fuel.getRecipeRemainder() != null || rawBurnTicks < 1) return 0;
        long progress = switch (station == null ? "" : station.toString()) {
            case "minecraft:furnace" -> rawBurnTicks;
            case "minecraft:smoker", "minecraft:blast_furnace" -> rawBurnTicks / 2;
            default -> 0;
        };
        return progress <= 1_000_000_000L ? progress : 0;
    }

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

    static Object recipeProviderIdentity(MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static Object stonecuttingProviderIdentity(MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static List<StonecuttingWork> stonecuttingRecipes(MinecraftClient client) {
        if (client.world == null) return List.of();
        List<RecipeEntry<net.minecraft.recipe.StonecuttingRecipe>> entries = client.world.getRecipeManager()
                .listAllOfType(net.minecraft.recipe.RecipeType.STONECUTTING);
        if (entries.size() > 4096) throw new IllegalArgumentException("stonecutting recipe source exceeds 4096 entries");
        List<StonecuttingWork> works = new ArrayList<>(entries.size());
        for (RecipeEntry<net.minecraft.recipe.StonecuttingRecipe> entry : entries) {
            try {
                net.minecraft.recipe.StonecuttingRecipe recipe = entry.value();
                List<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients.size() != 1 || ingredients.get(0).isEmpty()) continue;
                ItemStack output = result(recipe, client.world.getRegistryManager());
                if (output.isEmpty() || output.getCount() > 99) continue;
                works.add(new StonecuttingWork("stonecutting:" + entry.id(), ingredients.get(0), output, entry));
            } catch (RuntimeException unsupportedRow) {
                // Reject only this invalid native option; keep other synchronized rows usable.
            }
        }
        return List.copyOf(works);
    }

    static int stonecuttingRecipeIndex(MinecraftClient client, net.minecraft.screen.StonecutterScreenHandler handler,
                                       StonecuttingWork work) {
        if (client == null || client.world == null || handler == null || work == null
                || !(work.selectionKey() instanceof RecipeEntry<?> selected)
                || !(selected.value() instanceof net.minecraft.recipe.StonecuttingRecipe target)) return -1;
        boolean current = client.world.getRecipeManager().listAllOfType(net.minecraft.recipe.RecipeType.STONECUTTING)
                .stream().anyMatch(entry -> entry == selected);
        if (!current) return -1;
        ItemStack heldInput = handler.getSlot(0).getStack();
        if (heldInput.isEmpty() || !work.input().test(heldInput)) return -1;
        int match = -1;
        List<RecipeEntry<net.minecraft.recipe.StonecuttingRecipe>> visible = handler.getAvailableRecipes();
        for (int index = 0; index < visible.size(); index++) {
            RecipeEntry<net.minecraft.recipe.StonecuttingRecipe> candidateEntry = visible.get(index);
            if (candidateEntry != selected) continue;
            if (match >= 0) return -1;
            net.minecraft.recipe.StonecuttingRecipe candidate = candidateEntry.value();
            List<Ingredient> ingredients = candidate.getIngredients();
            if (candidate != target || ingredients.size() != 1 || ingredients.get(0) != work.input()
                    || !ingredients.get(0).test(heldInput)) return -1;
            ItemStack actual = result(candidate, client.world.getRegistryManager());
            ItemStack expected = work.outputPerOperation();
            if (actual.isEmpty() || actual.getCount() != expected.getCount()
                    || !ItemStack.canCombine(actual, expected)) return -1;
            match = index;
        }
        return match;
    }

    static void loadRecipes(MinecraftClient client, Consumer<RecipeCatalogSnapshot> publish) {
        if (client.world == null) { publish.accept(RecipeCatalogSnapshot.empty()); return; }
        Map<String, RecipeWork> works = new TreeMap<>();
        List<String> unsupported = new ArrayList<>();
        for (RecipeRef entry : recipes(client.world.getRecipeManager()).stream().sorted(Comparator.comparing(RecipeRef::id)).toList()) {
            Recipe<?> recipe = entry.recipe();
            ItemStack output = result(recipe, client.world.getRegistryManager());
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
                } else if (recipe instanceof AbstractCookingRecipe cooking && cookingStation(cooking) != null) {
                    List<Ingredient> ingredients = recipe.getIngredients();
                    if (ingredients.size() == 1 && !ingredients.get(0).isEmpty()) {
                        works.put(entry.id(), new RecipeWork(RecipeWork.Kind.SMELTING, output, 0, 0,
                                List.of(new RecipeWork.Input(-1, ingredients.get(0))), cookingTime(cooking),
                                cookingStation(cooking), null));
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
