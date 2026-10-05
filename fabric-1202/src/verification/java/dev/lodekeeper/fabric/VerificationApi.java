package dev.lodekeeper.fabric;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;

/** Minecraft 1.20.2–1.20.4 verifier construction hooks. */
final class VerificationApi {
    private VerificationApi() {}

    static String minecraftVersion() {
        return net.minecraft.SharedConstants.getGameVersion().getName();
    }

    static Block rubyOre(Identifier blockId, Identifier lootTableId) {
        return new RubyOreBlock(lootTableId);
    }

    static Item rubyOreItem(Block rubyOre, Identifier itemId) {
        return new BlockItem(rubyOre, new Item.Settings());
    }

    static Item ruby(Identifier itemId) {
        return new Item(new Item.Settings());
    }

    static Item rubyGear(Identifier itemId) {
        return new Item(new Item.Settings().maxCount(1));
    }

    static void openCreateWorldScreen(MinecraftClient client, Screen parent) {
        CreateWorldScreen.create(client, parent);
    }

    static void startFlatWorld(IntegratedServerLoader loader, String saveName, LevelInfo levelInfo,
                               GeneratorOptions options) {
        loader.createAndStart(saveName, levelInfo, options,
            registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                .createDimensionsRegistryHolder());
    }

    private static final class RubyOreBlock extends Block {
        private RubyOreBlock(Identifier lootTableId) {
            super(AbstractBlock.Settings.create().strength(3.0f, 3.0f).requiresTool());
            this.lootTableId = lootTableId;
        }
    }
}
