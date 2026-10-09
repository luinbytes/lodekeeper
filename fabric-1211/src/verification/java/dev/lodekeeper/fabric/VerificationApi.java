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

    private static final class NativeAnimalFixture {
        final String scenario;
        final java.util.List<net.minecraft.entity.passive.AnimalEntity> animals = new java.util.ArrayList<>();
        final java.util.Map<java.util.UUID, Float> health = new java.util.HashMap<>();
        int effects, submersionTick = -1;
        boolean noScaffold, shearsStaged;
        java.util.List<ItemStack> physicalBaseline = java.util.List.of();
        ItemStack cursorBaseline = ItemStack.EMPTY;
        String physicalBaselineDescription;
        int censusSamples, censusMismatchSamples, censusFirstTick = -1, censusLastTick = -1;
        final java.util.List<String> censusMismatches = new java.util.ArrayList<>();
        NativeAnimalFixture(String scenario) { this.scenario = scenario; }
    }

    static Object seedNativeAnimal(ServerPlayerEntity player, ServerWorld world, String scenario) {
        NativeAnimalFixture fixture = new NativeAnimalFixture(scenario);
        boolean airTransfer = "air_pending_transfer".equals(scenario);
        boolean wool = scenario.contains("_wool") || airTransfer, wrong = "wrong_components".equals(scenario);
        boolean air = "air_pending_attack".equals(scenario) || airTransfer;
        if (wool) player.getInventory().setStack("white_wool_inventory".equals(scenario) || airTransfer ? 20 : 7, new ItemStack(Items.SHEARS));
        if (airTransfer) player.getInventory().setStack(0, new ItemStack(Items.STICK));
        if ("beef_partial".equals(scenario)) player.getInventory().setStack(5, new ItemStack(Items.BEEF, 2));
        if (wrong) {
            ItemStack named = new ItemStack(Items.BEEF, 3);
            named.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, net.minecraft.text.Text.literal("fixture named beef"));
            player.getInventory().setStack(5, named);
        }
        if ("cooking".equals(scenario)) {
            player.getInventory().setStack(5, new ItemStack(Items.FURNACE));
            player.getInventory().setStack(6, new ItemStack(Items.COAL, 2));
            String originalSlot = System.getProperty("lodekeeper.verify.cookingOriginalSlot");
            if (originalSlot != null) {
                if (!java.util.List.of("3", "5").contains(originalSlot))
                    throw new IllegalArgumentException("cooking original slot must be3 or5");
                int selected = Integer.parseInt(originalSlot);
                ClientAccess.selectedSlot(player.getInventory(), selected);
                player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket(selected));
            }
        }
        int count = air ? 1 : wool ? 4 : "protected".equals(scenario) || wrong || "stop_after_interaction".equals(scenario) ? 1 : 12;
        for (int i = 0; i < count; i++) {
            net.minecraft.entity.passive.AnimalEntity animal;
            if (wool) {
                var sheep = new net.minecraft.entity.passive.SheepEntity(net.minecraft.entity.EntityType.SHEEP, world);
                sheep.setColor("red_wool".equals(scenario) ? net.minecraft.util.DyeColor.RED : net.minecraft.util.DyeColor.WHITE);
                sheep.setSheared(false); animal = sheep;
            } else if ("porkchop".equals(scenario) || wrong) {
                animal = new net.minecraft.entity.passive.PigEntity(net.minecraft.entity.EntityType.PIG, world);
            } else if ("mutton".equals(scenario)) {
                animal = new net.minecraft.entity.passive.SheepEntity(net.minecraft.entity.EntityType.SHEEP, world);
            } else animal = new CowEntity(net.minecraft.entity.EntityType.COW, world);
            animal.setBaby(false); animal.setAiDisabled(true);
            if ("air_pending_attack".equals(scenario)) animal.setInvulnerable(true);
            animal.refreshPositionAndAngles(air ? 1.5 : 3.5 + i % 4 * 3, 64, air ? 0.5 : -3.5 + i / 4 * 3, 0, 0);
            if (!world.spawnEntity(animal)) throw new IllegalStateException("native animal fixture spawn failed");
            fixture.animals.add(animal); fixture.health.put(animal.getUuid(), animal.getHealth());
        }
        if (wool && !airTransfer) {
            var other = new net.minecraft.entity.passive.SheepEntity(net.minecraft.entity.EntityType.SHEEP, world);
            other.setColor("red_wool".equals(scenario) ? net.minecraft.util.DyeColor.WHITE : net.minecraft.util.DyeColor.RED);
            other.setAiDisabled(true); other.setBaby(false); other.refreshPositionAndAngles(1.5, 64, 2.5, 0, 0);
            if (!world.spawnEntity(other)) throw new IllegalStateException("wrong-color fixture sheep spawn failed");
            fixture.animals.add(other); fixture.health.put(other.getUuid(), other.getHealth());
        }
        player.getInventory().markDirty();
        return fixture;
    }

    static Object seedNativeAnimalNoScaffold(ServerPlayerEntity player, ServerWorld world, String scenario) {
        if (!"white_wool_inventory".equals(scenario)) throw new IllegalArgumentException("no-scaffold variation requires white_wool_inventory");
        NativeAnimalFixture fixture = (NativeAnimalFixture) seedNativeAnimal(player, world, scenario);
        player.getInventory().setStack(0, new ItemStack(Items.STICK));
        player.getInventory().setStack(30, new ItemStack(Items.COBBLESTONE, 64));
        player.getInventory().markDirty();
        ClientAccess.selectedSlot(player.getInventory(), 0);
        fixture.physicalBaseline = nativeAnimalPhysicalStock(player);
        fixture.physicalBaselineDescription = fixture.physicalBaseline.stream().map(VerificationApi::nativeAnimalStockDescription).toList().toString();
        fixture.cursorBaseline = player.currentScreenHandler.getCursorStack().copy();
        if (!fixture.cursorBaseline.isEmpty()) throw new IllegalStateException("no-scaffold setup requires an empty server cursor");
        fixture.noScaffold = true;
        return fixture;
    }

    private static java.util.List<ItemStack> nativeAnimalPhysicalStock(ServerPlayerEntity player) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>(41);
        for (int slot = 0; slot < 36; slot++) stacks.add(player.getInventory().getStack(slot).copy());
        for (EquipmentSlot slot : java.util.List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) stacks.add(player.getEquippedStack(slot).copy());
        return java.util.List.copyOf(stacks);
    }

    private static String nativeAnimalStockDescription(ItemStack stack) {
        String value = stack + " components=" + stack.getComponents();
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    private static boolean nativeAnimalExactStock(ItemStack expected, ItemStack actual) {
        return expected.isEmpty() && actual.isEmpty() || !expected.isEmpty() && !actual.isEmpty()
                && expected.getCount() == actual.getCount() && ItemStack.areItemsAndComponentsEqual(expected, actual);
    }

    private static boolean nativeAnimalBorrowedShears(NativeAnimalFixture fixture, ItemStack stack, int sheared) {
        if (!stack.isOf(Items.SHEARS) || stack.getCount() != 1 || stack.getDamage() != sheared) return false;
        ItemStack undamaged = stack.copy();
        undamaged.setDamage(fixture.physicalBaseline.get(20).getDamage());
        return nativeAnimalExactStock(fixture.physicalBaseline.get(20), undamaged);
    }

    private static void observeNativeAnimalPhysicalStock(NativeAnimalFixture fixture, ServerPlayerEntity player, int tick, int sheared) {
        java.util.List<ItemStack> actual = nativeAnimalPhysicalStock(player);
        ItemStack cursor = player.currentScreenHandler.getCursorStack().copy();
        int toolCount = 0;
        boolean mismatch = false;
        for (int slot = 0; slot < actual.size(); slot++) {
            ItemStack before = fixture.physicalBaseline.get(slot), now = actual.get(slot);
            boolean toolSlot = slot == 20 || slot == 1;
            boolean tool = toolSlot && nativeAnimalBorrowedShears(fixture, now, sheared);
            if (tool) toolCount += now.getCount();
            boolean allowed = toolSlot ? now.isEmpty() || tool
                    : nativeAnimalExactStock(before, now) || slot < 36 && before.isEmpty()
                        && now.isOf(Items.WHITE_WOOL) && AnimalHarvestAction.ordinary(now);
            if (!allowed) {
                mismatch = true;
                if (fixture.censusMismatches.size() < 8) fixture.censusMismatches.add("tick=" + tick
                        + " physicalSlot=" + slot + " before=" + nativeAnimalStockDescription(before)
                        + " actual=" + nativeAnimalStockDescription(now));
            }
        }
        boolean cursorTool = nativeAnimalBorrowedShears(fixture, cursor, sheared);
        if (cursorTool) toolCount += cursor.getCount();
        if (!(nativeAnimalExactStock(fixture.cursorBaseline, cursor) || cursorTool) || toolCount != 1) {
            mismatch = true;
            if (fixture.censusMismatches.size() < 8) fixture.censusMismatches.add("tick=" + tick
                    + " cursor=" + nativeAnimalStockDescription(cursor) + " physicalShearsCount=" + toolCount + " observedShearWear=" + sheared);
        }
        if (nativeAnimalBorrowedShears(fixture, actual.get(1), sheared) && actual.get(20).isEmpty()) fixture.shearsStaged = true;
        if (fixture.censusSamples++ == 0) fixture.censusFirstTick = tick;
        fixture.censusLastTick = tick;
        if (mismatch) fixture.censusMismatchSamples++;
    }

    static void submergeNativeAnimal(Object handle, ServerPlayerEntity player, ServerWorld world, int tick) {
        NativeAnimalFixture fixture = (NativeAnimalFixture) handle;
        if (!java.util.List.of("air_pending_attack", "air_pending_transfer").contains(fixture.scenario) || fixture.submersionTick >= 0
                || fixture.animals.size() != 1 || fixture.effects != 0 || !fixture.animals.get(0).isAlive()
                || fixture.animals.get(0).getHealth() != fixture.health.get(fixture.animals.get(0).getUuid()))
            throw new IllegalStateException("native animal air fixture must retain its one undamaged target");
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if (Math.abs(x) != 3 && Math.abs(z) != 3) continue;
            for (int y = 64; y <= 65; y++) world.setBlockState(new BlockPos(x, y, z), Blocks.BEDROCK.getDefaultState(), 3);
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            world.setBlockState(new BlockPos(x, 64, z), Blocks.WATER.getDefaultState(), 3);
            world.setBlockState(new BlockPos(x, 65, z), Blocks.WATER.getDefaultState(), 3);
            if (x != 2 || z != 0) world.setBlockState(new BlockPos(x, 66, z), Blocks.BEDROCK.getDefaultState(), 3);
        }
        if (!teleport(player, world, 0.5, 64, 0.5, 0, 0)) throw new IllegalStateException("native animal air fixture teleport failed");
        player.setAir(170);
        fixture.submersionTick = tick;
    }

    static java.util.Map<String, String> nativeAnimalReceipt(Object handle, ServerPlayerEntity player, int tick) {
        NativeAnimalFixture fixture = (NativeAnimalFixture) handle;
        int dead = 0, sheared = 0, wrongColorSheared = 0;
        StringBuilder identities = new StringBuilder();
        for (var animal : fixture.animals) {
            float health = animal.getHealth();
            if (health < fixture.health.get(animal.getUuid())) fixture.effects++;
            fixture.health.put(animal.getUuid(), health);
            if (!animal.isAlive() || health <= 0) dead++;
            if (animal instanceof net.minecraft.entity.passive.SheepEntity sheep && sheep.isSheared()) {
                sheared++;
                if (fixture.scenario.contains("_wool") && !GameApi.sheepWool(sheep).toString().equals("minecraft:" + ("white_wool_inventory".equals(fixture.scenario) ? "white_wool" : fixture.scenario))) wrongColorSheared++;
            }
            identities.append(animal.getUuid()).append('/').append(animal.getType()).append('/').append(health).append(';');
        }
        ItemStack shears = player.getInventory().getStack(java.util.List.of("white_wool_inventory", "air_pending_transfer").contains(fixture.scenario) ? 20 : 7);
        int ordinaryBeef = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isOf(Items.BEEF) && AnimalHarvestAction.ordinary(stack)) ordinaryBeef += stack.getCount();
        }
        java.util.Map<String, String> receipt = new java.util.LinkedHashMap<>(java.util.Map.ofEntries(java.util.Map.entry("serverTick", Integer.toString(tick)),
                java.util.Map.entry("effects", Integer.toString(fixture.effects)), java.util.Map.entry("dead", Integer.toString(dead)),
                java.util.Map.entry("sheared", Integer.toString(sheared)), java.util.Map.entry("wrongColorSheared", Integer.toString(wrongColorSheared)),
                java.util.Map.entry("ordinaryBeef", Integer.toString(ordinaryBeef)),
                java.util.Map.entry("shearsPresent", Boolean.toString(shears.isOf(Items.SHEARS))),
                java.util.Map.entry("shearsOrdinary", Boolean.toString(AnimalHarvestAction.ordinary(shears))),
                java.util.Map.entry("shearsDamage", Integer.toString(shears.isOf(Items.SHEARS) ? shears.getDamage() : -1)),
                java.util.Map.entry("stagedHotbarEmpty", Boolean.toString(player.getInventory().getStack(1).isEmpty())),
                java.util.Map.entry("selectedSlot", Integer.toString(ClientAccess.selectedSlot(player.getInventory()))),
                java.util.Map.entry("cursorEmpty", Boolean.toString(player.currentScreenHandler.getCursorStack().isEmpty())),
                java.util.Map.entry("targets", identities.toString()),
                java.util.Map.entry("submersionTick", Integer.toString(fixture.submersionTick)),
                java.util.Map.entry("airSupply", Integer.toString(player.getAir())),
                java.util.Map.entry("maxAirSupply", Integer.toString(player.getMaxAir())),
                java.util.Map.entry("headInWater", Boolean.toString(player.isSubmergedInWater())),
                java.util.Map.entry("playerX", Double.toString(player.getX())),
                java.util.Map.entry("playerY", Double.toString(player.getY())),
                java.util.Map.entry("playerZ", Double.toString(player.getZ()))));
        if (fixture.noScaffold) {
            observeNativeAnimalPhysicalStock(fixture, player, tick, sheared - wrongColorSheared);
            receipt.put("physicalCensusSlots", "41");
            receipt.put("physicalCensusSlotOrder", "main0..35,HEAD,CHEST,LEGS,FEET,OFFHAND; cursor separate");
            receipt.put("physicalCensusBaseline", fixture.physicalBaselineDescription);
            receipt.put("physicalCensusSamples", Integer.toString(fixture.censusSamples));
            receipt.put("physicalCensusFirstTick", Integer.toString(fixture.censusFirstTick));
            receipt.put("physicalCensusLastTick", Integer.toString(fixture.censusLastTick));
            receipt.put("physicalCensusMismatchSamples", Integer.toString(fixture.censusMismatchSamples));
            receipt.put("physicalCensusMismatches", fixture.censusMismatches.toString());
            receipt.put("physicalCensusShearsStaged", Boolean.toString(fixture.shearsStaged));
            receipt.put("physicalCensusCobblestone30", Integer.toString(player.getInventory().getStack(30).getCount()));
            receipt.put("physicalCensusStick0", Integer.toString(player.getInventory().getStack(0).getCount()));
        }
        return java.util.Map.copyOf(receipt);
    }


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
        if (Boolean.getBoolean("lodekeeper.verify.threatStaircase")) return seedPreparedSafetyStaircase(player, world);
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

    private static int staircaseFeetY(int x) {
        return 64 + Math.max(0, x - 9);
    }

    private static boolean staircaseAirCell(int x, int y, int z) {
        if (x >= 0 && x <= 13 && z >= -1 && z <= 1)
            return y >= staircaseFeetY(x) && y <= staircaseFeetY(x) + 3;
        return (x == -7 && z == 0 || x == 20 && z == 20 || x == 18 && z == 20)
            && y >= 64 && y <= 66;
    }

    private static PreparedSafetyThreatFixture seedPreparedSafetyStaircase(ServerPlayerEntity player, ServerWorld world) {
        player.getServer().getTickManager().setFrozen(true);
        for (int x = -21; x <= 21; x++) for (int z = -21; z <= 21; z++) for (int y = 63; y <= 85; y++)
            world.setBlockState(new BlockPos(x, y, z), (staircaseAirCell(x, y, z) ? Blocks.AIR : Blocks.BEDROCK).getDefaultState(), 3);
        if (!player.getInventory().insertStack(new ItemStack(Items.DIAMOND_SWORD))
                || !player.getInventory().insertStack(new ItemStack(Items.WOODEN_PICKAXE))
                || !player.getInventory().insertStack(new ItemStack(Items.IRON_INGOT, 3))
                || !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE)))
            throw new IllegalStateException("could not seed staircase bucket materials and weapons");
        ClientAccess.selectedSlot(player.getInventory(), 0);
        ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
        zombie.setPos(20.5, 64.0, 20.5);
        zombie.setAiDisabled(true);
        zombie.setHealth(4.0F);
        CowEntity cow = new CowEntity(EntityType.COW, world);
        cow.setPos(18.5, 64.0, 20.5);
        cow.setAiDisabled(true);
        CreeperEntity creeper = new CreeperEntity(EntityType.CREEPER, world);
        creeper.setPos(-6.5, 64.0, 0.5);
        creeper.setAiDisabled(true);
        creeper.setHealth(20.0F);
        if (!world.spawnEntity(zombie) || !world.spawnEntity(cow) || !world.spawnEntity(creeper))
            throw new IllegalStateException("could not spawn staircase threat and protected controls");
        PreparedSafetyThreatFixture fixture = new PreparedSafetyThreatFixture(zombie, cow, cow.getHealth(), creeper);
        fixture.staircase = new StaircaseObservation(player, world);
        fixture.staircase.initialMined = staircaseBlocksMined(player);
        fixture.staircase.initialBlockItemUses = staircaseBlockItemUses(player);
        return fixture;
    }

    private static int staircaseBlocksMined(ServerPlayerEntity player) {
        int total = 0;
        for (var block : Registries.BLOCK)
            total += player.getStatHandler().getStat(net.minecraft.stat.Stats.MINED.getOrCreateStat(block));
        return total;
    }

    private static int staircaseBlockItemUses(ServerPlayerEntity player) {
        int total = 0;
        for (var item : Registries.ITEM) if (item instanceof BlockItem)
            total += player.getStatHandler().getStat(net.minecraft.stat.Stats.USED.getOrCreateStat(item));
        return total;
    }

    private static void appendStaircaseReceipt(Map<String, String> result, ServerPlayerEntity player, PreparedSafetyThreatFixture fixture) {
        StaircaseObservation observation = fixture.staircase;
        if (observation == null) return;
        int changed = 0;
        for (int x = -21; x <= 21; x++) for (int z = -21; z <= 21; z++) for (int y = 63; y <= 85; y++)
            if (!player.getServerWorld().getBlockState(new BlockPos(x, y, z)).isOf(staircaseAirCell(x, y, z) ? Blocks.AIR : Blocks.BEDROCK)) changed++;
        BlockPos feet = player.getBlockPos();
        boolean supported = feet.getX() == 13 && feet.getY() == 68 && feet.getZ() >= -1 && feet.getZ() <= 1
            && player.isOnGround() && Math.abs(player.getY() - 68.0) <= 0.0625 && !player.isTouchingWater()
            && player.getServerWorld().getBlockState(feet.down()).isOf(Blocks.BEDROCK)
            && player.getServerWorld().getBlockState(feet).isAir() && player.getServerWorld().getBlockState(feet.up()).isAir();
        result.put("staircaseBounds", "-21..21,63..85,-21..21");
        result.put("staircaseCells", "42527");
        result.put("staircaseSelectedSlot", Integer.toString(ClientAccess.selectedSlot(player.getInventory())));
        result.put("staircaseChangedCells", Integer.toString(changed));
        result.put("staircaseSupportedEndpoint", Boolean.toString(supported));
        result.put("staircaseHeightMask", Integer.toString(observation.heightMask));
        result.put("staircaseHeightFirstServerTicks", java.util.Arrays.toString(observation.heightTicks));
        result.put("staircaseReleaseServerTick", Integer.toString(observation.releaseServerTick));
        result.put("staircasePauseFenceServerTick", Integer.toString(observation.pauseFenceServerTick));
        result.put("staircaseMarkerFailure", observation.markerFailure);
        result.put("staircaseObservedServerTicks", Integer.toString(observation.observedTicks));
        result.put("staircaseMinimumPlayerHealth", Float.toString(observation.minimumPlayerHealth));
        result.put("staircasePlayerAlive", Boolean.toString(player.isAlive()));
        result.put("staircasePlayerDeaths", Integer.toString(player.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS))));
        result.put("staircaseBlocksMined", Integer.toString(staircaseBlocksMined(player) - observation.initialMined));
        result.put("staircaseBlockItemUses", Integer.toString(staircaseBlockItemUses(player) - observation.initialBlockItemUses));
        result.put("staircaseClockFrozen", Boolean.toString(player.getServer().getTickManager().isFrozen()));
        result.put("staircaseCreeperAiEnabled", Boolean.toString(!fixture.creeper.isAiDisabled()));
        result.put("staircaseCreeperPosition", fixture.creeper.getX() + "," + fixture.creeper.getY() + "," + fixture.creeper.getZ());
    }

    private static final class StaircaseObservation {
        final ServerPlayerEntity owner;
        final Object connection;
        final ServerWorld world;
        int releaseServerTick = -1, observedTicks, heightMask, initialMined, initialBlockItemUses;
        int pauseFenceServerTick = -1;
        String markerFailure = "";
        final int[] heightTicks = {-1, -1, -1, -1, -1};
        float minimumPlayerHealth = 20.0F;
        StaircaseObservation(ServerPlayerEntity owner, ServerWorld world) {
            this.owner = owner;
            this.connection = owner.networkHandler;
            this.world = world;
        }
    }

    private record StaircaseMarker(java.util.UUID session, java.util.UUID fixture, int stage)
            implements net.minecraft.network.packet.CustomPayload {
        private static final Id<StaircaseMarker> ID = new Id<>(Identifier.of("lodekeeper-verification", "staircase_fence"));
        private static final net.minecraft.network.codec.PacketCodec<net.minecraft.network.RegistryByteBuf, StaircaseMarker> CODEC = new net.minecraft.network.codec.PacketCodec<>() {
            @Override public StaircaseMarker decode(net.minecraft.network.RegistryByteBuf buffer) {
                return new StaircaseMarker(buffer.readUuid(), buffer.readUuid(), buffer.readInt());
            }
            @Override public void encode(net.minecraft.network.RegistryByteBuf buffer, StaircaseMarker marker) {
                buffer.writeUuid(marker.session()); buffer.writeUuid(marker.fixture()); buffer.writeInt(marker.stage());
            }
        };
        @Override public Id<? extends net.minecraft.network.packet.CustomPayload> getId() { return ID; }
    }

    static java.util.function.IntConsumer registerStaircaseNetworking(
            java.util.function.Supplier<PreparedSafetyThreatFixture> currentFixture,
            java.util.function.Supplier<java.util.UUID> originalPlayerId) {
        java.util.UUID session = java.util.UUID.randomUUID();
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(StaircaseMarker.ID, StaircaseMarker.CODEC);
        boolean registered = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(StaircaseMarker.ID, (marker, context) -> {
            PreparedSafetyThreatFixture fixture = currentFixture.get();
            if (fixture == null || fixture.staircase == null) return;
            StaircaseObservation observation = fixture.staircase;
            var player = context.player();
            if (!session.equals(marker.session()) || !fixture.creeper.getUuid().equals(marker.fixture())
                    || !player.getUuid().equals(originalPlayerId.get()) || observation.owner != player
                    || observation.connection != player.networkHandler || observation.world != player.getServerWorld()) {
                observation.markerFailure = "staircase marker original session, fixture, player, connection or world changed";
                return;
            }
            if (!observation.markerFailure.isEmpty()) return;
            if (marker.stage() == 0 && observation.releaseServerTick < 0
                    && player.getServerWorld().getServer().getTickManager().isFrozen()) {
                releasePreparedSafetyThreatClock(fixture, player);
            } else if (marker.stage() == 1 && observation.releaseServerTick >= 0
                    && observation.pauseFenceServerTick < 0 && !player.getServerWorld().getServer().getTickManager().isFrozen()) {
                observation.pauseFenceServerTick = player.getServerWorld().getServer().getTicks();
            } else observation.markerFailure = "staircase marker was repeated or out of order";
        });
        if (!registered) throw new IllegalStateException("staircase marker receiver already registered");
        return new java.util.function.IntConsumer() {
            private int nextStage;
            private Object player, connection, world, fixtureIdentity;
            @Override public void accept(int stage) {
                var client = net.minecraft.client.MinecraftClient.getInstance();
                PreparedSafetyThreatFixture fixture = currentFixture.get();
                if (stage != nextStage || stage > 1 || fixture == null || fixture.staircase == null
                        || client.player == null || client.world == null || client.getNetworkHandler() == null
                        || !client.player.getUuid().equals(originalPlayerId.get())
                        || !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(StaircaseMarker.ID))
                    throw new IllegalStateException("staircase marker unavailable or out of order");
                if (stage == 0) {
                    player = client.player; connection = client.getNetworkHandler(); world = client.world; fixtureIdentity = fixture;
                }
                if (player != client.player || connection != client.getNetworkHandler() || world != client.world || fixtureIdentity != fixture)
                    throw new IllegalStateException("staircase original client context changed");
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new StaircaseMarker(session, fixture.creeper.getUuid(), stage));
                nextStage++;
            }
        };
    }

    static PreparedSafetyThreatFixture seedPreparedSafetyContactFixture(ServerPlayerEntity player, ServerWorld world) {
        player.getServer().getTickManager().setFrozen(true);
        boolean lowHealthMode = "true".equals(System.getProperty("lodekeeper.verify.threatContactLowHealth"));
        int passageTopY = lowHealthMode ? 66 : 65;
        int shellTopY = lowHealthMode ? 76 : 68;
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = 63; y <= shellTopY; y++) {
            boolean passage = x >= 0 && x <= 6 && z >= 0 && z <= 1 && y >= 64 && y <= passageTopY;
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
        fixture.contact = new ContactThreatObservation(second, passageTopY, shellTopY);
        if (lowHealthMode)
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
        if (fixture.staircase != null) {
            fixture.staircase.releaseServerTick = player.getServer().getTicks();
        } else if (fixture.contact != null) {
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
        if (fixture.staircase != null && fixture.staircase.releaseServerTick >= 0) {
            StaircaseObservation observation = fixture.staircase;
            observation.observedTicks++;
            observation.minimumPlayerHealth = Math.min(observation.minimumPlayerHealth, player.getHealth());
            int height = (int) Math.floor(player.getY()) - 64;
            if (height >= 0 && height <= 4 && (observation.heightMask & 1 << height) == 0) {
                observation.heightMask |= 1 << height;
                observation.heightTicks[height] = serverTick;
            }
        }
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
        private final int passageTopY, shellTopY;
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

        private ContactThreatObservation(ZombieEntity second, int passageTopY, int shellTopY) {
            this.second = second;
            this.passageTopY = passageTopY;
            this.shellTopY = shellTopY;
        }
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
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = 63; y <= contact.shellTopY; y++) {
            if (x >= 0 && x <= 6 && z >= 0 && z <= 1 && y >= 64 && y <= contact.passageTopY) continue;
            shellCells++;
            if (!world.getBlockState(new BlockPos(x, y, z)).isOf(Blocks.BEDROCK)) changed++;
        }
        ItemStack pickaxe = player.getInventory().getStack(1);
        result.put("ironPickaxeDamage", Integer.toString(pickaxe.isOf(Items.IRON_PICKAXE) ? pickaxe.getDamage() : -1));
        result.put("contactShellCells", Integer.toString(shellCells));
        result.put("contactShellBounds", "-14..14,63.." + contact.shellTopY + ",-14..14");
        result.put("contactShellChangedCells", Integer.toString(changed));
        result.put("contactPassageBounds", "0..6,64.." + contact.passageTopY + ",0..1");
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
            } else if (fixture.staircase == null) {
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
        appendStaircaseReceipt(result, player, fixture);
        return Map.copyOf(result);
    }

    static final class PreparedSafetyThreatFixture {
        private StaircaseObservation staircase;
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
        var fixture = new PreparedSafetyStationRoomFixture(player, world, nearbyStoneCells, tunnel, approach);
        if (!tunnel && !approach) collectPreparedSafetyStationRoomReceipt(player, world, fixture, true);
        return fixture;
    }

    static Map<String, String> preparedSafetyStationRoomReceipt(ServerPlayerEntity player, ServerWorld world,
                                                                  PreparedSafetyStationRoomFixture fixture) {
        return collectPreparedSafetyStationRoomReceipt(player, world, fixture, false);
    }

    private static Map<String, String> collectPreparedSafetyStationRoomReceipt(ServerPlayerEntity player, ServerWorld world,
                                                                            PreparedSafetyStationRoomFixture fixture, boolean advanceHistory) {
        if (!fixture.tunnel && !fixture.approach) fixture.requireContext(player, world);
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
        BlockPos firstFurnacePosition = null;
        for (int x = -2; x <= 2; x++) for (int y = 64; y <= 70; y++) for (int z = -2; z <= 2; z++) {
            if (x * x + z * z > 4) continue;
            BlockPos position = new BlockPos(x, y, z);
            if (!world.getBlockState(position).isOf(Blocks.FURNACE)) continue;
            if (firstFurnacePosition == null) firstFurnacePosition = position;
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
        if (!fixture.tunnel && !fixture.approach) {
            if (advanceHistory) {
                Map<String, String> history = new LinkedHashMap<>();
                fixture.observeHistory(player, world, nearbyFurnaces, firstFurnacePosition, allFurnaceFloorsBedrock, history);
                fixture.publishedHistory = Map.copyOf(history);
            }
            result.putAll(fixture.publishedHistory);
            result.put("stationCurrentOrdinaryFurnaceCount", Integer.toString(fixture.ordinaryFurnaceCount(player)));
        }
        return Map.copyOf(result);
    }

    private static BlockPos[] stationApproachAirCells() {
        return new BlockPos[]{new BlockPos(0, 64, 0), new BlockPos(1, 65, 0), new BlockPos(1, 66, 0),
            new BlockPos(1, 64, 1), new BlockPos(1, 65, 1), new BlockPos(1, 66, 1), new BlockPos(0, 64, 1), new BlockPos(0, 65, 1)};
    }

    static final class PreparedSafetyStationRoomFixture implements Runnable {
        private final BlockPos[] nearbyStoneCells;
        private final boolean tunnel;
        private final boolean approach;
        private final BlockPos[] bedrockShellCells;
        private final ServerPlayerEntity capturedPlayer;
        private final ServerWorld capturedWorld;
        private final Object capturedServer;
        private final Thread capturedThread;
        private final int seededAtTick;
        private int lastObservedTick;
        private BlockPos placedPosition;
        private int debitTick = -1, placementTick = -1, removalTick = -1, returnTick = -1;
        private boolean placedOnBedrock;
        private boolean historyValid = true;
        private int endTickObservations;
        private int lastEndTick = -1;
        private Map<String, String> publishedHistory = Map.of();

        private PreparedSafetyStationRoomFixture(ServerPlayerEntity player, ServerWorld world,
                                                 BlockPos[] nearbyStoneCells, boolean tunnel, boolean approach) {
            this.capturedPlayer = player;
            this.capturedWorld = world;
            this.capturedServer = world.getServer();
            this.capturedThread = Thread.currentThread();
            this.seededAtTick = world.getServer().getTicks();
            this.lastObservedTick = seededAtTick;
            if (!tunnel && !approach && ordinaryFurnaceCount(player) != 1) {
                throw new IllegalStateException("station-room history requires exactly one seeded ordinary furnace");
            }
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

        private int ordinaryFurnaceCount(ServerPlayerEntity player) {
            int count = 0;
            ItemStack ordinary = new ItemStack(Items.FURNACE);
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = player.getInventory().getStack(slot);
                if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(ordinary, stack)) count += stack.getCount();
            }
            return count;
        }

        private void requireContext(ServerPlayerEntity player, ServerWorld world) {
            if (Thread.currentThread() != capturedThread || player != capturedPlayer || world != capturedWorld
                    || world.getServer() != capturedServer || player.getWorld() != world
                    || world.getServer().getPlayerManager().getPlayer(player.getUuid()) != player) {
                throw new IllegalStateException("station-room history lost its exact native server context");
            }
        }

        @Override public void run() {
            requireContext(capturedPlayer, capturedWorld);
            int tick = capturedWorld.getServer().getTicks();
            if (lastEndTick < 0 ? tick < seededAtTick || tick > seededAtTick + 1 : tick != lastEndTick + 1) historyValid = false;
            lastEndTick = tick;
            endTickObservations++;
            collectPreparedSafetyStationRoomReceipt(capturedPlayer, capturedWorld, this, true);
        }

        private void observeHistory(ServerPlayerEntity player, ServerWorld world, int furnaces,
                                    BlockPos position, boolean floorsBedrock, Map<String, String> result) {
            requireContext(player, world);
            int tick = world.getServer().getTicks();
            int stock = ordinaryFurnaceCount(player);
            if (tick < lastObservedTick || stock < 0 || stock > 1 || furnaces > 1 || !world.getBlockState(new BlockPos(0, 63, 0)).isOf(Blocks.BEDROCK)) historyValid = false;
            lastObservedTick = tick;
            if (debitTick >= 0 && placementTick < 0 && stock != 0) historyValid = false;
            if (debitTick < 0 && stock == 0) debitTick = tick;
            if (placementTick < 0 && furnaces == 1) {
                placedPosition = position;
                placementTick = tick;
                placedOnBedrock = floorsBedrock;
                if (stock != 0 || debitTick < seededAtTick || debitTick > placementTick || !placedOnBedrock) historyValid = false;
            }
            if (placementTick >= 0) {
                if (!world.getBlockState(placedPosition.down()).isOf(Blocks.BEDROCK)) historyValid = false;
                if (removalTick < 0) {
                    if (furnaces == 0 && world.getBlockState(placedPosition).isAir()) {
                        removalTick = tick;
                        if (removalTick <= placementTick) historyValid = false;
                    } else if (furnaces != 1 || !placedPosition.equals(position) || stock != 0) {
                        historyValid = false;
                    }
                } else if (furnaces != 0 || !world.getBlockState(placedPosition).isAir()) {
                    historyValid = false;
                }
                if (removalTick >= 0 && returnTick < 0 && stock == 1) returnTick = tick;
                if (returnTick >= 0 && stock != 1) historyValid = false;
            }
            result.put("stationHistoryEndTickObservations", Integer.toString(endTickObservations));
            result.put("stationHistoryLastEndServerTick", Integer.toString(lastEndTick));
            result.put("stationHistoryValid", Boolean.toString(historyValid));
            result.put("stationSeededAtServerTick", Integer.toString(seededAtTick));
            result.put("stationObservedAtServerTick", Integer.toString(tick));
            result.put("stationFurnaceDebitServerTick", Integer.toString(debitTick));
            result.put("stationPlacedServerTick", Integer.toString(placementTick));
            result.put("stationRemovedServerTick", Integer.toString(removalTick));
            result.put("stationReturnedServerTick", Integer.toString(returnTick));
            result.put("stationPlacedPosition", placedPosition == null ? "" : placedPosition.getX() + "," + placedPosition.getY() + "," + placedPosition.getZ());
            result.put("stationPlacedOnBedrock", Boolean.toString(placedOnBedrock));
            result.put("stationOrdinaryFurnaceCount", Integer.toString(stock));
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
