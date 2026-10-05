package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.AcquisitionSource;
import dev.lodekeeper.core.BlockId;
import dev.lodekeeper.core.CatalogSnapshot;
import dev.lodekeeper.core.CraftingSource;
import dev.lodekeeper.core.Ingredient;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.ItemSelector;
import dev.lodekeeper.core.RecipeSlot;
import dev.lodekeeper.core.Requirement;
import dev.lodekeeper.core.RecipeType;
import dev.lodekeeper.core.SmeltingSource;
import dev.lodekeeper.core.StationId;
import dev.lodekeeper.core.StationRequirement;
import dev.lodekeeper.core.TagId;
import dev.lodekeeper.core.ToolRequirement;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds a bounded registry-backed planner view using recipes actually visible to this client. */
final class GameCatalog {
    record RecipeInput(int recipeSlot, net.minecraft.world.item.crafting.Ingredient ingredient) {}
    record RecipeWork(Item output, int outputCount, ItemStack resultStack, RecipeType type, int width, int height,
                      List<RecipeInput> ingredients, int cookTicks) {
        RecipeWork {
            resultStack = resultStack.copy();
            ingredients = List.copyOf(ingredients);
        }
        public ItemStack resultStack() { return resultStack.copy(); }
    }
    private record RecipeAddition(AcquisitionSource source, RecipeWork work) {}

    final List<AcquisitionSource> sources = new ArrayList<>();
    final Map<String, RecipeWork> recipes = new HashMap<>();
    final Map<TagId, List<ItemId>> tags = new HashMap<>();
    final Set<ItemId> items = new TreeSet<>();
    final List<String> unsupported = new ArrayList<>();
    private final Minecraft client;
    private CatalogSnapshot cachedSnapshot;
    private boolean ready;
    private long loadGeneration;
    private Map<ItemId, Long> fuelTicksByItem = Map.of();

    GameCatalog(Minecraft client) { this.client = client; }

