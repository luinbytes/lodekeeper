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
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
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

    static Block dynamicCollisionBlock(Identifier blockId) {
        AbstractBlock.Settings settings = AbstractBlock.Settings.create()
            .registryKey(RegistryKey.of(RegistryKeys.BLOCK, blockId))
            .dynamicBounds();
        return new DynamicCollisionBlock(settings);
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

    static ItemStack namedGeometryStack(String name) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
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

    static void seedPreparedSafetyFixture(ServerPlayerEntity player, String mode) {
        if ("held-fuel".equals(mode)) {
            if (!player.getInventory().insertStack(new ItemStack(Items.RAW_IRON, 3))
                    || !player.getInventory().insertStack(new ItemStack(Items.OAK_LOG, 2))
                    || !player.getInventory().insertStack(new ItemStack(Items.OAK_PLANKS, 3))
                    || !player.getInventory().insertStack(new ItemStack(Items.FURNACE))
                    || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
                throw new IllegalStateException("could not seed the declared held-fuel stock");
            }
            return;
        }
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

    static Map<String, String> preparedSafetyHeldFuelReceipt(ServerWorld world) {
        Map<String, String> result = new LinkedHashMap<>();
        int furnaces = 0, input = 0, fuel = 0, output = 0;
        StringBuilder positions = new StringBuilder();
        for (int x = -6; x <= 6; x++) for (int y = 64; y <= 67; y++) for (int z = -6; z <= 6; z++) {
            BlockPos position = new BlockPos(x, y, z);
            if (!world.getBlockState(position).isOf(Blocks.FURNACE)) continue;
            if (furnaces++ > 0) positions.append(';');
            positions.append(x).append(',').append(y).append(',').append(z);
            if (!(world.getBlockEntity(position) instanceof net.minecraft.inventory.Inventory inventory)) {
                throw new IllegalStateException("native fixture furnace has no inventory");
            }
            input += inventory.getStack(0).getCount();
            fuel += inventory.getStack(1).getCount();
            output += inventory.getStack(2).getCount();
        }
        result.put("nearbyFurnaceCount", Integer.toString(furnaces));
        result.put("nativeFurnacePositions", positions.toString());
        result.put("furnaceInputCount", Integer.toString(input));
        result.put("furnaceFuelCount", Integer.toString(fuel));
        result.put("furnaceOutputCount", Integer.toString(output));
        return Map.copyOf(result);
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
        boolean tunnel = "true".equals(System.getProperty("lodekeeper.verify.stationRoomTunnel"));
        boolean approach = "approach".equals(System.getProperty("lodekeeper.verify.stationRoomTunnel"));
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
            world.setBlockState(new BlockPos(x, 63, z), Blocks.BEDROCK.getDefaultState(), 3);
            for (int y = approach ? 58 : 64; y <= 70; y++) {
                world.setBlockState(new BlockPos(x, y, z), (approach ? Blocks.BEDROCK : tunnel ? Blocks.DEEPSLATE : Blocks.STONE).getDefaultState(), 3);
            }
        }
        if (approach) {
            for (BlockPos position : stationApproachAirCells()) world.setBlockState(position, Blocks.AIR.getDefaultState(), 3);
            for (BlockPos position : new BlockPos[]{new BlockPos(1, 63, 1), new BlockPos(0, 63, 1)}) {
                world.setBlockState(position, Blocks.CRAFTING_TABLE.getDefaultState(), 3);
            }
        } else {
            world.setBlockState(new BlockPos(0, 64, 0), Blocks.AIR.getDefaultState(), 3);
            world.setBlockState(new BlockPos(0, 65, 0), Blocks.AIR.getDefaultState(), 3);
        }
        if (tunnel) {
            for (int x = -2; x <= 0; x++) for (int y = 64; y <= 66; y++) {
                world.setBlockState(new BlockPos(x, y, 0), Blocks.AIR.getDefaultState(), 3);
            }
            world.setBlockState(new BlockPos(-1, 64, 0), Blocks.CRAFTING_TABLE.getDefaultState(), 3);
        }
        if (!player.getInventory().insertStack(new ItemStack(Items.STONE_PICKAXE))
                || !player.getInventory().insertStack(new ItemStack(Items.FURNACE))
                || !player.getInventory().insertStack(new ItemStack(Items.COAL))
                || !player.getInventory().insertStack(new ItemStack(Items.RAW_IRON))) {
            throw new IllegalStateException("could not seed the prepared station-room pickaxe, furnace, coal, and raw iron");
        }
        ClientAccess.selectedSlot(player.getInventory(), 0);
        BlockPos[] nearbyStoneCells = new BlockPos[approach ? 0 : tunnel ? 66 : 73];
        int index = 0;
        if (!approach) for (int x = -2; x <= 2; x++) for (int y = 64; y <= 66; y++) for (int z = -2; z <= 2; z++) {
            if (tunnel ? x <= 0 && z == 0 : x == 0 && z == 0 && (y == 64 || y == 65)) continue;
            nearbyStoneCells[index++] = new BlockPos(x, y, z);
        }
        if (index != nearbyStoneCells.length) throw new IllegalStateException("station-room receipt cell count differs");
        return new PreparedSafetyStationRoomFixture(nearbyStoneCells, tunnel, approach);
    }

    static Map<String, String> preparedSafetyStationRoomReceipt(ServerPlayerEntity player, ServerWorld world,
                                                                  PreparedSafetyStationRoomFixture fixture) {
        int stillStone = 0;
        int changedStone = 0;
        StringBuilder changedPositions = new StringBuilder();
        for (BlockPos position : fixture.nearbyStoneCells) {
            var state = world.getBlockState(position);
            if (state.isOf(fixture.tunnel ? Blocks.DEEPSLATE : Blocks.STONE)) {
                stillStone++;
                continue;
            }
            if (changedStone++ > 0) changedPositions.append(';');
            changedPositions.append(position.getX()).append(',').append(position.getY()).append(',')
                .append(position.getZ()).append('=').append(Registries.BLOCK.getId(state.getBlock()));
        }
        int nearbyFurnaces = 0;
        int furnaceInput = 0, furnaceFuel = 0, furnaceOutput = 0;
        boolean allFurnaceFloorsBedrock = true;
        StringBuilder furnacePositions = new StringBuilder();
        for (int x = -2; x <= 2; x++) for (int y = 64; y <= 70; y++) for (int z = -2; z <= 2; z++) {
            if (x * x + z * z > 4) continue;
            BlockPos position = new BlockPos(x, y, z);
            if (!world.getBlockState(position).isOf(Blocks.FURNACE)) continue;
            if (nearbyFurnaces++ > 0) furnacePositions.append(';');
            furnacePositions.append(x).append(',').append(y).append(',').append(z);
            allFurnaceFloorsBedrock &= world.getBlockState(position.down()).isOf(Blocks.BEDROCK);
            if (fixture.tunnel || fixture.approach) {
                if (!(world.getBlockEntity(position) instanceof net.minecraft.inventory.Inventory inventory)) {
                    throw new IllegalStateException("native station-room furnace has no inventory");
                }
                furnaceInput += inventory.getStack(0).getCount();
                furnaceFuel += inventory.getStack(1).getCount();
                furnaceOutput += inventory.getStack(2).getCount();
            }
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
        result.put("preparedRoomStartPosition", fixture.approach ? "1.5,65,0.5" : fixture.tunnel ? "0.367555,64,0.505802" : "0.5,64,0.5");
        if (fixture.tunnel || fixture.approach) {
            result.put("playerPosition", player.getX() + "," + player.getY() + "," + player.getZ());
            result.put("playerYaw", Float.toString(player.getYaw()));
            result.put("playerPitch", Float.toString(player.getPitch()));
            result.put("stonePickaxeHeld", Boolean.toString(player.getMainHandStack().isOf(Items.STONE_PICKAXE)));
            result.put("furnaceInputCount", Integer.toString(furnaceInput));
            result.put("furnaceFuelCount", Integer.toString(furnaceFuel));
            result.put("furnaceOutputCount", Integer.toString(furnaceOutput));
        }
        if (fixture.tunnel) {
            int floorCells = 0, airCells = 0;
            StringBuilder airPositions = new StringBuilder();
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                if (world.getBlockState(new BlockPos(x, 63, z)).isOf(Blocks.BEDROCK)) floorCells++;
            }
            for (int x = -2; x <= 0; x++) for (int y = 64; y <= 66; y++) {
                if (x == -1 && y == 64) continue;
                if (world.getBlockState(new BlockPos(x, y, 0)).isOf(Blocks.AIR)) {
                    if (airCells++ > 0) airPositions.append(';');
                    airPositions.append(x).append(',').append(y).append(",0");
                }
            }
            result.put("stationRoomSubmode", "tunnel");
            result.put("craftingTablePresent", Boolean.toString(world.getBlockState(new BlockPos(-1, 64, 0)).isOf(Blocks.CRAFTING_TABLE)));
            result.put("tunnelAirCellCount", Integer.toString(airCells));
            result.put("tunnelAirPositions", airPositions.toString());
            result.put("roomFloorBedrockCellCount", Integer.toString(floorCells));
            result.put("roomFloorBedrock", Boolean.toString(floorCells == 361));
        }
        if (fixture.approach) {
            int shellChanged = 0, airCells = 0, floorCells = 0;
            StringBuilder airPositions = new StringBuilder();
            for (BlockPos position : fixture.bedrockShellCells) {
                if (!world.getBlockState(position).isOf(Blocks.BEDROCK)) shellChanged++;
            }
            for (BlockPos position : stationApproachAirCells()) {
                if (world.getBlockState(position).isOf(Blocks.AIR)) {
                    if (airCells++ > 0) airPositions.append(';');
                    airPositions.append(position.getX()).append(',').append(position.getY()).append(',').append(position.getZ());
                }
            }
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                if (world.getBlockState(new BlockPos(x, 63, z)).isOf(Blocks.BEDROCK)) floorCells++;
            }
            result.put("stationRoomSubmode", "approach");
            result.put("approachStartStance", "1,65,0");
            result.put("approachValidStance", "0,64,1");
            result.put("playerBlockPosition", (int) Math.floor(player.getX()) + "," + (int) Math.floor(player.getY()) + "," + (int) Math.floor(player.getZ()));
            result.put("approachAirCellCount", Integer.toString(airCells));
            result.put("approachAirPositions", airPositions.toString());
            result.put("bedrockShellCellCount", Integer.toString(fixture.bedrockShellCells.length));
            result.put("bedrockShellCellsChangedCount", Integer.toString(shellChanged));
            result.put("roomFloorBedrockCellCount", Integer.toString(floorCells));
            result.put("approachFloorTablesPresent", Boolean.toString(world.getBlockState(new BlockPos(1, 63, 1)).isOf(Blocks.CRAFTING_TABLE)
                && world.getBlockState(new BlockPos(0, 63, 1)).isOf(Blocks.CRAFTING_TABLE)));
            result.put("approachCeilingPresent", Boolean.toString(world.getBlockState(new BlockPos(0, 65, 0)).isOf(Blocks.BEDROCK)));
        }
        return Map.copyOf(result);
    }

    private static BlockPos[] stationApproachAirCells() {
        return new BlockPos[]{new BlockPos(0, 64, 0), new BlockPos(1, 65, 0), new BlockPos(1, 66, 0),
            new BlockPos(1, 64, 1), new BlockPos(1, 65, 1), new BlockPos(1, 66, 1), new BlockPos(0, 64, 1), new BlockPos(0, 65, 1)};
    }

    static final class PreparedSafetyStationRoomFixture {
        private final BlockPos[] nearbyStoneCells;
        private final boolean tunnel;
        private final boolean approach;
        private final BlockPos[] bedrockShellCells;

        private PreparedSafetyStationRoomFixture(BlockPos[] nearbyStoneCells, boolean tunnel, boolean approach) {
            this.nearbyStoneCells = nearbyStoneCells.clone();
            this.tunnel = tunnel;
            this.approach = approach;
            java.util.List<BlockPos> shell = new java.util.ArrayList<>();
            if (approach) {
                java.util.Set<BlockPos> mutableCells = new java.util.HashSet<>(java.util.List.of(stationApproachAirCells()));
                mutableCells.add(new BlockPos(1, 63, 1));
                mutableCells.add(new BlockPos(0, 63, 1));
                for (int x = -9; x <= 9; x++) for (int y = 58; y <= 70; y++) for (int z = -9; z <= 9; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (!mutableCells.contains(position)) shell.add(position);
                }
            }
            this.bedrockShellCells = shell.toArray(BlockPos[]::new);
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
    static PreparedSafetyPursuitFixture seedPreparedSafetyPursuitFixture(ServerPlayerEntity player, ServerWorld world) {
        throw new UnsupportedOperationException("Moving-food pursuit verification is limited to Minecraft 1.21.1 and 26.3");
    }

    static void observePreparedSafetyPursuitTick(PreparedSafetyPursuitFixture fixture, ServerPlayerEntity player, int serverTick) {
        throw new UnsupportedOperationException("Moving-food pursuit verification is unavailable in this profile");
    }

    static Map<String, String> preparedSafetyPursuitReceipt(ServerPlayerEntity player, PreparedSafetyPursuitFixture fixture) {
        throw new UnsupportedOperationException("Moving-food pursuit verification is unavailable in this profile");
    }

    static final class PreparedSafetyPursuitFixture {
        void beginObservation() {
            throw new UnsupportedOperationException("Moving-food pursuit verification is unavailable in this profile");
        }
    }

    static void seedPreparedSafetyWorkbench(ServerPlayerEntity player, ServerWorld world) {
        throw new UnsupportedOperationException("owned-workbench verification requires Minecraft 1.21.1 or 26.3");
    }

    static PreparedSafetyWorkbenchFixture prepareOwnedWorkbenchRecovery(ServerPlayerEntity player, ServerWorld world, boolean blocked) {
        throw new UnsupportedOperationException("owned-workbench verification requires Minecraft 1.21.1 or 26.3");
    }

    static Map<String, String> preparedSafetyWorkbenchReceipt(ServerPlayerEntity player, ServerWorld world, PreparedSafetyWorkbenchFixture fixture) {
        throw new UnsupportedOperationException("owned-workbench verification requires Minecraft 1.21.1 or 26.3");
    }

    static final class PreparedSafetyWorkbenchFixture { }

    static PreparedSafetyAirFixture seedPreparedSafetyAirFixture(ServerPlayerEntity player, ServerWorld world) {
        throw new UnsupportedOperationException("Air recovery verification is limited to Minecraft 1.21.1 and 26.3");
    }

    static void initializePreparedSafetyAirFixture(PreparedSafetyAirFixture fixture, ServerPlayerEntity player, int serverTick) {
        throw new UnsupportedOperationException("Air recovery verification is unavailable in this profile");
    }

    static void observePreparedSafetyAirTick(PreparedSafetyAirFixture fixture, ServerPlayerEntity player, int serverTick) {
        throw new UnsupportedOperationException("Air recovery verification is unavailable in this profile");
    }

    static Map<String, String> preparedSafetyAirReceipt(ServerPlayerEntity player, PreparedSafetyAirFixture fixture) {
        throw new UnsupportedOperationException("Air recovery verification is unavailable in this profile");
    }

    static final class PreparedSafetyAirFixture { }

}
