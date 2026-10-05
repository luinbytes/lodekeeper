package dev.lodekeeper.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;

/** Minecraft 1.21.1 signature for creating the isolated verifier world. */
final class VerificationApi {
    private VerificationApi() {}

    static String minecraftVersion() {
        return net.minecraft.SharedConstants.getGameVersion().getName();
    }

    static Block rubyOre() {
        return new RubyOreBlock();
    }

    static void startFlatWorld(IntegratedServerLoader loader, String saveName, LevelInfo levelInfo, GeneratorOptions options) {
        loader.createAndStart(saveName, levelInfo, options,
            registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createDimensionsRegistryHolder(),
            (Screen) null);
    }

    private static final class RubyOreBlock extends Block {
        private RubyOreBlock() {
            super(AbstractBlock.Settings.create().strength(3.0f, 3.0f).requiresTool());
            lootTableKey = RegistryKey.of(RegistryKeys.LOOT_TABLE,
                GameApi.identifier("lodekeeper_verification:blocks/ruby_ore"));
        }
    }
}
