package dev.lodekeeper.fabric.modern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Optional dev-only end-to-end verifier. It is inert unless explicitly enabled with a JVM flag. */
public final class RuntimeVerification implements ClientModInitializer {
    private static final String ENABLE_PROPERTY = "lodekeeper.verify";
    private static final int MAX_RUN_TICKS = 6_000;
    private static final int OBSERVE_EVERY_TICKS = 20;
    private static final int FLOOR_Y = 63;
    private static final int PLAYER_Y = FLOOR_Y + 1;

    private enum State {
        DISABLED, OPENING_WORLD, WAITING_FOR_WORLD, SETTING_UP, WAITING_FOR_EMPTY_SNAPSHOT,
        GATHERING_WOOD, CRAFTING_TABLE, CRAFTING_STICKS, CRAFTING_WOOD_PICK, CRAFTING_STONE_PICK,
        CRAFTING_FURNACE, SMELTING_IRON, CUSTOM_CONTENT, SETTING_UP_FOOD, WAITING_FOR_FOOD_FIXTURE,
        GATHERING_FOOD, CAPTURING, COMPLETE, FAILED
    }

    private Minecraft client;
    private State state = State.DISABLED;
    private Path verificationRoot;
    private Path evidenceDirectory;
    private Path worldsDirectory;
    private Path backupsDirectory;
    private LevelStorageSource storage;
    private UUID playerId;
    private String runId;
    private String worldId;
    private long startedAtNanos;
    private int clientTicks;
    private int caseStartedAtTick;
    private long caseStartedAtWorldTime;
    private long fixtureReadyServerTick;
    private int readyTicks;
    private int captureStartedAtTick;
    private boolean worldLaunchQueued;
    private boolean worldCreationStarted;
    private CompletableFuture<Long> setupFuture;
    private CompletableFuture<ServerSnapshot> observationFuture;
    private ServerSnapshot latestSnapshot;
    private final List<CaseResult> results = new ArrayList<>();
    private String activeCase;
    private String activeItem;
    private int activeCount;
    private boolean activeRequiresEmpty;
    private boolean activeStartedEmpty;
    private int activeFoodLevelAtStart;
    private int activeBreadCountAtStart;
    private int activeTableOpeningsAtStart;
    private int foodBreadCountBeforeSetup;
    private String failure = "";
    private volatile boolean serverTableOpened;
    private volatile boolean serverFurnaceOpened;
    private volatile int serverTableOpenings;
    private AbstractContainerMenu lastServerMenu;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        client = Minecraft.getInstance();
        startedAtNanos = System.nanoTime();
        try {
            prepareIsolatedPaths();
            VerificationContentInitializer.ensureSourceContract(client.gameDirectory.toPath());
        } catch (Exception exception) {
            state = State.FAILED;
            failure = "refusing to start verifier: " + exception;
            writeEvidence("failed");
            System.err.println("[Lodekeeper verification] " + failure);
            client.stop();
            return;
        }
        state = State.OPENING_WORLD;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ServerTickEvents.END_SERVER_TICK.register(this::observeServerMenu);
        System.out.println("[Lodekeeper verification] Enabled; isolated run directory=" + client.gameDirectory
            + ", world root=" + worldsDirectory + ", evidence=" + evidenceDirectory);
    }

    private void prepareIsolatedPaths() throws IOException {
        Path runDirectory = client.gameDirectory.toPath().toRealPath();
        Path verificationBase = runDirectory.resolve("verification");
        createContainedDirectory(runDirectory, verificationBase);
        Path actualBase = verificationBase.toRealPath();
        Path ordinarySaves = runDirectory.resolve("saves");
        Path actualSaves = Files.exists(ordinarySaves, LinkOption.NOFOLLOW_LINKS)
            ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
        if (overlaps(actualBase, actualSaves)) throw new IOException("verification output overlaps ordinary saves");

        runId = Instant.now().toString().replace(':', '-').replace('.', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
        worldId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        verificationRoot = actualBase.resolve(runId);
        createContainedDirectory(actualBase, verificationRoot);
        evidenceDirectory = verificationRoot.resolve("evidence");
        worldsDirectory = verificationRoot.resolve("worlds");
        backupsDirectory = verificationRoot.resolve("backups");
        createContainedDirectory(verificationRoot, evidenceDirectory);
        createContainedDirectory(verificationRoot, worldsDirectory);
        createContainedDirectory(verificationRoot, backupsDirectory);
        Path actualRoot = verificationRoot.toRealPath();
        evidenceDirectory = evidenceDirectory.toRealPath();
        worldsDirectory = worldsDirectory.toRealPath();
        backupsDirectory = backupsDirectory.toRealPath();
        if (!evidenceDirectory.startsWith(actualRoot) || !worldsDirectory.startsWith(actualRoot)
            || !backupsDirectory.startsWith(actualRoot) || overlaps(worldsDirectory, actualSaves)) {
            throw new IOException("verification paths escaped or overlap ordinary saves");
        }

        storage = new LevelStorageSource(worldsDirectory, backupsDirectory, client.directoryValidator(), client.getFixerUpper());
    }

    private static void createContainedDirectory(Path root, Path child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        if (!normalizedChild.startsWith(normalizedRoot) || normalizedChild.equals(normalizedRoot)) {
            throw new IOException("refusing a verifier path outside its root: " + normalizedChild);
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedChild)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("refusing linked or non-directory verifier path: " + current);
            }
        }
        Files.createDirectories(normalizedChild);
        if (!normalizedChild.toRealPath().startsWith(normalizedRoot.toRealPath())) {
            throw new IOException("verifier path resolves outside its root: " + normalizedChild);
        }
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private void tick(Minecraft currentClient) {
        if (state == State.COMPLETE || state == State.FAILED || state == State.DISABLED) return;
        clientTicks++;
        if (clientTicks > MAX_RUN_TICKS || System.nanoTime() - startedAtNanos > 300_000_000_000L) {
            fail("verification exceeded the five-minute limit");
            return;
        }
        try {
            if (state == State.OPENING_WORLD) {
                if (client.level != null) throw new IllegalStateException("start from the title screen; an existing world is active");
                if (!worldLaunchQueued && GameApi.screen(client) != null) queueWorldLaunch();
                return;
            }
            if (state == State.WAITING_FOR_WORLD) {
                if (client.level != null && client.player != null && GameApi.screen(client) == null) {
                    if (++readyTicks < 40) return;
                    playerId = client.player.getUUID();
                    configureAutomation();
                    beginFixtureSetup();
                }
                return;
            }
            if (state == State.SETTING_UP) {
                if (setupFuture != null && setupFuture.isDone()) {
                    fixtureReadyServerTick = setupFuture.join();
                    setupFuture = null;
                    requireEngine().terrain.changed();
                    state = State.WAITING_FOR_EMPTY_SNAPSHOT;
                    readyTicks = 0;
                    requestObservation();
                }
                return;
            }
            if (state == State.SETTING_UP_FOOD) {
                if (setupFuture != null && setupFuture.isDone()) {
                    fixtureReadyServerTick = setupFuture.join();
                    setupFuture = null;
                    requireEngine().terrain.changed();
                    state = State.WAITING_FOR_FOOD_FIXTURE;
                    readyTicks = 0;
                    requestObservation();
                }
                return;
            }
            if (state == State.WAITING_FOR_FOOD_FIXTURE) {
                if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick
                    && latestSnapshot.foodLevel == 7
                    && latestSnapshot.difficulty.equals(Difficulty.NORMAL.name())
                    && latestSnapshot.count(VerificationContentInitializer.BREAD_ID) >= foodBreadCountBeforeSetup + 1) {
                    if (++readyTicks >= 20) startFoodGatherCommand();
                } else {
                    readyTicks = 0;
                }
                return;
            }
            if (state == State.WAITING_FOR_EMPTY_SNAPSHOT) {
                if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick && latestSnapshot.inventoryEmpty()) {
                    readyTicks++;
                    if (readyTicks >= 20 && client.player.getY() > FLOOR_Y
                        && client.level.getBlockState(new BlockPos(6, PLAYER_Y, 0)).is(Blocks.OAK_LOG)) {
                        startGatherCommand();
                    }
                }
                return;
            }
            if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
            evaluateCurrentCase();
            if (state == State.CAPTURING && clientTicks - captureStartedAtTick >= 20) finishRun();
        } catch (Exception exception) {
            fail(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private void queueWorldLaunch() {
        worldLaunchQueued = true;
        state = State.WAITING_FOR_WORLD;
        // Dispatch from another thread so the supported client world-opening API is not nested in END_CLIENT_TICK.
        Util.backgroundExecutor().execute(() -> client.execute(() -> {
            if (state != State.WAITING_FOR_WORLD || worldCreationStarted) return;
            worldCreationStarted = true;
            try {
                VerificationApi.startFlatWorld(client, worldId, storage,
                    () -> System.out.println("[Lodekeeper verification] Vanilla integrated-world startup requested"),
                    throwable -> fail("isolated world creation failed: " + throwable));
            } catch (Throwable throwable) {
                fail("could not start isolated world: " + throwable);
            }
        }));
    }

    private void configureAutomation() {
        AutomationEngine engine = requireEngine();
        engine.stop();
        engine.config.prefix = "!lk ";
        engine.config.searchRadius = 48;
        engine.config.scanBlocksPerTick = 512;
        engine.config.pathNodesPerTick = 128;
        engine.config.pathNodeLimit = 16_000;
        engine.config.pathMillisPerTick = 2;
        engine.config.actionTimeoutTicks = 1_200;
        engine.config.pauseBelowHealth = 6.0F;
        engine.config.allowBreaking = true;
        engine.config.allowBuilding = true;
        engine.config.allowParkour = false;
        engine.config.autoEat = true;
    }

    private void beginFixtureSetup() {
        state = State.SETTING_UP;
        MinecraftServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayer player = requireServerPlayer(server);
                ServerLevel world = server.overworld();
                for (int x = -12; x <= 18; x++) {
                    for (int z = -6; z <= 6; z++) world.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z), Blocks.BEDROCK.defaultBlockState());
                }
                for (int index = 0; index < 8; index++) {
                    world.setBlockAndUpdate(new BlockPos(6 + index, PLAYER_Y, 0), Blocks.OAK_LOG.defaultBlockState());
                }
                for (int x = 6; x <= 17; x++) world.setBlockAndUpdate(new BlockPos(x, PLAYER_Y, 2), Blocks.STONE.defaultBlockState());
                world.setBlockAndUpdate(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.defaultBlockState());
                world.setBlockAndUpdate(new BlockPos(17, PLAYER_Y, 4), Blocks.IRON_ORE.defaultBlockState());
                Block rubyOre = BuiltInRegistries.BLOCK.getValue(Identifier.parse(VerificationContentInitializer.RUBY_ORE_ID));
                if (rubyOre == Blocks.AIR) throw new IllegalStateException("verifier ruby ore was not registered");
                for (int index = 0; index < 4; index++) {
                    world.setBlockAndUpdate(new BlockPos(8 + index, PLAYER_Y, 4), rubyOre.defaultBlockState());
                }
                long coalTicks = GameApi.fuelTicks(world, new ItemStack(Items.COAL));
                long plankTicks = GameApi.fuelTicks(world, new ItemStack(Items.OAK_PLANKS));
                if (coalTicks != 1600 || plankTicks != 300)
                    throw new IllegalStateException("standard furnace fuel snapshot differs: coal=" + coalTicks + ", oak_planks=" + plankTicks);
                clearInventory(player);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.teleportTo(0.5, PLAYER_Y, 0.5);
                player.containerMenu.broadcastChanges();
                scheduled.complete((long) server.getTickCount());
            } catch (Throwable throwable) {
                scheduled.completeExceptionally(throwable);
            }
        });
    }

    private static void clearInventory(ServerPlayer player) {
        player.getInventory().clearContent();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            player.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    private void observeServerMenu(MinecraftServer server) {
        if (playerId == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != lastServerMenu) {
            if (menu instanceof CraftingMenu) {
                serverTableOpened = true;
                serverTableOpenings++;
            }
            if (menu instanceof AbstractFurnaceMenu) serverFurnaceOpened = true;
            lastServerMenu = menu;
        }
    }

    private void startGatherCommand() {
        activeCase = "gather_wood_8";
        activeItem = "minecraft:oak_log";
        activeCount = 8;
        activeRequiresEmpty = true;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get wood 8");
        state = State.GATHERING_WOOD;
    }

    private void evaluateCurrentCase() {
        if (activeCase == null || state == State.CAPTURING || latestSnapshot == null) return;
        int observed = latestSnapshot.count(activeItem);
        boolean targetReached = observed >= activeCount && requireEngine().status().startsWith("idle")
            && (state != State.CRAFTING_WOOD_PICK || serverTableOpened)
            && (state != State.SMELTING_IRON || serverFurnaceOpened)
            && (state != State.CUSTOM_CONTENT || serverTableOpenings > activeTableOpeningsAtStart);
        if (targetReached && state == State.GATHERING_FOOD
            && (latestSnapshot.foodLevel <= activeFoodLevelAtStart
                || latestSnapshot.count(VerificationContentInitializer.BREAD_ID) >= activeBreadCountAtStart)) {
            fail("food-use goal completed without server-confirmed bread consumption and hunger recovery");
        } else if (targetReached) {
            String detail = switch (state) {
                case CUSTOM_CONTENT -> "server inventory reached the custom recipe output after opening the server crafting table";
                case GATHERING_FOOD -> "server inventory reached the log target; bread was consumed and hunger rose from "
                    + activeFoodLevelAtStart + " to " + latestSnapshot.foodLevel;
                default -> "server inventory reached target and the engine returned idle";
            };
            addResult(true, observed, detail);
            if (state == State.GATHERING_WOOD) startCraftingTableCommand();
            else if (state == State.CRAFTING_TABLE) startCraftingSticksCommand();
            else if (state == State.CRAFTING_STICKS) startAdditionalCase(State.CRAFTING_WOOD_PICK, "craft_wooden_pickaxe", "minecraft:wooden_pickaxe");
            else if (state == State.CRAFTING_WOOD_PICK) startAdditionalCase(State.CRAFTING_STONE_PICK, "craft_stone_pickaxe", "minecraft:stone_pickaxe");
            else if (state == State.CRAFTING_STONE_PICK) startAdditionalCase(State.CRAFTING_FURNACE, "craft_furnace", "minecraft:furnace");
            else if (state == State.CRAFTING_FURNACE) startAdditionalCase(State.SMELTING_IRON, "smelt_iron_ingot", "minecraft:iron_ingot");
            else if (state == State.SMELTING_IRON) startCustomContentCase();
            else if (state == State.CUSTOM_CONTENT) beginFoodFixtureSetup();
            else {
                state = State.CAPTURING;
                captureStartedAtTick = clientTicks;
            }
        } else if (requireEngine().status().startsWith("paused")) {
            fail("automation paused during " + activeCase + ": " + requireEngine().status());
        } else if (clientTicks - caseStartedAtTick > MAX_RUN_TICKS) {
            fail("case timed out: " + activeCase + "; engine status=" + requireEngine().status());
        }
    }

    private void startCraftingTableCommand() {
        activeCase = "craft_crafting_table";
        activeItem = "minecraft:crafting_table";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get crafting_table 1");
        state = State.CRAFTING_TABLE;
    }

    private void startCraftingSticksCommand() {
        activeCase = "craft_sticks_8";
        activeItem = "minecraft:stick";
        activeCount = 8;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get stick 8");
        state = State.CRAFTING_STICKS;
    }

    private void startAdditionalCase(State next, String name, String item) {
        activeCase = name;
        activeItem = item;
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get " + item + " 1");
        state = next;
    }

    private void startCustomContentCase() {
        activeCase = "custom_ruby_gear";
        activeItem = VerificationContentInitializer.RUBY_GEAR_ID;
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get " + activeItem + " 1");
        state = State.CUSTOM_CONTENT;
    }

    private void beginFoodFixtureSetup() {
        activeCase = "auto_eat_during_gather";
        activeItem = "minecraft:oak_log";
        activeCount = latestSnapshot.count(activeItem) + 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        foodBreadCountBeforeSetup = latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        beginCaseClock();
        state = State.SETTING_UP_FOOD;

        MinecraftServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayer player = requireServerPlayer(server);
                ServerLevel world = server.overworld();
                server.setDifficulty(Difficulty.NORMAL, true);
                player.getFoodData().setFoodLevel(7);
                player.getFoodData().setSaturation(0.0F);
                if (!player.getInventory().add(new ItemStack(Items.BREAD))) {
                    throw new IllegalStateException("could not add verifier bread to the inventory");
                }
                BlockPos extraLog = new BlockPos(6, PLAYER_Y, 0);
                if (!world.getBlockState(extraLog).isAir()) throw new IllegalStateException("food fixture log position is occupied");
                world.setBlockAndUpdate(extraLog, Blocks.OAK_LOG.defaultBlockState());
                player.containerMenu.broadcastChanges();
                scheduled.complete((long) server.getTickCount());
            } catch (Throwable throwable) {
                scheduled.completeExceptionally(throwable);
            }
        });
    }

    private void startFoodGatherCommand() {
        activeFoodLevelAtStart = latestSnapshot.foodLevel;
        activeBreadCountAtStart = latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get wood " + activeCount);
        state = State.GATHERING_FOOD;
    }

    private void beginCaseClock() {
        caseStartedAtTick = clientTicks;
        caseStartedAtWorldTime = client.level == null ? 0 : client.level.getGameTime();
        activeFoodLevelAtStart = latestSnapshot == null ? 0 : latestSnapshot.foodLevel;
        activeBreadCountAtStart = latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        activeTableOpeningsAtStart = serverTableOpenings;
    }

    private void sendCommand(String command) {
        if (client.getConnection() == null) throw new IllegalStateException("integrated client is not connected");
        client.getConnection().sendChat(command);
    }

    private void requestObservation() {
        if (observationFuture != null || client.player == null || client.getSingleplayerServer() == null) return;
        MinecraftServer server = requireServer();
        CompletableFuture<ServerSnapshot> capture = new CompletableFuture<>();
        observationFuture = capture;
        server.execute(() -> {
            try {
                ServerPlayer player = requireServerPlayer(server);
                Map<String, Integer> inventory = inventoryCounts(player);
                ServerLevel world = player.level();
                capture.complete(new ServerSnapshot(server.getTickCount(), world.getGameTime(), inventory,
                    player.getHealth(), player.getFoodData().getFoodLevel(), world.getDifficulty().name(),
                    player.getX(), player.getY(), player.getZ()));
            } catch (Throwable throwable) {
                capture.completeExceptionally(throwable);
            }
        });
        capture.whenComplete((snapshot, throwable) -> client.execute(() -> {
            if (observationFuture != capture) return;
            observationFuture = null;
            if (throwable != null) {
                fail("server observation failed: " + throwable.getMessage());
                return;
            }
            latestSnapshot = snapshot;
        }));
    }

    private static Map<String, Integer> inventoryCounts(ServerPlayer player) {
        Map<String, Integer> result = new HashMap<>();
        player.getInventory().getNonEquipmentItems().forEach(stack -> countStack(stack, result));
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            countStack(player.getItemBySlot(slot), result);
        }
        return Map.copyOf(result);
    }

    private static void countStack(ItemStack stack, Map<String, Integer> result) {
        if (!stack.isEmpty()) result.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }

    private ServerPlayer requireServerPlayer(MinecraftServer server) {
        if (playerId == null) throw new IllegalStateException("verifier player ID is not known");
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) throw new IllegalStateException("integrated server player is not present");
        return player;
    }

    private MinecraftServer requireServer() {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) throw new IllegalStateException("isolated integrated server is not running");
        return server;
    }

    private AutomationEngine requireEngine() {
        if (LodekeeperClient.engine == null) throw new IllegalStateException("client engine has not initialized");
        return LodekeeperClient.engine;
    }

    private void addResult(boolean passed, int observed, String detail) {
        long gameTicks = client.level == null ? 0 : Math.max(0, client.level.getGameTime() - caseStartedAtWorldTime);
        results.add(new CaseResult(activeCase, activeItem, activeCount, observed, activeStartedEmpty,
            passed && (!activeRequiresEmpty || activeStartedEmpty), clientTicks - caseStartedAtTick, gameTicks,
            requireEngine().status(), detail, latestSnapshot == null ? 0 : latestSnapshot.health,
            latestSnapshot == null ? "unknown" : latestSnapshot.difficulty,
            latestSnapshot != null && serverTableOpenings > activeTableOpeningsAtStart,
            state == State.SMELTING_IRON && serverFurnaceOpened,
            latestSnapshot == null ? 0 : activeFoodLevelAtStart, latestSnapshot == null ? 0 : latestSnapshot.foodLevel,
            latestSnapshot == null ? 0 : activeBreadCountAtStart,
            latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID),
            latestSnapshot == null ? 0 : latestSnapshot.x, latestSnapshot == null ? 0 : latestSnapshot.y,
            latestSnapshot == null ? 0 : latestSnapshot.z));
    }

    private void finishRun() {
        state = State.COMPLETE;
        boolean passed = results.size() == 9 && results.stream().allMatch(CaseResult::passed);
        writeEvidence(passed ? "passed" : "failed");
        System.out.println("[Lodekeeper verification] Finished " + results.size() + " server-observed cases; evidence=" + evidenceDirectory);
        client.stop();
    }

    private void fail(String reason) {
        if (state == State.FAILED || state == State.COMPLETE) return;
        failure = reason;
        if (activeCase != null && results.stream().noneMatch(result -> result.name.equals(activeCase))) {
            int observed = latestSnapshot == null || activeItem == null ? 0 : latestSnapshot.count(activeItem);
            try { addResult(false, observed, reason); }
            catch (RuntimeException ignored) { }
        }
        state = State.FAILED;
        try {
            if (LodekeeperClient.engine != null) LodekeeperClient.engine.stop();
            writeEvidence("failed");
        } catch (Exception exception) {
            System.err.println("[Lodekeeper verification] Could not write evidence: " + exception.getMessage());
        }
        System.err.println("[Lodekeeper verification] FAILED: " + reason);
        if (client != null && client.isRunning()) client.stop();
    }

    private void writeEvidence(String status) {
        if (evidenceDirectory == null || runId == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("runId", runId);
            root.addProperty("status", status);
            root.addProperty("minecraftVersion", VerificationApi.minecraftVersion());
            root.addProperty("worldKind", "isolated_superflat_fixture");
            root.addProperty("evidenceAuthority", "integrated_server_inventory_menu_and_hunger");
            root.addProperty("runDirectory", client.gameDirectory.toPath().toRealPath().toString());
            root.addProperty("worldId", worldId);
            root.addProperty("elapsedMillis", (System.nanoTime() - startedAtNanos) / 1_000_000L);
            root.addProperty("clientTicks", clientTicks);
            root.addProperty("failure", failure);
            root.addProperty("serverTableOpened", serverTableOpened);
            root.addProperty("serverFurnaceOpened", serverFurnaceOpened);
            root.addProperty("serverTableOpenings", serverTableOpenings);
            JsonArray cases = new JsonArray();
            for (CaseResult result : results) {
                JsonObject item = new JsonObject();
                item.addProperty("name", result.name);
                item.addProperty("item", result.item);
                item.addProperty("expected", result.expected);
                item.addProperty("serverObserved", result.observed);
                item.addProperty("inventoryEmptyAtStart", result.inventoryEmptyAtStart);
                item.addProperty("passed", result.passed);
                item.addProperty("clientTicks", result.clientTicks);
                item.addProperty("worldTicks", result.worldTicks);
                item.addProperty("engineStatus", result.engineStatus);
                item.addProperty("detail", result.detail);
                item.addProperty("serverHealth", result.health);
                item.addProperty("serverDifficulty", result.difficulty);
                item.addProperty("serverCraftingTableOpenedDuringCase", result.tableOpenedDuringCase);
                item.addProperty("serverFurnaceOpenedDuringCase", result.furnaceOpenedDuringCase);
                item.addProperty("serverFoodLevelAtStart", result.foodLevelAtStart);
                item.addProperty("serverFoodLevelObserved", result.foodLevelObserved);
                item.addProperty("serverBreadAtStart", result.breadAtStart);
                item.addProperty("serverBreadObserved", result.breadObserved);
                item.addProperty("serverPosition", result.x + "," + result.y + "," + result.z);
                cases.add(item);
            }
            root.add("cases", cases);
            Files.writeString(evidenceDirectory.resolve("run-" + runId + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            System.err.println("[Lodekeeper verification] Evidence write failed: " + exception.getMessage());
        }
    }

    private record ServerSnapshot(int serverTick, long worldTime, Map<String, Integer> inventory,
                                  float health, int foodLevel, String difficulty, double x, double y, double z) {
        int count(String id) { return inventory.getOrDefault(id, 0); }
        boolean inventoryEmpty() { return inventory.isEmpty(); }
    }

    private record CaseResult(String name, String item, int expected, int observed, boolean inventoryEmptyAtStart,
                              boolean passed, int clientTicks, long worldTicks, String engineStatus, String detail,
                              float health, String difficulty, boolean tableOpenedDuringCase,
                              boolean furnaceOpenedDuringCase,
                              int foodLevelAtStart, int foodLevelObserved, int breadAtStart, int breadObserved,
                              double x, double y, double z) {}
}
