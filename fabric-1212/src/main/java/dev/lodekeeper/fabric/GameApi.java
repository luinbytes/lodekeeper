package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.StationId;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.FuelRegistry;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.display.FurnaceRecipeDisplay;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.recipe.display.SlotDisplayContexts;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Minecraft 1.21.2–1.21.11 recipe-display family. Native recipes and their remainder callbacks stay on the integrated
 * server thread. Remote crafting is limited to learned displays with consistent ingredient metadata and declared
 * remainder contracts; server-only custom remainder overrides cannot be inferred from an ordinary display.
 */
final class GameApi {
    record FoodInfo(int nutrition, float saturation, boolean safe) {}

    record SlotFacts(Set<ItemId> items, ItemStack remainder, boolean remainderDeclared,
                     boolean containsExplicitDeclaration, boolean empty) {
        SlotFacts {
            items = Set.copyOf(items);
            remainder = remainder.copy();
        }
        @Override public ItemStack remainder() { return remainder.copy(); }
    }

    private static final int MAX_UNSUPPORTED_DETAILS = 256;

    private GameApi() {}

    static boolean animalAttackWindow(net.minecraft.entity.LivingEntity animal) {
        return animal.timeUntilRegen <= 10 && animal.hurtTime <= 0;
    }

    static boolean supportsAnimalHarvest() { return false; }
    static dev.lodekeeper.core.ItemId sheepWool(net.minecraft.entity.passive.SheepEntity sheep) {
        return dev.lodekeeper.core.ItemId.parse("minecraft:" + sheep.getColor().getName() + "_wool");
    }
    static void shearAnimal(net.minecraft.client.MinecraftClient client, net.minecraft.entity.passive.SheepEntity sheep) {
        throw new IllegalStateException("Animal shearing is unsupported in this API family");
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
                player.isOnGround(), player.horizontalCollision));
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
        return WeaponWearApi.defenseAttackWear(stack);
    }

    static double defenseAttackDamage(net.minecraft.entity.player.PlayerEntity player, ItemStack stack) {
        var attribute = net.minecraft.entity.attribute.EntityAttributes.ATTACK_DAMAGE;
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

    static double blockReach(MinecraftClient client) { return client.player.getBlockInteractionRange(); }

    static Identifier identifier(String value) {
        int separator = value.indexOf(':');
        return separator < 0 ? Identifier.of("minecraft", value)
                : Identifier.of(value.substring(0, separator), value.substring(separator + 1));
    }

    static boolean canCombine(ItemStack first, ItemStack second) {
        return ItemStack.areItemsAndComponentsEqual(first, second);
    }

    static boolean hasSilkTouch(ItemStack stack) {
        var enchantments = stack.getEnchantments();
        return enchantments.getEnchantments().stream().anyMatch(enchantment ->
                enchantment.matchesKey(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchantment) > 0);
    }

    static long cookingFuelProgressTicks(Item fuel, StationId station, long rawBurnTicks) {
        if (fuel == null || !fuel.getRecipeRemainder().isEmpty() || rawBurnTicks < 1) return 0;
        long progress = switch (station == null ? "" : station.toString()) {
            case "minecraft:furnace" -> rawBurnTicks;
            case "minecraft:smoker", "minecraft:blast_furnace" -> rawBurnTicks / 2;
            default -> 0;
        };
        return progress <= 1_000_000_000L ? progress : 0;
    }

    private static StationId cookingStation(RecipeType<?> type) {
        if (type == RecipeType.SMELTING) return StationId.parse("minecraft:furnace");
        if (type == RecipeType.SMOKING) return StationId.parse("minecraft:smoker");
        if (type == RecipeType.BLASTING) return StationId.parse("minecraft:blast_furnace");
        return null;
    }

    /** Only an exact, single native block-item display can establish a remote station. */
    private static StationId cookingStation(SlotDisplay display) {
        if (!(display instanceof SlotDisplay.ItemSlotDisplay)
                && !(display instanceof SlotDisplay.StackSlotDisplay)) return null;
        SlotFacts shown = slotFacts(display);
        if (shown.empty() || shown.remainderDeclared() || !shown.remainder().isEmpty() || shown.items().size() != 1) {
            return null;
        }
        return switch (shown.items().iterator().next().toString()) {
            case "minecraft:furnace" -> StationId.parse("minecraft:furnace");
            case "minecraft:smoker" -> StationId.parse("minecraft:smoker");
            case "minecraft:blast_furnace" -> StationId.parse("minecraft:blast_furnace");
            default -> null;
        };
    }

    static Object recipeProviderIdentity(MinecraftClient client) {
        if (client.world == null) return null;
        IntegratedServer server = client.getServer();
        if (server != null) return server.getRecipeManager();
        return client.player == null ? null : client.player.getRecipeBook();
    }

    static Object stonecuttingProviderIdentity(MinecraftClient client) {
        return client.world == null ? null : client.world.getRecipeManager().getStonecutterRecipes();
    }

    static List<StonecuttingWork> stonecuttingRecipes(MinecraftClient client) {
        if (client.world == null) return List.of();
        List<net.minecraft.recipe.display.CuttingRecipeDisplay.GroupEntry<net.minecraft.recipe.StonecuttingRecipe>> entries =
                client.world.getRecipeManager().getStonecutterRecipes().entries();
        if (entries.size() > 4096) throw new IllegalArgumentException("stonecutting recipe source exceeds 4096 entries");
        List<StonecuttingWork> works = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            try {
                var entry = entries.get(index);
                Ingredient input = entry.input();
                if (input == null) continue;
                ItemStack output = stonecuttingDisplayOutput(entry.recipe().optionDisplay(), client.world);
                if (output.isEmpty()) continue;
                works.add(new StonecuttingWork("stonecutting:sync:" + index, input, output, entry));
            } catch (RuntimeException unsupportedRow) {
                // Reject only this malformed display row; preserve the remaining synced options.
            }
        }
        return List.copyOf(works);
    }

    static int stonecuttingRecipeIndex(MinecraftClient client, net.minecraft.screen.StonecutterScreenHandler handler,
                                       StonecuttingWork work) {
        if (client == null || client.world == null || handler == null || work == null
                || !(work.selectionKey() instanceof net.minecraft.recipe.display.CuttingRecipeDisplay.GroupEntry<?> selected)) return -1;
        var currentEntries = client.world.getRecipeManager().getStonecutterRecipes().entries();
        if (currentEntries.stream().noneMatch(entry -> entry == selected)) return -1;
        ItemStack heldInput = handler.getSlot(0).getStack();
        if (heldInput.isEmpty() || !work.input().test(heldInput)) return -1;
        int match = -1;
        var visible = handler.getAvailableRecipes().entries();
        for (int index = 0; index < visible.size(); index++) {
            var candidate = visible.get(index);
            if (candidate != selected) continue;
            if (match >= 0 || candidate.input() != work.input() || !candidate.input().test(heldInput)) return -1;
            ItemStack actual = stonecuttingDisplayOutput(candidate.recipe().optionDisplay(), client.world);
            ItemStack expected = work.outputPerOperation();
            if (actual.isEmpty() || actual.getCount() != expected.getCount()
                    || !ItemStack.areItemsAndComponentsEqual(actual, expected)) return -1;
            match = index;
        }
        return match;
    }

    private static ItemStack stonecuttingDisplayOutput(SlotDisplay display, World world) {
        List<ItemStack> alternatives = display.appendStacks(SlotDisplayContexts.createParameters(world),
                (net.minecraft.recipe.display.DisplayedItemFactory.FromStack<ItemStack>) ItemStack::copy)
                .limit(65).toList();
        if (alternatives.isEmpty() || alternatives.size() > 64 || alternatives.stream().anyMatch(ItemStack::isEmpty)) {
            return ItemStack.EMPTY;
        }
        ItemStack expected = alternatives.getFirst();
        if (expected.getCount() > 99) return ItemStack.EMPTY;
        for (int index = 1; index < alternatives.size(); index++) {
            ItemStack alternative = alternatives.get(index);
            if (alternative.getCount() != expected.getCount()
                    || !ItemStack.areItemsAndComponentsEqual(alternative, expected)) return ItemStack.EMPTY;
        }
        return expected.copy();
    }

    static void loadRecipes(MinecraftClient client, Consumer<RecipeCatalogSnapshot> publish) {
        ClientWorld expectedWorld = client.world;
        if (expectedWorld == null || client.player == null) {
            publish.accept(RecipeCatalogSnapshot.empty());
            return;
        }

        Map<ItemId, Long> fuels = fuelSnapshot(expectedWorld);
        IntegratedServer server = client.getServer();
        if (server == null) {
            publish.accept(remoteSnapshot(client.player.getRecipeBook(), fuels));
            return;
        }

        ServerRecipeManager expectedManager = server.getRecipeManager();
        try {
            server.execute(() -> {
                RecipeCatalogSnapshot snapshot;
                try {
                    if (server.getRecipeManager() != expectedManager) return;
                    snapshot = integratedSnapshot(client, expectedWorld, server, expectedManager, fuels);
                } catch (RuntimeException failure) {
                    snapshot = new RecipeCatalogSnapshot(Map.of(),
                            List.of("integrated recipe scan: " + message(failure)), fuels);
                }
                RecipeCatalogSnapshot completed = snapshot;
                client.execute(() -> {
                    if (client.world != expectedWorld || client.getServer() != server
                            || server.getRecipeManager() != expectedManager) return;
                    publish.accept(completed);
                });
            });
        } catch (RuntimeException failure) {
            publish.accept(new RecipeCatalogSnapshot(Map.of(),
                    List.of("integrated recipe scan could not be scheduled: " + message(failure)), fuels));
        }
    }

    static int blockBreakWear(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.item.ShearsItem) {
            if (stack.getItem().getClass() != net.minecraft.item.ShearsItem.class) return -1;
            var shearTool = stack.get(DataComponentTypes.TOOL);
            return shearTool == null ? 1 : Math.max(1, shearTool.damagePerBlock());
        }
        if (!stack.isDamageable()) return 0;
        var tool = stack.get(DataComponentTypes.TOOL);
        return tool == null ? -1 : tool.damagePerBlock();
    }

    static boolean isSword(ItemStack stack) { return stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS); }

    static boolean isAxe(ItemStack stack) { return stack.isIn(net.minecraft.registry.tag.ItemTags.AXES); }

    static int attackWear(ItemStack stack) { return WeaponWearApi.attackWear(stack); }

    static dev.lodekeeper.core.Ingredient ingredient(Ingredient ingredient) {
        List<ItemId> choices = new ArrayList<>();
        ingredient.getMatchingItems().forEach(entry -> choices.add(GameCatalog.id(entry.value())));
        return dev.lodekeeper.core.Ingredient.choices(choices.stream().distinct().sorted().toList(), 1);
    }

    static FoodInfo food(ItemStack stack) {
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        var consumable = stack.get(DataComponentTypes.CONSUMABLE);
        boolean safe = consumable != null && consumable.onConsumeEffects().stream()
                .allMatch(effect -> effect instanceof net.minecraft.item.consume.PlaySoundConsumeEffect);
        return food == null ? null : new FoodInfo(food.nutrition(), food.saturation(), safe);
    }

    private static Map<ItemId, Long> fuelSnapshot(World world) {
        FuelRegistry registry = world.getFuelRegistry();
        Map<ItemId, Long> fuels = new TreeMap<>();
        for (Item item : registry.getFuelItems()) {
            int ticks = registry.getFuelTicks(item.getDefaultStack());
            if (ticks > 0) fuels.put(GameCatalog.id(item), (long) ticks);
        }
        return Map.copyOf(fuels);
    }

    private static RecipeCatalogSnapshot integratedSnapshot(MinecraftClient client, ClientWorld expectedWorld,
                                                               IntegratedServer server, ServerRecipeManager manager,
                                                               Map<ItemId, Long> fuels) {
        Map<String, RecipeWork> works = new TreeMap<>();
        List<String> unsupported = new ArrayList<>();
        Collection<RecipeEntry<?>> entries = manager.values();
        for (RecipeEntry<?> entry : entries.stream().sorted(Comparator.comparing(value -> value.id().getValue().toString())).toList()) {
            Recipe<?> recipe = entry.value();
            String recipeId = entry.id().getValue().toString();
            List<RecipeDisplay> displays;
            try {
                displays = recipe.getDisplays();
            } catch (RuntimeException failure) {
                addUnsupported(unsupported, recipeId + ": display generation failed: " + message(failure));
                continue;
            }
            if (displays.isEmpty()) {
                addUnsupported(unsupported, recipeId + ": recipe provides no executable display");
                continue;
            }
            for (int displayIndex = 0; displayIndex < displays.size(); displayIndex++) {
                RecipeDisplay display = displays.get(displayIndex);
                String key = recipeId + "#display:" + displayIndex;
                try {
                    RecipeWork work = integratedWork(client, expectedWorld, server, manager, recipe, display);
                    if (work == null) {
                        addUnsupported(unsupported, key + ": unsupported recipe/display combination "
                                + recipe.getClass().getSimpleName() + "/" + display.getClass().getSimpleName());
                    } else if (works.putIfAbsent(key, work) != null) {
                        addUnsupported(unsupported, key + ": duplicate recipe display key");
                    }
                } catch (RuntimeException failure) {
                    addUnsupported(unsupported, key + ": " + message(failure));
                }
            }
        }
        return new RecipeCatalogSnapshot(works, unsupported, fuels);
    }

    private static RecipeWork integratedWork(MinecraftClient client, ClientWorld expectedWorld,
                                             IntegratedServer server, ServerRecipeManager manager,
                                             Recipe<?> recipe, RecipeDisplay display) {
        if (recipe instanceof ShapedRecipe shaped && display instanceof ShapedCraftingRecipeDisplay shapedDisplay) {
            if (shaped.getWidth() != shapedDisplay.width() || shaped.getHeight() != shapedDisplay.height()) {
                throw new IllegalArgumentException("display dimensions disagree with the integrated recipe");
            }
            List<Optional<Ingredient>> nativeSlots = shaped.getIngredients();
            if (nativeSlots.size() != shaped.getWidth() * shaped.getHeight()
                    || shapedDisplay.ingredients().size() != nativeSlots.size()) {
                throw new IllegalArgumentException("shaped recipe does not provide a complete grid");
            }
            List<RecipeWork.Input> inputs = new ArrayList<>();
            for (int slot = 0; slot < nativeSlots.size(); slot++) {
                SlotFacts shown = slotFacts(shapedDisplay.ingredients().get(slot));
                Optional<Ingredient> actual = nativeSlots.get(slot);
                if (actual.isEmpty()) {
                    if (!shown.empty() || !shown.remainder().isEmpty()) {
                        throw new IllegalArgumentException("display fills native shaped-recipe hole " + slot);
                    }
                    continue;
                }
                if (shown.empty() || !nativeItems(actual.get()).equals(shown.items())) {
                    throw new IllegalArgumentException("display ingredient does not match native shaped slot " + slot);
                }
                inputs.add(new RecipeWork.Input(slot, actual.get()));
            }
            if (inputs.isEmpty()) throw new IllegalArgumentException("shaped recipe has no inputs");
            ItemStack output = outputStack(shapedDisplay.result());
            return new RecipeWork(RecipeWork.Kind.SHAPED_CRAFTING, output, shaped.getWidth(), shaped.getHeight(),
                    inputs, 0, serverRemainderResolver(client, expectedWorld, server, manager, (CraftingRecipe) recipe));
        }

        if (recipe instanceof ShapelessRecipe && display instanceof ShapelessCraftingRecipeDisplay shapelessDisplay) {
            List<Ingredient> nativeInputs = recipe.getIngredientPlacement().getIngredients();
            if (nativeInputs.isEmpty() || nativeInputs.size() > 9
                    || shapelessDisplay.ingredients().size() != nativeInputs.size()) {
                throw new IllegalArgumentException("shapeless display and native ingredient counts disagree");
            }
            List<Ingredient> remaining = new ArrayList<>(nativeInputs);
            List<RecipeWork.Input> inputs = new ArrayList<>();
            for (int slot = 0; slot < shapelessDisplay.ingredients().size(); slot++) {
                SlotFacts shown = slotFacts(shapelessDisplay.ingredients().get(slot));
                if (shown.empty()) throw new IllegalArgumentException("shapeless recipe contains an empty ingredient slot");
                inputs.add(new RecipeWork.Input(slot, takeMatching(shown.items(), remaining)));
            }
            if (!remaining.isEmpty()) throw new IllegalArgumentException("shapeless display omitted native ingredients");
            return new RecipeWork(RecipeWork.Kind.SHAPELESS_CRAFTING, outputStack(shapelessDisplay.result()), 0, 0,
                    inputs, 0, serverRemainderResolver(client, expectedWorld, server, manager, (CraftingRecipe) recipe));
        }

        if (recipe instanceof AbstractCookingRecipe cooking && display instanceof FurnaceRecipeDisplay furnaceDisplay) {
            StationId nativeStation = cookingStation(recipe.getType());
            StationId displayStation = cookingStation(furnaceDisplay.craftingStation());
            if (nativeStation == null || !nativeStation.equals(displayStation)) {
                throw new IllegalArgumentException("furnace display station disagrees with its native recipe type");
            }
            List<Ingredient> nativeInputs = cooking.getIngredientPlacement().getIngredients();
            SlotFacts shown = slotFacts(furnaceDisplay.ingredient());
            if (nativeInputs.size() != 1 || shown.empty() || !shown.remainder().isEmpty()
                    || !nativeItems(nativeInputs.get(0)).equals(shown.items())) {
                throw new IllegalArgumentException("furnace display does not match its native ingredient");
            }
            if (furnaceDisplay.duration() != cooking.getCookingTime() || furnaceDisplay.duration() < 1) {
                throw new IllegalArgumentException("furnace display duration disagrees with its native recipe");
            }
            return new RecipeWork(RecipeWork.Kind.SMELTING, outputStack(furnaceDisplay.result()), 0, 0,
                    List.of(new RecipeWork.Input(-1, nativeInputs.get(0))), furnaceDisplay.duration(),
                    nativeStation, null);
        }
        return null;
    }

    private static RecipeWork.RemainderResolver serverRemainderResolver(MinecraftClient client, ClientWorld world,
                                                                         IntegratedServer server,
                                                                         ServerRecipeManager manager,
                                                                         CraftingRecipe recipe) {
        return (handler, gridWidth, inputGrid) -> {
            if (client.world != world || client.getServer() != server || server.getRecipeManager() != manager) {
                return CompletableFuture.failedFuture(new IllegalStateException("world or recipe generation changed before server remainder lookup"));
            }
            if (inputGrid.size() != gridWidth * gridWidth) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("incomplete concrete crafting grid"));
            }
            List<ItemStack> concreteGrid = inputGrid.stream().map(ItemStack::copy).toList();
            CompletableFuture<List<ItemStack>> result = new CompletableFuture<>();
            try {
                server.execute(() -> {
                    List<ItemStack> expanded;
                    try {
                        if (server.getRecipeManager() != manager) {
                            throw new IllegalStateException("integrated recipe manager was replaced before remainder lookup");
                        }
                        CraftingRecipeInput.Positioned positioned = CraftingRecipeInput.createPositioned(
                                gridWidth, gridWidth, concreteGrid);
                        List<ItemStack> recipeRemainders = recipe.getRecipeRemainders(positioned.input());
                        expanded = RecipeWork.expandPositionedRemainders(gridWidth, gridWidth,
                                positioned.left(), positioned.top(), positioned.input().getWidth(),
                                positioned.input().getHeight(), recipeRemainders);
                        expanded = expanded.stream().map(ItemStack::copy).toList();
                        if (server.getRecipeManager() != manager) {
                            throw new IllegalStateException("integrated recipes changed during remainder lookup");
                        }
                    } catch (Throwable failure) {
                        completeExceptionallyOnClient(client, result, failure);
                        return;
                    }
                    List<ItemStack> safeRemainders = expanded;
                    client.execute(() -> {
                        if (client.world != world || client.getServer() != server
                                || server.getRecipeManager() != manager) {
                            result.completeExceptionally(new IllegalStateException(
                                    "world or recipe generation changed during remainder lookup"));
                        } else {
                            result.complete(safeRemainders);
                        }
                    });
                });
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
            return result;
        };
    }

    private static void completeExceptionallyOnClient(MinecraftClient client,
                                                       CompletableFuture<List<ItemStack>> future,
                                                       Throwable failure) {
        try {
            client.execute(() -> future.completeExceptionally(failure));
        } catch (RuntimeException rejected) {
            future.completeExceptionally(failure);
        }
    }

    private static RecipeCatalogSnapshot remoteSnapshot(ClientRecipeBook book, Map<ItemId, Long> fuels) {
        Map<String, RecipeWork> works = new TreeMap<>();
        List<String> unsupported = new ArrayList<>();
        List<RecipeDisplayEntry> entries = book.getOrderedResults().stream()
                .flatMap((RecipeResultCollection group) -> group.getAllRecipes().stream())
                .sorted(Comparator.comparing(entry -> entry.id().toString())).toList();
        for (RecipeDisplayEntry entry : entries) {
            String key = "learned:" + entry.id();
            try {
                RecipeWork work = remoteWork(entry);
                if (work == null) {
                    addUnsupported(unsupported, key + ": unsupported learned display "
                            + entry.display().getClass().getSimpleName());
                } else if (works.putIfAbsent(key, work) != null) {
                    addUnsupported(unsupported, key + ": duplicate learned recipe key");
                }
            } catch (RuntimeException failure) {
                addUnsupported(unsupported, key + ": " + message(failure));
            }
        }
        return new RecipeCatalogSnapshot(works, unsupported, fuels);
    }

    private static RecipeWork remoteWork(RecipeDisplayEntry entry) {
        RecipeDisplay display = entry.display();
        List<Ingredient> requirements = entry.craftingRequirements().orElseThrow(
                () -> new IllegalArgumentException("server did not send native ingredient requirements"));
        if (requirements.isEmpty() || requirements.size() > 9) {
            throw new IllegalArgumentException("invalid compact ingredient requirement count");
        }
        List<Ingredient> remaining = new ArrayList<>(requirements);

        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            int width = shaped.width(), height = shaped.height();
            if (width < 1 || height < 1 || width > 3 || height > 3 || width * height > 9
                    || shaped.ingredients().size() != width * height) {
                throw new IllegalArgumentException("invalid shaped display dimensions or grid");
            }
            List<RecipeWork.Input> inputs = new ArrayList<>();
            Map<Integer, ItemStack> remainders = new HashMap<>();
            for (int slot = 0; slot < shaped.ingredients().size(); slot++) {
                SlotFacts shown = slotFacts(shaped.ingredients().get(slot));
                if (shown.empty()) {
                    if (!shown.remainder().isEmpty()) throw new IllegalArgumentException("empty shaped cell declares a remainder");
                    continue;
                }
                requireRemoteRemainderContract(shown);
                inputs.add(new RecipeWork.Input(slot, takeMatching(shown.items(), remaining)));
                if (!shown.remainder().isEmpty()) remainders.put(slot, shown.remainder());
            }
            if (inputs.isEmpty() || !remaining.isEmpty()) {
                throw new IllegalArgumentException("shaped display and compact native requirements do not match");
            }
            return new RecipeWork(RecipeWork.Kind.SHAPED_CRAFTING, outputStack(shaped.result()), width, height,
                    inputs, 0, declaredRemainders(true, width, height, inputs.size(), remainders));
        }

        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            if (shapeless.ingredients().isEmpty() || shapeless.ingredients().size() > 9
                    || shapeless.ingredients().size() != remaining.size()) {
                throw new IllegalArgumentException("invalid shapeless display or compact requirement count");
            }
            List<RecipeWork.Input> inputs = new ArrayList<>();
            Map<Integer, ItemStack> remainders = new HashMap<>();
            for (int slot = 0; slot < shapeless.ingredients().size(); slot++) {
                SlotFacts shown = slotFacts(shapeless.ingredients().get(slot));
                if (shown.empty()) throw new IllegalArgumentException("shapeless display contains an empty slot");
                requireRemoteRemainderContract(shown);
                inputs.add(new RecipeWork.Input(slot, takeMatching(shown.items(), remaining)));
                if (!shown.remainder().isEmpty()) remainders.put(slot, shown.remainder());
            }
            if (!remaining.isEmpty()) throw new IllegalArgumentException("shapeless display left unmatched native requirements");
            return new RecipeWork(RecipeWork.Kind.SHAPELESS_CRAFTING, outputStack(shapeless.result()), 0, 0,
                    inputs, 0, declaredRemainders(false, 0, 0, inputs.size(), remainders));
        }

        if (display instanceof FurnaceRecipeDisplay furnace) {
            StationId station = cookingStation(furnace.craftingStation());
            SlotFacts shown = slotFacts(furnace.ingredient());
            if (station == null || shown.empty() || !shown.remainder().isEmpty() || furnace.duration() < 1
                    || remaining.size() != 1) {
                throw new IllegalArgumentException("furnace display station or compact native requirement is unsupported");
            }
            Ingredient nativeInput = takeMatching(shown.items(), remaining);
            if (!remaining.isEmpty()) {
                throw new IllegalArgumentException("furnace display left unmatched native requirements");
            }
            return new RecipeWork(RecipeWork.Kind.SMELTING, outputStack(furnace.result()), 0, 0,
                    List.of(new RecipeWork.Input(-1, nativeInput)), furnace.duration(), station, null);
        }
        return null;
    }

    private static RecipeWork.RemainderResolver declaredRemainders(boolean shaped, int width, int height,
                                                                    int inputCount,
                                                                    Map<Integer, ItemStack> byRecipeSlot) {
        Map<Integer, ItemStack> declared = new HashMap<>();
        byRecipeSlot.forEach((slot, stack) -> declared.put(slot, stack.copy()));
        return (handler, gridWidth, inputGrid) -> {
            if (inputGrid.size() != gridWidth * gridWidth) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("incomplete concrete crafting grid"));
            }
            List<ItemStack> fullGrid = new ArrayList<>(Collections.nCopies(gridWidth * gridWidth, ItemStack.EMPTY));
            declared.forEach((slot, remainder) -> {
                int gridSlot = RecipeGridLayout.craftingSlot(shaped, width, height, slot, inputCount, gridWidth);
                fullGrid.set(gridSlot, remainder.copy());
            });
            return CompletableFuture.completedFuture(fullGrid);
        };
    }

    static SlotFacts slotFacts(SlotDisplay display) {
        return slotFacts(display, 0, new int[]{256});
    }

    private static SlotFacts slotFacts(SlotDisplay display, int depth, int[] remainingNodes) {
        if (depth > 8 || --remainingNodes[0] < 0) {
            throw new IllegalArgumentException("ingredient display exceeds bounded nesting or alternative count");
        }
        ItemStack remainder = ItemStack.EMPTY;
        SlotDisplay input = display;
        boolean remainderDeclared = false;
        if (display instanceof SlotDisplay.WithRemainderSlotDisplay withRemainder) {
            input = withRemainder.input();
            if (input instanceof SlotDisplay.WithRemainderSlotDisplay) {
                throw new IllegalArgumentException("nested remainder displays are not supported");
            }
            remainder = concreteDisplayStack(withRemainder.remainder());
            if (remainder == null) throw new IllegalArgumentException("remainder display is not an exact item stack");
            remainderDeclared = true;
        }
        if (input instanceof SlotDisplay.CompositeSlotDisplay composite) {
            if (composite.contents().isEmpty()) throw new IllegalArgumentException("ingredient display has no alternatives");
            Set<ItemId> alternatives = new TreeSet<>();
            ItemStack commonRemainder = null;
            boolean allDeclared = true;
            boolean anyDeclared = false;
            for (SlotDisplay child : composite.contents()) {
                SlotFacts choice = slotFacts(child, depth + 1, remainingNodes);
                if (choice.empty()) throw new IllegalArgumentException("ingredient alternative is an empty slot");
                alternatives.addAll(choice.items());
                if (commonRemainder == null) commonRemainder = choice.remainder();
                else if (!sameRemainder(commonRemainder, choice.remainder())) {
                    throw new IllegalArgumentException("ingredient alternatives have different remainder contracts");
                }
                allDeclared &= choice.remainderDeclared();
                anyDeclared |= choice.containsExplicitDeclaration();
            }
            if (remainderDeclared) {
                if ((anyDeclared || !commonRemainder.isEmpty()) && !sameRemainder(remainder, commonRemainder)) {
                    throw new IllegalArgumentException("outer remainder disagrees with ingredient alternative");
                }
            } else {
                remainder = commonRemainder;
                remainderDeclared = allDeclared;
            }
            return new SlotFacts(alternatives, remainder, remainderDeclared, remainderDeclared || anyDeclared, false);
        }
        if (input instanceof SlotDisplay.EmptySlotDisplay) return new SlotFacts(Set.of(), remainder, remainderDeclared, remainderDeclared, true);
        if (input instanceof SlotDisplay.ItemSlotDisplay item) {
            return new SlotFacts(Set.of(GameCatalog.id(item.item().value())), remainder, remainderDeclared, remainderDeclared, false);
        }
        if (input instanceof SlotDisplay.StackSlotDisplay stack) {
            if (stack.stack().isEmpty()) throw new IllegalArgumentException("empty stack display is not an ingredient");
            return new SlotFacts(Set.of(GameCatalog.id(stack.stack().getItem())), remainder, remainderDeclared, remainderDeclared, false);
        }
        if (input instanceof SlotDisplay.TagSlotDisplay tag) {
            Set<ItemId> allowed = new TreeSet<>();
            Registries.ITEM.iterateEntries(tag.tag()).forEach(entry -> allowed.add(GameCatalog.id(entry.value())));
            if (allowed.isEmpty()) throw new IllegalArgumentException("tag display resolves to no registered items");
            return new SlotFacts(allowed, remainder, remainderDeclared, remainderDeclared, false);
        }
        throw new IllegalArgumentException("unsupported slot display " + input.getClass().getSimpleName());
    }

    private static boolean sameRemainder(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        return first.getCount() == second.getCount() && canCombine(first, second);
    }

    private static void requireRemoteRemainderContract(SlotFacts shown) {
        if (shown.remainderDeclared()) return;
        for (ItemId id : shown.items()) {
            ItemStack itemRemainder = GameCatalog.item(id).getRecipeRemainder();
            if (!itemRemainder.isEmpty()) {
                throw new IllegalArgumentException("learned display omits remainder metadata for " + id
                        + ", whose item has a default crafting remainder");
            }
        }
    }

    private static ItemStack concreteDisplayStack(SlotDisplay display) {
        if (display instanceof SlotDisplay.EmptySlotDisplay) return ItemStack.EMPTY;
        if (display instanceof SlotDisplay.ItemSlotDisplay item) return item.item().value().getDefaultStack();
        if (display instanceof SlotDisplay.StackSlotDisplay stack) return stack.stack().copy();
        return null;
    }

    private static ItemStack outputStack(SlotDisplay display) {
        ItemStack stack = concreteDisplayStack(display);
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException("result display is not an exact item stack");
        return stack.copy();
    }

    private static Set<ItemId> nativeItems(Ingredient ingredient) {
        Set<ItemId> result = new TreeSet<>();
        ingredient.getMatchingItems().forEach(entry -> result.add(GameCatalog.id(entry.value())));
        return Set.copyOf(result);
    }

    private static Ingredient takeMatching(Set<ItemId> shownItems, List<Ingredient> remaining) {
        for (int index = 0; index < remaining.size(); index++) {
            Ingredient candidate = remaining.get(index);
            if (nativeItems(candidate).equals(shownItems)) return remaining.remove(index);
        }
        throw new IllegalArgumentException("display item set does not match any unused native predicate");
    }

    private static void addUnsupported(List<String> unsupported, String detail) {
        if (unsupported.size() < MAX_UNSUPPORTED_DETAILS) unsupported.add(detail);
        else if (unsupported.size() == MAX_UNSUPPORTED_DETAILS) unsupported.add("additional unsupported recipes omitted");
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
