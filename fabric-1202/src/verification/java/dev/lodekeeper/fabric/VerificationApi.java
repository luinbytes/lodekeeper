package dev.lodekeeper.fabric;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.CowEntity;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
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

/** Minecraft 1.20.2–1.20.4 verifier construction hooks. */
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
        stack.setCustomName(Text.literal(name));
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

    static void seedPreparedSafetyFixture(ServerPlayerEntity player, String mode) {
        if ("equipment".equals(mode)) {
            if (!player.getInventory().insertStack(new ItemStack(Items.IRON_HELMET))) {
                throw new IllegalStateException("could not seed the prepared iron helmet");
            }
            return;
        }
        if (!"offhand".equals(mode)) throw new IllegalArgumentException("unsupported prepared safety mode: " + mode);
        if (!player.getInventory().insertStack(new ItemStack(Items.COOKED_BEEF))
                || !player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared offhand stock");
        }
        player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.COOKED_BEEF));
        player.getHungerManager().setFoodLevel(7);
        player.getHungerManager().setSaturationLevel(0.0F);
    }

    static void seedPreparedSafetyIngredients(ServerPlayerEntity player) {
        if (!player.getInventory().insertStack(new ItemStack(Items.OAK_LOG, 2))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared oak logs and owned crafting table");
        }
        player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.OAK_LOG, 8));
    }

    static Map<String, String> equippedItems(ServerPlayerEntity player) {
        Map<String, String> result = new LinkedHashMap<>();
        putEquippedItem(result, player, "head", EquipmentSlot.HEAD);
        putEquippedItem(result, player, "chest", EquipmentSlot.CHEST);
        putEquippedItem(result, player, "legs", EquipmentSlot.LEGS);
        putEquippedItem(result, player, "feet", EquipmentSlot.FEET);
        putEquippedItem(result, player, "offhand", EquipmentSlot.OFFHAND);
        return Map.copyOf(result);
    }

    private static void putEquippedItem(Map<String, String> result, ServerPlayerEntity player,
                                        String name, EquipmentSlot slot) {
        ItemStack stack = player.getEquippedStack(slot);
        if (!stack.isEmpty()) result.put(name, Registries.ITEM.getId(stack.getItem()).toString());
    }

    static boolean serverCursorEmpty(ServerPlayerEntity player) {
        return player.currentScreenHandler.getCursorStack().isEmpty();
    }

    static PreparedSafetyThreatFixture seedPreparedSafetyThreatFixture(ServerPlayerEntity player, ServerWorld world) {
        for (int x = 0; x <= 2; x++) for (int z = 0; z <= 1; z++) {
            world.setBlockState(new BlockPos(x, 67, z), Blocks.BEDROCK.getDefaultState(), 3);
        }
        world.setBlockState(new BlockPos(3, 64, 0), Blocks.BEDROCK.getDefaultState(), 3);
        world.setBlockState(new BlockPos(3, 65, 0), Blocks.BEDROCK.getDefaultState(), 3);
        if (!player.getInventory().insertStack(new ItemStack(Items.DIAMOND_SWORD))
                || !player.getInventory().insertStack(new ItemStack(Items.WOODEN_PICKAXE))
                || !player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared threat weapons and bucket stock");
        }
        ClientAccess.selectedSlot(player.getInventory(), 0);
        ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
        zombie.refreshPositionAndAngles(2.5, 64.0, 0.5, 180.0F, 0.0F);
        zombie.setAiDisabled(true);
        zombie.setHealth(4.0F);
        CowEntity cow = new CowEntity(EntityType.COW, world);
        cow.refreshPositionAndAngles(2.5, 64.0, 1.5, 180.0F, 0.0F);
        cow.setAiDisabled(true);
        if (!world.spawnEntity(zombie) || !world.spawnEntity(cow)) {
            throw new IllegalStateException("could not spawn the prepared native zombie and cow");
        }
        return new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth());
    }

    static Map<String, String> preparedSafetyThreatReceipt(ServerPlayerEntity player,
                                                            PreparedSafetyThreatFixture fixture) {
        ItemStack sword = player.getInventory().getStack(0);
        ItemStack pickaxe = player.getInventory().getStack(1);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("zombieUuid", fixture.zombie.getUuid().toString());
        result.put("zombieAlive", Boolean.toString(fixture.zombie.isAlive()));
        result.put("zombieRemoved", Boolean.toString(fixture.zombie.isRemoved()));
        result.put("zombieHealth", Float.toString(fixture.zombie.getHealth()));
        result.put("cowUuid", fixture.cow.getUuid().toString());
        result.put("cowAlive", Boolean.toString(fixture.cow.isAlive()));
        result.put("cowInitialHealth", Float.toString(fixture.cowInitialHealth));
        result.put("cowHealth", Float.toString(fixture.cow.getHealth()));
        result.put("diamondSwordDamage", Integer.toString(sword.isOf(Items.DIAMOND_SWORD) ? sword.getDamage() : -1));
        result.put("woodenPickaxeDamage", Integer.toString(pickaxe.isOf(Items.WOODEN_PICKAXE) ? pickaxe.getDamage() : -1));
        result.put("preparedThreatsCleared", Boolean.toString(!fixture.zombie.isAlive() && fixture.zombie.getHealth() <= 0.0F));
        return Map.copyOf(result);
    }

    static final class PreparedSafetyThreatFixture {
        private final ZombieEntity zombie;
        private final CowEntity cow;
        private final float cowInitialHealth;

        private PreparedSafetyThreatFixture(ZombieEntity zombie, CowEntity cow, float cowInitialHealth) {
            this.zombie = zombie;
            this.cow = cow;
            this.cowInitialHealth = cowInitialHealth;
        }
    }
    static PreparedSafetyStationRoomFixture seedPreparedSafetyStationRoomFixture(ServerPlayerEntity player, ServerWorld world) {
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
            world.setBlockState(new BlockPos(x, 63, z), Blocks.BEDROCK.getDefaultState(), 3);
            for (int y = 64; y <= 70; y++) {
                world.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState(), 3);
            }
        }
        world.setBlockState(new BlockPos(0, 64, 0), Blocks.AIR.getDefaultState(), 3);
        world.setBlockState(new BlockPos(0, 65, 0), Blocks.AIR.getDefaultState(), 3);
        if (!player.getInventory().insertStack(new ItemStack(Items.STONE_PICKAXE))
                || !player.getInventory().insertStack(new ItemStack(Items.FURNACE))
                || !player.getInventory().insertStack(new ItemStack(Items.COAL))
                || !player.getInventory().insertStack(new ItemStack(Items.RAW_IRON))) {
            throw new IllegalStateException("could not seed the prepared station-room pickaxe, furnace, coal, and raw iron");
        }
        ClientAccess.selectedSlot(player.getInventory(), 0);
        BlockPos[] nearbyStoneCells = new BlockPos[73];
        int index = 0;
        for (int x = -2; x <= 2; x++) for (int y = 64; y <= 66; y++) for (int z = -2; z <= 2; z++) {
            if (x == 0 && z == 0 && (y == 64 || y == 65)) continue;
            nearbyStoneCells[index++] = new BlockPos(x, y, z);
        }
        if (index != nearbyStoneCells.length) throw new IllegalStateException("station-room receipt cell count differs");
        return new PreparedSafetyStationRoomFixture(nearbyStoneCells);
    }

    static Map<String, String> preparedSafetyStationRoomReceipt(ServerPlayerEntity player, ServerWorld world,
                                                                  PreparedSafetyStationRoomFixture fixture) {
        int stillStone = 0;
        int changedStone = 0;
        StringBuilder changedPositions = new StringBuilder();
        for (BlockPos position : fixture.nearbyStoneCells) {
            var state = world.getBlockState(position);
            if (state.isOf(Blocks.STONE)) {
                stillStone++;
                continue;
            }
            if (changedStone++ > 0) changedPositions.append(';');
            changedPositions.append(position.getX()).append(',').append(position.getY()).append(',')
                .append(position.getZ()).append('=').append(Registries.BLOCK.getId(state.getBlock()));
        }
        int nearbyFurnaces = 0;
        boolean allFurnaceFloorsBedrock = true;
        StringBuilder furnacePositions = new StringBuilder();
        for (int x = -2; x <= 2; x++) for (int y = 64; y <= 70; y++) for (int z = -2; z <= 2; z++) {
            if (x * x + z * z > 4) continue;
            BlockPos position = new BlockPos(x, y, z);
            if (!world.getBlockState(position).isOf(Blocks.FURNACE)) continue;
            if (nearbyFurnaces++ > 0) furnacePositions.append(';');
            furnacePositions.append(x).append(',').append(y).append(',').append(z);
            allFurnaceFloorsBedrock &= world.getBlockState(position.down()).isOf(Blocks.BEDROCK);
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("roomStoneCellCandidateCount", Integer.toString(fixture.nearbyStoneCells.length));
        result.put("roomStoneCellsStillStone", Integer.toString(stillStone));
        result.put("roomStoneCellsChangedCount", Integer.toString(changedStone));
        result.put("roomStoneCellsChangedPositions", changedPositions.toString());
        result.put("nearbyFurnaceCount", Integer.toString(nearbyFurnaces));
        result.put("nearbyFurnacePositions", furnacePositions.toString());
        result.put("playerSupportBedrock", Boolean.toString(world.getBlockState(new BlockPos(0, 63, 0)).isOf(Blocks.BEDROCK)));
        result.put("stationFloorBedrock", Boolean.toString(nearbyFurnaces > 0 && allFurnaceFloorsBedrock));
        result.put("preparedRoomStartPosition", "0.5,64,0.5");
        return Map.copyOf(result);
    }

    static final class PreparedSafetyStationRoomFixture {
        private final BlockPos[] nearbyStoneCells;

        private PreparedSafetyStationRoomFixture(BlockPos[] nearbyStoneCells) {
            this.nearbyStoneCells = nearbyStoneCells.clone();
        }
    }



    private static final class DynamicCollisionBlock extends Block {
        private DynamicCollisionBlock(AbstractBlock.Settings settings) {
            super(settings);
        }

        @Override
        public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return VerificationContentInitializer.geometryBlockFull ? VoxelShapes.fullCube() : VoxelShapes.empty();
        }
    }
}
