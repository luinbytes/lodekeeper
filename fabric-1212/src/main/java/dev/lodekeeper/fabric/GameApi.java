package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
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

    private record SlotFacts(Set<ItemId> items, ItemStack remainder, boolean remainderDeclared, boolean empty) {
        private SlotFacts {
            items = Set.copyOf(items);
            remainder = remainder.copy();
        }
        @Override public ItemStack remainder() { return remainder.copy(); }
    }

    private static final int MAX_UNSUPPORTED_DETAILS = 256;

    private GameApi() {}

    static double blockReach(MinecraftClient client) { return client.player.getBlockInteractionRange(); }

    static Identifier identifier(String value) {
        int separator = value.indexOf(':');
        return separator < 0 ? Identifier.of("minecraft", value)
                : Identifier.of(value.substring(0, separator), value.substring(separator + 1));
    }

    static boolean canCombine(ItemStack first, ItemStack second) {
        return ItemStack.areItemsAndComponentsEqual(first, second);
    }

    static Object recipeProviderIdentity(MinecraftClient client) {
        if (client.world == null) return null;
        IntegratedServer server = client.getServer();
        if (server != null) return server.getRecipeManager();
        return client.player == null ? null : client.player.getRecipeBook();
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
        for (RecipeEntry<?> entry : entries.stream().sorted(Comparator.comparing(value -> value.id().toString())).toList()) {
            Recipe<?> recipe = entry.value();
            String recipeId = entry.id().toString();
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

        if (recipe instanceof AbstractCookingRecipe cooking && recipe.getType() == RecipeType.SMELTING
                && display instanceof FurnaceRecipeDisplay furnaceDisplay) {
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
                    List.of(new RecipeWork.Input(-1, nativeInputs.get(0))), furnaceDisplay.duration(), null);
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
            SlotFacts shown = slotFacts(furnace.ingredient());
            if (shown.empty() || !shown.remainder().isEmpty() || furnace.duration() < 1
                    || remaining.size() != 1) {
                throw new IllegalArgumentException("furnace display and compact native requirement do not match");
            }
            Ingredient nativeInput = takeMatching(shown.items(), remaining);
            if (!remaining.isEmpty()) {
                throw new IllegalArgumentException("furnace display left unmatched native requirements");
            }
            return new RecipeWork(RecipeWork.Kind.SMELTING, outputStack(furnace.result()), 0, 0,
                    List.of(new RecipeWork.Input(-1, nativeInput)), furnace.duration(), null);
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

    private static SlotFacts slotFacts(SlotDisplay display) {
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
        if (input instanceof SlotDisplay.EmptySlotDisplay) return new SlotFacts(Set.of(), remainder, remainderDeclared, true);
        if (input instanceof SlotDisplay.ItemSlotDisplay item) {
            return new SlotFacts(Set.of(GameCatalog.id(item.item().value())), remainder, remainderDeclared, false);
        }
        if (input instanceof SlotDisplay.StackSlotDisplay stack) {
            if (stack.stack().isEmpty()) throw new IllegalArgumentException("empty stack display is not an ingredient");
            return new SlotFacts(Set.of(GameCatalog.id(stack.stack().getItem())), remainder, remainderDeclared, false);
        }
        if (input instanceof SlotDisplay.TagSlotDisplay tag) {
            Set<ItemId> allowed = new TreeSet<>();
            Registries.ITEM.iterateEntries(tag.tag()).forEach(entry -> allowed.add(GameCatalog.id(entry.value())));
            if (allowed.isEmpty()) throw new IllegalArgumentException("tag display resolves to no registered items");
            return new SlotFacts(allowed, remainder, remainderDeclared, false);
        }
        throw new IllegalArgumentException("unsupported slot display " + input.getClass().getSimpleName());
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
