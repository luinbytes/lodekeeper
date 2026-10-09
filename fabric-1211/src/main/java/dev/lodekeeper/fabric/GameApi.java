package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.StationId;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.enchantment.Enchantments;
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
import net.minecraft.recipe.input.CraftingRecipeInput;
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

/** Minecraft 1.21.1 names for the shared client adapter's narrow compatibility surface. */
final class GameApi {
    record RecipeRef(String id, Recipe<?> recipe) {}
    record FoodInfo(int nutrition, float saturation, boolean safe) {}

    private GameApi() {}

    static boolean cropMovementInputWitness(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                                           dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session,
                                           Object player, Object observedInput, Object installedInput, Object predecessor) {
        return supportsCropHarvest() && owner != null && owner.getPrimaryBaritone() != null
                && owner.getPrimaryBaritone().getInputOverrideHandler()
                    instanceof dev.lodekeeper.navigation.kernel.utils.InputOverrideHandler handler
                && handler.hasMovementInputWitness(owner, session, player, observedInput, installedInput, predecessor);
    }
    static Object airMovementInputPredecessor(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                                              dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session,
                                              Object player, Object observedInput) {
        return supportsCropHarvest() && owner != null && owner.getPrimaryBaritone() != null
                && owner.getPrimaryBaritone().getInputOverrideHandler()
                    instanceof dev.lodekeeper.navigation.kernel.utils.InputOverrideHandler handler
                ? handler.movementInputPredecessor(owner, session, player, observedInput) : null;
    }
    static boolean supportsCropHarvest() { return "1.21.1".equals(net.minecraft.SharedConstants.getGameVersion().getName()); }
    static boolean cropMatches(net.minecraft.block.BlockState state, dev.lodekeeper.core.NativeWork.CropKind kind) {
        return state.isOf(switch (kind) {
            case WHEAT -> net.minecraft.block.Blocks.WHEAT;
            case CARROT -> net.minecraft.block.Blocks.CARROTS;
            case POTATO -> net.minecraft.block.Blocks.POTATOES;
            case BEETROOT -> net.minecraft.block.Blocks.BEETROOTS;
        });
    }
    static boolean cropMature(net.minecraft.block.BlockState state, dev.lodekeeper.core.NativeWork.CropKind kind) {
        return cropMatches(state, kind) && ((net.minecraft.block.CropBlock) state.getBlock()).isMature(state);
    }
    static boolean cropReplanted(net.minecraft.block.BlockState state, dev.lodekeeper.core.NativeWork.CropKind kind) {
        return cropMatches(state, kind) && ((net.minecraft.block.CropBlock) state.getBlock()).getAge(state) == 0;
    }

    static boolean cropInstantBreak(net.minecraft.client.MinecraftClient client, net.minecraft.util.math.BlockPos position, net.minecraft.block.BlockState state) {
        return state.getHardness(client.world, position) == 0.0f;
    }
    static void sendCropBreak(net.minecraft.client.MinecraftClient client, net.minecraft.util.math.BlockPos position, net.minecraft.util.math.Direction face) {
        client.interactionManager.attackBlock(position, face);
    }
    static void sendCropPlant(net.minecraft.client.MinecraftClient client, net.minecraft.util.hit.BlockHitResult hit) {
        client.interactionManager.interactBlock(client.player, net.minecraft.util.Hand.MAIN_HAND, hit);
    }

