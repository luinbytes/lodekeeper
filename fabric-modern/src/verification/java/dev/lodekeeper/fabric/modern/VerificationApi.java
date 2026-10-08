package dev.lodekeeper.fabric.modern;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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
import net.minecraft.world.item.Item;
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


    static Object seedShieldScenario(ServerPlayer player, ServerLevel world, String scenario) {
        int iron = "queued".equals(scenario) ? 12 : "iron_short".equals(scenario) ? 9 : 10;
        int planks = "planks_short".equals(scenario) ? 18 : 19;
        if (!player.getInventory().add(new ItemStack(Items.IRON_INGOT, iron))
                || !player.getInventory().add(new ItemStack(Items.OAK_PLANKS, planks)))
            throw new IllegalStateException("could not seed shield scenario stock");
        for (int x = -5; x <= 8; x++) for (int z = -4; z <= 4; z++) {
            world.setBlock(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState(), 3);
            for (int y = 64; y <= 66; y++) world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            world.setBlock(new BlockPos(x, 67, z), Blocks.BEDROCK.defaultBlockState(), 3);
        }
        world.setBlock(new BlockPos(-2, 64, 0), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        ShieldScenarioFixture fixture = new ShieldScenarioFixture(scenario, iron, planks);
        if (java.util.List.of("worn", "occupied", "manual").contains(scenario)) {
            ItemStack shield = new ItemStack(Items.SHIELD);
            shield.setDamageValue(200);
            player.getInventory().setItem(20, shield);
            if ("occupied".equals(scenario)) player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TORCH, 8));
            if (!player.getInventory().add(new ItemStack(Items.STONE_SWORD)))
                throw new IllegalStateException("could not seed ordinary shield defense sword");
            net.minecraft.world.entity.monster.zombie.Zombie zombie = (net.minecraft.world.entity.monster.zombie.Zombie) preparedMob(world, "minecraft:zombie");
            zombie.setPos(2.0, 64.0, 0.5);
            zombie.setBaby(false);
            zombie.setNoAi(true);
            zombie.setTarget(player);
            zombie.setHealth(zombie.getMaxHealth());
            if (!world.addFreshEntity(zombie)) throw new IllegalStateException("could not seed controlled shield threat");
            fixture.zombie = zombie;
            registerShieldDamageReceipt(player, fixture);
        }
        player.getInventory().setChanged();
        return fixture;
    }

    private static void registerShieldDamageReceipt(ServerPlayer player, ShieldScenarioFixture fixture) {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (entity != player || source.getEntity() != fixture.zombie) return;
            fixture.damageEvents++;
            if (blocked) fixture.blockedDamageEvents++;
            fixture.lastBaseDamage = baseDamage;
            fixture.lastDamage = damage;
        });
    }

    static void observeShieldScenario(Object value, ServerPlayer player, int serverTick) {
        ShieldScenarioFixture fixture = (ShieldScenarioFixture) value;
        ItemStack source = player.getInventory().getItem(20), offhand = player.getOffhandItem();
        boolean equipped = source.isEmpty() && offhand.is(Items.SHIELD) && offhand.getDamageValue() >= 200;
        if (equipped && fixture.equipServerTick < 0) fixture.equipServerTick = serverTick;
        if (fixture.equipServerTick >= 0 && source.is(Items.SHIELD) && offhand.isEmpty())
            fixture.restoreServerTick = serverTick;
        boolean nativeUse = player.isUsingItem() && player.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND
                && player.getUseItem().is(Items.SHIELD);
        if (nativeUse) fixture.nativeUseServerTicks++;
        fixture.consecutiveUseTicks = nativeUse ? fixture.consecutiveUseTicks + 1 : 0;
        if (fixture.zombie != null && fixture.zombie.isAlive() && fixture.consecutiveUseTicks >= 6 && !fixture.attackProbeIssued) {
            fixture.attackProbeIssued = true;
            fixture.attackProbeServerTick = serverTick;
            fixture.zombie.doHurtTarget((ServerLevel) player.level(), player);
        }
        fixture.minimumHealth = Math.min(fixture.minimumHealth, player.getHealth());
    }

    static Map<String, String> shieldScenarioReceipt(Object value, ServerPlayer player, int serverTick) {
        ShieldScenarioFixture fixture = (ShieldScenarioFixture) value;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("scenario", fixture.scenario);
        result.put("serverTick", Integer.toString(serverTick));
        result.put("initialIron", Integer.toString(fixture.iron));
        result.put("initialPlanks", Integer.toString(fixture.planks));
        for (Item item : java.util.List.of(Items.IRON_INGOT, Items.OAK_PLANKS, Items.SHIELD, Items.BUCKET, Items.SHEARS)) {
            int count = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(item)) count += stack.getCount();
            }
            result.put(BuiltInRegistries.ITEM.getKey(item).toString(), Integer.toString(count));
        }
        result.put("craftedShields", Integer.toString(player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.SHIELD))));
        result.put("craftedBuckets", Integer.toString(player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.BUCKET))));
        ItemStack source = player.getInventory().getItem(20), offhand = player.getOffhandItem();
        result.put("sourceItem", source.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(source.getItem()).toString());
        result.put("sourceCount", Integer.toString(source.getCount()));
        result.put("sourceDamage", Integer.toString(source.getDamageValue()));
        result.put("offhandItem", offhand.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString());
        result.put("offhandCount", Integer.toString(offhand.getCount()));
        result.put("offhandDamage", Integer.toString(offhand.getDamageValue()));
        result.put("equipServerTick", Integer.toString(fixture.equipServerTick));
        result.put("restoreServerTick", Integer.toString(fixture.restoreServerTick));
        result.put("nativeUseServerTicks", Integer.toString(fixture.nativeUseServerTicks));
        result.put("usingItem", Boolean.toString(player.isUsingItem()));
        result.put("blockedDamageEvents", Integer.toString(fixture.blockedDamageEvents));
        result.put("damageEvents", Integer.toString(fixture.damageEvents));
        result.put("attackProbeIssued", Boolean.toString(fixture.attackProbeIssued));
        result.put("attackProbeServerTick", Integer.toString(fixture.attackProbeServerTick));
        result.put("attackProbeKind", "controlled_NoAI_zombie_native_doHurtTarget_after_six_shield_use_ticks");
        result.put("lastBaseDamage", Float.toString(fixture.lastBaseDamage));
        result.put("lastDamage", Float.toString(fixture.lastDamage));
        result.put("minimumHealth", Float.toString(fixture.minimumHealth));
        result.put("cursorEmpty", Boolean.toString(player.containerMenu.getCarried().isEmpty()));
        result.put("zombieAlive", Boolean.toString(fixture.zombie != null && fixture.zombie.isAlive()));
        result.put("tablePresent", Boolean.toString(player.level().getBlockState(new BlockPos(-2, 64, 0)).is(Blocks.CRAFTING_TABLE)));
        return Map.copyOf(result);
    }

    private static final class ShieldScenarioFixture {
        private final String scenario;
        private final int iron, planks;
        private net.minecraft.world.entity.monster.zombie.Zombie zombie;
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

    static void seedPreparedSafetyFixture(ServerPlayer player, String mode) {
        if ("held-fuel".equals(mode)) {
            if (!player.getInventory().add(new ItemStack(Items.RAW_IRON, 3))
                    || !player.getInventory().add(new ItemStack(Items.OAK_LOG, 2))
                    || !player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 3))
                    || !player.getInventory().add(new ItemStack(Items.FURNACE))
                    || !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
                throw new IllegalStateException("could not seed the declared held-fuel stock");
            }
            return;
        }
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

    static void seedPreparedSafetyWorkbench(ServerPlayer player, ServerLevel world) {
        for (int x = -10; x <= 10; x++) for (int z = -5; z <= 5; z++) {
            for (int y = 64; y <= 67; y++) world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        if (!player.getInventory().add(new ItemStack(Items.OAK_PLANKS, 12))) {
            throw new IllegalStateException("could not seed the twelve ordinary workbench planks");
        }
    }

    static PreparedSafetyWorkbenchFixture prepareOwnedWorkbenchRecovery(ServerPlayer player,
                                                                          ServerLevel world, boolean blocked) {
        BlockPos table = null;
        for (int x = -10; x <= 10; x++) for (int z = -5; z <= 5; z++) for (int y = 64; y <= 67; y++) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (!world.getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) continue;
            if (table != null) throw new IllegalStateException("setup command left more than one native crafting table");
            table = candidate;
        }
        if (table == null) throw new IllegalStateException("setup command did not leave its native crafting table");
        for (int x = table.getX() - 2; x <= table.getX() + 10; x++) for (int z = table.getZ() - 3; z <= table.getZ() + 3; z++) {
            world.setBlock(new BlockPos(x, table.getY() - 1, z), Blocks.BEDROCK.defaultBlockState(), 3);
        }
        if (blocked) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            world.setBlock(new BlockPos(table.getX() + dx, table.getY() + dy, table.getZ() + dz), Blocks.BEDROCK.defaultBlockState(), 3);
        }
        BlockPos stone = new BlockPos(table.getX() + 8, table.getY(), table.getZ());
        world.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        player.teleportTo(table.getX() + 6.5, table.getY(), table.getZ() + 0.5);
        return new PreparedSafetyWorkbenchFixture(table, stone, blocked);
    }

    static Map<String, String> preparedSafetyWorkbenchReceipt(ServerPlayer player, ServerLevel world,
                                                                PreparedSafetyWorkbenchFixture fixture) {
        Map<String, String> receipt = new LinkedHashMap<>();
        receipt.put("tablePosition", fixture.table.getX() + "," + fixture.table.getY() + "," + fixture.table.getZ());
        receipt.put("stonePosition", fixture.stone.getX() + "," + fixture.stone.getY() + "," + fixture.stone.getZ());
        receipt.put("tablePresent", Boolean.toString(world.getBlockState(fixture.table).is(Blocks.CRAFTING_TABLE)));
        receipt.put("tableAir", Boolean.toString(world.getBlockState(fixture.table).isAir()));
        receipt.put("stonePresent", Boolean.toString(world.getBlockState(fixture.stone).is(Blocks.STONE)));
        receipt.put("stoneAir", Boolean.toString(world.getBlockState(fixture.stone).isAir()));
        receipt.put("blocked", Boolean.toString(fixture.blocked));
        int shell = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            if (world.getBlockState(new BlockPos(fixture.table.getX() + dx, fixture.table.getY() + dy, fixture.table.getZ() + dz)).is(Blocks.BEDROCK)) shell++;
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

    static PreparedSafetyAirFixture seedPreparedSafetyAirFixture(ServerPlayer player, ServerLevel world) {
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) {
            world.setBlock(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState(), 3);
        }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
            for (int y = 64; y <= 65; y++) {
                world.setBlock(new BlockPos(x, y, z), Blocks.BEDROCK.defaultBlockState(), 3);
            }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            world.setBlock(new BlockPos(x, 64, z), Blocks.WATER.defaultBlockState(), 3);
            world.setBlock(new BlockPos(x, 65, z), Blocks.WATER.defaultBlockState(), 3);
            if (x != 2 || z != 0) {
                world.setBlock(new BlockPos(x, 66, z), Blocks.BEDROCK.defaultBlockState(), 3);
            }
        }
        world.setBlock(new BlockPos(4, 65, 0), Blocks.BEDROCK.defaultBlockState(), 3);
        world.setBlock(new BlockPos(4, 66, 0), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        if (!player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().add(new ItemStack(Items.COOKED_BEEF, 2))) {
            throw new IllegalStateException("could not seed the prepared air-recovery iron and food stock");
        }
        return new PreparedSafetyAirFixture(world);
    }

    static void initializePreparedSafetyAirFixture(PreparedSafetyAirFixture fixture,
                                                    ServerPlayer player, int serverTick) {
        player.setHealth(3.0F);
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(0.0F);
        setAirSupply(player, 200);
        fixture.begin(player, serverTick);
    }

    static void observePreparedSafetyAirTick(PreparedSafetyAirFixture fixture,
                                              ServerPlayer player, int serverTick) {
        fixture.observe(player, serverTick);
    }

    static Map<String, String> preparedSafetyAirReceipt(ServerPlayer player,
                                                         PreparedSafetyAirFixture fixture) {
        return fixture.receipt(player);
    }

    private static void setAirSupply(ServerPlayer player, int airSupply) {
        player.setAirSupply(airSupply);
    }

    private static int airSupply(ServerPlayer player) {
        return player.getAirSupply();
    }

    private static boolean headInWater(ServerPlayer player) {
        return player.isUnderWater();
    }

    private static String position(ServerPlayer player) {
        return Double.toString(player.getX()) + "," + Double.toString(player.getY()) + ","
            + Double.toString(player.getZ());
    }

    static final class PreparedSafetyAirFixture {
        private final ServerLevel world;
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

        private PreparedSafetyAirFixture(ServerLevel world) {
            this.world = world;
        }

        private void begin(ServerPlayer player, int serverTick) {
            recording = true;
            startServerTick = serverTick;
            initialAirSupply = airSupply(player);
            maximumAirSupply = player.getMaxAirSupply();
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

        private void observe(ServerPlayer player, int serverTick) {
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

        private void appendPosition(int serverTick, ServerPlayer player) {
            if (serverPositionSamples.length() > 0) serverPositionSamples.append(';');
            serverPositionSamples.append(serverTick).append('=').append(position(player));
        }

        private Map<String, String> receipt(ServerPlayer player) {
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
            result.put("foodLevel", Integer.toString(player.getFoodData().getFoodLevel()));
            result.put("saturation", Float.toString(player.getFoodData().getSaturationLevel()));
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
            result.put("dryExitPresent", Boolean.toString(world.getBlockState(new BlockPos(3, 66, 0)).isAir()
                && world.getBlockState(new BlockPos(3, 67, 0)).isAir()
                && world.getBlockState(new BlockPos(3, 65, 0)).is(Blocks.BEDROCK)));
            result.put("craftingTablePresent", Boolean.toString(world.getBlockState(new BlockPos(4, 66, 0)).is(Blocks.CRAFTING_TABLE)
                && world.getBlockState(new BlockPos(4, 65, 0)).is(Blocks.BEDROCK)));
            result.put("exitDistance", Double.toString(Math.sqrt(13.0)));
            return Map.copyOf(result);
        }

        private boolean waterSourceCellsPresent() {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 64; y <= 65; y++) {
                BlockPos position = new BlockPos(x, y, z);
                if (!world.getBlockState(position).is(Blocks.WATER) || !world.getFluidState(position).isSource()) return false;
            }
            return true;
        }

        private boolean lowWaterRoofPresent() {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                if (x == 2 && z == 0) continue;
                if (!world.getBlockState(new BlockPos(x, 66, z)).is(Blocks.BEDROCK)) return false;
            }
            return world.getBlockState(new BlockPos(2, 66, 0)).isAir();
        }

        private boolean waterBoundaryPresent() {
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
                for (int y = 64; y <= 65; y++) {
                    if (!world.getBlockState(new BlockPos(x, y, z)).is(Blocks.BEDROCK)) return false;
                }
            }
            return true;
        }
    }

    static PreparedSafetyPursuitFixture seedPreparedSafetyPursuitFixture(ServerPlayer player,
                                                                          ServerLevel world) {
        boolean toolVariant = "pursuit-tool".equals(System.getProperty("lodekeeper.verify.preparedSafety"));
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
        if (toolVariant) {
            player.getInventory().setItem(7, new ItemStack(Items.STONE_PICKAXE));
            if (!player.getInventory().getItem(7).is(Items.STONE_PICKAXE)) {
                throw new IllegalStateException("could not seed the pursuit-tool stone pickaxe in hotbar slot 7");
            }
        }
        player.getFoodData().setFoodLevel(7);
        player.getFoodData().setSaturation(0.0F);
        Mob cow = preparedMob(world, "minecraft:cow");
        cow.setPos(8.5, 64.0, 0.5);
        cow.setYRot(180.0F);
        cow.setXRot(0.0F);
        if (!world.addFreshEntity(cow)) throw new IllegalStateException("could not spawn the prepared pursuit cow");
        return new PreparedSafetyPursuitFixture(cow, player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel(), toolVariant,
            toolVariant ? 7 : -1, toolVariant ? player.getInventory().getItem(7).getDamageValue() : -1);
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
        if (fixture.toolVariant) {
            int stonePickaxeSlot = findStonePickaxeSlot(player);
            ItemStack stonePickaxe = stonePickaxeSlot < 0 ? ItemStack.EMPTY : player.getInventory().getItem(stonePickaxeSlot);
            result.put("pursuitToolVariant", "true");
            result.put("stonePickaxeInitialSlot", Integer.toString(fixture.stonePickaxeInitialSlot));
            result.put("stonePickaxeCurrentSlot", Integer.toString(stonePickaxeSlot));
            result.put("stonePickaxeInitialDamage", Integer.toString(fixture.stonePickaxeInitialDamage));
            result.put("stonePickaxeDamage", Integer.toString(stonePickaxeSlot < 0 ? -1 : stonePickaxe.getDamageValue()));
            result.put("stonePickaxeMaxDamage", Integer.toString(stonePickaxeSlot < 0 ? -1 : stonePickaxe.getMaxDamage()));
        }
        return Map.copyOf(result);
    }

    private static int findStonePickaxeSlot(ServerPlayer player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getItem(slot).is(Items.STONE_PICKAXE)) return slot;
        }
        return -1;
    }

    static final class PreparedSafetyPursuitFixture {
        private final Mob cow;
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

        private PreparedSafetyPursuitFixture(Mob cow, int initialFoodLevel, float initialSaturation,
                                              boolean toolVariant, int stonePickaxeInitialSlot,
                                              int stonePickaxeInitialDamage) {
            this.cow = cow;
            cowUuid = cow.getUUID().toString();
            initialHealth = cow.getHealth();
            lastObservedHealth = initialHealth;
            this.initialFoodLevel = initialFoodLevel;
            this.initialSaturation = initialSaturation;
            this.toolVariant = toolVariant;
            this.stonePickaxeInitialSlot = stonePickaxeInitialSlot;
            this.stonePickaxeInitialDamage = stonePickaxeInitialDamage;
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

    static Map<String, String> preparedSafetyHeldFuelReceipt(ServerLevel world) {
        Map<String, String> result = new LinkedHashMap<>();
        int furnaces = 0, input = 0, fuel = 0, output = 0;
        StringBuilder positions = new StringBuilder();
        for (int x = -6; x <= 6; x++) for (int y = 64; y <= 67; y++) for (int z = -6; z <= 6; z++) {
            BlockPos position = new BlockPos(x, y, z);
            if (!world.getBlockState(position).is(Blocks.FURNACE)) continue;
            if (furnaces++ > 0) positions.append(';');
            positions.append(x).append(',').append(y).append(',').append(z);
            if (!(world.getBlockEntity(position) instanceof net.minecraft.world.Container inventory)) {
                throw new IllegalStateException("native fixture furnace has no inventory");
            }
            input += inventory.getItem(0).getCount();
            fuel += inventory.getItem(1).getCount();
            output += inventory.getItem(2).getCount();
        }
        result.put("nearbyFurnaceCount", Integer.toString(furnaces));
        result.put("nativeFurnacePositions", positions.toString());
        result.put("furnaceInputCount", Integer.toString(input));
        result.put("furnaceFuelCount", Integer.toString(fuel));
        result.put("furnaceOutputCount", Integer.toString(output));
        return Map.copyOf(result);
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
        if (Boolean.getBoolean("lodekeeper.verify.threatContact")) return seedPreparedSafetyContactFixture(player, world);
        if (Boolean.getBoolean("lodekeeper.verify.threatCreeperContact")) {
            player.level().getServer().tickRateManager().setFrozen(true);
            for (int x = -12; x <= 24; x++) for (int z = -6; z <= 24; z++) {
                world.setBlock(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState(), 3);
            }
            world.setBlock(new BlockPos(20, 67, 20), Blocks.BEDROCK.defaultBlockState(), 3);
            if (!player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD))
                    || !player.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE))
                    || !player.getInventory().add(new ItemStack(Items.STONE_SWORD))
                    || !player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3))
                    || !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
                throw new IllegalStateException("could not seed the creeper-contact weapons and bucket stock");
            }
            player.getInventory().setSelectedSlot(2);
            Mob zombie = preparedMob(world, "minecraft:zombie");
            zombie.setPos(20.5, 64.0, 20.5);
            zombie.setYRot(180.0F);
            zombie.setXRot(0.0F);
            zombie.setNoAi(true);
            zombie.setHealth(4.0F);
            Mob cow = preparedMob(world, "minecraft:cow");
            cow.setPos(18.5, 64.0, 20.5);
            cow.setYRot(180.0F);
            cow.setXRot(0.0F);
            cow.setNoAi(true);
            Mob creeper = preparedMob(world, "minecraft:creeper");
            creeper.setPos(2.5, 64.0, 0.5);
            creeper.setYRot(-90.0F);
            creeper.setXRot(0.0F);
            creeper.setNoAi(false);
            creeper.setHealth(20.0F);
            creeper.setTarget(player);
            if (!world.addFreshEntity(zombie) || !world.addFreshEntity(cow) || !world.addFreshEntity(creeper)) {
                throw new IllegalStateException("could not spawn the creeper-contact targets and protected cow");
            }
            return new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth(), creeper, true);
        }
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
        if (waterRetreat) zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
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

    static PreparedSafetyThreatFixture seedPreparedSafetyContactFixture(ServerPlayer player, ServerLevel world) {
        player.level().getServer().tickRateManager().setFrozen(true);
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = 63; y <= 68; y++) {
            boolean passage = x >= 0 && x <= 6 && z >= 0 && z <= 1 && y >= 64 && y <= 65;
            world.setBlock(new BlockPos(x, y, z), (passage ? Blocks.AIR : Blocks.BEDROCK).defaultBlockState(), 3);
        }
        if (!player.getInventory().add(new ItemStack(Items.DIAMOND_SWORD))
                || !player.getInventory().add(new ItemStack(Items.IRON_PICKAXE))
                || !player.getInventory().add(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
            throw new IllegalStateException("could not seed the live contact sword, iron pickaxe and bucket stock");
        }
        player.getInventory().setSelectedSlot(0);
        Mob first = preparedMob(world, "minecraft:zombie");
        Mob second = preparedMob(world, "minecraft:zombie");
        Mob cow = preparedMob(world, "minecraft:cow");
        first.setPos(1.8, 64.0, 0.45);
        second.setPos(1.8, 64.0, 1.45);
        cow.setPos(2.5, 64.0, 1.5);
        ((net.minecraft.world.entity.monster.zombie.Zombie) first).setBaby(false);
        ((net.minecraft.world.entity.monster.zombie.Zombie) second).setBaby(false);
        first.setNoAi(false);
        second.setNoAi(false);
        first.setHealth(first.getMaxHealth());
        second.setHealth(second.getMaxHealth());
        first.setTarget(player);
        second.setTarget(player);
        cow.setNoAi(true);
        if (!world.addFreshEntity(first) || !world.addFreshEntity(second) || !world.addFreshEntity(cow)) {
            throw new IllegalStateException("could not spawn both full-health AI-enabled contact zombies and protected cow");
        }
        PreparedSafetyThreatFixture fixture = new PreparedSafetyThreatFixture(first, cow, cow.getHealth());
        fixture.contact = new ContactThreatObservation(second);
        if ("true".equals(System.getProperty("lodekeeper.verify.threatContactLowHealth")))
            fixture.contact.lowHealth = new ContactLowHealthObservation(player, world);
        java.util.function.BiConsumer<net.minecraft.world.entity.LivingEntity, net.minecraft.world.damagesource.DamageSource> confirmSwordDamage = (entity, source) -> {
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
                if (target >= 0) low.pending[target] = contact.observing && source.getEntity() == player
                        ? new ContactSwordAttempt(!player.onGround(), entity.getHealth(), source) : null;
            }
            int index = entity == fixture.zombie ? 0 : entity == contact.second ? 1 : -1;
            if (index >= 0) {
                contact.pendingSwordDamage[index] = contact.observing && source.getEntity() == player
                        && player.getMainHandItem().is(Items.DIAMOND_SWORD)
                        ? new ContactSwordAttempt(!player.onGround(), entity.getHealth(), source) : null;
            }
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) ->
                confirmSwordDamage.accept(entity, source));
        ServerLivingEntityEvents.AFTER_DEATH.register(confirmSwordDamage::accept);
        return fixture;
    }

    static void releasePreparedSafetyThreatClock(PreparedSafetyThreatFixture fixture, ServerPlayer player) {
        if (fixture.contact != null) {
            fixture.contact.observing = true;
            fixture.contact.releaseServerTick = player.level().getServer().getTickCount();
        } else if (fixture.creeperContact) {
            fixture.creeperContactReleaseServerTick = player.level().getServer().getTickCount();
        } else {
            throw new IllegalStateException("live contact fixture is absent");
        }
        player.level().getServer().tickRateManager().setFrozen(false);
    }

    static void observePreparedSafetyThreatTick(PreparedSafetyThreatFixture fixture, ServerPlayer player, int serverTick) {
        if (fixture.creeperContact && fixture.creeperContactReleaseServerTick >= 0) {
            fixture.creeperContactObservedServerTicks++;
            fixture.creeperContactMinimumPlayerHealth = Math.min(fixture.creeperContactMinimumPlayerHealth, player.getHealth());
            fixture.creeperContactPeakFuse = Math.max(fixture.creeperContactPeakFuse, ((net.minecraft.world.entity.monster.Creeper) fixture.creeper).getSwelling(1.0f));
            if (fixture.creeper.getHealth() < fixture.creeperContactLastHealth) {
                var damage = fixture.creeper.getLastDamageSource();
                fixture.creeperContactLastDamage = damage == null ? "" : damage.getMsgId();
                fixture.creeperContactLastDamageByPlayer = damage != null && damage.getEntity() == player;
            }
            fixture.creeperContactLastHealth = fixture.creeper.getHealth();
            if (!fixture.creeper.isAlive() && fixture.creeper.getHealth() <= 0.0F
                    && "player".equals(fixture.creeperContactLastDamage) && fixture.creeperContactLastDamageByPlayer)
                fixture.creeperContactPlayerKillObserved = true;
            if (fixture.creeper.isRemoved() && !fixture.creeper.isAlive() && fixture.creeper.getHealth() > 0.0F
                    && ((net.minecraft.world.entity.monster.Creeper) fixture.creeper).getSwelling(1.0f) >= 1.0F)
                fixture.creeperContactExplosionObserved = true;
        }
        if (fixture.contact == null || !fixture.contact.observing) return;
        ContactThreatObservation contact = fixture.contact;
        contact.observedServerTicks++;
        ContactLowHealthObservation low = contact.lowHealth;
        if (low != null && low.fenceTick >= 0 && low.landingTick < 0 && player.onGround()
                && Math.abs(player.getY() - 64.0) <= 0.0625
                && player.level().getBlockCollisions(player, player.getBoundingBox().move(0.0, -0.05, 0.0)).iterator().hasNext())
            low.landingTick = serverTick;
        boolean airborne = !player.onGround();
        boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 0.0004;
        if (airborne) contact.airborneTicks++;
        if (moving) contact.horizontalMotionTicks++;
        Mob[] zombies = new Mob[]{fixture.zombie, contact.second};
        for (int index = 0; index < zombies.length; index++) {
            Mob zombie = zombies[index];
            if (zombie.getHealth() < contact.lastHealth[index]) {
                if (airborne || moving) contact.mobHealthDropsWhilePlayerUnsettled++;
                var damage = zombie.getLastDamageSource();
                contact.lastDamage[index] = damage == null ? "" : damage.getMsgId();
                contact.lastDamageByPlayer[index] = damage != null && damage.getEntity() == player;
                if (damage != null && damage.getEntity() == player && "player".equals(damage.getMsgId())) contact.playerHits[index]++;
                else contact.foreignDamage[index]++;
            }
            contact.lastHealth[index] = zombie.getHealth();
        }
        if (player.getHealth() < contact.lastPlayerHealth) {
            var damage = player.getLastDamageSource();
            if (damage != null && (damage.getEntity() == fixture.zombie || damage.getEntity() == contact.second)) contact.nativePlayerHits++;
        }
        contact.lastPlayerHealth = player.getHealth();
    }

    static final int CONTACT_LOW_HEALTH_RELEASE = 3;

    private record ContactLowHealthMarker(java.util.UUID session, java.util.UUID fixture, int stage)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        private static final Type<ContactLowHealthMarker> ID = new Type<>(Identifier.fromNamespaceAndPath("lodekeeper-verification", "contact_low_health"));
        private static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, ContactLowHealthMarker> CODEC = new net.minecraft.network.codec.StreamCodec<>() {
            @Override public ContactLowHealthMarker decode(net.minecraft.network.RegistryFriendlyByteBuf buffer) {
                return new ContactLowHealthMarker(buffer.readUUID(), buffer.readUUID(), buffer.readInt());
            }
            @Override public void encode(net.minecraft.network.RegistryFriendlyByteBuf buffer, ContactLowHealthMarker marker) {
                buffer.writeUUID(marker.session()); buffer.writeUUID(marker.fixture()); buffer.writeInt(marker.stage());
            }
        };
        @Override public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() { return ID; }
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
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(ContactLowHealthMarker.ID, ContactLowHealthMarker.CODEC);
        boolean registered = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(ContactLowHealthMarker.ID, (marker, context) -> {
            PreparedSafetyThreatFixture fixture = currentFixture.get();
            ServerPlayer player = context.player();
            java.util.UUID expectedPlayer = originalPlayerId.get();
            if (!session.equals(marker.session()) || player == null || fixture == null || fixture.contact == null
                    || fixture.contact.lowHealth == null || !fixture.zombie.getUUID().equals(marker.fixture())
                    || !player.getUUID().equals(expectedPlayer)) {
                markerDiagnostic.accept("event=identity-rejected stage=" + marker.stage() + " session=" + marker.session() + " expectedSession=" + session
                    + " fixture=" + marker.fixture() + " currentFixture=" + (fixture == null || fixture.zombie == null ? "unknown" : fixture.zombie.getUUID())
                    + " contactPresent=" + (fixture != null && fixture.contact != null)
                    + " lowHealthPresent=" + (fixture != null && fixture.contact != null && fixture.contact.lowHealth != null)
                    + " player=" + (player == null ? "unknown" : player.getUUID()) + " originalPlayer=" + (expectedPlayer == null ? "unknown" : expectedPlayer)
                    + " originalPlayerMatch=" + (player == null || expectedPlayer == null ? "unknown" : player.getUUID().equals(expectedPlayer))
                    + " serverTick=" + (player == null || player.level().getServer() == null ? "unknown" : player.level().getServer().getTickCount())
                    + " health=" + (player == null ? "unknown" : player.getHealth()) + " onGround=" + (player == null ? "unknown" : player.onGround()));
                return;
            }
            ContactLowHealthObservation low = fixture.contact.lowHealth;
            if (marker.stage() == 0 || marker.stage() == CONTACT_LOW_HEALTH_RELEASE) markerDiagnostic.accept("event=identity-accepted stage=" + marker.stage() + " session=" + marker.session()
                + " fixture=" + fixture.zombie.getUUID() + " player=" + player.getUUID() + " originalPlayerMatch=true"
                + " ownerMatch=" + (low.owner == player) + " connectionMatch=" + (low.connection == null || player.connection == null ? "unknown" : low.connection == player.connection)
                + " fixtureIdentity=" + System.identityHashCode(fixture) + " connectionIdentity=" + System.identityHashCode(player.connection)
                + " originalWorldIdentity=" + System.identityHashCode(low.world) + " playerWorldIdentity=" + System.identityHashCode(player.level())
                + " serverTick=" + (player.level().getServer() == null ? "unknown" : player.level().getServer().getTickCount())
                + " health=" + player.getHealth() + " onGround=" + player.onGround());
            if (!low.failure.isEmpty()) {
                markerDiagnostic.accept("event=prior-failure-rejected stage=" + marker.stage() + " fixture=" + marker.fixture() + " failure=" + low.failure);
                return;
            }
            if (low.owner != player || low.connection == null || low.connection != player.connection
                    || low.world == null || player.level() != low.world || fixture.zombie.level() != low.world
                    || fixture.contact.second.level() != low.world || fixture.cow.level() != low.world) {
                low.failure = "low-health marker original player, connection or fixture world changed";
                markerDiagnostic.accept("event=context-rejected stage=" + marker.stage() + " fixture=" + marker.fixture() + " failure=" + low.failure);
                return;
            }
            if (marker.stage() != CONTACT_LOW_HEALTH_RELEASE && marker.stage() != 0 && marker.stage() != 1 && marker.stage() != 2) {
                low.failure = "invalid low-health marker stage";
                markerDiagnostic.accept("event=stage-rejected stage=" + marker.stage() + " fixture=" + marker.fixture());
                return;
            }
            int serverTick = player.level().getServer().getTickCount();
            if (marker.stage() == CONTACT_LOW_HEALTH_RELEASE) {
                ContactThreatObservation contact = fixture.contact;
                var clock = low.world.getServer().tickRateManager();
                boolean admitted = clock.isFrozen() && !contact.observing && contact.releaseServerTick == -1
                    && low.mutationTick == -1 && low.fenceTick == -1 && low.pauseFenceTick == -1
                    && low.landingTick == -1 && player.onGround();
                String identity = " stage=" + marker.stage() + " session=" + marker.session() + " fixture=" + marker.fixture()
                    + " fixtureIdentity=" + System.identityHashCode(fixture) + " player=" + player.getUUID()
                    + " playerIdentity=" + System.identityHashCode(player) + " connectionIdentity=" + System.identityHashCode(low.connection)
                    + " world=" + low.world.dimension().identifier() + " worldIdentity=" + System.identityHashCode(low.world)
                    + " playerWorldIdentity=" + System.identityHashCode(player.level());
                markerDiagnostic.accept("event=release-before" + identity + " serverTick=" + serverTick
                    + " frozen=" + clock.isFrozen() + " observing=" + contact.observing + " releaseTick=" + contact.releaseServerTick
                    + " mutationTick=" + low.mutationTick + " fenceTick=" + low.fenceTick + " pauseFenceTick=" + low.pauseFenceTick
                    + " failure=" + low.failure + " onGround=" + player.onGround() + " admitted=" + admitted);
                if (!admitted) {
                    low.failure = "low-health release requires the original grounded frozen unreleased fixture";
                    markerDiagnostic.accept("event=release-rejected" + identity + " failure=" + low.failure);
                    return;
                }
                try {
                    releasePreparedSafetyThreatClock(fixture, player);
                    if (!contact.observing || clock.isFrozen() || contact.releaseServerTick != serverTick)
                        low.failure = "low-health release state read-back failed";
                } catch (RuntimeException exception) {
                    low.failure = "low-health release failed: " + exception.getClass().getSimpleName();
                }
                markerDiagnostic.accept("event=release-after" + identity + " serverTick=" + low.world.getServer().getTickCount()
                    + " frozen=" + clock.isFrozen() + " observing=" + contact.observing + " releaseTick=" + contact.releaseServerTick
                    + " mutationTick=" + low.mutationTick + " fenceTick=" + low.fenceTick + " pauseFenceTick=" + low.pauseFenceTick
                    + " failure=" + low.failure + " onGround=" + player.onGround() + " admitted=" + low.failure.isEmpty());
            } else if (marker.stage() == 0) {
                markerDiagnostic.accept("event=mutation-gate serverTick=" + serverTick + " duplicate=" + (low.mutationTick >= 0)
                    + " observing=" + fixture.contact.observing + " onGround=" + player.onGround() + " health=" + player.getHealth()
                    + " admitted=" + (low.mutationTick < 0 && fixture.contact.observing && !player.onGround()));
                if (low.mutationTick >= 0 || !fixture.contact.observing || player.onGround()) {
                    low.failure = "low-health mutation missed the first native airborne hop or was repeated";
                    return;
                }
                low.healthBefore = player.getHealth();
                low.mutationDamageEvents = low.confirmedDamageEvents;
                markerDiagnostic.accept("event=setter-before serverTick=" + player.level().getServer().getTickCount()
                    + " health=" + player.getHealth() + " onGround=" + player.onGround());
                player.setHealth(6.0F);
                low.healthAfter = player.getHealth();
                low.mutationTick = serverTick;
                fixture.contact.lastPlayerHealth = player.getHealth();
                markerDiagnostic.accept("event=mutation-complete serverTick=" + player.level().getServer().getTickCount()
                    + " health=" + player.getHealth() + " onGround=" + player.onGround() + " mutationTick=" + low.mutationTick);
            } else if (marker.stage() == 1) {
                if (markerDiagnosticRecords[0] < 32) {
                    int diagnosticMutationTick = low.mutationTick, diagnosticFenceTick = low.fenceTick;
                    boolean diagnosticOnGround = player.onGround();
                    float diagnosticHealth = player.getHealth();
                    markerDiagnostic.accept("event=onset-gate stage=1 serverTick=" + serverTick
                        + " session=" + marker.session() + " expectedSession=" + session
                        + " fixture=" + marker.fixture() + " currentFixture=" + fixture.zombie.getUUID()
                        + " fixtureIdentity=" + System.identityHashCode(fixture) + " player=" + player.getUUID()
                        + " playerIdentity=" + System.identityHashCode(player) + " ownerIdentity=" + System.identityHashCode(low.owner)
                        + " connectionIdentity=" + System.identityHashCode(player.connection)
                        + " originalConnectionIdentity=" + System.identityHashCode(low.connection)
                        + " worldIdentity=" + System.identityHashCode(low.world) + " playerWorldIdentity=" + System.identityHashCode(player.level())
                        + " contextAlreadyAdmitted=true originalPlayerMatch=true ownerMatch=true connectionMatch=true"
                        + " health=" + diagnosticHealth + " onGround=" + diagnosticOnGround
                        + " mutationTick=" + diagnosticMutationTick + " fenceTick=" + diagnosticFenceTick
                        + " rejectMutationMissing=" + (diagnosticMutationTick < 0) + " rejectFencePresent=" + (diagnosticFenceTick >= 0)
                        + " rejectGrounded=" + diagnosticOnGround + " rejectHealthAboveSix=" + (diagnosticHealth > 6.0F)
                        + " admitted=" + !(diagnosticMutationTick < 0 || diagnosticFenceTick >= 0 || diagnosticOnGround || diagnosticHealth > 6.0F)
                        + " priorFailure=" + low.failure + " confirmedDamageEvents=" + low.confirmedDamageEvents);
                }
                if (low.mutationTick < 0 || low.fenceTick >= 0 || player.onGround() || player.getHealth() > 6.0F) {
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
            }
        });
        if (!registered) throw new IllegalStateException("low-health marker receiver already registered");
        class ClientObservation implements java.util.function.IntConsumer {
            private PreparedSafetyThreatFixture originalFixture;
            private java.util.UUID fixtureId;
            private Object player, connection, world;
            private boolean onset;
            private int clientMarkerDiagnosticRecords;

            private boolean sameContext(PreparedSafetyThreatFixture fixture, net.minecraft.client.Minecraft client) {
                return originalFixture != null && fixture == originalFixture && fixtureId.equals(fixture.zombie.getUUID())
                    && client.player == player && client.getConnection() == connection && client.level == world
                    && client.player != null && client.player.getUUID().equals(originalPlayerId.get());
            }

            @Override public void accept(int stage) {
                if (stage != CONTACT_LOW_HEALTH_RELEASE && stage != 0 && stage != 1 && stage != 2)
                    throw new IllegalArgumentException("invalid low-health marker stage");
                PreparedSafetyThreatFixture fixture = currentFixture.get();
                net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                if (fixture == null || fixture.contact == null || fixture.contact.lowHealth == null
                        || client.player == null || client.getConnection() == null || client.level == null
                        || !client.player.getUUID().equals(originalPlayerId.get())
                        || !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(ContactLowHealthMarker.ID))
                    throw new IllegalStateException("low-health marker transport unavailable for current fixture");
                if (stage == CONTACT_LOW_HEALTH_RELEASE) {
                    if (originalFixture != null) throw new IllegalStateException("low-health release was repeated");
                    originalFixture = fixture;
                    fixtureId = fixture.zombie.getUUID();
                    player = client.player;
                    connection = client.getConnection();
                    world = client.level;
                }
                if (!sameContext(fixture, client))
                    throw new IllegalStateException("low-health original client player, connection or fixture changed");
                if (stage == 1) onset = true;
                if (stage == 1 && clientMarkerDiagnosticRecords < 8) System.out.println("[Lodekeeper verification] low-health client marker record="
                    + (++clientMarkerDiagnosticRecords) + "/8 event=onset-send stage=1 session=" + session + " fixture=" + fixtureId
                    + " fixtureIdentity=" + System.identityHashCode(fixture) + " player=" + client.player.getUUID()
                    + " playerIdentity=" + System.identityHashCode(client.player) + " connectionIdentity=" + System.identityHashCode(connection)
                    + " worldIdentity=" + System.identityHashCode(world) + " contextAlreadyAdmitted=true"
                    + " clientWorldTick=" + client.level.getGameTime()
                    + " clientPlayerAge=" + client.player.tickCount + " health=" + client.player.getHealth() + " onGround=" + client.player.onGround());
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new ContactLowHealthMarker(session, fixtureId, stage));
            }
        }
        ClientObservation observation = new ClientObservation();
        var attackPhase = Identifier.fromNamespaceAndPath("lodekeeper-verification", "contact_low_health_attempts");
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.addPhaseOrdering(attackPhase, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register(attackPhase, (player, world, hand, target, hit) -> {
            if (observation.onset && player instanceof net.minecraft.client.player.LocalPlayer
                    && (player == observation.player || player.getUUID().equals(originalPlayerId.get())))
                observeAttackAttempt.accept(player == observation.player && world == observation.world
                    && observation.sameContext(currentFixture.get(), net.minecraft.client.Minecraft.getInstance()) ? 1 : -1);
            return net.minecraft.world.InteractionResult.PASS;
        });
        return observation;
    }

    private static final class ContactLowHealthObservation {
        private final ServerPlayer owner;
        private final Object connection;
        private final ServerLevel world;
        private final ContactSwordAttempt[] pending = new ContactSwordAttempt[3];
        private int mutationTick = -1, fenceTick = -1, landingTick = -1, pauseFenceTick = -1;
        private int confirmedDamageEvents, mutationDamageEvents = -1, fenceDamageEvents = -1, pauseFenceDamageEvents = -1;
        private float healthBefore = Float.NaN, healthAfter = Float.NaN;
        private String failure = "";
        private ContactLowHealthObservation(ServerPlayer owner, ServerLevel world) {
            this.owner = owner;
            this.connection = owner.connection;
            this.world = world;
        }
    }

    private static void observeContactLowHealthDamage(PreparedSafetyThreatFixture fixture, net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
        ContactLowHealthObservation low = fixture.contact.lowHealth;
        if (low == null) return;
        int index = entity == fixture.zombie ? 0 : entity == fixture.contact.second ? 1 : entity == fixture.cow ? 2 : -1;
        if (index < 0) return;
        ContactSwordAttempt attempt = low.pending[index];
        low.pending[index] = null;
        if (attempt != null && attempt.source() == source && entity.getHealth() < attempt.healthBefore()) {
            low.confirmedDamageEvents++;
            if (low.fenceTick >= 0 && low.failure.isEmpty()) low.failure = "native player damage confirmed after client-observed low-health onset";
        }
    }

    private record ContactSwordAttempt(boolean airborne, float healthBefore, net.minecraft.world.damagesource.DamageSource source) { }

    private static final class ContactThreatObservation {
        private ContactLowHealthObservation lowHealth;
        private final Mob second;
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

        private ContactThreatObservation(Mob second) { this.second = second; }
    }

    private static void appendContactThreatReceipt(Map<String, String> result, ServerPlayer player, PreparedSafetyThreatFixture fixture) {
        ContactThreatObservation contact = fixture.contact;
        if (contact == null) return;
        Mob[] zombies = new Mob[]{fixture.zombie, contact.second};
        for (int index = 0; index < zombies.length; index++) {
            Mob zombie = zombies[index];
            String prefix = "contactZombie" + index;
            result.put(prefix + "BlockCollision", Boolean.toString(player.level().getBlockCollisions(zombie, zombie.getBoundingBox()).iterator().hasNext()));
            result.put(prefix + "Position", zombie.getX() + "," + zombie.getY() + "," + zombie.getZ());
            result.put(prefix + "Uuid", zombie.getUUID().toString());
            result.put(prefix + "Health", Float.toString(zombie.getHealth()));
            result.put(prefix + "Alive", Boolean.toString(zombie.isAlive()));
            result.put(prefix + "AiEnabled", Boolean.toString(!zombie.isNoAi()));
            result.put(prefix + "Adult", Boolean.toString(!((net.minecraft.world.entity.monster.zombie.Zombie) zombie).isBaby()));
            result.put(prefix + "TargetsPlayer", Boolean.toString(zombie.getTarget() == player));
            result.put(prefix + "Visible", Boolean.toString(player.hasLineOfSight(zombie)));
            result.put(prefix + "DistanceSquared", Double.toString(zombie.distanceToSqr(player)));
            result.put(prefix + "OnFire", Boolean.toString(zombie.isOnFire()));
            result.put(prefix + "PlayerHits", Integer.toString(contact.playerHits[index]));
            result.put(prefix + "ForeignDamage", Integer.toString(contact.foreignDamage[index]));
            result.put(prefix + "LastDamage", contact.lastDamage[index]);
            result.put(prefix + "LastDamageByPlayer", Boolean.toString(contact.lastDamageByPlayer[index]));
        }
        int changed = 0, shellCells = 0;
        var world = player.level();
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = 63; y <= 68; y++) {
            if (x >= 0 && x <= 6 && z >= 0 && z <= 1 && y >= 64 && y <= 65) continue;
            shellCells++;
            if (!world.getBlockState(new BlockPos(x, y, z)).is(Blocks.BEDROCK)) changed++;
        }
        ItemStack pickaxe = player.getInventory().getItem(1);
        result.put("ironPickaxeDamage", Integer.toString(pickaxe.is(Items.IRON_PICKAXE) ? pickaxe.getDamageValue() : -1));
        result.put("contactShellCells", Integer.toString(shellCells));
        result.put("contactShellChangedCells", Integer.toString(changed));
        result.put("contactPassageBounds", "0..6,64..65,0..1");
        result.put("contactClockFrozen", Boolean.toString(player.level().getServer().tickRateManager().isFrozen()));
        result.put("contactClockReleaseServerTick", Integer.toString(contact.releaseServerTick));
        result.put("contactObservedServerTicks", Integer.toString(contact.observedServerTicks));
        result.put("contactCowNoAi", Boolean.toString(fixture.cow.isNoAi()));
        result.put("contactCowPosition", fixture.cow.getX() + "," + fixture.cow.getY() + "," + fixture.cow.getZ());
        result.put("contactCowBlockCollision", Boolean.toString(player.level().getBlockCollisions(fixture.cow, fixture.cow.getBoundingBox()).iterator().hasNext()));
        result.put("contactAirborneServerTicks", Integer.toString(contact.airborneTicks));
        result.put("contactAirborneSwordDamageEvents", Integer.toString(contact.airborneSwordDamageEvents));
        result.put("contactGroundedSwordDamageEvents", Integer.toString(contact.groundedSwordDamageEvents));
        result.put("contactHorizontalMotionServerTicks", Integer.toString(contact.horizontalMotionTicks));
        result.put("contactMobHealthDropsWhilePlayerUnsettled", Integer.toString(contact.mobHealthDropsWhilePlayerUnsettled));
        result.put("contactScope", "live_multi_threat_from_idle_no_prior_native_path");
        result.put("contactNativePlayerHits", Integer.toString(contact.nativePlayerHits));
        result.put("contactPlayerAlive", Boolean.toString(player.isAlive()));
        result.put("contactPlayerDeaths", Integer.toString(player.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DEATHS))));
        result.put("contactPlayerPosition", player.getX() + "," + player.getY() + "," + player.getZ());
        result.put("contactPlayerYaw", Float.toString(player.getYRot()));
        result.put("contactPlayerPitch", Float.toString(player.getXRot()));
        ContactLowHealthObservation low = contact.lowHealth;
        if (low != null) {
            result.put("lowHealthPlayerUuid", low.owner.getUUID().toString());
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
        result.put("zombieAiEnabled", Boolean.toString(!fixture.zombie.isNoAi()));
        result.put("zombiePosition", fixture.zombie.getX() + "," + fixture.zombie.getY() + "," + fixture.zombie.getZ());
        var lastDamage = fixture.zombie.getLastDamageSource();
        result.put("zombieLastDamage", lastDamage == null ? "" : lastDamage.getMsgId());
        result.put("cowUuid", fixture.cow.getUUID().toString());
        result.put("cowAlive", Boolean.toString(fixture.cow.isAlive()));
        result.put("cowInitialHealth", Float.toString(fixture.cowInitialHealth));
        result.put("cowHealth", Float.toString(fixture.cow.getHealth()));
        result.put("cowPosition", fixture.cow.getX() + "," + fixture.cow.getY() + "," + fixture.cow.getZ());
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
            if (fixture.creeperContact) {
                boolean openPlatform = world.getBlockState(new BlockPos(2, 63, 0)).is(Blocks.BEDROCK)
                    && world.getBlockState(new BlockPos(2, 64, 0)).isAir()
                    && world.getBlockState(new BlockPos(2, 65, 0)).isAir()
                    && world.getBlockState(new BlockPos(2, 66, 0)).isAir();
                result.put("creeperAiEnabled", Boolean.toString(!fixture.creeper.isNoAi()));
                result.put("creeperTargetsPlayer", Boolean.toString(fixture.creeper.getTarget() == player));
                result.put("creeperLastDamage", fixture.creeperContactLastDamage);
                result.put("creeperLastDamageByPlayer", Boolean.toString(fixture.creeperContactLastDamageByPlayer));
                result.put("creeperContactObservedServerTicks", Integer.toString(fixture.creeperContactObservedServerTicks));
                result.put("creeperContactMinimumPlayerHealth", Float.toString(fixture.creeperContactMinimumPlayerHealth));
                result.put("creeperContactPeakFuse", Float.toString(fixture.creeperContactPeakFuse));
                result.put("creeperContactFuse", Float.toString(((net.minecraft.world.entity.monster.Creeper) fixture.creeper).getSwelling(1.0f)));
                result.put("creeperContactFuseSpeed", Integer.toString(((net.minecraft.world.entity.monster.Creeper) fixture.creeper).getSwellDir()));
                result.put("creeperContactPlayerKillObserved", Boolean.toString(fixture.creeperContactPlayerKillObserved));
                result.put("creeperContactExplosionObserved", Boolean.toString(fixture.creeperContactExplosionObserved));
                boolean escaped = fixture.creeper.isAlive() && !fixture.creeper.isRemoved()
                    && fixture.creeper.getHealth() == 20.0F && fixture.creeper.distanceToSqr(player) >= 144.0
                    && ((net.minecraft.world.entity.monster.Creeper) fixture.creeper).getSwelling(1.0f) <= 0.0F && ((net.minecraft.world.entity.monster.Creeper) fixture.creeper).getSwellDir() <= 0;
                result.put("creeperContactOutcome", fixture.creeperContactPlayerKillObserved ? "player_kill"
                    : fixture.creeperContactExplosionObserved ? "exploded" : escaped ? "escaped_alive" : "pending");
                result.put("creeperContactControlShade", Boolean.toString(world.getBlockState(new BlockPos(20, 67, 20)).is(Blocks.BEDROCK)));
                result.put("creeperContactClockFrozen", Boolean.toString(player.level().getServer().tickRateManager().isFrozen()));
                result.put("creeperContactClockReleaseServerTick", Integer.toString(fixture.creeperContactReleaseServerTick));
                result.put("creeperContactOpenPlatform", Boolean.toString(openPlatform));
                result.put("creeperContactPlayerHealth", Float.toString(player.getHealth()));
                result.put("creeperContactPlayerAlive", Boolean.toString(player.isAlive()));
                result.put("creeperContactPlayerDeaths", Integer.toString(player.getStats().getValue(
                    net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.DEATHS))));
                result.put("stoneSwordDamage", Integer.toString(player.getInventory().getItem(2).is(Items.STONE_SWORD)
                    ? player.getInventory().getItem(2).getDamageValue() : -1));
            } else {
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
        }
        appendContactThreatReceipt(result, player, fixture);
        return Map.copyOf(result);
    }

    static final class PreparedSafetyThreatFixture {
        private ContactThreatObservation contact;
        private final Mob zombie;
        private final Mob cow;
        private final float cowInitialHealth;
        private final Mob creeper;
        private final boolean creeperContact;
        private int creeperContactReleaseServerTick = -1;
        private int creeperContactObservedServerTicks;
        private float creeperContactMinimumPlayerHealth = 20.0F;
        private float creeperContactLastHealth = 20.0F, creeperContactPeakFuse;
        private String creeperContactLastDamage = "";
        private boolean creeperContactLastDamageByPlayer, creeperContactPlayerKillObserved, creeperContactExplosionObserved;

        private PreparedSafetyThreatFixture(Mob zombie, Mob cow, float cowInitialHealth) {
            this(zombie, cow, cowInitialHealth, null);
        }
        private PreparedSafetyThreatFixture(Mob zombie, Mob cow, float cowInitialHealth, Mob creeper) {
            this(zombie, cow, cowInitialHealth, creeper, false);
        }
        private PreparedSafetyThreatFixture(Mob zombie, Mob cow, float cowInitialHealth, Mob creeper,
                                            boolean creeperContact) {
            this.zombie = zombie;
            this.cow = cow;
            this.cowInitialHealth = cowInitialHealth;
            this.creeper = creeper;
            this.creeperContact = creeperContact;
        }
    }

    static PreparedSafetyStationRoomFixture seedPreparedSafetyStationRoomFixture(ServerPlayer player, ServerLevel world) {
        boolean tunnel = "true".equals(System.getProperty("lodekeeper.verify.stationRoomTunnel"));
        boolean approach = "approach".equals(System.getProperty("lodekeeper.verify.stationRoomTunnel"));
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
            world.setBlock(new BlockPos(x, 63, z), Blocks.BEDROCK.defaultBlockState(), 3);
            for (int y = approach ? 58 : 64; y <= 70; y++) {
                world.setBlock(new BlockPos(x, y, z), (approach ? Blocks.BEDROCK : tunnel ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState(), 3);
            }
        }
        if (approach) {
            for (BlockPos position : stationApproachAirCells()) world.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
            for (BlockPos position : new BlockPos[]{new BlockPos(1, 63, 1), new BlockPos(0, 63, 1)}) {
                world.setBlock(position, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            }
        } else {
            world.setBlock(new BlockPos(0, 64, 0), Blocks.AIR.defaultBlockState(), 3);
            world.setBlock(new BlockPos(0, 65, 0), Blocks.AIR.defaultBlockState(), 3);
        }
        if (tunnel) {
            for (int x = -2; x <= 0; x++) for (int y = 64; y <= 66; y++) {
                world.setBlock(new BlockPos(x, y, 0), Blocks.AIR.defaultBlockState(), 3);
            }
            world.setBlock(new BlockPos(-1, 64, 0), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        }
        if (!player.getInventory().add(new ItemStack(Items.STONE_PICKAXE))
                || !player.getInventory().add(new ItemStack(Items.FURNACE))
                || !player.getInventory().add(new ItemStack(Items.COAL))
                || !player.getInventory().add(new ItemStack(Items.RAW_IRON))) {
            throw new IllegalStateException("could not seed the prepared station-room pickaxe, furnace, coal, and raw iron");
        }
        player.getInventory().setSelectedSlot(0);
        BlockPos[] nearbyStoneCells = new BlockPos[approach ? 0 : tunnel ? 66 : 73];
        int index = 0;
        if (!approach) for (int x = -2; x <= 2; x++) for (int y = 64; y <= 66; y++) for (int z = -2; z <= 2; z++) {
            if (tunnel ? x <= 0 && z == 0 : x == 0 && z == 0 && (y == 64 || y == 65)) continue;
            nearbyStoneCells[index++] = new BlockPos(x, y, z);
        }
        if (index != nearbyStoneCells.length) throw new IllegalStateException("station-room receipt cell count differs");
        return new PreparedSafetyStationRoomFixture(nearbyStoneCells, tunnel, approach);
    }

    static Map<String, String> preparedSafetyStationRoomReceipt(ServerPlayer player, ServerLevel world,
                                                                  PreparedSafetyStationRoomFixture fixture) {
        int stillStone = 0;
        int changedStone = 0;
        StringBuilder changedPositions = new StringBuilder();
        for (BlockPos position : fixture.nearbyStoneCells) {
            var state = world.getBlockState(position);
            if (state.is(fixture.tunnel ? Blocks.DEEPSLATE : Blocks.STONE)) {
                stillStone++;
                continue;
            }
            if (changedStone++ > 0) changedPositions.append(';');
            changedPositions.append(position.getX()).append(',').append(position.getY()).append(',')
                .append(position.getZ()).append('=').append(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }
        int nearbyFurnaces = 0;
        int furnaceInput = 0, furnaceFuel = 0, furnaceOutput = 0;
        boolean allFurnaceFloorsBedrock = true;
        StringBuilder furnacePositions = new StringBuilder();
        for (int x = -2; x <= 2; x++) for (int y = 64; y <= 70; y++) for (int z = -2; z <= 2; z++) {
            if (x * x + z * z > 4) continue;
            BlockPos position = new BlockPos(x, y, z);
            if (!world.getBlockState(position).is(Blocks.FURNACE)) continue;
            if (nearbyFurnaces++ > 0) furnacePositions.append(';');
            furnacePositions.append(x).append(',').append(y).append(',').append(z);
            allFurnaceFloorsBedrock &= world.getBlockState(position.below()).is(Blocks.BEDROCK);
            if (fixture.tunnel || fixture.approach) {
                if (!(world.getBlockEntity(position) instanceof net.minecraft.world.Container inventory)) {
                    throw new IllegalStateException("native station-room furnace has no inventory");
                }
                furnaceInput += inventory.getItem(0).getCount();
                furnaceFuel += inventory.getItem(1).getCount();
                furnaceOutput += inventory.getItem(2).getCount();
            }
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
        result.put("preparedRoomStartPosition", fixture.approach ? "1.5,65,0.5" : fixture.tunnel ? "0.367555,64,0.505802" : "0.5,64,0.5");
        if (fixture.tunnel || fixture.approach) {
            result.put("playerPosition", player.getX() + "," + player.getY() + "," + player.getZ());
            result.put("playerYaw", Float.toString(player.getYRot()));
            result.put("playerPitch", Float.toString(player.getXRot()));
            result.put("stonePickaxeHeld", Boolean.toString(player.getMainHandItem().is(Items.STONE_PICKAXE)));
            result.put("furnaceInputCount", Integer.toString(furnaceInput));
            result.put("furnaceFuelCount", Integer.toString(furnaceFuel));
            result.put("furnaceOutputCount", Integer.toString(furnaceOutput));
        }
        if (fixture.tunnel) {
            int floorCells = 0, airCells = 0;
            StringBuilder airPositions = new StringBuilder();
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                if (world.getBlockState(new BlockPos(x, 63, z)).is(Blocks.BEDROCK)) floorCells++;
            }
            for (int x = -2; x <= 0; x++) for (int y = 64; y <= 66; y++) {
                if (x == -1 && y == 64) continue;
                if (world.getBlockState(new BlockPos(x, y, 0)).is(Blocks.AIR)) {
                    if (airCells++ > 0) airPositions.append(';');
                    airPositions.append(x).append(',').append(y).append(",0");
                }
            }
            result.put("stationRoomSubmode", "tunnel");
            result.put("craftingTablePresent", Boolean.toString(world.getBlockState(new BlockPos(-1, 64, 0)).is(Blocks.CRAFTING_TABLE)));
            result.put("tunnelAirCellCount", Integer.toString(airCells));
            result.put("tunnelAirPositions", airPositions.toString());
            result.put("roomFloorBedrockCellCount", Integer.toString(floorCells));
            result.put("roomFloorBedrock", Boolean.toString(floorCells == 361));
        }
        if (fixture.approach) {
            int shellChanged = 0, airCells = 0, floorCells = 0;
            StringBuilder airPositions = new StringBuilder();
            for (BlockPos position : fixture.bedrockShellCells) {
                if (!world.getBlockState(position).is(Blocks.BEDROCK)) shellChanged++;
            }
            for (BlockPos position : stationApproachAirCells()) {
                if (world.getBlockState(position).is(Blocks.AIR)) {
                    if (airCells++ > 0) airPositions.append(';');
                    airPositions.append(position.getX()).append(',').append(position.getY()).append(',').append(position.getZ());
                }
            }
            for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                if (world.getBlockState(new BlockPos(x, 63, z)).is(Blocks.BEDROCK)) floorCells++;
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
            result.put("approachFloorTablesPresent", Boolean.toString(world.getBlockState(new BlockPos(1, 63, 1)).is(Blocks.CRAFTING_TABLE)
                && world.getBlockState(new BlockPos(0, 63, 1)).is(Blocks.CRAFTING_TABLE)));
            result.put("approachCeilingPresent", Boolean.toString(world.getBlockState(new BlockPos(0, 65, 0)).is(Blocks.BEDROCK)));
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
