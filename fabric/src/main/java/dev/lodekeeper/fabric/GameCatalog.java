package dev.lodekeeper.fabric;

import dev.lodekeeper.core.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import java.util.*;
import java.util.function.Consumer;

/** Discovers recipe transforms from synchronized data rather than a hardcoded item task list. */
final class GameCatalog {
    final List<AcquisitionSource> sources = new ArrayList<>();
    final Map<String, RecipeWork> recipes = new HashMap<>();
    final Map<TagId, List<ItemId>> tags = new HashMap<>();
    final Set<ItemId> items = new TreeSet<>();
    final List<String> unsupported = new ArrayList<>();
    private final MinecraftClient client;
    private Object recipeProvider;
    private CatalogSnapshot cachedSnapshot;
    private long generation;
    private boolean ready;
    private final Map<ItemId, Long> fuelBurnTicks = new TreeMap<>();
    GameCatalog(MinecraftClient client) { this.client = client; }
    void load() {
        long requestedGeneration = ++generation;
        ready = false;
        recipeProvider = GameApi.recipeProviderIdentity(client);
        cachedSnapshot = null; sources.clear(); recipes.clear(); tags.clear(); items.clear(); unsupported.clear();
        fuelBurnTicks.clear();
        for (Item item : Registries.ITEM) {
            items.add(id(item));
            Registries.ITEM.getEntry(item).streamTags().forEach(tag -> tags.computeIfAbsent(TagId.parse(tag.id().toString()), ignored -> new ArrayList<>()).add(id(item)));
        }
        if (client.world == null) { ready = true; return; }
        Consumer<RecipeCatalogSnapshot> publish = snapshot -> {
            if (requestedGeneration != generation || client.world == null
                    || recipeProvider != GameApi.recipeProviderIdentity(client)) return;
            applyRecipeSnapshot(snapshot);
        };
        try {
            GameApi.loadRecipes(client, publish);
        } catch (RuntimeException exception) {
            unsupported.add("recipe provider: " + describe(exception));
            finishLoad();
        }
    }
    private void applyRecipeSnapshot(RecipeCatalogSnapshot snapshot) {
        sources.clear(); recipes.clear(); unsupported.clear(); fuelBurnTicks.clear(); cachedSnapshot = null;
        unsupported.addAll(snapshot.unsupported());
        fuelBurnTicks.putAll(snapshot.fuelBurnTicks());
        snapshot.recipes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = entry.getKey();
            RecipeWork work = entry.getValue();
            try {
                ItemStack output = work.outputPerOperation();
                List<Requirement> requirements = new ArrayList<>();
                switch (work.kind()) {
                    case SHAPED_CRAFTING -> {
                        if (work.width() > 2 || work.height() > 2) requirements.add(station(Blocks.CRAFTING_TABLE));
                        List<RecipeSlot> slots = recipeSlots(work.inputs(), true);
                        if (slots.isEmpty()) return;
                        sources.add(new CraftingSource(key, id(output.getItem()), output.getCount(), RecipeType.SHAPED,
                                work.width(), work.height(), slots, requirements));
                    }
                    case SHAPELESS_CRAFTING -> {
                        if (work.inputs().size() > 4) requirements.add(station(Blocks.CRAFTING_TABLE));
                        sources.add(new CraftingSource(key, id(output.getItem()), output.getCount(), RecipeType.SHAPELESS,
                                0, 0, recipeSlots(work.inputs(), false), requirements));
                    }
                    case SMELTING -> {
                        StationId cookingStation = work.cookingStation();
                        if (work.cookTicks() < 100) {
                            unsupported.add(key + ": cooking timers below 100 ticks need a separate transfer schedule");
                            return;
                        }
                        requirements.add(station(cookingBlock(cookingStation)));
                        Map<ItemId, Long> fuelProgressTicks = new TreeMap<>();
                        for (Map.Entry<ItemId, Long> fuel : fuelBurnTicks.entrySet()) {
                            Item nativeFuel = item(fuel.getKey());
                            long progressTicks = GameApi.cookingFuelProgressTicks(
                                    nativeFuel, cookingStation, fuel.getValue());
                            int fuelStackLimit = nativeFuel.getDefaultStack().getMaxCount();
                            if (fuelStackLimit > 99 || progressTicks < 32 || fuelStackLimit > 1
                                    && (long) Math.max(1, fuelStackLimit / 2) * progressTicks < 800) continue;
                            if (nativeFuel.getDefaultStack().getMaxCount() == 1) {
                                progressTicks -= progressTicks % work.cookTicks();
                            }
                            if (progressTicks > 0) fuelProgressTicks.put(fuel.getKey(), progressTicks);
                            if (fuelProgressTicks.size() == 256) break;
                        }
                        List<ItemSelector> fuels = fuelProgressTicks.keySet().stream().map(ItemSelector::item).toList();
                        if (fuels.isEmpty()) {
                            unsupported.add(key + ": world exposes no usable " + cookingStation + " fuels");
                            return;
                        }
                        sources.add(new SmeltingSource(key, id(output.getItem()), output.getCount(),
                                ingredient(work.inputs().get(0).predicate()), fuels, work.cookTicks(), requirements,
                                fuelProgressTicks));
                    }
                }
                recipes.put(key, work);
            } catch (IllegalArgumentException ex) {
                unsupported.add(key + ": " + ex.getMessage());
            }
        });
        finishLoad();
    }
    private void finishLoad() {
        gatherSources();
        ExtensionCatalog.append(this);
        cachedSnapshot = null;
        ready = true;
        if (Boolean.getBoolean("lodekeeper.verify")) {
            System.out.println("[Lodekeeper verification] catalog recipes=" + recipes.size()
                    + ", sources=" + sources.size() + ", rejected=" + unsupported.size()
                    + ", samples=" + unsupported.stream().limit(3).toList());
        }
    }
    private List<RecipeSlot> recipeSlots(List<RecipeWork.Input> inputs, boolean shaped) {
        List<RecipeSlot> slots = new ArrayList<>();
        for (RecipeWork.Input input : inputs) {
            slots.add(new RecipeSlot(shaped ? input.slot() : -1, ingredient(input.predicate())));
        }
        return slots;
    }
    private Ingredient ingredient(net.minecraft.recipe.Ingredient ingredient) {
        return GameApi.ingredient(ingredient);
    }
    private static String describe(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
    private void gatherSources() {
        // Drop overrides are deliberate: a block's item form is not necessarily its survival drop.
        Map<Block, Item> drops = Map.ofEntries(
            Map.entry(Blocks.STONE, Items.COBBLESTONE), Map.entry(Blocks.DEEPSLATE, Items.COBBLED_DEEPSLATE),
            Map.entry(Blocks.COAL_ORE, Items.COAL), Map.entry(Blocks.DEEPSLATE_COAL_ORE, Items.COAL),
            Map.entry(Blocks.IRON_ORE, Items.RAW_IRON), Map.entry(Blocks.DEEPSLATE_IRON_ORE, Items.RAW_IRON),
            Map.entry(Blocks.COPPER_ORE, Items.RAW_COPPER), Map.entry(Blocks.DEEPSLATE_COPPER_ORE, Items.RAW_COPPER),
            Map.entry(Blocks.GOLD_ORE, Items.RAW_GOLD), Map.entry(Blocks.DEEPSLATE_GOLD_ORE, Items.RAW_GOLD),
            Map.entry(Blocks.DIAMOND_ORE, Items.DIAMOND), Map.entry(Blocks.DEEPSLATE_DIAMOND_ORE, Items.DIAMOND),
            Map.entry(Blocks.EMERALD_ORE, Items.EMERALD), Map.entry(Blocks.DEEPSLATE_EMERALD_ORE, Items.EMERALD),
            Map.entry(Blocks.REDSTONE_ORE, Items.REDSTONE), Map.entry(Blocks.DEEPSLATE_REDSTONE_ORE, Items.REDSTONE),
            Map.entry(Blocks.LAPIS_ORE, Items.LAPIS_LAZULI), Map.entry(Blocks.DEEPSLATE_LAPIS_ORE, Items.LAPIS_LAZULI),
            Map.entry(Blocks.NETHER_QUARTZ_ORE, Items.QUARTZ), Map.entry(Blocks.CLAY, Items.CLAY_BALL));
        Set<Block> simple = Set.of(Blocks.DIRT, Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.NETHERRACK, Blocks.END_STONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE, Blocks.OBSIDIAN, Blocks.ANCIENT_DEBRIS);
        for (Block block : Registries.BLOCK) {
            var state = block.getDefaultState();
            Item drop = drops.get(block);
            if (drop == null && (state.isIn(BlockTags.LOGS) || simple.contains(block))) drop = block.asItem();
            if (drop == null || drop == Items.AIR) continue;
            List<Requirement> requirements = new ArrayList<>();
            if (state.isToolRequired()) {
                ItemId[] tools;
                if (state.isIn(BlockTags.NEEDS_DIAMOND_TOOL)) tools = new ItemId[]{id(Items.DIAMOND_PICKAXE), id(Items.NETHERITE_PICKAXE)};
                else if (state.isIn(BlockTags.NEEDS_IRON_TOOL)) tools = new ItemId[]{id(Items.IRON_PICKAXE), id(Items.DIAMOND_PICKAXE), id(Items.NETHERITE_PICKAXE)};
                else if (state.isIn(BlockTags.NEEDS_STONE_TOOL)) tools = new ItemId[]{id(Items.STONE_PICKAXE), id(Items.IRON_PICKAXE), id(Items.DIAMOND_PICKAXE), id(Items.NETHERITE_PICKAXE)};
                else tools = new ItemId[]{id(Items.WOODEN_PICKAXE), id(Items.STONE_PICKAXE), id(Items.IRON_PICKAXE), id(Items.DIAMOND_PICKAXE), id(Items.NETHERITE_PICKAXE)};
                // Vanilla tool components charge one point for each successful block break.
                requirements.add(new ToolRequirement(Ingredient.of(tools), 2, "harvest " + Registries.BLOCK.getId(block), 1));
            }
            sources.add(new GatherSource("gather:" + Registries.BLOCK.getId(block), id(drop), 1, List.of(BlockId.parse(Registries.BLOCK.getId(block).toString())), requirements));
        }
    }
    CatalogSnapshot snapshot() {
        if (cachedSnapshot != null) return cachedSnapshot;
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        for (Item item : Registries.ITEM) builder.item(id(item), item.getDefaultStack().getMaxDamage(),
                fuelBurnTicks.getOrDefault(id(item), 0L));
        tags.forEach(builder::tag);
        sources.forEach(builder::source);
        cachedSnapshot = builder.build();
        return cachedSnapshot;
    }
    long generation() { return generation; }
    boolean ready() { return ready; }
    boolean usesProvider(Object provider) { return recipeProvider == provider; }
    boolean usesCurrentProvider() { return client.world != null && recipeProvider == GameApi.recipeProviderIdentity(client); }
    static ItemId id(Item item) { return ItemId.parse(Registries.ITEM.getId(item).toString()); }
    static StationRequirement station(Block block) { return new StationRequirement(StationId.parse(Registries.BLOCK.getId(block).toString()), id(block.asItem()), "use station"); }
    static Item item(ItemId id) { return Registries.ITEM.get(GameApi.identifier(id.toString())); }

    private static Block cookingBlock(StationId station) {
        if (station == null || !station.namespace().equals("minecraft")) {
            throw new IllegalArgumentException("unsupported cooking station " + station);
        }
        return switch (station.path()) {
            case "furnace" -> Blocks.FURNACE;
            case "smoker" -> Blocks.SMOKER;
            case "blast_furnace" -> Blocks.BLAST_FURNACE;
            default -> throw new IllegalArgumentException("unsupported cooking station " + station);
        };
    }
}
