package dev.lodekeeper.fabric;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.passive.CowEntity;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
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

    static void seedPreparedSafetyWorkbench(ServerPlayerEntity player, ServerWorld world) {
        for (int x = -10; x <= 10; x++) for (int z = -5; z <= 5; z++) {
            for (int y = 64; y <= 67; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 3);
        }
        if (!player.getInventory().insertStack(new ItemStack(Items.OAK_PLANKS, 12))) {
            throw new IllegalStateException("could not seed the twelve ordinary workbench planks");
        }
    }

    static PreparedSafetyWorkbenchFixture prepareOwnedWorkbenchRecovery(ServerPlayerEntity player,
                                                                          ServerWorld world, boolean blocked) {
        BlockPos table = null;
        for (int x = -10; x <= 10; x++) for (int z = -5; z <= 5; z++) for (int y = 64; y <= 67; y++) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (!world.getBlockState(candidate).isOf(Blocks.CRAFTING_TABLE)) continue;
            if (table != null) throw new IllegalStateException("setup command left more than one native crafting table");
            table = candidate;
        }
        if (table == null) throw new IllegalStateException("setup command did not leave its native crafting table");
        for (int x = table.getX() - 2; x <= table.getX() + 10; x++) for (int z = table.getZ() - 3; z <= table.getZ() + 3; z++) {
            world.setBlockState(new BlockPos(x, table.getY() - 1, z), Blocks.BEDROCK.getDefaultState(), 3);
        }
        if (blocked) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            world.setBlockState(new BlockPos(table.getX() + dx, table.getY() + dy, table.getZ() + dz), Blocks.BEDROCK.getDefaultState(), 3);
        }
        BlockPos stone = new BlockPos(table.getX() + 8, table.getY(), table.getZ());
        world.setBlockState(stone, Blocks.STONE.getDefaultState(), 3);
        if (!teleport(player, world, table.getX() + 6.5, table.getY(), table.getZ() + 0.5, 90.0F, 0.0F)) {
            throw new IllegalStateException("could not move the stopped verifier player six blocks from its owned table");
        }
        return new PreparedSafetyWorkbenchFixture(table, stone, blocked);
    }

    static Map<String, String> preparedSafetyWorkbenchReceipt(ServerPlayerEntity player, ServerWorld world,
                                                                PreparedSafetyWorkbenchFixture fixture) {
        Map<String, String> receipt = new LinkedHashMap<>();
        receipt.put("tablePosition", fixture.table.getX() + "," + fixture.table.getY() + "," + fixture.table.getZ());
        receipt.put("stonePosition", fixture.stone.getX() + "," + fixture.stone.getY() + "," + fixture.stone.getZ());
        receipt.put("tablePresent", Boolean.toString(world.getBlockState(fixture.table).isOf(Blocks.CRAFTING_TABLE)));
        receipt.put("tableAir", Boolean.toString(world.getBlockState(fixture.table).isAir()));
        receipt.put("stonePresent", Boolean.toString(world.getBlockState(fixture.stone).isOf(Blocks.STONE)));
        receipt.put("stoneAir", Boolean.toString(world.getBlockState(fixture.stone).isAir()));
        receipt.put("blocked", Boolean.toString(fixture.blocked));
        int shell = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            if (world.getBlockState(new BlockPos(fixture.table.getX() + dx, fixture.table.getY() + dy, fixture.table.getZ() + dz)).isOf(Blocks.BEDROCK)) shell++;
        }
        receipt.put("bedrockShellCells", Integer.toString(shell));
        double dx = player.getX() - fixture.table.getX() - 0.5;
        double dz = player.getZ() - fixture.table.getZ() - 0.5;
        receipt.put("horizontalDistance", Double.toString(Math.sqrt(dx * dx + dz * dz)));
        receipt.put("playerFeetY", Double.toString(player.getY()));
        return receipt;
    }

    static final class PreparedSafetyWorkbenchFixture {
        private final BlockPos table;
        private final BlockPos stone;
        private final boolean blocked;
        private PreparedSafetyWorkbenchFixture(BlockPos table, BlockPos stone, boolean blocked) {
            this.table = table;
            this.stone = stone;
            this.blocked = blocked;
        }
    }

    static PreparedSafetyAirFixture seedPreparedSafetyAirFixture(ServerPlayerEntity player, ServerWorld world) {
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) {
            world.setBlockState(new BlockPos(x, 63, z), Blocks.BEDROCK.getDefaultState(), 3);
        }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
            for (int y = 64; y <= 65; y++) {
                world.setBlockState(new BlockPos(x, y, z), Blocks.BEDROCK.getDefaultState(), 3);
            }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            world.setBlockState(new BlockPos(x, 64, z), Blocks.WATER.getDefaultState(), 3);
            world.setBlockState(new BlockPos(x, 65, z), Blocks.WATER.getDefaultState(), 3);
            if (x != 2 || z != 0) {
                world.setBlockState(new BlockPos(x, 66, z), Blocks.BEDROCK.getDefaultState(), 3);
            }
        }
        world.setBlockState(new BlockPos(4, 65, 0), Blocks.BEDROCK.getDefaultState(), 3);
        world.setBlockState(new BlockPos(4, 66, 0), Blocks.CRAFTING_TABLE.getDefaultState(), 3);
        if (!player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().insertStack(new ItemStack(Items.COOKED_BEEF, 2))) {
            throw new IllegalStateException("could not seed the prepared air-recovery iron and food stock");
        }
        return new PreparedSafetyAirFixture(world);
    }

    static void initializePreparedSafetyAirFixture(PreparedSafetyAirFixture fixture,
                                                    ServerPlayerEntity player, int serverTick) {
        player.setHealth(3.0F);
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(0.0F);
        setAirSupply(player, 200);
        fixture.begin(player, serverTick);
    }

    static void observePreparedSafetyAirTick(PreparedSafetyAirFixture fixture,
                                              ServerPlayerEntity player, int serverTick) {
        fixture.observe(player, serverTick);
    }

    static Map<String, String> preparedSafetyAirReceipt(ServerPlayerEntity player,
                                                         PreparedSafetyAirFixture fixture) {
        return fixture.receipt(player);
    }

    private static void setAirSupply(ServerPlayerEntity player, int airSupply) {
        player.setAir(airSupply);
    }

    private static int airSupply(ServerPlayerEntity player) {
        return player.getAir();
    }

    private static boolean headInWater(ServerPlayerEntity player) {
        return player.isSubmergedInWater();
    }

    private static String position(ServerPlayerEntity player) {
        return Double.toString(player.getX()) + "," + Double.toString(player.getY()) + ","
            + Double.toString(player.getZ());
    }

    static final class PreparedSafetyAirFixture {
        private final ServerWorld world;
        private boolean recording;
        private int startServerTick = -1;
        private int lastServerTick = -1;
        private int initialAirSupply = -1;
        private int minimumAirSupply = Integer.MAX_VALUE;
        private int maximumAirSupply = -1;
        private float initialHealth = Float.NaN;
        private float minimumHealth = Float.POSITIVE_INFINITY;
        private boolean initialHeadInWater;
        private boolean startHeadObserved;
        private int firstHeadInWaterServerTick = -1;
        private boolean wasAlive;
        private int deathsObserved;
        private int firstHeadOutOfWaterServerTick = -1;
        private double startX, startY, startZ;
        private double currentX, currentY, currentZ;
        private double maximumDistanceFromStart;
        private final StringBuilder serverPositionSamples = new StringBuilder();

        private PreparedSafetyAirFixture(ServerWorld world) {
            this.world = world;
        }

        private void begin(ServerPlayerEntity player, int serverTick) {
            recording = true;
            startServerTick = serverTick;
            initialAirSupply = airSupply(player);
            maximumAirSupply = player.getMaxAir();
            minimumAirSupply = initialAirSupply;
            initialHealth = player.getHealth();
            minimumHealth = initialHealth;
            initialHeadInWater = false;
            startHeadObserved = false;
            wasAlive = player.isAlive();
            startX = currentX = player.getX();
            startY = currentY = player.getY();
            startZ = currentZ = player.getZ();
            lastServerTick = serverTick;
            appendPosition(serverTick, player);
        }

        private void observe(ServerPlayerEntity player, int serverTick) {
            if (!recording) return;
            lastServerTick = serverTick;
            int currentAir = airSupply(player);
            boolean currentHeadInWater = headInWater(player);
            if (!startHeadObserved && serverTick > startServerTick && currentHeadInWater) {
                initialHeadInWater = true;
                startHeadObserved = true;
                firstHeadInWaterServerTick = serverTick;
            }
            minimumAirSupply = Math.min(minimumAirSupply, currentAir);
            minimumHealth = Math.min(minimumHealth, player.getHealth());
            if (wasAlive && !player.isAlive()) deathsObserved++;
            wasAlive = player.isAlive();
            if (initialHeadInWater && !currentHeadInWater && firstHeadOutOfWaterServerTick < 0) {
                firstHeadOutOfWaterServerTick = serverTick;
            }
            currentX = player.getX();
            currentY = player.getY();
            currentZ = player.getZ();
            double dx = currentX - startX, dy = currentY - startY, dz = currentZ - startZ;
            maximumDistanceFromStart = Math.max(maximumDistanceFromStart,
                Math.sqrt(dx * dx + dy * dy + dz * dz));
            if ((serverTick - startServerTick) % 10 == 0) appendPosition(serverTick, player);
        }

        private void appendPosition(int serverTick, ServerPlayerEntity player) {
            if (!serverPositionSamples.isEmpty()) serverPositionSamples.append(';');
            serverPositionSamples.append(serverTick).append('=').append(position(player));
        }

        private Map<String, String> receipt(ServerPlayerEntity player) {
            Map<String, String> result = new LinkedHashMap<>();
            result.put("fixtureAirSupply", Integer.toString(initialAirSupply));
            result.put("airSupply", Integer.toString(airSupply(player)));
            result.put("maxAirSupply", Integer.toString(maximumAirSupply));
            result.put("minimumAirSupply", Integer.toString(minimumAirSupply));
            result.put("headInWaterAtStart", Boolean.toString(initialHeadInWater));
            result.put("firstHeadInWaterServerTick", Integer.toString(firstHeadInWaterServerTick));
            result.put("headInWater", Boolean.toString(headInWater(player)));
            result.put("initialHealth", Float.toString(initialHealth));
            result.put("minimumHealth", Float.toString(minimumHealth));
            result.put("foodLevel", Integer.toString(player.getHungerManager().getFoodLevel()));
            result.put("saturation", Float.toString(player.getHungerManager().getSaturationLevel()));
            result.put("deathsObserved", Integer.toString(deathsObserved));
            result.put("firstHeadOutOfWaterServerTick", Integer.toString(firstHeadOutOfWaterServerTick));
            result.put("serverStartTick", Integer.toString(startServerTick));
            result.put("serverLastObservedTick", Integer.toString(lastServerTick));
            result.put("serverStartPosition", startX + "," + startY + "," + startZ);
            result.put("serverCurrentPosition", position(player));
            result.put("serverPositionSamples", serverPositionSamples.toString());
            result.put("maximumDistanceFromStart", Double.toString(maximumDistanceFromStart));
            result.put("waterSourceCellsPresent", Boolean.toString(waterSourceCellsPresent()));
            result.put("lowWaterRoofPresent", Boolean.toString(lowWaterRoofPresent()));
            result.put("waterBoundaryPresent", Boolean.toString(waterBoundaryPresent()));
            result.put("dryExitPresent", Boolean.toString(world.getBlockState(new BlockPos(3, 66, 0)).isOf(Blocks.AIR)
                && world.getBlockState(new BlockPos(3, 67, 0)).isOf(Blocks.AIR)
                && world.getBlockState(new BlockPos(3, 65, 0)).isOf(Blocks.BEDROCK)));
            result.put("craftingTablePresent", Boolean.toString(world.getBlockState(new BlockPos(4, 66, 0)).isOf(Blocks.CRAFTING_TABLE)
                && world.getBlockState(new BlockPos(4, 65, 0)).isOf(Blocks.BEDROCK)));
            result.put("exitDistance", Double.toString(Math.sqrt(13.0)));
            return Map.copyOf(result);
        }

        private boolean waterSourceCellsPresent() {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 64; y <= 65; y++) {
                BlockPos position = new BlockPos(x, y, z);
                if (!world.getBlockState(position).isOf(Blocks.WATER) || !world.getFluidState(position).isStill()) return false;
            }
            return true;
        }

        private boolean lowWaterRoofPresent() {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                if (x == 2 && z == 0) continue;
                if (!world.getBlockState(new BlockPos(x, 66, z)).isOf(Blocks.BEDROCK)) return false;
            }
            return world.getBlockState(new BlockPos(2, 66, 0)).isOf(Blocks.AIR);
        }

        private boolean waterBoundaryPresent() {
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
                for (int y = 64; y <= 65; y++) {
                    if (!world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.BEDROCK)) return false;
                }
            }
            return true;
        }
    }

    static void seedPreparedSafetyIngredients(ServerPlayerEntity player) {
        if (!player.getInventory().insertStack(new ItemStack(Items.OAK_LOG, 2))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared oak logs and owned crafting table");
        }
        player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.OAK_LOG, 8));
    }

    static PreparedSafetyPursuitFixture seedPreparedSafetyPursuitFixture(ServerPlayerEntity player,
                                                                          ServerWorld world) {
        boolean toolVariant = "pursuit-tool".equals(System.getProperty("lodekeeper.verify.preparedSafety"));
        for (int x = -12; x <= 18; x++) for (int y = 64; y <= 67; y++) {
            world.setBlockState(new BlockPos(x, y, -6), Blocks.BEDROCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(x, y, 6), Blocks.BEDROCK.getDefaultState(), 3);
        }
        for (int z = -6; z <= 6; z++) for (int y = 64; y <= 67; y++) {
            world.setBlockState(new BlockPos(-12, y, z), Blocks.BEDROCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(18, y, z), Blocks.BEDROCK.getDefaultState(), 3);
        }
        if (!player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the pursuit bucket ingredients and crafting table");
        }
        if (toolVariant) {
            player.getInventory().setStack(7, new ItemStack(Items.STONE_PICKAXE));
            if (!player.getInventory().getStack(7).isOf(Items.STONE_PICKAXE)) {
                throw new IllegalStateException("could not seed the pursuit-tool stone pickaxe in hotbar slot 7");
            }
        }
        player.getHungerManager().setFoodLevel(7);
        player.getHungerManager().setSaturationLevel(0.0F);
        CowEntity cow = new CowEntity(EntityType.COW, world);
        cow.refreshPositionAndAngles(8.5, 64.0, 0.5, 180.0F, 0.0F);
        if (!world.spawnEntity(cow)) throw new IllegalStateException("could not spawn the prepared pursuit cow");
        return new PreparedSafetyPursuitFixture(cow, player.getHungerManager().getFoodLevel(),
            player.getHungerManager().getSaturationLevel(), toolVariant,
            toolVariant ? 7 : -1, toolVariant ? player.getInventory().getStack(7).getDamage() : -1);
    }

    static void observePreparedSafetyPursuitTick(PreparedSafetyPursuitFixture fixture,
                                                  ServerPlayerEntity player, int serverTick) {
        fixture.observe(player, serverTick);
    }

    static Map<String, String> preparedSafetyPursuitReceipt(ServerPlayerEntity player,
                                                              PreparedSafetyPursuitFixture fixture) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("cowUuid", fixture.cow.getUuid().toString());
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
        result.put("currentFoodLevel", Integer.toString(player.getHungerManager().getFoodLevel()));
        result.put("currentSaturation", Float.toString(player.getHungerManager().getSaturationLevel()));
        if (fixture.toolVariant) {
            int stonePickaxeSlot = findStonePickaxeSlot(player);
            ItemStack stonePickaxe = stonePickaxeSlot < 0 ? ItemStack.EMPTY : player.getInventory().getStack(stonePickaxeSlot);
            result.put("pursuitToolVariant", "true");
            result.put("stonePickaxeInitialSlot", Integer.toString(fixture.stonePickaxeInitialSlot));
            result.put("stonePickaxeCurrentSlot", Integer.toString(stonePickaxeSlot));
            result.put("stonePickaxeInitialDamage", Integer.toString(fixture.stonePickaxeInitialDamage));
            result.put("stonePickaxeDamage", Integer.toString(stonePickaxeSlot < 0 ? -1 : stonePickaxe.getDamage()));
            result.put("stonePickaxeMaxDamage", Integer.toString(stonePickaxeSlot < 0 ? -1 : stonePickaxe.getMaxDamage()));
        }
        return Map.copyOf(result);
    }

    private static int findStonePickaxeSlot(ServerPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isOf(Items.STONE_PICKAXE)) return slot;
        }
        return -1;
    }

    static final class PreparedSafetyPursuitFixture {
        private final CowEntity cow;
        private final String cowUuid;
        private final float initialHealth;
        private final int initialFoodLevel;
        private final float initialSaturation;
        private final boolean toolVariant;
        private final int stonePickaxeInitialSlot;
        private final int stonePickaxeInitialDamage;
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

        private PreparedSafetyPursuitFixture(CowEntity cow, int initialFoodLevel, float initialSaturation,
                                              boolean toolVariant, int stonePickaxeInitialSlot,
                                              int stonePickaxeInitialDamage) {
            this.cow = cow;
            cowUuid = cow.getUuid().toString();
            initialHealth = cow.getHealth();
            lastObservedHealth = initialHealth;
            this.initialFoodLevel = initialFoodLevel;
            this.initialSaturation = initialSaturation;
            this.toolVariant = toolVariant;
            this.stonePickaxeInitialSlot = stonePickaxeInitialSlot;
            this.stonePickaxeInitialDamage = stonePickaxeInitialDamage;
        }

        void beginObservation() { observing = true; }

        private void observe(ServerPlayerEntity player, int serverTick) {
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
        boolean waterRetreat = Boolean.getBoolean("lodekeeper.verify.threatWaterRetreat");
        if (waterRetreat) {
            for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) {
                world.setBlockState(new BlockPos(x, 63, z), Blocks.BEDROCK.getDefaultState(), 3);
                for (int y = 64; y <= 66; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 3);
            }
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++) {
                world.setBlockState(new BlockPos(x, 62, z), Blocks.BEDROCK.getDefaultState(), 3);
                world.setBlockState(new BlockPos(x, 63, z), Blocks.WATER.getDefaultState(), 3);
            }
            for (int z = -3; z <= -1; z++) world.setBlockState(new BlockPos(0, 65, z), Blocks.BEDROCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(20, 63, 20), Blocks.BEDROCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(18, 63, 20), Blocks.BEDROCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(20, 67, 20), Blocks.BEDROCK.getDefaultState(), 3);
        } else {
            for (int x = 0; x <= 2; x++) for (int z = 0; z <= 1; z++) {
                world.setBlockState(new BlockPos(x, 67, z), Blocks.BEDROCK.getDefaultState(), 3);
            }
            world.setBlockState(new BlockPos(3, 64, 0), Blocks.BEDROCK.getDefaultState(), 3);
            world.setBlockState(new BlockPos(3, 65, 0), Blocks.BEDROCK.getDefaultState(), 3);
        }
        if (!player.getInventory().insertStack(new ItemStack(Items.DIAMOND_SWORD))
                || !player.getInventory().insertStack(new ItemStack(Items.WOODEN_PICKAXE))
                || !player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the prepared threat weapons and bucket stock");
        }
        ClientAccess.selectedSlot(player.getInventory(), 0);
        ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
        zombie.refreshPositionAndAngles(waterRetreat ? 20.5 : 2.5, 64.0, waterRetreat ? 20.5 : 0.5, 180.0F, 0.0F);
        zombie.setAiDisabled(true);
        zombie.setHealth(4.0F);
        CowEntity cow = new CowEntity(EntityType.COW, world);
        cow.refreshPositionAndAngles(waterRetreat ? 18.5 : 2.5, 64.0, waterRetreat ? 20.5 : 1.5, 180.0F, 0.0F);
        cow.setAiDisabled(true);
        if (!world.spawnEntity(zombie) || !world.spawnEntity(cow)) {
            throw new IllegalStateException("could not spawn the prepared native zombie and cow");
        }
        CreeperEntity creeper = null;
        if (waterRetreat) {
            creeper = new CreeperEntity(EntityType.CREEPER, world);
            creeper.refreshPositionAndAngles(2.5, 64.0, 5.5, 0.0F, 0.0F);
            creeper.setAiDisabled(true);
            creeper.setHealth(20.0F);
            if (!world.spawnEntity(creeper)) throw new IllegalStateException("could not spawn the prepared native creeper");
        }
        return waterRetreat ? new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth(), creeper)
            : new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth());
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
        if (fixture.creeper != null) {
            var world = fixture.creeper.getWorld();
            result.put("creeperUuid", fixture.creeper.getUuid().toString());
            result.put("creeperAlive", Boolean.toString(fixture.creeper.isAlive()));
            result.put("creeperRemoved", Boolean.toString(fixture.creeper.isRemoved()));
            result.put("creeperHealth", Float.toString(fixture.creeper.getHealth()));
            result.put("creeperDistanceSquared", Double.toString(fixture.creeper.squaredDistanceTo(player)));
            boolean roofPresent = true, waterPresent = true, floorPresent = true;
            for (int z = -3; z <= -1; z++) roofPresent &= world.getBlockState(new BlockPos(0, 65, z)).isOf(Blocks.BEDROCK);
            for (int x = -2; x <= 2; x++) for (int z = -3; z <= 3; z++) {
                BlockPos cell = new BlockPos(x, 63, z);
                waterPresent &= world.getBlockState(cell).isOf(Blocks.WATER) && world.getFluidState(cell).isStill();
                floorPresent &= world.getBlockState(new BlockPos(x, 62, z)).isOf(Blocks.BEDROCK);
            }
            BlockPos feet = player.getBlockPos();
            result.put("lowWaterRoofPresent", Boolean.toString(roofPresent));
            result.put("waterSourceCellsPresent", Boolean.toString(waterPresent));
            result.put("waterFloorPresent", Boolean.toString(floorPresent));
            result.put("playerInWater", Boolean.toString(player.isTouchingWater()));
            result.put("playerSupportBedrock", Boolean.toString(world.getBlockState(feet.down()).isOf(Blocks.BEDROCK)));
            result.put("playerBodyCellsAir", Boolean.toString(world.getBlockState(feet).isOf(Blocks.AIR)
                    && world.getBlockState(feet.up()).isOf(Blocks.AIR)));
        }
        return Map.copyOf(result);
    }

    static final class PreparedSafetyThreatFixture {
        private final ZombieEntity zombie;
        private final CowEntity cow;
        private final float cowInitialHealth;
        private final CreeperEntity creeper;

        private PreparedSafetyThreatFixture(ZombieEntity zombie, CowEntity cow, float cowInitialHealth) {
            this(zombie, cow, cowInitialHealth, null);
        }
        private PreparedSafetyThreatFixture(ZombieEntity zombie, CowEntity cow, float cowInitialHealth, CreeperEntity creeper) {
            this.zombie = zombie;
            this.cow = cow;
            this.cowInitialHealth = cowInitialHealth;
            this.creeper = creeper;
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
        protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return VerificationContentInitializer.geometryBlockFull ? VoxelShapes.fullCube() : VoxelShapes.empty();
        }
    }
}
