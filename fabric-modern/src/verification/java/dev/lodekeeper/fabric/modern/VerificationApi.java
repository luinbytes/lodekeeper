package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Version-specific vanilla world-creation flow for the isolated runtime verifier. */
final class VerificationApi {
    private VerificationApi() {}

    static String minecraftVersion() {
        return net.minecraft.SharedConstants.getCurrentVersion().name();
    }

    static Block dynamicCollisionBlock(Identifier blockId) {
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, blockId))
            .dynamicShape();
        return new DynamicCollisionBlock(properties);
    }

    static ItemStack namedGeometryStack(String name) {
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    static void seedPreparedSafetyFixture(ServerPlayer player, String mode) {
        if ("equipment".equals(mode)) {
            if (!player.getInventory().add(new ItemStack(Items.IRON_HELMET))) {
                throw new IllegalStateException("could not seed the prepared iron helmet");
            }
            return;
        }
        if (!"offhand".equals(mode)) throw new IllegalArgumentException("unsupported prepared safety mode: " + mode);
        if (!player.getInventory().add(new ItemStack(Items.COOKED_BEEF))
                || !player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared offhand stock");
        }
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.COOKED_BEEF));
        player.getFoodData().setFoodLevel(7);
        player.getFoodData().setSaturation(0.0F);
    }

    static PreparedSafetyPursuitFixture seedPreparedSafetyPursuitFixture(ServerPlayer player,
                                                                          ServerLevel world) {
        for (int x = -12; x <= 18; x++) for (int y = 64; y <= 67; y++) {
            world.setBlockAndUpdate(new BlockPos(x, y, -6), Blocks.BEDROCK.defaultBlockState());
            world.setBlockAndUpdate(new BlockPos(x, y, 6), Blocks.BEDROCK.defaultBlockState());
        }
        for (int z = -6; z <= 6; z++) for (int y = 64; y <= 67; y++) {
            world.setBlockAndUpdate(new BlockPos(-12, y, z), Blocks.BEDROCK.defaultBlockState());
            world.setBlockAndUpdate(new BlockPos(18, y, z), Blocks.BEDROCK.defaultBlockState());
        }
        if (!player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the pursuit bucket ingredients and crafting table");
        }
        player.getFoodData().setFoodLevel(7);
        player.getFoodData().setSaturation(0.0F);
        Mob cow = preparedMob(world, "minecraft:cow");
        cow.setPos(8.5, 64.0, 0.5);
        cow.setYRot(180.0F);
        cow.setXRot(0.0F);
        if (!world.addFreshEntity(cow)) throw new IllegalStateException("could not spawn the prepared pursuit cow");
        return new PreparedSafetyPursuitFixture(cow, player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel());
    }

    static void observePreparedSafetyPursuitTick(PreparedSafetyPursuitFixture fixture,
                                                  ServerPlayer player, int serverTick) {
        fixture.observe(player, serverTick);
    }

    static Map<String, String> preparedSafetyPursuitReceipt(ServerPlayer player,
                                                              PreparedSafetyPursuitFixture fixture) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("cowUuid", fixture.cow.getUUID().toString());
        result.put("cowInitialUuid", fixture.cowUuid);
        result.put("cowAlive", Boolean.toString(fixture.cow.isAlive()));
        result.put("cowRemoved", Boolean.toString(fixture.cow.isRemoved()));
        result.put("cowInitialHealth", Float.toString(fixture.initialHealth));
        result.put("cowHealth", Float.toString(fixture.cow.getHealth()));
        result.put("cowHealthDropsObserved", Integer.toString(fixture.healthDrops));
        result.put("pursuitObservationStarted", Boolean.toString(fixture.observationStartServerTick >= 0));
        result.put("pursuitObservationStartServerTick", Integer.toString(fixture.observationStartServerTick));
        result.put("pursuitObservationEndServerTick", Integer.toString(fixture.observationEndServerTick));
        result.put("pursuitObservedServerTicks", Integer.toString(fixture.observedServerTicks));
        result.put("cowObservationStartX", Double.toString(fixture.observationStartX));
        result.put("cowObservationStartY", Double.toString(fixture.observationStartY));
        result.put("cowObservationStartZ", Double.toString(fixture.observationStartZ));
        result.put("cowCurrentX", Double.toString(fixture.cow.getX()));
        result.put("cowCurrentY", Double.toString(fixture.cow.getY()));
        result.put("cowCurrentZ", Double.toString(fixture.cow.getZ()));
        result.put("cowMaximumHorizontalDisplacement", Double.toString(fixture.maximumHorizontalDisplacement));
        result.put("maximumDistanceFromPlayerStart", Double.toString(fixture.maximumDistanceFromPlayerStart));
        result.put("playerObservationStartX", Double.toString(fixture.playerObservationStartX));
        result.put("playerObservationStartY", Double.toString(fixture.playerObservationStartY));
        result.put("playerObservationStartZ", Double.toString(fixture.playerObservationStartZ));
        result.put("playerCurrentX", Double.toString(player.getX()));
        result.put("playerCurrentY", Double.toString(player.getY()));
        result.put("playerCurrentZ", Double.toString(player.getZ()));
        result.put("initialFoodLevel", Integer.toString(fixture.initialFoodLevel));
        result.put("initialSaturation", Float.toString(fixture.initialSaturation));
        result.put("currentFoodLevel", Integer.toString(player.getFoodData().getFoodLevel()));
        result.put("currentSaturation", Float.toString(player.getFoodData().getSaturationLevel()));
        return Map.copyOf(result);
    }

    static final class PreparedSafetyPursuitFixture {
        private final Mob cow;
        private final String cowUuid;
        private final float initialHealth;
        private final int initialFoodLevel;
        private final float initialSaturation;
        private volatile boolean observing;
        private float lastObservedHealth;
        private int healthDrops;
        private int observationStartServerTick = -1;
        private int observationEndServerTick = -1;
        private int observedServerTicks;
        private double observationStartX = Double.NaN, observationStartY = Double.NaN,
            observationStartZ = Double.NaN;
        private double playerObservationStartX = Double.NaN, playerObservationStartY = Double.NaN,
            playerObservationStartZ = Double.NaN;
        private double maximumHorizontalDisplacement;
        private double maximumDistanceFromPlayerStart;

        private PreparedSafetyPursuitFixture(Mob cow, int initialFoodLevel, float initialSaturation) {
            this.cow = cow;
            cowUuid = cow.getUUID().toString();
            initialHealth = cow.getHealth();
            lastObservedHealth = initialHealth;
            this.initialFoodLevel = initialFoodLevel;
            this.initialSaturation = initialSaturation;
        }

        void beginObservation() { observing = true; }

        private void observe(ServerPlayer player, int serverTick) {
            if (!observing) return;
            if (observationStartServerTick < 0) {
                observationStartServerTick = serverTick;
                observationStartX = cow.getX();
                observationStartY = cow.getY();
                observationStartZ = cow.getZ();
                playerObservationStartX = player.getX();
                playerObservationStartY = player.getY();
                playerObservationStartZ = player.getZ();
            }
            float health = cow.getHealth();
            if (health < lastObservedHealth - 0.001F) healthDrops++;
            lastObservedHealth = health;
            double dx = cow.getX() - observationStartX;
            double dz = cow.getZ() - observationStartZ;
            maximumHorizontalDisplacement = Math.max(maximumHorizontalDisplacement, Math.sqrt(dx * dx + dz * dz));
            double playerDx = cow.getX() - playerObservationStartX;
            double playerDy = cow.getY() - playerObservationStartY;
            double playerDz = cow.getZ() - playerObservationStartZ;
            maximumDistanceFromPlayerStart = Math.max(maximumDistanceFromPlayerStart,
                Math.sqrt(playerDx * playerDx + playerDy * playerDy + playerDz * playerDz));
            observationEndServerTick = serverTick;
            observedServerTicks++;
        }
    }

    static void seedPreparedSafetyIngredients(ServerPlayer player) {
        if (!player.getInventory().add(new ItemStack(Items.OAK_LOG, 2))
                || !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared oak logs and owned crafting table");
        }
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.OAK_LOG, 8));
    }

    static Map<String, String> equippedItems(ServerPlayer player) {
        Map<String, String> result = new LinkedHashMap<>();
        putEquippedItem(result, player, "head", EquipmentSlot.HEAD);
        putEquippedItem(result, player, "chest", EquipmentSlot.CHEST);
        putEquippedItem(result, player, "legs", EquipmentSlot.LEGS);
        putEquippedItem(result, player, "feet", EquipmentSlot.FEET);
        putEquippedItem(result, player, "offhand", EquipmentSlot.OFFHAND);
        return Map.copyOf(result);
    }

    private static void putEquippedItem(Map<String, String> result, ServerPlayer player,
                                        String name, EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        if (!stack.isEmpty()) result.put(name, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    static boolean serverCursorEmpty(ServerPlayer player) {
        return player.containerMenu.getCarried().isEmpty();
    }

    static PreparedSafetyThreatFixture seedPreparedSafetyThreatFixture(ServerPlayer player, ServerLevel world) {
        boolean waterRetreat = Boolean.getBoolean("lodekeeper.verify.threatWaterRetreat");
        if (waterRetreat) {
            for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) {
                world.setBlock(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState(), 3);
                for (int y = 64; y <= 66; y++) world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++) {
                world.setBlock(new BlockPos(x, 62, z), Blocks.BEDROCK.defaultBlockState(), 3);
                world.setBlock(new BlockPos(x, 63, z), Blocks.WATER.defaultBlockState(), 3);
            }
            for (int z = -3; z <= -1; z++) world.setBlock(new BlockPos(0, 65, z), Blocks.BEDROCK.defaultBlockState(), 3);
            world.setBlock(new BlockPos(20, 63, 20), Blocks.BEDROCK.defaultBlockState(), 3);
            world.setBlock(new BlockPos(18, 63, 20), Blocks.BEDROCK.defaultBlockState(), 3);
            world.setBlock(new BlockPos(20, 67, 20), Blocks.BEDROCK.defaultBlockState(), 3);
        } else {
            for (int x = 0; x <= 2; x++) for (int z = 0; z <= 1; z++) {
                world.setBlock(new BlockPos(x, 67, z), Blocks.BEDROCK.defaultBlockState(), 3);
            }
            world.setBlock(new BlockPos(3, 64, 0), Blocks.BEDROCK.defaultBlockState(), 3);
            world.setBlock(new BlockPos(3, 65, 0), Blocks.BEDROCK.defaultBlockState(), 3);
        }
        if (!player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD))
                || !player.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE))
                || !player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared threat weapons and bucket stock");
        }
        player.getInventory().setSelectedSlot(0);
        Mob zombie = preparedMob(world, "minecraft:zombie");
        zombie.setPos(waterRetreat ? 20.5 : 2.5, 64.0, waterRetreat ? 20.5 : 0.5);
        zombie.setYRot(180.0F);
        zombie.setXRot(0.0F);
        zombie.setNoAi(true);
        zombie.setHealth(4.0F);
        Mob cow = preparedMob(world, "minecraft:cow");
        cow.setPos(waterRetreat ? 18.5 : 2.5, 64.0, waterRetreat ? 20.5 : 1.5);
        cow.setYRot(180.0F);
        cow.setXRot(0.0F);
        cow.setNoAi(true);
        if (!world.addFreshEntity(zombie) || !world.addFreshEntity(cow)) {
            throw new IllegalStateException("could not spawn the prepared native zombie and cow");
        }
        Mob creeper = null;
        if (waterRetreat) {
            creeper = preparedMob(world, "minecraft:creeper");
            creeper.setPos(2.5, 64.0, 5.5);
            creeper.setNoAi(true);
            creeper.setHealth(20.0F);
            if (!world.addFreshEntity(creeper)) throw new IllegalStateException("could not spawn the prepared native creeper");
        }
        return waterRetreat ? new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth(), creeper)
            : new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth());
    }

    private static Mob preparedMob(ServerLevel world, String id) {
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
        var entity = type == null ? null : type.create(world, EntitySpawnReason.COMMAND);
        if (!(entity instanceof Mob mob)
                || !id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())) {
            throw new IllegalStateException("prepared native mob type is unavailable: " + id);
        }
        return mob;
    }

    static Map<String, String> preparedSafetyThreatReceipt(ServerPlayer player,
                                                            PreparedSafetyThreatFixture fixture) {
        ItemStack sword = player.getInventory().getItem(0);
        ItemStack pickaxe = player.getInventory().getItem(1);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("zombieUuid", fixture.zombie.getUUID().toString());
        result.put("zombieAlive", Boolean.toString(fixture.zombie.isAlive()));
        result.put("zombieRemoved", Boolean.toString(fixture.zombie.isRemoved()));
        result.put("zombieHealth", Float.toString(fixture.zombie.getHealth()));
        result.put("zombieOnFire", Boolean.toString(fixture.zombie.isOnFire()));
        result.put("zombieCanSeeSky", Boolean.toString(fixture.zombie.level().canSeeSky(fixture.zombie.blockPosition())));
        var lastDamage = fixture.zombie.getLastDamageSource();
        result.put("zombieLastDamage", lastDamage == null ? "" : lastDamage.getMsgId());
        result.put("cowUuid", fixture.cow.getUUID().toString());
        result.put("cowAlive", Boolean.toString(fixture.cow.isAlive()));
        result.put("cowInitialHealth", Float.toString(fixture.cowInitialHealth));
        result.put("cowHealth", Float.toString(fixture.cow.getHealth()));
        result.put("diamondSwordDamage", Integer.toString(sword.is(Items.DIAMOND_SWORD) ? sword.getDamageValue() : -1));
        result.put("woodenPickaxeDamage", Integer.toString(pickaxe.is(Items.WOODEN_PICKAXE) ? pickaxe.getDamageValue() : -1));
        result.put("preparedThreatsCleared", Boolean.toString(!fixture.zombie.isAlive() && fixture.zombie.getHealth() <= 0.0F));
        if (fixture.creeper != null) {
            var world = fixture.creeper.level();
            result.put("creeperUuid", fixture.creeper.getUUID().toString());
            result.put("creeperAlive", Boolean.toString(fixture.creeper.isAlive()));
            result.put("creeperRemoved", Boolean.toString(fixture.creeper.isRemoved()));
            result.put("creeperHealth", Float.toString(fixture.creeper.getHealth()));
            result.put("creeperDistanceSquared", Double.toString(fixture.creeper.distanceToSqr(player)));
            boolean roofPresent = true, waterPresent = true, floorPresent = true;
            for (int z = -3; z <= -1; z++) roofPresent &= world.getBlockState(new BlockPos(0, 65, z)).is(Blocks.BEDROCK);
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++) {
                BlockPos cell = new BlockPos(x, 63, z);
                waterPresent &= world.getBlockState(cell).is(Blocks.WATER) && world.getFluidState(cell).isSource();
                floorPresent &= world.getBlockState(new BlockPos(x, 62, z)).is(Blocks.BEDROCK);
            }
            BlockPos feet = player.blockPosition();
            result.put("lowWaterRoofPresent", Boolean.toString(roofPresent));
            result.put("waterSourceCellsPresent", Boolean.toString(waterPresent));
            result.put("waterFloorPresent", Boolean.toString(floorPresent));
            result.put("playerInWater", Boolean.toString(player.isInWater()));
            result.put("playerSupportBedrock", Boolean.toString(world.getBlockState(feet.below()).is(Blocks.BEDROCK)));
            result.put("playerBodyCellsAir", Boolean.toString(world.getBlockState(feet).is(Blocks.AIR)
                    && world.getBlockState(feet.above()).is(Blocks.AIR)));
        }
        return Map.copyOf(result);
    }

    static final class PreparedSafetyThreatFixture {
        private final Mob zombie;
        private final Mob cow;
        private final float cowInitialHealth;
        private final Mob creeper;

        private PreparedSafetyThreatFixture(Mob zombie, Mob cow, float cowInitialHealth) {
            this(zombie, cow, cowInitialHealth, null);
        }
        private PreparedSafetyThreatFixture(Mob zombie, Mob cow, float cowInitialHealth, Mob creeper) {
            this.zombie = zombie;
            this.cow = cow;
            this.cowInitialHealth = cowInitialHealth;
            this.creeper = creeper;
        }
    }

    static PreparedSafetyStationRoomFixture seedPreparedSafetyStationRoomFixture(ServerPlayer player, ServerLevel world) {
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
            world.setBlock(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState(), 3);
            for (int y = 64; y <= 70; y++) {
                world.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        world.setBlock(new BlockPos(0, 64, 0), Blocks.AIR.defaultBlockState(), 3);
        world.setBlock(new BlockPos(0, 65, 0), Blocks.AIR.defaultBlockState(), 3);
        if (!player.getInventory().add(new ItemStack(Items.STONE_PICKAXE))
                || !player.getInventory().add(new ItemStack(Items.FURNACE))
                || !player.getInventory().add(new ItemStack(Items.COAL))
                || !player.getInventory().add(new ItemStack(Items.RAW_IRON))) {
            throw new IllegalStateException("could not seed the prepared station-room pickaxe, furnace, coal, and raw iron");
        }
        player.getInventory().setSelectedSlot(0);
        BlockPos[] nearbyStoneCells = new BlockPos[73];
        int index = 0;
        for (int x = -2; x <= 2; x++) for (int y = 64; y <= 66; y++) for (int z = -2; z <= 2; z++) {
            if (x == 0 && z == 0 && (y == 64 || y == 65)) continue;
            nearbyStoneCells[index++] = new BlockPos(x, y, z);
        }
        if (index != nearbyStoneCells.length) throw new IllegalStateException("station-room receipt cell count differs");
        return new PreparedSafetyStationRoomFixture(nearbyStoneCells);
    }

    static Map<String, String> preparedSafetyStationRoomReceipt(ServerPlayer player, ServerLevel world,
                                                                  PreparedSafetyStationRoomFixture fixture) {
        int stillStone = 0;
        int changedStone = 0;
        StringBuilder changedPositions = new StringBuilder();
        for (BlockPos position : fixture.nearbyStoneCells) {
            var state = world.getBlockState(position);
            if (state.is(Blocks.STONE)) {
                stillStone++;
                continue;
            }
            if (changedStone++ > 0) changedPositions.append(';');
            changedPositions.append(position.getX()).append(',').append(position.getY()).append(',')
                .append(position.getZ()).append('=').append(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        int nearbyFurnaces = 0;
        boolean allFurnaceFloorsBedrock = true;
        StringBuilder furnacePositions = new StringBuilder();
        for (int x = -2; x <= 2; x++) for (int y = 64; y <= 70; y++) for (int z = -2; z <= 2; z++) {
            if (x * x + z * z > 4) continue;
            BlockPos position = new BlockPos(x, y, z);
            if (!world.getBlockState(position).is(Blocks.FURNACE)) continue;
            if (nearbyFurnaces++ > 0) furnacePositions.append(';');
            furnacePositions.append(x).append(',').append(y).append(',').append(z);
            allFurnaceFloorsBedrock &= world.getBlockState(position.below()).is(Blocks.BEDROCK);
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("roomStoneCellCandidateCount", Integer.toString(fixture.nearbyStoneCells.length));
        result.put("roomStoneCellsStillStone", Integer.toString(stillStone));
        result.put("roomStoneCellsChangedCount", Integer.toString(changedStone));
        result.put("roomStoneCellsChangedPositions", changedPositions.toString());
        result.put("nearbyFurnaceCount", Integer.toString(nearbyFurnaces));
        result.put("nearbyFurnacePositions", furnacePositions.toString());
        result.put("playerSupportBedrock", Boolean.toString(world.getBlockState(new BlockPos(0, 63, 0)).is(Blocks.BEDROCK)));
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



    static void startFlatWorld(Minecraft client, String worldId, LevelStorageSource storage,
                               Runnable started, java.util.function.Consumer<Throwable> failed) {
        CreateWorldScreen.openFresh(client, () -> {}, (screen, registries, worldData, gameRules, ignoredTempDataPackDir) -> {
            LevelStorageSource.LevelStorageAccess access = null;
            try {
                if (!storage.isNewLevelIdAcceptable(worldId)) {
                    throw new IOException("isolated world ID already exists or is invalid: " + worldId);
                }
                access = storage.validateAndCreateAccess(worldId);
                WorldCreationUiState settings = screen.getUiState();
                client.createWorldOpenFlows().createLevelFromExistingSettings(
                    access,
                    settings.getSettings().dataPackResources(),
                    registries,
                    worldData,
                    gameRules
                );
                started.run();
                return true;
            } catch (Throwable failure) {
                if (access != null) access.safeClose();
                failed.accept(failure);
                return false;
            }
        });

        Screen current = GameApi.screen(client);
        if (!(current instanceof CreateWorldScreen createScreen)) {
            throw new IllegalStateException("vanilla world creation screen did not open");
        }
        WorldCreationUiState settings = createScreen.getUiState();
        settings.setName("Lodekeeper verification " + worldId.substring(worldId.length() - 8));
        settings.setSeed("483920105");
        settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
        settings.setDifficulty(Difficulty.PEACEFUL);
        settings.setAllowCommands(false);
        settings.setGenerateStructures(false);
        settings.setBonusChest(false);
        WorldCreationUiState.WorldTypeEntry flat = java.util.stream.Stream
            .concat(settings.getNormalPresetList().stream(), settings.getAltPresetList().stream())
            .filter(entry -> entry.preset().is(WorldPresets.FLAT))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("built-in flat preset is unavailable"));
        settings.setWorldType(flat);

        Button create = createButton(createScreen);
        if (create == null || !create.isActive()) {
            throw new IllegalStateException("vanilla create-world button is unavailable");
        }
        create.onPress(new KeyEvent(0, 0, 0));
    }

    static void startNormalWorld(Minecraft client, String worldId, LevelStorageSource storage, String seed,
                                 Runnable started, java.util.function.Consumer<Throwable> failed) {
        CreateWorldScreen.openFresh(client, () -> {}, (screen, registries, worldData, gameRules, ignoredTempDataPackDir) -> {
            LevelStorageSource.LevelStorageAccess access = null;
            try {
                if (!storage.isNewLevelIdAcceptable(worldId)) {
                    throw new IOException("isolated world ID already exists or is invalid: " + worldId);
                }
                access = storage.validateAndCreateAccess(worldId);
                WorldCreationUiState settings = screen.getUiState();
                client.createWorldOpenFlows().createLevelFromExistingSettings(
                    access,
                    settings.getSettings().dataPackResources(),
                    registries,
                    worldData,
                    gameRules
                );
                started.run();
                return true;
            } catch (Throwable failure) {
                if (access != null) access.safeClose();
                failed.accept(failure);
                return false;
            }
        });

        Screen current = GameApi.screen(client);
        if (!(current instanceof CreateWorldScreen createScreen)) {
            throw new IllegalStateException("vanilla world creation screen did not open");
        }
        WorldCreationUiState settings = createScreen.getUiState();
        settings.setName("Lodekeeper natural verification " + worldId.substring(worldId.length() - 8));
        settings.setSeed(seed);
        settings.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
        settings.setDifficulty(Difficulty.NORMAL);
        settings.setAllowCommands(false);
        settings.setGenerateStructures(true);
        settings.setBonusChest(false);
        WorldCreationUiState.WorldTypeEntry normal = java.util.stream.Stream
            .concat(settings.getNormalPresetList().stream(), settings.getAltPresetList().stream())
            .filter(entry -> entry.preset().is(WorldPresets.NORMAL))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("built-in normal preset is unavailable"));
        settings.setWorldType(normal);

        Button create = createButton(createScreen);
        if (create == null || !create.isActive()) {
            throw new IllegalStateException("vanilla create-world button is unavailable");
        }
        create.onPress(new KeyEvent(0, 0, 0));
    }

    private static Button createButton(CreateWorldScreen screen) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button
                && button.getMessage().getContents() instanceof TranslatableContents translated
                && translated.getKey().equals("selectWorld.create")) {
                return button;
            }
        }
        return null;
    }

    private static final class DynamicCollisionBlock extends Block {
        private DynamicCollisionBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos,
                                               CollisionContext context) {
            return VerificationContentInitializer.geometryBlockFull ? Shapes.block() : Shapes.empty();
        }
    }
}
