package dev.lodekeeper.fabric;

import dev.lodekeeper.core.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import java.util.*;

/** Discovers recipe transforms from synchronized data rather than a hardcoded item task list. */
final class GameCatalog {
    final List<AcquisitionSource> sources = new ArrayList<>();
    final Map<String, net.minecraft.recipe.Recipe<?>> recipes = new HashMap<>();
    final Map<TagId, List<ItemId>> tags = new HashMap<>();
    final Set<ItemId> items = new TreeSet<>();
    final List<String> unsupported = new ArrayList<>();
    private final MinecraftClient client;
    private CatalogSnapshot cachedSnapshot;
    GameCatalog(MinecraftClient client) { this.client = client; }
    void load() {
        cachedSnapshot = null; sources.clear(); recipes.clear(); tags.clear(); items.clear(); unsupported.clear();
        for (Item item : Registries.ITEM) {
            items.add(id(item));
            Registries.ITEM.getEntry(item).streamTags().forEach(tag -> tags.computeIfAbsent(TagId.parse(tag.id().toString()), ignored -> new ArrayList<>()).add(id(item)));
        }
        if (client.world == null) return;
        var manager = client.world.getRecipeManager();
        for (GameApi.RecipeRef entry : GameApi.recipes(manager).stream().sorted(Comparator.comparing(GameApi.RecipeRef::id)).toList()) {
            var recipe = entry.recipe();
            ItemStack output = GameApi.result(recipe, client.world.getRegistryManager());
            if (output.isEmpty()) continue;
            String key = entry.id();
            try {
                List<Requirement> requirements = new ArrayList<>();
                if (recipe instanceof ShapedRecipe shaped) {
                    if (!recipe.fits(2, 2)) requirements.add(station(Blocks.CRAFTING_TABLE));
                    List<RecipeSlot> slots = slots(recipe.getIngredients(), true);
                    if (slots.isEmpty()) continue;
                    sources.add(new CraftingSource(key, id(output.getItem()), output.getCount(), RecipeType.SHAPED, shaped.getWidth(), shaped.getHeight(), slots, requirements));
                } else if (recipe instanceof ShapelessRecipe) {
                    if (recipe.getIngredients().size() > 4) requirements.add(station(Blocks.CRAFTING_TABLE));
                    sources.add(new CraftingSource(key, id(output.getItem()), output.getCount(), RecipeType.SHAPELESS, 0, 0, slots(recipe.getIngredients(), false), requirements));
                } else if (recipe instanceof AbstractCookingRecipe cooking && recipe.getType() == net.minecraft.recipe.RecipeType.SMELTING) {
                    requirements.add(station(Blocks.FURNACE));
                    // The core computes fuel units from burn duration and total cook ticks.
                    sources.add(new SmeltingSource(key, id(output.getItem()), output.getCount(), ingredient(recipe.getIngredients().get(0)), List.of(ItemSelector.item(id(Items.COAL)), ItemSelector.tag(TagId.parse("minecraft:planks"))), GameApi.cookingTime(cooking), requirements));
                } else {
                    unsupported.add(key + " (" + Registries.RECIPE_SERIALIZER.getId(recipe.getSerializer()) + ")");
                    continue;
                }
                recipes.put(key, recipe);
            } catch (IllegalArgumentException ex) { unsupported.add(key + ": " + ex.getMessage()); }
        }
        gatherSources();
        ExtensionCatalog.append(this);
    }
    private List<RecipeSlot> slots(List<net.minecraft.recipe.Ingredient> ingredients, boolean shaped) {
        List<RecipeSlot> slots = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) if (!ingredients.get(i).isEmpty()) slots.add(new RecipeSlot(shaped ? i : -1, ingredient(ingredients.get(i))));
        return slots;
    }
    private Ingredient ingredient(net.minecraft.recipe.Ingredient ingredient) {
        List<ItemId> choices = Arrays.stream(ingredient.getMatchingStacks()).filter(s -> !s.isEmpty()).map(s -> id(s.getItem())).distinct().sorted().toList();
        return Ingredient.choices(choices, 1);
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
                requirements.add(new ToolRequirement(Ingredient.of(tools), 8, "harvest " + Registries.BLOCK.getId(block)));
            }
            sources.add(new GatherSource("gather:" + Registries.BLOCK.getId(block), id(drop), 1, List.of(BlockId.parse(Registries.BLOCK.getId(block).toString())), requirements));
        }
    }
    CatalogSnapshot snapshot() {
        if (cachedSnapshot != null) return cachedSnapshot;
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        Map<Item, Integer> fuels = net.minecraft.block.entity.AbstractFurnaceBlockEntity.createFuelTimeMap();
        for (Item item : Registries.ITEM) builder.item(id(item), item.getDefaultStack().getMaxDamage(), fuels.getOrDefault(item, 0));
        tags.forEach(builder::tag);
        sources.forEach(builder::source);
        cachedSnapshot = builder.build();
        return cachedSnapshot;
    }
    static ItemId id(Item item) { return ItemId.parse(Registries.ITEM.getId(item).toString()); }
    static StationRequirement station(Block block) { return new StationRequirement(StationId.parse(Registries.BLOCK.getId(block).toString()), id(block.asItem()), "use station"); }
    static Item item(ItemId id) { return Registries.ITEM.get(GameApi.identifier(id.toString())); }
}
