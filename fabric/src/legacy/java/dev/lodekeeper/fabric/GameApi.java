package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.StationId;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
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

    static boolean isHostileMob(net.minecraft.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.entity.mob.MobEntity mob)
                || !(entity instanceof net.minecraft.entity.mob.Monster)
                || entity instanceof net.minecraft.entity.mob.Angerable
                || entity instanceof net.minecraft.entity.mob.PiglinEntity) return false;
        return !(entity instanceof net.minecraft.entity.mob.SpiderEntity)
                || mob.getTarget() != null || mob.getBrightnessAtEyes() < 0.5f;
    }

    static double defenseReach(net.minecraft.entity.player.PlayerEntity player) { return 3.0; }

    static boolean defenseWithinReach(net.minecraft.entity.player.PlayerEntity player, net.minecraft.entity.Entity target) {
        var eye = player.getEyePos();
        var box = target.getBoundingBox();
        double dx = Math.max(box.minX - eye.x, Math.max(0.0, eye.x - box.maxX));
        double dy = Math.max(box.minY - eye.y, Math.max(0.0, eye.y - box.maxY));
        double dz = Math.max(box.minZ - eye.z, Math.max(0.0, eye.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz < 9.0;
    }

    static boolean defenseHasSweepCollateral(net.minecraft.entity.player.PlayerEntity player, net.minecraft.entity.Entity target) {
        java.util.List<net.minecraft.entity.LivingEntity> nearby = new java.util.ArrayList<>();
        player.getEntityWorld().collectEntitiesByType(net.minecraft.util.TypeFilter.instanceOf(net.minecraft.entity.LivingEntity.class),
                target.getBoundingBox().expand(1.0, 0.25, 1.0), living -> living != player && living != target, nearby, 17);
        if (nearby.size() >= 17) return true;
        for (var living : nearby) {
            double distance = player.squaredDistanceTo(living);
            if (!Double.isFinite(distance)) return true;
            if (distance >= 9.0) continue;
            if (!(living instanceof net.minecraft.entity.mob.MobEntity mob) || !isHostileMob(mob)
                    || mob.getTarget() != null && mob.getTarget() != player) return true;
        }
        return false;
    }

    static int defenseAttackWear(ItemStack stack) {
        int known = attackWear(stack);
        if (known >= 0) return known;
        Class<?> type = stack.getItem().getClass();
        return type == net.minecraft.item.PickaxeItem.class || type == net.minecraft.item.ShovelItem.class
                || type == net.minecraft.item.HoeItem.class ? 2 : -1;
    }

    static double defenseAttackDamage(net.minecraft.entity.player.PlayerEntity player, ItemStack stack) {
        var attribute = net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE;
        var current = player.getAttributeInstance(attribute);
        if (current == null) return Double.NaN;
        var trial = new net.minecraft.entity.attribute.EntityAttributeInstance(attribute, ignored -> {});
        trial.setFrom(current);
        try {
            for (var modifier : player.getMainHandStack().getAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND).get(attribute)) trial.removeModifier(modifier);
            for (var modifier : stack.getAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND).get(attribute)) trial.addTemporaryModifier(modifier);
            double value = trial.getValue();
            return Double.isFinite(value) && value > 0 ? value : Double.NaN;
        } catch (IllegalArgumentException unsupportedModifiers) {
            return Double.NaN;
        }
    }

    static boolean hasCustomName(ItemStack stack) { return stack.hasCustomName(); }

    static double blockReach(net.minecraft.client.MinecraftClient client) { return client.interactionManager.getReachDistance(); }

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

    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getOutput(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookTime(); }

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
        return manager.values().stream().map(recipe -> new RecipeRef(recipe.getId().toString(), recipe)).toList();
    }

    static Object recipeProviderIdentity(net.minecraft.client.MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static Object stonecuttingProviderIdentity(net.minecraft.client.MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static List<StonecuttingWork> stonecuttingRecipes(net.minecraft.client.MinecraftClient client) {
        if (client.world == null) return List.of();
        List<net.minecraft.recipe.StonecuttingRecipe> recipes = client.world.getRecipeManager()
                .listAllOfType(net.minecraft.recipe.RecipeType.STONECUTTING);
        if (recipes.size() > 4096) throw new IllegalArgumentException("stonecutting recipe source exceeds 4096 entries");
        List<StonecuttingWork> works = new ArrayList<>(recipes.size());
        for (net.minecraft.recipe.StonecuttingRecipe recipe : recipes) {
            try {
                List<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients.size() != 1 || ingredients.get(0).isEmpty()) continue;
                ItemStack output = result(recipe, client.world.getRegistryManager());
                if (output.isEmpty() || output.getCount() > 99) continue;
                works.add(new StonecuttingWork("stonecutting:" + recipe.getId(), ingredients.get(0), output, recipe));
            } catch (RuntimeException unsupportedRow) {
                // Reject only this invalid native option; keep other synchronized rows usable.
            }
        }
        return List.copyOf(works);
    }

    static int stonecuttingRecipeIndex(net.minecraft.client.MinecraftClient client,
                                       net.minecraft.screen.StonecutterScreenHandler handler, StonecuttingWork work) {
        if (client == null || client.world == null || handler == null || work == null
                || !(work.selectionKey() instanceof net.minecraft.recipe.StonecuttingRecipe target)) return -1;
        boolean current = client.world.getRecipeManager().listAllOfType(net.minecraft.recipe.RecipeType.STONECUTTING)
                .stream().anyMatch(recipe -> recipe == target);
        if (!current) return -1;
        ItemStack heldInput = handler.getSlot(0).getStack();
        if (heldInput.isEmpty() || !work.input().test(heldInput)) return -1;
        int match = -1;
        List<net.minecraft.recipe.StonecuttingRecipe> visible = handler.getAvailableRecipes();
        for (int index = 0; index < visible.size(); index++) {
            net.minecraft.recipe.StonecuttingRecipe candidate = visible.get(index);
            if (candidate != target) continue;
            if (match >= 0) return -1;
            List<Ingredient> ingredients = candidate.getIngredients();
            if (ingredients.size() != 1 || ingredients.get(0) != work.input()
                    || !ingredients.get(0).test(heldInput)) return -1;
            ItemStack actual = result(candidate, client.world.getRegistryManager());
            ItemStack expected = work.outputPerOperation();
            if (actual.isEmpty() || actual.getCount() != expected.getCount()
                    || !ItemStack.canCombine(actual, expected)) return -1;
            match = index;
        }
        return match;
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