    static boolean supportsTravel() { return "1.21.1".equals(net.minecraft.SharedConstants.getGameVersion().getName()); }
    static java.util.UUID resolveTravelPlayer(net.minecraft.client.MinecraftClient client, String selector) {
        var player = travelPlayerCensus(client, selector, null);
        if (player == null) throw new IllegalArgumentException("Travel requires one unique live player and a complete bounded census");
        return player.getUuid();
    }
    static net.minecraft.entity.Entity loadedTravelPlayer(net.minecraft.client.MinecraftClient client, java.util.UUID id) {
        return id == null ? null : travelPlayerCensus(client, null, id);
    }
    private static net.minecraft.entity.Entity travelPlayerCensus(net.minecraft.client.MinecraftClient client, String selector, java.util.UUID pinned) {
        if (!supportsTravel() || client.player == null || client.world == null || client.getNetworkHandler() == null) return null;
        var world = client.world;
        var local = client.player;
        var network = client.getNetworkHandler();
        var connection = network.getConnection();
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        if (connection == null || !connection.isOpen() || owner == null || !owner.isCurrent(session)
                || session.world() != world) return null;
        long deadline = System.nanoTime() + 2_000_000L;
        int entries = 0;
        java.util.Set<java.util.UUID> profileIds = new java.util.HashSet<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<java.util.UUID> loadedIds = new java.util.HashSet<>();
        java.util.UUID selected = pinned;
        net.minecraft.entity.Entity found = null;
        try {
            for (var entry : network.getPlayerList()) {
                if (++entries > 128 || System.nanoTime() - deadline >= 0 || entry == null) return null;
                var profile = entry.getProfile();
                if (profile == null || profile.getId() == null || profile.getName() == null
                        || !profileIds.add(profile.getId()) || !names.add(profile.getName())) return null;
                if (selector != null && (selector.equals(profile.getName()) || selector.equals(profile.getId().toString()))) {
                    if (selected != null) return null;
                    selected = profile.getId();
                }
            }
            for (var player : world.getPlayers()) {
                if (++entries > 128 || System.nanoTime() - deadline >= 0 || player == null
                        || player.getUuid() == null || !loadedIds.add(player.getUuid())) return null;
                if (selected != null && selected.equals(player.getUuid())) found = player;
            }
        } catch (RuntimeException incomplete) { return null; }
        if (System.nanoTime() - deadline >= 0 || selected == null || !profileIds.contains(selected)
                || found == null || found == local || selected.equals(local.getUuid()) || !found.isAlive() || found.isRemoved()
                || found.getWorld() != world || client.world != world || client.player != local
                || client.getNetworkHandler() != network || network.getConnection() != connection || !connection.isOpen()
                || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != owner || !owner.isCurrent(session)) return null;
        double distance = found.squaredDistanceTo(local);
        return System.nanoTime() - deadline < 0 && Double.isFinite(distance) && distance <= 4096 ? found : null;
    }
    static boolean travelPose(net.minecraft.client.MinecraftClient client) {
        return supportsTravel() && client.player != null && client.world != null && client.player.isAlive()
                && !client.player.hasVehicle() && !client.player.isSleeping() && !client.player.getAbilities().flying
                && !client.player.isFallFlying() && !client.player.isTouchingWater() && !client.player.isClimbing();
    }
    static boolean travelBounds(net.minecraft.client.MinecraftClient client, net.minecraft.util.math.BlockPos feet) {
        return supportsTravel() && client.world != null && feet.getY() >= client.world.getBottomY()
                && feet.getY() < client.world.getTopY() && client.world.getWorldBorder().contains(feet);
    }


    static boolean animalAttackWindow(net.minecraft.entity.LivingEntity animal) {
        return animal.timeUntilRegen <= 10 && animal.hurtTime <= 0;
    }

    static boolean supportsAnimalHarvest() { return "1.21.1".equals(net.minecraft.SharedConstants.getGameVersion().getName()); }
    static dev.lodekeeper.core.ItemId sheepWool(net.minecraft.entity.passive.SheepEntity sheep) {
        return dev.lodekeeper.core.ItemId.parse("minecraft:" + sheep.getColor().getName() + "_wool");
    }
    static void shearAnimal(net.minecraft.client.MinecraftClient client, net.minecraft.entity.passive.SheepEntity sheep) {
        client.interactionManager.interactEntity(client.player, sheep, net.minecraft.util.Hand.MAIN_HAND);
    }



