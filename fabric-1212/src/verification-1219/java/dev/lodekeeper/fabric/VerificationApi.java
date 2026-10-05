package dev.lodekeeper.fabric;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.loot.LootTable;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameRules;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

import java.util.Optional;
import java.util.Set;

/** Minecraft 1.21.9–1.21.10 verifier registration and world-start API. */
final class VerificationApi {
    private VerificationApi() {}

    static void screenshot(java.io.File directory, String name, MinecraftClient client,
                           java.util.function.Consumer<net.minecraft.text.Text> complete) {
        net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(directory, name, client.getFramebuffer(), 1, complete);
    }

    static String minecraftVersion() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
            .getMetadata().getVersion().getFriendlyString();
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
        CreateWorldScreen.show(client, () -> client.setScreen(parent));
    }

    static GameRules gameRules(WorldCreator creator) {
        return creator.getGameRules().copy(creator.getGeneratorOptionsHolder()
            .dataConfiguration().enabledFeatures());
    }

    static boolean teleport(ServerPlayerEntity player, ServerWorld world, double x, double y, double z,
                            float yaw, float pitch) {
        // Empty relative flags keep the fixture spawn absolute; true restores the camera to the verifier player.
        return player.teleport(world, x, y, z, Set.of(), yaw, pitch, true);
    }

    static void startFlatWorld(IntegratedServerLoader loader, String saveName, LevelInfo levelInfo,
                               GeneratorOptions options) {
        loader.createAndStart(saveName, levelInfo, options,
            registry -> registry.getOrThrow(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                .createDimensionsRegistryHolder(), (Screen) null);
    }
}
