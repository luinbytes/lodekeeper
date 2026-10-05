package dev.lodekeeper.fabric;

import com.google.gson.*;
import dev.lodekeeper.core.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Explicit custom drop contracts: registry discovery cannot infer arbitrary server loot rules. */
final class ExtensionCatalog {
    static void append(GameCatalog catalog) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("lodekeeper-sources.json");
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 262144) throw new IllegalArgumentException("Source file exceeds 256 KiB");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Expected schema 1");
            JsonArray sources = root.getAsJsonArray("gather");
            if (sources.size() > 256) throw new IllegalArgumentException("At most 256 custom sources");
            Set<String> sourceIds = new HashSet<>();
            catalog.sources.forEach(source -> sourceIds.add(source.sourceId()));
            for (JsonElement entry : sources) {
                try {
                    JsonObject source = entry.getAsJsonObject();
                    if (source.has("enabled") && !source.get("enabled").getAsBoolean()) continue;
                    String sourceId = "extension:" + source.get("id").getAsString();
                    if (sourceIds.contains(sourceId)) throw new IllegalArgumentException("Duplicate source id " + sourceId);
                    ItemId output = ItemId.parse(source.get("item").getAsString());
                    if (!Registries.ITEM.containsId(new Identifier(output.toString()))) throw new IllegalArgumentException("Unknown item " + output);
                    List<BlockId> blocks = new ArrayList<>();
                    for (JsonElement block : source.getAsJsonArray("blocks")) {
                        BlockId id = BlockId.parse(block.getAsString());
                        if (!Registries.BLOCK.containsId(new Identifier(id.toString()))) throw new IllegalArgumentException("Unknown block " + id);
                        blocks.add(id);
                    }
                    List<Requirement> requirements = new ArrayList<>();
                    if (source.has("tools")) {
                        List<ItemId> tools = new ArrayList<>();
                        for (JsonElement tool : source.getAsJsonArray("tools")) {
                            ItemId id = ItemId.parse(tool.getAsString());
                            if (!Registries.ITEM.containsId(new Identifier(id.toString()))) throw new IllegalArgumentException("Unknown tool " + id);
                            tools.add(id);
                        }
                        requirements.add(new ToolRequirement(Ingredient.choices(tools, 1), 8, "custom harvest"));
                    }
                    // Yield remains one: random drops and fortune are verified from observed inventory.
                    catalog.sources.add(new GatherSource(sourceId, output, 1, blocks, requirements));
                    sourceIds.add(sourceId);
                } catch (RuntimeException ex) { catalog.unsupported.add("Custom source: " + ex.getMessage()); }
            }
        } catch (Exception ex) { catalog.unsupported.add("lodekeeper-sources.json: " + ex.getMessage()); }
    }
}