    static boolean ordinaryShield(ItemStack stack) {
        if (stack.isEmpty() || !stack.isOf(net.minecraft.item.Items.SHIELD) || stack.getCount() != 1
                || !stack.isDamageable() || stack.hasEnchantments() || hasCustomName(stack)
                || stack.getMaxDamage() - stack.getDamage() <= 100) return false;
        ItemStack normalized = stack.copy(), ordinary = new ItemStack(net.minecraft.item.Items.SHIELD);
        normalized.setDamage(0);
        ordinary.setDamage(0);
        return canCombine(normalized, ordinary);
    }

    static boolean sameShield(ItemStack current, ItemStack expected) {
        if (current.isEmpty() || expected.isEmpty() || current.getCount() != 1 || expected.getCount() != 1
                || !current.isOf(net.minecraft.item.Items.SHIELD) || current.getDamage() < expected.getDamage()) return false;
        ItemStack first = current.copy(), second = expected.copy();
        first.setDamage(0);
        second.setDamage(0);
        return canCombine(first, second);
    }

    static boolean startShieldUse(net.minecraft.client.MinecraftClient client) {
        return client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.OFF_HAND).isAccepted()
                && client.player.isUsingItem() && client.player.getActiveHand() == net.minecraft.util.Hand.OFF_HAND;
    }

    static boolean ownsShieldUse(net.minecraft.entity.player.PlayerEntity player, ItemStack expected) {
        return player.isUsingItem() && player.getActiveHand() == net.minecraft.util.Hand.OFF_HAND
                && sameShield(player.getActiveItem(), expected) && sameShield(player.getOffHandStack(), expected);
    }
    static boolean isHostileMob(net.minecraft.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.entity.mob.MobEntity mob)
                || !(entity instanceof net.minecraft.entity.mob.Monster)
                || entity instanceof net.minecraft.entity.mob.Angerable
                || entity instanceof net.minecraft.entity.mob.PiglinEntity) return false;
        return !(entity instanceof net.minecraft.entity.mob.SpiderEntity)
                || mob.getTarget() != null || mob.getBrightnessAtEyes() < 0.5f;
    }

    static void attackAirborneForDefense(net.minecraft.client.MinecraftClient client, net.minecraft.entity.Entity target) {
        var player = client.player;
        if (player == null || client.interactionManager == null || player.isOnGround())
            throw new IllegalStateException("airborne defense pose is unavailable");
        player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.OnGroundOnly(
                player.isOnGround()));
        client.interactionManager.attackEntity(player, target);
    }

    static double defenseReach(net.minecraft.entity.player.PlayerEntity player) { return player.getEntityInteractionRange(); }

    static boolean defenseWithinReach(net.minecraft.entity.player.PlayerEntity player, net.minecraft.entity.Entity target) {
        double reach = defenseReach(player);
        return Double.isFinite(reach) && reach > 0 && player.canInteractWithEntity(target, 0.0);
    }

    static boolean defenseHasSweepCollateral(net.minecraft.world.World world, net.minecraft.entity.player.PlayerEntity player, net.minecraft.entity.Entity target) {
        java.util.List<net.minecraft.entity.LivingEntity> nearby = new java.util.ArrayList<>();
        world.collectEntitiesByType(net.minecraft.util.TypeFilter.instanceOf(net.minecraft.entity.LivingEntity.class),
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
            player.getMainHandStack().applyAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND, (type, modifier) -> {
                if (type.equals(attribute)) trial.removeModifier(modifier);
            });
            stack.applyAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND, (type, modifier) -> {
                if (type.equals(attribute)) trial.addTemporaryModifier(modifier);
            });
            double value = trial.getValue();
            return Double.isFinite(value) && value > 0 ? value : Double.NaN;
        } catch (IllegalArgumentException unsupportedModifiers) {
            return Double.NaN;
        }
    }

    static boolean hasCustomName(ItemStack stack) { return stack.get(DataComponentTypes.CUSTOM_NAME) != null; }

    static double blockReach(net.minecraft.client.MinecraftClient client) { return client.player.getBlockInteractionRange(); }

    static Identifier identifier(String value) {
        int separator = value.indexOf(':');
        return separator < 0 ? Identifier.of("minecraft", value)
                : Identifier.of(value.substring(0, separator), value.substring(separator + 1));
    }

    static boolean canCombine(ItemStack first, ItemStack second) { return ItemStack.areItemsAndComponentsEqual(first, second); }

    static boolean hasSilkTouch(ItemStack stack) {
        var enchantments = stack.getEnchantments();
        return enchantments.getEnchantments().stream().anyMatch(enchantment ->
                enchantment.matchesKey(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchantment) > 0);
    }

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

    static boolean isSword(ItemStack stack) { return stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS); }

    static boolean isAxe(ItemStack stack) { return stack.isIn(net.minecraft.registry.tag.ItemTags.AXES); }

    /** Vanilla swords lose 1 durability and axes lose 2 when their native attack hook succeeds. */
    static int attackWear(ItemStack stack) {
        if (!stack.isDamageable()) return 0;
        Class<?> type = stack.getItem().getClass();
        if (type == net.minecraft.item.SwordItem.class) return 1;
        if (type == net.minecraft.item.AxeItem.class) return 2;
        return -1;
    }

    static ItemStack result(Recipe<?> recipe, DynamicRegistryManager registries) { return recipe.getResult(registries); }

    static int cookingTime(AbstractCookingRecipe recipe) { return recipe.getCookingTime(); }

    private static StationId cookingStation(AbstractCookingRecipe recipe) {
        if (recipe.getType() == RecipeType.SMELTING) return StationId.parse("minecraft:furnace");
        if (recipe.getType() == RecipeType.SMOKING) return StationId.parse("minecraft:smoker");
        if (recipe.getType() == RecipeType.BLASTING) return StationId.parse("minecraft:blast_furnace");
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
            List<ItemStack> copied = inputGrid.stream().map(ItemStack::copy).toList();
            CraftingRecipeInput.Positioned positioned = CraftingRecipeInput.createPositioned(gridWidth, gridWidth, copied);
            CraftingRecipeInput input = positioned.input();
            @SuppressWarnings("rawtypes") Recipe raw = recipe;
            return CompletableFuture.completedFuture(RecipeWork.expandPositionedRemainders(gridWidth, gridWidth,
                    positioned.left(), positioned.top(), input.getWidth(), input.getHeight(), raw.getRemainder(input)));
        };
    }

    static List<RecipeRef> recipes(RecipeManager manager) {
        return manager.values().stream().map((RecipeEntry<?> entry) -> new RecipeRef(entry.id().toString(), entry.value())).toList();
    }

    static Object recipeProviderIdentity(net.minecraft.client.MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static Object stonecuttingProviderIdentity(net.minecraft.client.MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager();
    }

    static List<StonecuttingWork> stonecuttingRecipes(net.minecraft.client.MinecraftClient client) {
        if (client.world == null) return List.of();
        net.minecraft.recipe.RecipeManager manager = client.world.getRecipeManager();
        List<RecipeEntry<net.minecraft.recipe.StonecuttingRecipe>> entries = manager
                .listAllOfType(RecipeType.STONECUTTING);
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

    static int stonecuttingRecipeIndex(net.minecraft.client.MinecraftClient client,
                                       net.minecraft.screen.StonecutterScreenHandler handler, StonecuttingWork work) {
        if (client == null || client.world == null || handler == null || work == null
                || !(work.selectionKey() instanceof RecipeEntry<?> selected)
                || !(selected.value() instanceof net.minecraft.recipe.StonecuttingRecipe target)) return -1;
        boolean current = client.world.getRecipeManager().listAllOfType(RecipeType.STONECUTTING)
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
                    || !ItemStack.areItemsAndComponentsEqual(actual, expected)) return -1;
            match = index;
        }
        return match;
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
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        return food == null ? null : new FoodInfo(food.nutrition(), food.saturation(), food.effects().isEmpty());
    }
}
