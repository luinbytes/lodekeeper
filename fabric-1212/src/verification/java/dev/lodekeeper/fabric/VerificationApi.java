package dev.lodekeeper.fabric;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.util.Optional;

/** Minecraft 1.21.2–1.21.3 verifier registration and world-start API. */
final class VerificationApi {
    private VerificationApi() {}

    static String minecraftVersion() {
        return net.minecraft.SharedConstants.getGameVersion().getName();
    }

    static Block rubyOre(Identifier blockId, Identifier lootTableId) {
        RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, blockId);
        RegistryKey<LootTable> lootKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, lootTableId);
        AbstractBlock.Settings settings = AbstractBlock.Settings.create()
            .strength(3.0f, 3.0f)
            .requiresTool()
            .registryKey(blockKey)
            .lootTable(Optional.of(lootKey));
        return new Block(settings);
    }

    static Item rubyOreItem(Block rubyOre, Identifier itemId) {
        return new BlockItem(rubyOre, new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, itemId)));
    }

    static Item ruby(Identifier itemId) {
        return new Item(new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, itemId)));
    }

    static Item rubyGear(Identifier itemId) {
        return new Item(new Item.Settings().maxCount(1).registryKey(RegistryKey.of(RegistryKeys.ITEM, itemId)));
    }

    static void openCreateWorldScreen(MinecraftClient client, Screen parent) {
        CreateWorldScreen.show(client, parent);
    }

    static void startFlatWorld(IntegratedServerLoader loader, String saveName, LevelInfo levelInfo,
                               GeneratorOptions options) {
        loader.createAndStart(saveName, levelInfo, options,
            registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                .createDimensionsRegistryHolder(), (Screen) null);
    }
}
