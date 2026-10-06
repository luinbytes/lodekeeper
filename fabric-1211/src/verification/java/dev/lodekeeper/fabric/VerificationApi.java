package dev.lodekeeper.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.GameRules;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;

/** Minecraft 1.21.1 signature for creating the isolated verifier world. */
final class VerificationApi {
    private VerificationApi() {}

    static void screenshot(java.io.File directory, String name, MinecraftClient client,
                           java.util.function.Consumer<net.minecraft.text.Text> complete) {
        net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(directory, name, client.getFramebuffer(), complete);
    }

    static String minecraftVersion() {
        return net.minecraft.SharedConstants.getGameVersion().getName();
    }

    static Block rubyOre(Identifier blockId, Identifier lootTableId) {
        return new RubyOreBlock(lootTableId);
    }

    static Block dynamicCollisionBlock(Identifier blockId) {
        return new DynamicCollisionBlock(AbstractBlock.Settings.create().dynamicBounds());
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

    static ItemStack namedGeometryStack(String name) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    static void openCreateWorldScreen(MinecraftClient client, Screen parent) {
        CreateWorldScreen.create(client, parent);
    }

    static GameRules gameRules(WorldCreator creator) {
        return new GameRules();
    }

    static boolean teleport(ServerPlayerEntity player, ServerWorld world, double x, double y, double z,
                            float yaw, float pitch) {
        player.teleport(world, x, y, z, yaw, pitch);
        return true;
    }

    static void startFlatWorld(IntegratedServerLoader loader, String saveName, LevelInfo levelInfo, GeneratorOptions options) {
        loader.createAndStart(saveName, levelInfo, options,
            registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createDimensionsRegistryHolder(),
            (Screen) null);
    }

    static void startNormalWorld(IntegratedServerLoader loader, String saveName, LevelInfo levelInfo, GeneratorOptions options) {
        loader.createAndStart(saveName, levelInfo, options,
            registry -> registry.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.DEFAULT).createDimensionsRegistryHolder(),
            (Screen) null);
    }

    private static final class RubyOreBlock extends Block {
        private RubyOreBlock(Identifier lootTableId) {
            super(AbstractBlock.Settings.create().strength(3.0f, 3.0f).requiresTool());
            lootTableKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, lootTableId);
        }
    }

    private static final class DynamicCollisionBlock extends Block {
        private DynamicCollisionBlock(AbstractBlock.Settings settings) {
            super(settings);
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return VerificationContentInitializer.geometryBlockFull ? VoxelShapes.fullCube() : VoxelShapes.empty();
        }
    }
}
