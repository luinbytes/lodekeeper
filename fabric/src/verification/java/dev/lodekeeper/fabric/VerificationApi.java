package dev.lodekeeper.fabric;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;

/** Version-specific entry point for creating a disposable verification world. */
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
            registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createDimensionsRegistryHolder());
    }

    private static final class RubyOreBlock extends Block {
        private RubyOreBlock() {
            super(AbstractBlock.Settings.create().strength(3.0f, 3.0f).requiresTool());
            lootTableId = GameApi.identifier("lodekeeper_verification:blocks/ruby_ore");
        }
    }
}
