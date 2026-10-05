package dev.lodekeeper.fabric.modern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lodekeeper.core.BlockId;
import dev.lodekeeper.core.GatherSource;
import dev.lodekeeper.core.Ingredient;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.Requirement;
import dev.lodekeeper.core.ToolRequirement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit modded/custom drop contracts; registry IDs alone cannot reveal loot-table behavior. */
final class ExtensionCatalog {
    private ExtensionCatalog() {}

    static void append(GameCatalog catalog) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("lodekeeper-sources.json");
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 262_144) throw new IllegalArgumentException("Source file exceeds 256 KiB");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("schema") || root.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Expected schema 1");
            JsonArray entries = root.getAsJsonArray("gather");
            if (entries == null || entries.size() > 256) throw new IllegalArgumentException("Expected at most 256 gather sources");
            Set<String> sourceIds = new HashSet<>();
            catalog.sources.forEach(source -> sourceIds.add(source.sourceId()));
            for (JsonElement element : entries) {
                try {
                    JsonObject row = element.getAsJsonObject();
                    if (row.has("enabled") && !row.get("enabled").getAsBoolean()) continue;
                    String sourceId = "extension:" + row.get("id").getAsString();
                    if (!sourceIds.add(sourceId)) throw new IllegalArgumentException("Duplicate source id " + sourceId);
                    ItemId output = ItemId.parse(row.get("item").getAsString());
                    if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(output.toString()))) throw new IllegalArgumentException("Unknown item " + output);
                    List<BlockId> blocks = new ArrayList<>();
                    for (JsonElement block : row.getAsJsonArray("blocks")) {
                        BlockId id = BlockId.parse(block.getAsString());
                        if (!BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id.toString()))) throw new IllegalArgumentException("Unknown block " + id);
                        blocks.add(id);
                    }
                    if (blocks.isEmpty()) throw new IllegalArgumentException("At least one block is required");
                    List<Requirement> requirements = new ArrayList<>();
                    if (row.has("tools")) {
                        List<ItemId> tools = new ArrayList<>();
                        for (JsonElement tool : row.getAsJsonArray("tools")) {
                            ItemId id = ItemId.parse(tool.getAsString());
                            if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(id.toString()))) throw new IllegalArgumentException("Unknown tool " + id);
                            tools.add(id);
                        }
                        if (tools.isEmpty()) throw new IllegalArgumentException("Tools list cannot be empty");
                        requirements.add(new ToolRequirement(Ingredient.choices(tools, 1), 8, "custom harvest"));
                    }
                    catalog.sources.add(new GatherSource(sourceId, output, 1, blocks, requirements));
                } catch (RuntimeException ex) {
                    catalog.unsupported.add("Custom source: " + ex.getMessage());
                }
            }
        } catch (Exception ex) {
            catalog.unsupported.add("lodekeeper-sources.json: " + ex.getMessage());
        }
    }
}
