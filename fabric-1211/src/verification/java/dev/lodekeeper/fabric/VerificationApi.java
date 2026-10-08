package dev.lodekeeper.fabric;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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


    static Object seedShieldScenario(ServerPlayerEntity player, ServerWorld world, String scenario) {
        int iron = "queued".equals(scenario) ? 12 : "iron_short".equals(scenario) ? 9 : 10;
        int planks = "planks_short".equals(scenario) ? 18 : 19;
        if (!player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, iron))
                || !player.getInventory().insertStack(new ItemStack(Items.OAK_PLANKS, planks)))
            throw new IllegalStateException("could not seed shield scenario stock");
        for (int x = -5; x <= 8; x++) for (int z = -4; z <= 4; z++) {
            world.setBlockState(new BlockPos(x, 63, z), Blocks.BEDROCK.getDefaultState(), 3);
            for (int y = 64; y <= 66; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), 3);
            world.setBlockState(new BlockPos(x, 67, z), Blocks.BEDROCK.getDefaultState(), 3);
        }
        world.setBlockState(new BlockPos(-2, 64, 0), Blocks.CRAFTING_TABLE.getDefaultState(), 3);
        ShieldScenarioFixture fixture = new ShieldScenarioFixture(scenario, iron, planks);
        if (java.util.List.of("worn", "occupied", "manual").contains(scenario)) {
            ItemStack shield = new ItemStack(Items.SHIELD);
            shield.setDamage(200);
            player.getInventory().setStack(20, shield);
            if ("occupied".equals(scenario)) player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 8));
            if (!player.getInventory().insertStack(new ItemStack(Items.STONE_SWORD)))
                throw new IllegalStateException("could not seed ordinary shield defense sword");
            ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
            zombie.refreshPositionAndAngles(2.0, 64.0, 0.5, 90.0F, 0.0F);
            zombie.setBaby(false);
            zombie.setAiDisabled(true);
            zombie.setTarget(player);
            zombie.setHealth(zombie.getMaxHealth());
            if (!world.spawnEntity(zombie)) throw new IllegalStateException("could not seed controlled shield threat");
            fixture.zombie = zombie;
            registerShieldDamageReceipt(player, fixture);
        }
        player.getInventory().markDirty();
        return fixture;
    }

    private static void registerShieldDamageReceipt(ServerPlayerEntity player, ShieldScenarioFixture fixture) {
        try {
            var field = ServerLivingEntityEvents.class.getField("AFTER_DAMAGE");
            var type = (java.lang.reflect.ParameterizedType) field.getGenericType();
            Class<?> callback = (Class<?>) type.getActualTypeArguments()[0];
            Object listener = java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                (proxy, method, arguments) -> {
                    if (arguments != null && arguments.length == 5 && arguments[0] == player
                            && arguments[1] instanceof net.minecraft.entity.damage.DamageSource source
                            && source.getAttacker() == fixture.zombie) {
                        fixture.damageEvents++;
                        if (Boolean.TRUE.equals(arguments[4])) fixture.blockedDamageEvents++;
                        fixture.lastBaseDamage = ((Number) arguments[2]).floatValue();
                        fixture.lastDamage = ((Number) arguments[3]).floatValue();
                    }
                    return null;
                });
            var event = (net.fabricmc.fabric.api.event.Event<?>) field.get(null);
            net.fabricmc.fabric.api.event.Event.class.getMethod("register", Object.class).invoke(event, listener);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("native AFTER_DAMAGE shield receipt unavailable", exception);
        }
    }

    static void observeShieldScenario(Object value, ServerPlayerEntity player, int serverTick) {
        ShieldScenarioFixture fixture = (ShieldScenarioFixture) value;
        ItemStack source = player.getInventory().getStack(20), offhand = player.getOffHandStack();
        boolean equipped = source.isEmpty() && offhand.isOf(Items.SHIELD) && offhand.getDamage() >= 200;
        if (equipped && fixture.equipServerTick < 0) fixture.equipServerTick = serverTick;
        if (fixture.equipServerTick >= 0 && source.isOf(Items.SHIELD) && offhand.isEmpty())
            fixture.restoreServerTick = serverTick;
        boolean nativeUse = player.isUsingItem() && player.getActiveHand() == net.minecraft.util.Hand.OFF_HAND
                && player.getActiveItem().isOf(Items.SHIELD);
        if (nativeUse) fixture.nativeUseServerTicks++;
        fixture.consecutiveUseTicks = nativeUse ? fixture.consecutiveUseTicks + 1 : 0;
        if (fixture.zombie != null && fixture.zombie.isAlive() && fixture.consecutiveUseTicks >= 6 && !fixture.attackProbeIssued) {
            fixture.attackProbeIssued = true;
            fixture.attackProbeServerTick = serverTick;
            fixture.zombie.tryAttack(player);
        }
        fixture.minimumHealth = Math.min(fixture.minimumHealth, player.getHealth());
    }

    static Map<String, String> shieldScenarioReceipt(Object value, ServerPlayerEntity player, int serverTick) {
        ShieldScenarioFixture fixture = (ShieldScenarioFixture) value;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("scenario", fixture.scenario);
        result.put("serverTick", Integer.toString(serverTick));
        result.put("initialIron", Integer.toString(fixture.iron));
        result.put("initialPlanks", Integer.toString(fixture.planks));
        for (Item item : java.util.List.of(Items.IRON_INGOT, Items.OAK_PLANKS, Items.SHIELD, Items.BUCKET, Items.SHEARS)) {
            int count = 0;
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (stack.isOf(item)) count += stack.getCount();
            }
            result.put(Registries.ITEM.getId(item).toString(), Integer.toString(count));
        }
        result.put("craftedShields", Integer.toString(player.getStatHandler().getStat(net.minecraft.stat.Stats.CRAFTED.getOrCreateStat(Items.SHIELD))));
        result.put("craftedBuckets", Integer.toString(player.getStatHandler().getStat(net.minecraft.stat.Stats.CRAFTED.getOrCreateStat(Items.BUCKET))));
        ItemStack source = player.getInventory().getStack(20), offhand = player.getOffHandStack();
        result.put("sourceItem", source.isEmpty() ? "empty" : Registries.ITEM.getId(source.getItem()).toString());
        result.put("sourceCount", Integer.toString(source.getCount()));
        result.put("sourceDamage", Integer.toString(source.getDamage()));
        result.put("offhandItem", offhand.isEmpty() ? "empty" : Registries.ITEM.getId(offhand.getItem()).toString());
        result.put("offhandCount", Integer.toString(offhand.getCount()));
        result.put("offhandDamage", Integer.toString(offhand.getDamage()));
        result.put("equipServerTick", Integer.toString(fixture.equipServerTick));
        result.put("restoreServerTick", Integer.toString(fixture.restoreServerTick));
        result.put("nativeUseServerTicks", Integer.toString(fixture.nativeUseServerTicks));
        result.put("usingItem", Boolean.toString(player.isUsingItem()));
        result.put("blockedDamageEvents", Integer.toString(fixture.blockedDamageEvents));
        result.put("damageEvents", Integer.toString(fixture.damageEvents));
        result.put("attackProbeIssued", Boolean.toString(fixture.attackProbeIssued));
        result.put("attackProbeServerTick", Integer.toString(fixture.attackProbeServerTick));
        result.put("attackProbeKind", "controlled_NoAI_zombie_native_tryAttack_after_six_shield_use_ticks");
        result.put("lastBaseDamage", Float.toString(fixture.lastBaseDamage));
        result.put("lastDamage", Float.toString(fixture.lastDamage));
        result.put("minimumHealth", Float.toString(fixture.minimumHealth));
        result.put("cursorEmpty", Boolean.toString(player.currentScreenHandler.getCursorStack().isEmpty()));
        result.put("zombieAlive", Boolean.toString(fixture.zombie != null && fixture.zombie.isAlive()));
        result.put("tablePresent", Boolean.toString(player.getServerWorld().getBlockState(new BlockPos(-2, 64, 0)).isOf(Blocks.CRAFTING_TABLE)));
        return Map.copyOf(result);
    }

    private static final class ShieldScenarioFixture {
        private final String scenario;
        private final int iron, planks;
        private ZombieEntity zombie;
        private int equipServerTick = -1, restoreServerTick = -1, attackProbeServerTick = -1;
        private int nativeUseServerTicks, consecutiveUseTicks, damageEvents, blockedDamageEvents;
        private boolean attackProbeIssued;
        private float lastBaseDamage, lastDamage, minimumHealth = 20.0F;
        private ShieldScenarioFixture(String scenario, int iron, int planks) {
            this.scenario = scenario;
            this.iron = iron;
            this.planks = planks;
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
        if (Boolean.getBoolean("lodekeeper.verify.threatContact")) return seedPreparedSafetyContactFixture(player, world);
        if (Boolean.getBoolean("lodekeeper.verify.threatCreeperContact")) {
            player.getServer().getTickManager().setFrozen(true);
            for (int x = -12; x <= 24; x++) for (int z = -6; z <= 24; z++) {
                world.setBlockState(new BlockPos(x, 63, z), Blocks.BEDROCK.getDefaultState(), 3);
            }
            world.setBlockState(new BlockPos(20, 67, 20), Blocks.BEDROCK.getDefaultState(), 3);
            if (!player.getInventory().insertStack(new ItemStack(Items.DIAMOND_SWORD))
                    || !player.getInventory().insertStack(new ItemStack(Items.WOODEN_PICKAXE))
                    || !player.getInventory().insertStack(new ItemStack(Items.STONE_SWORD))
                    || !player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                    || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
                throw new IllegalStateException("could not seed the creeper-contact weapons and bucket stock");
            }
            ClientAccess.selectedSlot(player.getInventory(), 2);
            ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
            zombie.refreshPositionAndAngles(20.5, 64.0, 20.5, 180.0F, 0.0F);
            zombie.setAiDisabled(true);
            zombie.setHealth(4.0F);
            CowEntity cow = new CowEntity(EntityType.COW, world);
            cow.refreshPositionAndAngles(18.5, 64.0, 20.5, 180.0F, 0.0F);
            cow.setAiDisabled(true);
            CreeperEntity creeper = new CreeperEntity(EntityType.CREEPER, world);
            creeper.refreshPositionAndAngles(2.5, 64.0, 0.5, -90.0F, 0.0F);
            creeper.setAiDisabled(false);
            creeper.setHealth(20.0F);
            creeper.setTarget(player);
            if (!world.spawnEntity(zombie) || !world.spawnEntity(cow) || !world.spawnEntity(creeper)) {
                throw new IllegalStateException("could not spawn the creeper-contact targets and protected cow");
            }
            return new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth(), creeper, true);
        }
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
        if (waterRetreat) zombie.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
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

    static PreparedSafetyThreatFixture seedPreparedSafetyContactFixture(ServerPlayerEntity player, ServerWorld world) {
        player.getServer().getTickManager().setFrozen(true);
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = 63; y <= 68; y++) {
            boolean passage = x >= 0 && x <= 6 && z >= 0 && z <= 1 && y >= 64 && y <= 65;
            world.setBlockState(new BlockPos(x, y, z), (passage ? Blocks.AIR : Blocks.BEDROCK).getDefaultState(), 3);
        }
        if (!player.getInventory().insertStack(new ItemStack(Items.DIAMOND_SWORD))
                || !player.getInventory().insertStack(new ItemStack(Items.IRON_PICKAXE))
                || !player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the live contact sword, iron pickaxe and bucket stock");
        }
        ClientAccess.selectedSlot(player.getInventory(), 0);
        ZombieEntity first = new ZombieEntity(EntityType.ZOMBIE, world);
        ZombieEntity second = new ZombieEntity(EntityType.ZOMBIE, world);
        CowEntity cow = new CowEntity(EntityType.COW, world);
        first.refreshPositionAndAngles(1.8, 64.0, 0.45, 90.0F, 0.0F);
        second.refreshPositionAndAngles(1.8, 64.0, 1.45, 90.0F, 0.0F);
        cow.refreshPositionAndAngles(2.5, 64.0, 1.5, 90.0F, 0.0F);
        first.setBaby(false);
        second.setBaby(false);
        first.setAiDisabled(false);
        second.setAiDisabled(false);
        first.setHealth(first.getMaxHealth());
        second.setHealth(second.getMaxHealth());
        first.setTarget(player);
        second.setTarget(player);
        cow.setAiDisabled(true);
        if (!world.spawnEntity(first) || !world.spawnEntity(second) || !world.spawnEntity(cow)) {
            throw new IllegalStateException("could not spawn both full-health AI-enabled contact zombies and protected cow");
        }
        PreparedSafetyThreatFixture fixture = new PreparedSafetyThreatFixture(first, cow, cow.getHealth());
        fixture.contact = new ContactThreatObservation(second);
        if ("true".equals(System.getProperty("lodekeeper.verify.threatContactLowHealth")))
            fixture.contact.lowHealth = new ContactLowHealthObservation(player);
        java.util.function.BiConsumer<net.minecraft.entity.LivingEntity, net.minecraft.entity.damage.DamageSource> confirmSwordDamage = (entity, source) -> {
            ContactThreatObservation contact = fixture.contact;
            observeContactLowHealthDamage(fixture, entity, source);
            int index = entity == fixture.zombie ? 0 : entity == contact.second ? 1 : -1;
            if (index < 0) return;
            ContactSwordAttempt attempt = contact.pendingSwordDamage[index];
            contact.pendingSwordDamage[index] = null;
            if (!contact.observing || attempt == null || attempt.source() != source
                    || !(entity.getHealth() < attempt.healthBefore())) return;
            if (attempt.airborne()) contact.airborneSwordDamageEvents++;
            else contact.groundedSwordDamageEvents++;
        };
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            ContactThreatObservation contact = fixture.contact;
            ContactLowHealthObservation low = contact.lowHealth;
            if (low != null) {
                int target = entity == fixture.zombie ? 0 : entity == contact.second ? 1 : entity == fixture.cow ? 2 : -1;
                if (target >= 0) low.pending[target] = contact.observing && source.getAttacker() == player
                        ? new ContactSwordAttempt(!player.isOnGround(), entity.getHealth(), source) : null;
            }
            int index = entity == fixture.zombie ? 0 : entity == contact.second ? 1 : -1;
            if (index >= 0) {
                contact.pendingSwordDamage[index] = contact.observing && source.getAttacker() == player
                        && player.getMainHandStack().isOf(Items.DIAMOND_SWORD)
                        ? new ContactSwordAttempt(!player.isOnGround(), entity.getHealth(), source) : null;
            }
            return true;
        });
        VerificationDamageEvents.registerAfterDamage(confirmSwordDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(confirmSwordDamage::accept);
        return fixture;
    }

    static void releasePreparedSafetyThreatClock(PreparedSafetyThreatFixture fixture, ServerPlayerEntity player) {
        if (fixture.contact != null) {
            fixture.contact.observing = true;
            fixture.contact.releaseServerTick = player.getServer().getTicks();
        } else if (fixture.creeperContact) {
            fixture.creeperContactReleaseServerTick = player.getServer().getTicks();
        } else {
            throw new IllegalStateException("live contact fixture is absent");
        }
        player.getServer().getTickManager().setFrozen(false);
    }

    static void observePreparedSafetyThreatTick(PreparedSafetyThreatFixture fixture, ServerPlayerEntity player, int serverTick) {
        if (fixture.creeperContact && fixture.creeperContactReleaseServerTick >= 0) {
            fixture.creeperContactObservedServerTicks++;
            fixture.creeperContactMinimumPlayerHealth = Math.min(fixture.creeperContactMinimumPlayerHealth, player.getHealth());
            fixture.creeperContactPeakFuse = Math.max(fixture.creeperContactPeakFuse, fixture.creeper.getClientFuseTime(1.0f));
            if (fixture.creeper.getHealth() < fixture.creeperContactLastHealth) {
                var damage = fixture.creeper.getRecentDamageSource();
                fixture.creeperContactLastDamage = damage == null ? "" : damage.getName();
                fixture.creeperContactLastDamageByPlayer = damage != null && damage.getAttacker() == player;
            }
            fixture.creeperContactLastHealth = fixture.creeper.getHealth();
            if (!fixture.creeper.isAlive() && fixture.creeper.getHealth() <= 0.0F
                    && "player".equals(fixture.creeperContactLastDamage) && fixture.creeperContactLastDamageByPlayer)
                fixture.creeperContactPlayerKillObserved = true;
            if (fixture.creeper.isRemoved() && !fixture.creeper.isAlive() && fixture.creeper.getHealth() > 0.0F
                    && fixture.creeper.getClientFuseTime(1.0f) >= 1.0F)
                fixture.creeperContactExplosionObserved = true;
        }
        if (fixture.contact == null || !fixture.contact.observing) return;
        ContactThreatObservation contact = fixture.contact;
        contact.observedServerTicks++;
        ContactLowHealthObservation low = contact.lowHealth;
        if (low != null && low.fenceTick >= 0 && low.landingTick < 0 && player.isOnGround()
                && Math.abs(player.getY() - 64.0) <= 0.0625
                && player.getServerWorld().getBlockCollisions(player, player.getBoundingBox().offset(0.0, -0.05, 0.0)).iterator().hasNext())
            low.landingTick = serverTick;
        boolean airborne = !player.isOnGround();
        boolean moving = player.getVelocity().horizontalLengthSquared() > 0.0004;
        if (airborne) contact.airborneTicks++;
        if (moving) contact.horizontalMotionTicks++;
        ZombieEntity[] zombies = new ZombieEntity[]{fixture.zombie, contact.second};
        for (int index = 0; index < zombies.length; index++) {
            ZombieEntity zombie = zombies[index];
            if (zombie.getHealth() < contact.lastHealth[index]) {
                if (airborne || moving) contact.mobHealthDropsWhilePlayerUnsettled++;
                var damage = zombie.getRecentDamageSource();
                contact.lastDamage[index] = damage == null ? "" : damage.getName();
                contact.lastDamageByPlayer[index] = damage != null && damage.getAttacker() == player;
                if (damage != null && damage.getAttacker() == player && "player".equals(damage.getName())) contact.playerHits[index]++;
                else contact.foreignDamage[index]++;
            }
            contact.lastHealth[index] = zombie.getHealth();
        }
        if (player.getHealth() < contact.lastPlayerHealth) {
            var damage = player.getRecentDamageSource();
            if (damage != null && (damage.getAttacker() == fixture.zombie || damage.getAttacker() == contact.second)) contact.nativePlayerHits++;
        }
        contact.lastPlayerHealth = player.getHealth();
    }

    private record ContactLowHealthMarker(java.util.UUID session, java.util.UUID fixture, int stage)
            implements net.minecraft.network.packet.CustomPayload {
        private static final Id<ContactLowHealthMarker> ID = new Id<>(Identifier.of("lodekeeper-verification", "contact_low_health"));
        private static final net.minecraft.network.codec.PacketCodec<net.minecraft.network.RegistryByteBuf, ContactLowHealthMarker> CODEC = new net.minecraft.network.codec.PacketCodec<>() {
            @Override public ContactLowHealthMarker decode(net.minecraft.network.RegistryByteBuf buffer) {
                return new ContactLowHealthMarker(buffer.readUuid(), buffer.readUuid(), buffer.readInt());
            }
            @Override public void encode(net.minecraft.network.RegistryByteBuf buffer, ContactLowHealthMarker marker) {
                buffer.writeUuid(marker.session()); buffer.writeUuid(marker.fixture()); buffer.writeInt(marker.stage());
            }
        };
        @Override public Id<? extends net.minecraft.network.packet.CustomPayload> getId() { return ID; }
    }

    static java.util.function.IntConsumer registerContactLowHealthNetworking(
            java.util.function.Supplier<PreparedSafetyThreatFixture> currentFixture,
            java.util.function.Supplier<java.util.UUID> originalPlayerId,
            java.util.function.IntConsumer observeAttackAttempt) {
        java.util.UUID session = java.util.UUID.randomUUID();
        int[] markerDiagnosticRecords = {0};
        java.util.function.Consumer<String> markerDiagnostic = message -> {
            if (markerDiagnosticRecords[0] < 32) System.out.println("[Lodekeeper verification] low-health marker record="
                    + (++markerDiagnosticRecords[0]) + "/32 " + message);
        };
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(ContactLowHealthMarker.ID, ContactLowHealthMarker.CODEC);
        boolean registered = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(ContactLowHealthMarker.ID, (marker, context) -> {
            PreparedSafetyThreatFixture fixture = currentFixture.get();
            ServerPlayerEntity player = context.player();
            if (!session.equals(marker.session()) || fixture == null || fixture.contact == null
                    || fixture.contact.lowHealth == null || !fixture.zombie.getUuid().equals(marker.fixture())
                    || !player.getUuid().equals(originalPlayerId.get())) return;
            ContactLowHealthObservation low = fixture.contact.lowHealth;
            if (low.owner != player || low.connection != player.networkHandler) {
                low.failure = "low-health marker original player or connection changed";
                return;
            }
            int serverTick = player.getServerWorld().getServer().getTicks();
            if (marker.stage() == 0) {
                if (low.mutationTick >= 0 || !fixture.contact.observing || player.isOnGround()) {
                    low.failure = "low-health mutation missed the first native airborne hop or was repeated";
                    return;
                }
                low.healthBefore = player.getHealth();
                low.mutationDamageEvents = low.confirmedDamageEvents;
                player.setHealth(6.0F);
                low.healthAfter = player.getHealth();
                low.mutationTick = serverTick;
                fixture.contact.lastPlayerHealth = player.getHealth();
            } else if (marker.stage() == 1) {
                if (markerDiagnosticRecords[0] < 32) {
                    int diagnosticMutationTick = low.mutationTick, diagnosticFenceTick = low.fenceTick;
                    boolean diagnosticOnGround = player.isOnGround();
                    float diagnosticHealth = player.getHealth();
                    markerDiagnostic.accept("event=onset-gate stage=1 serverTick=" + serverTick
                        + " session=" + marker.session() + " expectedSession=" + session
                        + " fixture=" + marker.fixture() + " currentFixture=" + fixture.zombie.getUuid()
                        + " fixtureIdentity=" + System.identityHashCode(fixture) + " player=" + player.getUuid()
                        + " playerIdentity=" + System.identityHashCode(player) + " ownerIdentity=" + System.identityHashCode(low.owner)
                        + " connectionIdentity=" + System.identityHashCode(player.networkHandler)
                        + " originalConnectionIdentity=" + System.identityHashCode(low.connection)
                        + " contextAlreadyAdmitted=true originalPlayerMatch=true ownerMatch=true connectionMatch=true"
                        + " health=" + diagnosticHealth + " onGround=" + diagnosticOnGround
                        + " mutationTick=" + diagnosticMutationTick + " fenceTick=" + diagnosticFenceTick
                        + " rejectMutationMissing=" + (diagnosticMutationTick < 0) + " rejectFencePresent=" + (diagnosticFenceTick >= 0)
                        + " rejectGrounded=" + diagnosticOnGround + " rejectHealthAboveSix=" + (diagnosticHealth > 6.0F)
                        + " admitted=" + !(diagnosticMutationTick < 0 || diagnosticFenceTick >= 0 || diagnosticOnGround || diagnosticHealth > 6.0F)
                        + " priorFailure=" + low.failure + " confirmedDamageEvents=" + low.confirmedDamageEvents);
                }
                if (low.mutationTick < 0 || low.fenceTick >= 0 || player.isOnGround() || player.getHealth() > 6.0F) {
                    low.failure = "low-health onset fence missed native airborne health or was repeated";
                    return;
                }
                low.fenceTick = serverTick;
                low.fenceDamageEvents = low.confirmedDamageEvents;
            } else if (marker.stage() == 2) {
                if (low.fenceTick < 0 || low.pauseFenceTick >= 0) {
                    low.failure = "low-health pause fence preceded onset or was repeated";
                    return;
                }
                low.pauseFenceTick = serverTick;
                low.pauseFenceDamageEvents = low.confirmedDamageEvents;
            } else low.failure = "invalid low-health marker stage";
        });
        if (!registered) throw new IllegalStateException("low-health marker receiver already registered");
        class ClientObservation implements java.util.function.IntConsumer {
            private PreparedSafetyThreatFixture originalFixture;
            private java.util.UUID fixtureId;
            private Object player, connection, world;
            private boolean onset;
            private int clientMarkerDiagnosticRecords;

            private boolean sameContext(PreparedSafetyThreatFixture fixture, MinecraftClient client) {
                return fixture == originalFixture && fixture != null && fixtureId.equals(fixture.zombie.getUuid())
                    && client.player == player && client.getNetworkHandler() == connection && client.world == world
                    && client.player != null && client.player.getUuid().equals(originalPlayerId.get());
            }

            @Override public void accept(int stage) {
                PreparedSafetyThreatFixture fixture = currentFixture.get();
                MinecraftClient client = MinecraftClient.getInstance();
                if (fixture == null || fixture.contact == null || fixture.contact.lowHealth == null
                        || client.player == null || !client.player.getUuid().equals(originalPlayerId.get())
                        || !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(ContactLowHealthMarker.ID))
                    throw new IllegalStateException("low-health marker transport unavailable for current fixture");
                if (stage == 0 && originalFixture == null) {
                    originalFixture = fixture;
                    fixtureId = fixture.zombie.getUuid();
                    player = client.player;
                    connection = client.getNetworkHandler();
                    world = client.world;
                }
                if (!sameContext(fixture, client))
                    throw new IllegalStateException("low-health original client player, connection or fixture changed");
                if (stage == 1) onset = true;
                if (stage == 1 && clientMarkerDiagnosticRecords < 8) System.out.println("[Lodekeeper verification] low-health client marker record="
                    + (++clientMarkerDiagnosticRecords) + "/8 event=onset-send stage=1 session=" + session + " fixture=" + fixtureId
                    + " fixtureIdentity=" + System.identityHashCode(fixture) + " player=" + client.player.getUuid()
                    + " playerIdentity=" + System.identityHashCode(client.player) + " connectionIdentity=" + System.identityHashCode(connection)
                    + " worldIdentity=" + System.identityHashCode(world) + " contextAlreadyAdmitted=true"
                    + " clientWorldTick=" + (client.world == null ? "unknown" : client.world.getTime())
                    + " clientPlayerAge=" + client.player.age + " health=" + client.player.getHealth() + " onGround=" + client.player.isOnGround());
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new ContactLowHealthMarker(session, fixtureId, stage));
            }
        }
        ClientObservation observation = new ClientObservation();
        var attackPhase = Identifier.of("lodekeeper-verification", "contact_low_health_attempts");
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.addPhaseOrdering(attackPhase, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register(attackPhase, (player, world, hand, target, hit) -> {
            if (observation.onset && player instanceof net.minecraft.client.network.ClientPlayerEntity
                    && (player == observation.player || player.getUuid().equals(originalPlayerId.get())))
                observeAttackAttempt.accept(player == observation.player && world == observation.world
                    && observation.sameContext(currentFixture.get(), MinecraftClient.getInstance()) ? 1 : -1);
            return net.minecraft.util.ActionResult.PASS;
        });
        return observation;
    }

    private static final class ContactLowHealthObservation {
        private final ServerPlayerEntity owner;
        private final Object connection;
        private final ContactSwordAttempt[] pending = new ContactSwordAttempt[3];
        private int mutationTick = -1, fenceTick = -1, landingTick = -1, pauseFenceTick = -1;
        private int confirmedDamageEvents, mutationDamageEvents = -1, fenceDamageEvents = -1, pauseFenceDamageEvents = -1;
        private float healthBefore = Float.NaN, healthAfter = Float.NaN;
        private String failure = "";
        private ContactLowHealthObservation(ServerPlayerEntity owner) { this.owner = owner; this.connection = owner.networkHandler; }
    }

    private static void observeContactLowHealthDamage(PreparedSafetyThreatFixture fixture, net.minecraft.entity.LivingEntity entity, net.minecraft.entity.damage.DamageSource source) {
        ContactLowHealthObservation low = fixture.contact.lowHealth;
        if (low == null) return;
        int index = entity == fixture.zombie ? 0 : entity == fixture.contact.second ? 1 : entity == fixture.cow ? 2 : -1;
        if (index < 0) return;
        ContactSwordAttempt attempt = low.pending[index];
        low.pending[index] = null;
        if (attempt != null && attempt.source() == source && entity.getHealth() < attempt.healthBefore()) {
            low.confirmedDamageEvents++;
            if (low.fenceTick >= 0) low.failure = "native player damage confirmed after client-observed low-health onset";
        }
    }

    private record ContactSwordAttempt(boolean airborne, float healthBefore, net.minecraft.entity.damage.DamageSource source) { }

    private static final class ContactThreatObservation {
        private ContactLowHealthObservation lowHealth;
        private final ZombieEntity second;
        private final float[] lastHealth = new float[]{20.0F, 20.0F};
        private final int[] playerHits = new int[2];
        private final ContactSwordAttempt[] pendingSwordDamage = new ContactSwordAttempt[2];
        private final int[] foreignDamage = new int[2];
        private final String[] lastDamage = new String[]{"", ""};
        private final boolean[] lastDamageByPlayer = new boolean[2];
        private float lastPlayerHealth = 20.0F;
        private int nativePlayerHits, observedServerTicks;
        private int airborneTicks, horizontalMotionTicks, mobHealthDropsWhilePlayerUnsettled;
        private int airborneSwordDamageEvents, groundedSwordDamageEvents;
        private int releaseServerTick = -1;
        private boolean observing;

        private ContactThreatObservation(ZombieEntity second) { this.second = second; }
    }

    private static void appendContactThreatReceipt(Map<String, String> result, ServerPlayerEntity player, PreparedSafetyThreatFixture fixture) {
        ContactThreatObservation contact = fixture.contact;
        if (contact == null) return;
        ZombieEntity[] zombies = new ZombieEntity[]{fixture.zombie, contact.second};
        for (int index = 0; index < zombies.length; index++) {
            ZombieEntity zombie = zombies[index];
            String prefix = "contactZombie" + index;
            result.put(prefix + "BlockCollision", Boolean.toString(player.getWorld().getBlockCollisions(zombie, zombie.getBoundingBox()).iterator().hasNext()));
            result.put(prefix + "Position", zombie.getX() + "," + zombie.getY() + "," + zombie.getZ());
            result.put(prefix + "Uuid", zombie.getUuid().toString());
            result.put(prefix + "Health", Float.toString(zombie.getHealth()));
            result.put(prefix + "Alive", Boolean.toString(zombie.isAlive()));
            result.put(prefix + "AiEnabled", Boolean.toString(!zombie.isAiDisabled()));
            result.put(prefix + "Adult", Boolean.toString(!zombie.isBaby()));
            result.put(prefix + "TargetsPlayer", Boolean.toString(zombie.getTarget() == player));
            result.put(prefix + "Visible", Boolean.toString(player.canSee(zombie)));
            result.put(prefix + "DistanceSquared", Double.toString(zombie.squaredDistanceTo(player)));
            result.put(prefix + "OnFire", Boolean.toString(zombie.isOnFire()));
            result.put(prefix + "PlayerHits", Integer.toString(contact.playerHits[index]));
            result.put(prefix + "ForeignDamage", Integer.toString(contact.foreignDamage[index]));
            result.put(prefix + "LastDamage", contact.lastDamage[index]);
            result.put(prefix + "LastDamageByPlayer", Boolean.toString(contact.lastDamageByPlayer[index]));
        }
        int changed = 0, shellCells = 0;
        var world = player.getWorld();
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = 63; y <= 68; y++) {
            if (x >= 0 && x <= 6 && z >= 0 && z <= 1 && y >= 64 && y <= 65) continue;
            shellCells++;
            if (!world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.BEDROCK)) changed++;
        }
        ItemStack pickaxe = player.getInventory().getStack(1);
        result.put("ironPickaxeDamage", Integer.toString(pickaxe.isOf(Items.IRON_PICKAXE) ? pickaxe.getDamage() : -1));
        result.put("contactShellCells", Integer.toString(shellCells));
        result.put("contactShellChangedCells", Integer.toString(changed));
        result.put("contactPassageBounds", "0..6,64..65,0..1");
        result.put("contactClockFrozen", Boolean.toString(player.getServer().getTickManager().isFrozen()));
        result.put("contactClockReleaseServerTick", Integer.toString(contact.releaseServerTick));
        result.put("contactObservedServerTicks", Integer.toString(contact.observedServerTicks));
        result.put("contactCowNoAi", Boolean.toString(fixture.cow.isAiDisabled()));
        result.put("contactCowPosition", fixture.cow.getX() + "," + fixture.cow.getY() + "," + fixture.cow.getZ());
        result.put("contactCowBlockCollision", Boolean.toString(player.getWorld().getBlockCollisions(fixture.cow, fixture.cow.getBoundingBox()).iterator().hasNext()));
        result.put("contactAirborneServerTicks", Integer.toString(contact.airborneTicks));
        result.put("contactAirborneSwordDamageEvents", Integer.toString(contact.airborneSwordDamageEvents));
        result.put("contactGroundedSwordDamageEvents", Integer.toString(contact.groundedSwordDamageEvents));
        result.put("contactHorizontalMotionServerTicks", Integer.toString(contact.horizontalMotionTicks));
        result.put("contactMobHealthDropsWhilePlayerUnsettled", Integer.toString(contact.mobHealthDropsWhilePlayerUnsettled));
        result.put("contactScope", "live_multi_threat_from_idle_no_prior_native_path");
        result.put("contactNativePlayerHits", Integer.toString(contact.nativePlayerHits));
        result.put("contactPlayerAlive", Boolean.toString(player.isAlive()));
        result.put("contactPlayerDeaths", Integer.toString(player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS))));
        result.put("contactPlayerPosition", player.getX() + "," + player.getY() + "," + player.getZ());
        result.put("contactPlayerYaw", Float.toString(player.getYaw()));
        result.put("contactPlayerPitch", Float.toString(player.getPitch()));
        ContactLowHealthObservation low = contact.lowHealth;
        if (low != null) {
            result.put("lowHealthPlayerUuid", low.owner.getUuid().toString());
            result.put("lowHealthMutationServerTick", Integer.toString(low.mutationTick));
            result.put("lowHealthMutationBefore", Float.toString(low.healthBefore));
            result.put("lowHealthMutationAfter", Float.toString(low.healthAfter));
            result.put("lowHealthMutationDamageEvents", Integer.toString(low.mutationDamageEvents));
            result.put("lowHealthFenceServerTick", Integer.toString(low.fenceTick));
            result.put("lowHealthFenceDamageEvents", Integer.toString(low.fenceDamageEvents));
            result.put("lowHealthPauseFenceServerTick", Integer.toString(low.pauseFenceTick));
            result.put("lowHealthPauseFenceDamageEvents", Integer.toString(low.pauseFenceDamageEvents));
            result.put("lowHealthConfirmedDamageEvents", Integer.toString(low.confirmedDamageEvents));
            result.put("lowHealthPostFenceDamageEvents", Integer.toString(low.fenceTick < 0 ? -1 : low.confirmedDamageEvents - low.fenceDamageEvents));
            result.put("lowHealthLandingServerTick", Integer.toString(low.landingTick));
            result.put("lowHealthMarkerFailure", low.failure);
        }
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
        result.put("zombieAiEnabled", Boolean.toString(!fixture.zombie.isAiDisabled()));
        result.put("zombiePosition", fixture.zombie.getX() + "," + fixture.zombie.getY() + "," + fixture.zombie.getZ());
        result.put("cowUuid", fixture.cow.getUuid().toString());
        result.put("cowAlive", Boolean.toString(fixture.cow.isAlive()));
        result.put("cowInitialHealth", Float.toString(fixture.cowInitialHealth));
        result.put("cowHealth", Float.toString(fixture.cow.getHealth()));
        result.put("cowPosition", fixture.cow.getX() + "," + fixture.cow.getY() + "," + fixture.cow.getZ());
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
            if (fixture.creeperContact) {
                boolean openPlatform = world.getBlockState(new BlockPos(2, 63, 0)).isOf(Blocks.BEDROCK)
                    && world.getBlockState(new BlockPos(2, 64, 0)).isOf(Blocks.AIR)
                    && world.getBlockState(new BlockPos(2, 65, 0)).isOf(Blocks.AIR)
                    && world.getBlockState(new BlockPos(2, 66, 0)).isOf(Blocks.AIR);
                result.put("creeperAiEnabled", Boolean.toString(!fixture.creeper.isAiDisabled()));
                result.put("creeperTargetsPlayer", Boolean.toString(fixture.creeper.getTarget() == player));
                result.put("creeperLastDamage", fixture.creeperContactLastDamage);
                result.put("creeperLastDamageByPlayer", Boolean.toString(fixture.creeperContactLastDamageByPlayer));
                result.put("creeperContactObservedServerTicks", Integer.toString(fixture.creeperContactObservedServerTicks));
                result.put("creeperContactMinimumPlayerHealth", Float.toString(fixture.creeperContactMinimumPlayerHealth));
                result.put("creeperContactPeakFuse", Float.toString(fixture.creeperContactPeakFuse));
                result.put("creeperContactFuse", Float.toString(fixture.creeper.getClientFuseTime(1.0f)));
                result.put("creeperContactFuseSpeed", Integer.toString(fixture.creeper.getFuseSpeed()));
                result.put("creeperContactPlayerKillObserved", Boolean.toString(fixture.creeperContactPlayerKillObserved));
                result.put("creeperContactExplosionObserved", Boolean.toString(fixture.creeperContactExplosionObserved));
                boolean escaped = fixture.creeper.isAlive() && !fixture.creeper.isRemoved()
                    && fixture.creeper.getHealth() == 20.0F && fixture.creeper.squaredDistanceTo(player) >= 144.0
                    && fixture.creeper.getClientFuseTime(1.0f) <= 0.0F && fixture.creeper.getFuseSpeed() <= 0;
                result.put("creeperContactOutcome", fixture.creeperContactPlayerKillObserved ? "player_kill"
                    : fixture.creeperContactExplosionObserved ? "exploded" : escaped ? "escaped_alive" : "pending");
                result.put("creeperContactControlShade", Boolean.toString(world.getBlockState(new BlockPos(20, 67, 20)).isOf(Blocks.BEDROCK)));
                result.put("creeperContactClockFrozen", Boolean.toString(player.getServer().getTickManager().isFrozen()));
                result.put("creeperContactClockReleaseServerTick", Integer.toString(fixture.creeperContactReleaseServerTick));
                result.put("creeperContactOpenPlatform", Boolean.toString(openPlatform));
                result.put("creeperContactPlayerHealth", Float.toString(player.getHealth()));
                result.put("creeperContactPlayerAlive", Boolean.toString(player.isAlive()));
                result.put("creeperContactPlayerDeaths", Integer.toString(player.getStatHandler().getStat(
                    net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS))));
                result.put("stoneSwordDamage", Integer.toString(player.getInventory().getStack(2).isOf(Items.STONE_SWORD)
                    ? player.getInventory().getStack(2).getDamage() : -1));
            } else {
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
        }
        appendContactThreatReceipt(result, player, fixture);
        return Map.copyOf(result);
    }

    static final class PreparedSafetyThreatFixture {
        private ContactThreatObservation contact;
        private final ZombieEntity zombie;
        private final CowEntity cow;
        private final float cowInitialHealth;
        private final CreeperEntity creeper;
        private final boolean creeperContact;
        private int creeperContactReleaseServerTick = -1;
        private int creeperContactObservedServerTicks;
        private float creeperContactMinimumPlayerHealth = 20.0F;
        private float creeperContactLastHealth = 20.0F, creeperContactPeakFuse;
        private String creeperContactLastDamage = "";
        private boolean creeperContactLastDamageByPlayer, creeperContactPlayerKillObserved, creeperContactExplosionObserved;

        private PreparedSafetyThreatFixture(ZombieEntity zombie, CowEntity cow, float cowInitialHealth) {
            this(zombie, cow, cowInitialHealth, null);
        }
        private PreparedSafetyThreatFixture(ZombieEntity zombie, CowEntity cow, float cowInitialHealth, CreeperEntity creeper) {
            this(zombie, cow, cowInitialHealth, creeper, false);
        }
        private PreparedSafetyThreatFixture(ZombieEntity zombie, CowEntity cow, float cowInitialHealth,
                                            CreeperEntity creeper, boolean creeperContact) {
            this.zombie = zombie;
            this.cow = cow;
            this.cowInitialHealth = cowInitialHealth;
            this.creeper = creeper;
            this.creeperContact = creeperContact;
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
}