    void load() {
        long generation = ++loadGeneration;
        cachedSnapshot = null;
        ready = false;
        sources.clear(); recipes.clear(); tags.clear(); items.clear(); unsupported.clear();
        pendingDefinitions.clear();
        fuelTicksByItem = Map.of();
        loadRegisteredItems();
        loadConservativeGatherSources();
        appendExtensions();
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null && client.level != null) {
            Level level = client.level;
            server.execute(() -> {
                List<RecipeAddition> additions = new ArrayList<>();
                List<String> rejected = new ArrayList<>();
                var manager = server.getRecipeManager();
                ServerLevel serverLevel = server.getLevel(level.dimension());
                if (serverLevel == null) {
                    client.execute(() -> {
                        if (loadGeneration == generation && client.level == level) {
                            unsupported.add("Integrated-server recipe view is unavailable");
                            ready = true;
                        }
                    });
                    return;
                }
                Map<ItemId, Long> resolvedFuelTicks = new TreeMap<>();
                for (Item item : BuiltInRegistries.ITEM) {
                    long ticks = GameApi.fuelTicks(serverLevel, new ItemStack(item));
                    if (ticks > 0) resolvedFuelTicks.put(id(item), ticks);
                }
                Map<ItemId, Long> fuelSnapshot = Map.copyOf(resolvedFuelTicks);
                for (RecipeHolder<?> holder : manager.getRecipes()) {
                    String recipeId = holder.id().identifier().toString();
                    try {
                        manager.listDisplaysForRecipe(holder.id(), entry -> {
                            try {
                                RecipeAddition addition = addition(entry, "server:" + recipeId + ":" + entry.id().index(),
                                        serverLevel, rejected, holder.value(), fuelSnapshot);
                                if (addition != null) additions.add(addition);
                            } catch (RuntimeException ex) {
                                rejected.add(recipeId + ": " + ex.getMessage());
                            }
                        });
                    } catch (RuntimeException ex) {
                        rejected.add(recipeId + ": " + ex.getMessage());
                    }
                }
                client.execute(() -> {
                    if (loadGeneration != generation || client.level != level) return;
                    publishFuelSnapshot(fuelSnapshot);
                    additions.forEach(this::install);
                    unsupported.addAll(rejected);
                    ready = true;
                    cachedSnapshot = null;
                });
            });
            return;
        }
        refreshLearnedRecipes();
        ready = true;
    }

    private void publishFuelSnapshot(Map<ItemId, Long> fuelSnapshot) {
        fuelTicksByItem = Map.copyOf(fuelSnapshot);
        pendingDefinitions.replaceAll((item, definition) -> new ItemDefinitionCompat(definition.durability(),
                fuelTicksByItem.getOrDefault(item, 0L), definition.aliases()));
        cachedSnapshot = null;
    }

    boolean ready() { return ready; }

    /** Adds newly synchronized recipe-book displays without rescanning already known entries. */
    void refreshLearnedRecipes() {
        if (client.player == null || client.level == null || client.getSingleplayerServer() != null) return;
        for (var collection : client.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                String key = "learned:" + entry.id().index();
                if (recipes.containsKey(key)) continue;
                try {
                    RecipeAddition addition = addition(entry, key, client.level, unsupported);
                    if (addition != null) install(addition);
                } catch (RuntimeException ex) {
                    unsupported.add(key + ": " + ex.getMessage());
                }
            }
        }
    }

    private void loadRegisteredItems() {
        Map<ItemId, Long> initialFuelTicks = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemId itemId = id(item);
            items.add(itemId);
            String displayName = new ItemStack(item).getHoverName().getString();
            List<String> aliases = displayName.isBlank() ? List.of() : List.of(displayName);
            var stack = new ItemStack(item);
            long fuelTicks = GameApi.initialFuelTicks(client.level, stack);
            if (fuelTicks > 0) initialFuelTicks.put(itemId, fuelTicks);
            pendingDefinitions.put(itemId, new ItemDefinitionCompat(stack.getMaxDamage(), fuelTicks, aliases));
            item.builtInRegistryHolder().tags().forEach(tag ->
                    tags.computeIfAbsent(TagId.parse(tag.location().toString()), ignored -> new ArrayList<>()).add(itemId));
        }
        fuelTicksByItem = Map.copyOf(initialFuelTicks);
        tags.replaceAll((key, values) -> values.stream().distinct().sorted().toList());
    }

    private record ItemDefinitionCompat(int durability, long fuelTicks, List<String> aliases) {}
    private final Map<ItemId, ItemDefinitionCompat> pendingDefinitions = new TreeMap<>();

    private void loadConservativeGatherSources() {
        Map<String, String> dropOverrides = Map.ofEntries(
                Map.entry("minecraft:stone", "minecraft:cobblestone"),
                Map.entry("minecraft:deepslate", "minecraft:cobbled_deepslate"),
                Map.entry("minecraft:coal_ore", "minecraft:coal"), Map.entry("minecraft:deepslate_coal_ore", "minecraft:coal"),
                Map.entry("minecraft:iron_ore", "minecraft:raw_iron"), Map.entry("minecraft:deepslate_iron_ore", "minecraft:raw_iron"),
                Map.entry("minecraft:copper_ore", "minecraft:raw_copper"), Map.entry("minecraft:deepslate_copper_ore", "minecraft:raw_copper"),
                Map.entry("minecraft:gold_ore", "minecraft:raw_gold"), Map.entry("minecraft:deepslate_gold_ore", "minecraft:raw_gold"),
                Map.entry("minecraft:diamond_ore", "minecraft:diamond"), Map.entry("minecraft:deepslate_diamond_ore", "minecraft:diamond"),
                Map.entry("minecraft:emerald_ore", "minecraft:emerald"), Map.entry("minecraft:deepslate_emerald_ore", "minecraft:emerald"),
                Map.entry("minecraft:redstone_ore", "minecraft:redstone"), Map.entry("minecraft:deepslate_redstone_ore", "minecraft:redstone"),
                Map.entry("minecraft:lapis_ore", "minecraft:lapis_lazuli"), Map.entry("minecraft:deepslate_lapis_ore", "minecraft:lapis_lazuli"),
                Map.entry("minecraft:nether_quartz_ore", "minecraft:quartz"), Map.entry("minecraft:clay", "minecraft:clay_ball"));
        Set<String> directBlocks = Set.of("minecraft:dirt", "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
                "minecraft:netherrack", "minecraft:end_stone", "minecraft:soul_sand", "minecraft:soul_soil",
                "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:obsidian", "minecraft:ancient_debris");
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier blockKey = BuiltInRegistries.BLOCK.getKey(block);
            String blockId = blockKey.toString();
            var state = block.defaultBlockState();
            Item drop = itemByKey(dropOverrides.get(blockId));
            if (drop == null && (state.is(BlockTags.LOGS) || directBlocks.contains(blockId))) drop = block.asItem();
            if (drop == null || drop == Items.AIR) continue;
            List<Requirement> requirements = new ArrayList<>();
            if (state.requiresCorrectToolForDrops()) {
                ItemId[] tools;
                if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) tools = itemIds("minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe");
                else if (state.is(BlockTags.NEEDS_IRON_TOOL)) tools = itemIds("minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe");
                else if (state.is(BlockTags.NEEDS_STONE_TOOL)) tools = itemIds("minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe");
                else tools = itemIds("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe");
                if (tools.length > 0) requirements.add(new ToolRequirement(Ingredient.choices(List.of(tools), 1), 8, "harvest " + blockId));
            }
            sources.add(new dev.lodekeeper.core.GatherSource("gather:" + blockKey, id(drop), 1,
                    List.of(BlockId.parse(blockId)), requirements));
        }
    }

    private ItemId[] itemIds(String... names) {
        List<ItemId> found = new ArrayList<>();
        for (String name : names) {
            Item item = itemByKey(name);
            if (item != null && item != Items.AIR) found.add(id(item));
        }
        return found.toArray(ItemId[]::new);
    }

    private Item itemByKey(String key) {
        if (key == null) return null;
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(key));
    }

    private RecipeAddition addition(RecipeDisplayEntry entry, String sourceId, Level level, List<String> rejected) {
        return addition(entry, sourceId, level, rejected, null, fuelTicksByItem);
    }

    private RecipeAddition addition(RecipeDisplayEntry entry, String sourceId, Level level, List<String> rejected,
                                    Recipe<?> authoritativeRecipe) {
        return addition(entry, sourceId, level, rejected, authoritativeRecipe, fuelTicksByItem);
    }

    private RecipeAddition addition(RecipeDisplayEntry entry, String sourceId, Level level, List<String> rejected,
                                    Recipe<?> authoritativeRecipe, Map<ItemId, Long> fuelTicks) {
        var context = SlotDisplayContext.fromLevel(level);
        List<ItemStack> resultStacks = entry.resultItems(context).stream().filter(stack -> !stack.isEmpty()).toList();
        if (resultStacks.isEmpty()) return null;
        Item output = resultStacks.getFirst().getItem();
        int outputCount = resultStacks.getFirst().getCount();
        ItemStack resultStack = resultStacks.getFirst().copy();
        if (resultStacks.stream().anyMatch(stack -> !ItemStack.isSameItemSameComponents(stack, resultStack))) {
            rejected.add(sourceId + ": recipe has multiple distinct display outputs");
            return null;
        }
        Optional<List<net.minecraft.world.item.crafting.Ingredient>> requirements = entry.craftingRequirements();
        if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
            if (authoritativeRecipe instanceof ShapedRecipe nativeShape) {
                if (nativeShape.getWidth() != shaped.width() || nativeShape.getHeight() != shaped.height()) {
                    rejected.add(sourceId + ": shaped display dimensions disagree with its authoritative recipe");
                    return null;
                }
                List<net.minecraft.world.item.crafting.Ingredient> grid = new ArrayList<>();
                nativeShape.getIngredients().forEach(cell -> grid.add(cell.orElse(null)));
                if (grid.size() != nativeShape.getWidth() * nativeShape.getHeight()) {
                    rejected.add(sourceId + ": authoritative shaped recipe has an invalid grid");
                    return null;
                }
                Block station = displayStation(entry, context, rejected);
                if (station == Blocks.AIR) return null;
                return craftingAddition(sourceId, output, outputCount, resultStack, RecipeType.SHAPED,
                        nativeShape.getWidth(), nativeShape.getHeight(), grid, station, rejected);
            }
            if (requirements.isEmpty()) {
                rejected.add(sourceId + ": shaped display omits executable ingredient requirements");
                return null;
            }
            List<net.minecraft.world.item.crafting.Ingredient> grid = remoteShapedGrid(shaped, requirements.get(), level);
            if (grid == null) {
                rejected.add(sourceId + ": shaped display cannot be matched safely to its native ingredient predicates");
                return null;
            }
            Block station = displayStation(entry, context, rejected);
            if (station == Blocks.AIR) return null;
            return craftingAddition(sourceId, output, outputCount, resultStack, RecipeType.SHAPED, shaped.width(), shaped.height(), grid, station, rejected);
        }
        if (entry.display() instanceof ShapelessCraftingRecipeDisplay shapeless) {
            if (requirements.isEmpty()) {
                rejected.add(sourceId + ": shapeless display omits executable ingredient requirements");
                return null;
            }
            Block station = displayStation(entry, context, rejected);
            if (station == Blocks.AIR) return null;
            return craftingAddition(sourceId, output, outputCount, resultStack, RecipeType.SHAPELESS, 0, 0, requirements.get(), station, rejected);
        }
        if (entry.display() instanceof FurnaceRecipeDisplay furnace) {
            if (requirements.isEmpty() || requirements.get().isEmpty()) {
                rejected.add(sourceId + ": furnace display omits executable ingredient requirements");
                return null;
            }
            Block station = displayStation(entry, context, rejected);
            if (station == Blocks.AIR) return null;
            return smeltingAddition(sourceId, output, outputCount, resultStack, requirements.get().getFirst(),
                    furnace.duration(), station, rejected, fuelTicks);
        }
        rejected.add(sourceId + ": unsupported recipe display " + entry.display().getClass().getSimpleName());
        return null;
    }

    /** Display cells keep holes; compact requirements supply the exact executable predicates. */
    private List<net.minecraft.world.item.crafting.Ingredient> remoteShapedGrid(
            ShapedCraftingRecipeDisplay shaped, List<net.minecraft.world.item.crafting.Ingredient> requirements,
            Level level) {
        int cells = shaped.width() * shaped.height();
        if (shaped.width() < 1 || shaped.width() > 3 || shaped.height() < 1 || shaped.height() > 3
                || shaped.ingredients().size() != cells || requirements.isEmpty() || requirements.size() > cells) return null;
        List<Set<Item>> allowed = new ArrayList<>();
        for (var ingredient : requirements) {
            if (ingredient == null || ingredient.getClass() != net.minecraft.world.item.crafting.Ingredient.class) return null;
            Set<Item> items = ingredient.items().map(Holder::value).collect(java.util.stream.Collectors.toSet());
            if (items.isEmpty()) return null;
            allowed.add(items);
        }
        boolean[] used = new boolean[requirements.size()];
        List<net.minecraft.world.item.crafting.Ingredient> grid = new ArrayList<>(cells);
        for (SlotDisplay display : shaped.ingredients()) {
            if (display == SlotDisplay.Empty.INSTANCE) { grid.add(null); continue; }
            Set<Item> displayed;
            if (display instanceof SlotDisplay.TagSlotDisplay tag) {
                displayed = GameApi.tagItems(tag, level);
            } else if (display instanceof SlotDisplay.ItemSlotDisplay item) {
                displayed = Set.of(item.item().value());
            } else return null;
            int match = -1;
            for (int index = 0; index < allowed.size(); index++) {
                if (!used[index] && allowed.get(index).equals(displayed)) { match = index; break; }
            }
            if (match < 0) return null;
            used[match] = true;
            grid.add(requirements.get(match));
        }
        for (boolean matched : used) if (!matched) return null;
        return grid;
    }

    private Block displayStation(RecipeDisplayEntry entry, net.minecraft.util.context.ContextMap context,
                                 List<String> rejected) {
        ItemStack stack = entry.display().craftingStation().resolveForFirstStack(context);
        if (stack.isEmpty()) return null;
        Block block = Block.byItem(stack.getItem());
        if (block == Blocks.AIR) {
            rejected.add("display " + entry.id().index() + ": station item is not a registered block item");
            return Blocks.AIR;
        }
        return block;
    }

    private RecipeAddition craftingAddition(String sourceId, Item output, int outputCount, ItemStack resultStack, RecipeType type,
                                             int width, int height,
                                             List<net.minecraft.world.item.crafting.Ingredient> ingredients,
                                             Block displayStation, List<String> rejected) {
        if (ingredients.isEmpty() || ingredients.size() > 256) return null;
        List<RecipeSlot> slots = new ArrayList<>();
        List<RecipeInput> workInputs = new ArrayList<>();
        for (int index = 0; index < ingredients.size(); index++) {
            var ingredient = ingredients.get(index);
            if (ingredient == null || ingredient.isEmpty()) continue;
            int sourceSlot = type == RecipeType.SHAPED ? index : -1;
            int actionSlot = type == RecipeType.SHAPED ? index : workInputs.size();
            slots.add(new RecipeSlot(sourceSlot, coreIngredient(ingredient)));
            workInputs.add(new RecipeInput(actionSlot, ingredient));
        }
        if (slots.isEmpty()) return null;
        List<Requirement> requirements = new ArrayList<>();
        if (type == RecipeType.SHAPED && (width > 2 || height > 2) || type == RecipeType.SHAPELESS && slots.size() > 4) {
            Block station = displayStation == null ? Blocks.CRAFTING_TABLE : displayStation;
            requirements.add(GameCatalog.station(station));
        }
        var source = new CraftingSource(sourceId, id(output), outputCount, type, width, height, slots, requirements);
        return new RecipeAddition(source, new RecipeWork(output, outputCount, resultStack, type, width, height, workInputs, 0));
    }

    private RecipeAddition smeltingAddition(String sourceId, Item output, int outputCount, ItemStack resultStack,
                                              net.minecraft.world.item.crafting.Ingredient input, int cookTicks,
                                              Block displayStation, List<String> rejected,
                                              Map<ItemId, Long> fuelTicks) {
        Block station = displayStation == null ? Blocks.FURNACE : displayStation;
        if (station != Blocks.FURNACE) {
            rejected.add(sourceId + ": only the standard furnace fuel context is supported");
            return null;
        }
        List<ItemSelector> fuels = fuelTicks.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> ItemSelector.item(entry.getKey()))
                .limit(256).toList();
        if (fuels.isEmpty()) {
            rejected.add(sourceId + ": no context-independent fuel data is available");
            return null;
        }
        List<Requirement> requirements = List.of(GameCatalog.station(station));
        var source = new SmeltingSource(sourceId, id(output), outputCount, coreIngredient(input), fuels,
                Math.max(1, cookTicks), requirements);
        List<RecipeInput> workInputs = List.of(new RecipeInput(-1, input));
        return new RecipeAddition(source, new RecipeWork(output, outputCount, resultStack, RecipeType.SHAPELESS, 0, 0, workInputs, cookTicks));
    }

    private Ingredient coreIngredient(net.minecraft.world.item.crafting.Ingredient ingredient) {
        List<ItemId> choices = ingredient.items().map(Holder::value).map(GameCatalog::id).distinct().sorted().toList();
        if (choices.isEmpty()) throw new IllegalArgumentException("ingredient resolves to no registered items");
        return Ingredient.choices(choices, 1);
    }

    private void install(RecipeAddition addition) {
        if (addition == null || recipes.containsKey(addition.source().sourceId())) return;
        sources.add(addition.source());
        recipes.put(addition.source().sourceId(), addition.work());
        cachedSnapshot = null;
    }

    private void appendExtensions() {
        // ExtensionCatalog is deliberately separate because arbitrary modded block drops cannot be inferred from an item form.
        ExtensionCatalog.append(this);
    }

    CatalogSnapshot snapshot() {
        if (cachedSnapshot != null) return cachedSnapshot;
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        pendingDefinitions.forEach((item, definition) -> builder.item(item, definition.durability(), definition.fuelTicks(), definition.aliases().toArray(String[]::new)));
        tags.forEach(builder::tag);
        sources.forEach(builder::source);
        cachedSnapshot = builder.build();
        return cachedSnapshot;
    }

    static ItemId id(Item item) { return ItemId.parse(BuiltInRegistries.ITEM.getKey(item).toString()); }
    static StationRequirement station(Block block) {
        return new StationRequirement(StationId.parse(BuiltInRegistries.BLOCK.getKey(block).toString()), id(block.asItem()), "use station");
    }
    static Item item(ItemId id) { return BuiltInRegistries.ITEM.getValue(Identifier.parse(id.toString())); }
    static Block block(BlockId id) { return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id.toString())); }
}
