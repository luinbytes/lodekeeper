package dev.lodekeeper.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.resource.DataConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static final int MAX_RUN_TICKS = 6_000; // five minutes at 20 client ticks per second
    private static final int OBSERVE_EVERY_TICKS = 20;
    private static final int FIXTURE_FLOOR_Y = 63;
    private static final int PLAYER_Y = FIXTURE_FLOOR_Y + 1;

    private enum State { DISABLED, OPENING_WORLD, WAITING_FOR_WORLD, SETTING_UP, WAITING_FOR_EMPTY_SNAPSHOT,
        GATHERING_WOOD, CRAFTING_TABLE, CRAFTING_STICKS, CRAFTING_WOOD_PICK, CRAFTING_STONE_PICK, CRAFTING_FURNACE,
        SMELTING_IRON, CUSTOM_CONTENT, SETTING_UP_FOOD, WAITING_FOR_FOOD_FIXTURE, GATHERING_FOOD,
        CAPTURING, COMPLETE, FAILED }

    private MinecraftClient client;
    private State state = State.DISABLED;
    private Path verificationRoot;
    private Path evidenceDirectory;
    private UUID playerId;
    private String runId;
    private long startedAtNanos;
    private int clientTicks;
    private int caseStartedAtTick;
    private long caseStartedAtWorldTime;
    private long fixtureReadyServerTick;
    private int readyTicks;
    private CompletableFuture<Long> setupFuture;
    private CompletableFuture<ServerSnapshot> observationFuture;
    private ServerSnapshot latestSnapshot;
    private boolean worldLaunchStarted;
    private int screenshotWritesPending;
    private int captureStartedAtTick;
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
    private volatile boolean serverTableOpened, serverFurnaceOpened;
    private volatile int serverTableOpenings;
    private net.minecraft.screen.ScreenHandler lastServerScreenHandler;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        client = MinecraftClient.getInstance();
        try {
            Path runDirectory = client.runDirectory.toPath().toRealPath();
            verificationRoot = runDirectory.resolve("verification");
            Path ordinarySaves = runDirectory.resolve("saves");
            Files.createDirectories(verificationRoot);
            Path actualVerificationRoot = verificationRoot.toRealPath();
            if (!actualVerificationRoot.startsWith(runDirectory)) {
                throw new IOException("verification output resolves outside the development run directory");
            }
            Path actualSaves = Files.exists(ordinarySaves) ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
            if (actualVerificationRoot.startsWith(actualSaves) || actualSaves.startsWith(actualVerificationRoot)) {
                throw new IOException("verification output overlaps the normal saves directory");
            }
            Path requestedEvidenceDirectory = actualVerificationRoot.resolve("evidence");
            Files.createDirectories(requestedEvidenceDirectory);
            evidenceDirectory = requestedEvidenceDirectory.toRealPath();
            if (!evidenceDirectory.startsWith(actualVerificationRoot)) {
                throw new IOException("evidence output resolves outside the verification directory");
            }
            runId = Instant.now().toString().replace(':', '-').replace('.', '-') + "-" + UUID.randomUUID().toString().substring(0, 8);
            startedAtNanos = System.nanoTime();
            state = State.OPENING_WORLD;
            try {
                VerificationContentInitializer.ensureSourceContract(client.runDirectory.toPath());
            } catch (IOException exception) {
                failure = "refusing verifier source contract: " + exception.getMessage();
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
            ServerTickEvents.END_SERVER_TICK.register(server -> {
                if (playerId == null) return;
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (player == null) return;
                net.minecraft.screen.ScreenHandler handler = player.currentScreenHandler;
                if (handler != lastServerScreenHandler) {
                    if (handler instanceof net.minecraft.screen.CraftingScreenHandler) {
                        serverTableOpened = true;
                        serverTableOpenings++;
                    }
                    if (handler instanceof net.minecraft.screen.FurnaceScreenHandler) serverFurnaceOpened = true;
                    lastServerScreenHandler = handler;
                }
            });
            System.out.println("[Lodekeeper verification] Enabled. World and evidence paths are under " + verificationRoot);
        } catch (Exception exception) {
            state = State.FAILED;
            failure = exception.toString();
            System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
        }
    }

    private void tick(MinecraftClient currentClient) {
        if (state == State.COMPLETE || state == State.FAILED || state == State.DISABLED) return;
        clientTicks++;
        if (clientTicks % 100 == 0) System.out.println("[Lodekeeper verification] state=" + state + ", engine=" + requireEngine().status() + ", screen=" + (client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName()) + ", server=" + latestSnapshot);
        if (clientTicks > MAX_RUN_TICKS || System.nanoTime() - startedAtNanos > 300_000_000_000L) {
            fail("verification exceeded the five-minute limit");
            return;
        }
        try {
            if (state == State.OPENING_WORLD) {
                // Startup and resource-reload overlays must finish on ordinary client frames.
                if (client.getOverlay() != null) return;
                if (client.world != null) throw new IllegalStateException("start from the title screen; an existing world is active");
                VerificationApi.openCreateWorldScreen(client, client.currentScreen);
                state = State.WAITING_FOR_WORLD;
                return;
            }
            if (state == State.WAITING_FOR_WORLD) {
                if (client.world != null && client.player != null) {
                    if (client.currentScreen != null || ++readyTicks < 40) return;
                    playerId = client.player.getUuid();
                    configureAutomation();
                    beginFixtureSetup();
                    return;
                }
                if (!worldLaunchStarted && client.getOverlay() == null
                        && client.currentScreen instanceof CreateWorldScreen createScreen) {
                    worldLaunchStarted = true;
                    WorldCreator creator = createScreen.getWorldCreator();
                    // Server startup renders a loading loop. Run it outside the enclosing client tick.
                    client.send(() -> {
                        try {
                            if (client.getOverlay() != null) {
                                worldLaunchStarted = false;
                                return;
                            }
                            if (client.world != null || client.currentScreen != createScreen) {
                                throw new IllegalStateException("world creation screen changed before verifier startup");
                            }
                            startIsolatedFlatWorld(creator);
                        } catch (Exception exception) {
                            fail("isolated world startup failed: " + exception);
                        }
                    });
                }
                return;
            }
            if (state == State.SETTING_UP) {
                if (setupFuture != null && setupFuture.isDone()) {
                    fixtureReadyServerTick = setupFuture.join();
                    setupFuture = null;
                    engineTerrainChanged();
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
                    engineTerrainChanged();
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
                    if (readyTicks >= 20 && client.player.getY() > 63 && client.world.getBlockState(new BlockPos(6, PLAYER_Y, 0)).isOf(Blocks.OAK_LOG)) startGatherCommand();
                }
                return;
            }
            if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
            evaluateCurrentCase();
            if (state == State.CAPTURING && screenshotWritesPending == 0 && clientTicks - captureStartedAtTick >= 20) finishRun();
            else if (state == State.CAPTURING && clientTicks - captureStartedAtTick >= 100) finishRun();
        } catch (Exception exception) {
            fail(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private void startIsolatedFlatWorld(WorldCreator creator) throws IOException {
        WorldCreator.WorldType flat = creator.getNormalWorldTypes().stream()
            .filter(type -> type.preset().matchesKey(WorldPresets.FLAT)).findFirst()
            .or(() -> creator.getExtendedWorldTypes().stream().filter(type -> type.preset().matchesKey(WorldPresets.FLAT)).findFirst())
            .orElseThrow(() -> new IllegalStateException("The built-in superflat preset is unavailable"));
        creator.setWorldName("Lodekeeper verification " + runId.substring(Math.max(0, runId.length() - 8)));
        creator.setGameMode(WorldCreator.Mode.SURVIVAL);
        creator.setDifficulty(Difficulty.PEACEFUL);
        creator.setCheatsEnabled(false);
        creator.setGenerateStructures(false);
        creator.setBonusChestEnabled(false);
        creator.setSeed("483920105");
        creator.setWorldType(flat);
        creator.update();
        if (!creator.getWorldType().preset().matchesKey(WorldPresets.FLAT)) {
            throw new IllegalStateException("Could not select the superflat preset");
        }

        var holder = creator.getGeneratorOptionsHolder();
        if (holder == null) throw new IllegalStateException("World generator settings are not ready");
        String saveName = "run-" + runId.substring(Math.max(0, runId.length() - 8));
        LevelInfo levelInfo = new LevelInfo("Lodekeeper verification", GameMode.SURVIVAL, false, Difficulty.PEACEFUL,
            false, VerificationApi.gameRules(creator), DataConfiguration.SAFE_MODE);
        Path worldsDirectory = verificationRoot.resolve("worlds");
        Files.createDirectories(worldsDirectory);
        Path actualWorldsDirectory = worldsDirectory.toRealPath();
        if (!actualWorldsDirectory.startsWith(verificationRoot.toRealPath())) {
            throw new IOException("isolated world storage resolves outside the verification directory");
        }
        Path runDirectory = client.runDirectory.toPath().toRealPath();
        Path ordinarySaves = runDirectory.resolve("saves");
        Path actualSaves = Files.exists(ordinarySaves) ? ordinarySaves.toRealPath() : ordinarySaves.normalize();
        if (actualWorldsDirectory.startsWith(actualSaves) || actualSaves.startsWith(actualWorldsDirectory)) {
            throw new IOException("isolated world storage overlaps the normal saves directory");
        }
        client.setScreen(null);
        IntegratedServerLoader loader = new IntegratedServerLoader(client, LevelStorage.create(actualWorldsDirectory));
        VerificationApi.startFlatWorld(loader, saveName, levelInfo, holder.generatorOptions());
    }

    private void configureAutomation() {
        AutomationEngine engine = requireEngine();
        engine.stop();
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
        IntegratedServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                ServerWorld world = server.getOverworld();
                // A bounded, level pad makes the fixture deterministic while retaining normal survival physics.
                for (int x = -12; x <= 18; x++) {
                    for (int z = -6; z <= 6; z++) world.setBlockState(new BlockPos(x, FIXTURE_FLOOR_Y, z), Blocks.BEDROCK.getDefaultState(), 3);
                }
                for (int index = 0; index < 8; index++) {
                    world.setBlockState(new BlockPos(6 + index, PLAYER_Y, 0), Blocks.OAK_LOG.getDefaultState(), 3);
                }
                for (int x = 6; x <= 17; x++) world.setBlockState(new BlockPos(x, PLAYER_Y, 2), Blocks.STONE.getDefaultState(), 3);
                world.setBlockState(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.getDefaultState(), 3);
                world.setBlockState(new BlockPos(17, PLAYER_Y, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                Block rubyOre = Registries.BLOCK.get(GameApi.identifier(VerificationContentInitializer.RUBY_ORE_ID));
                if (rubyOre == Blocks.AIR) throw new IllegalStateException("verifier ruby ore was not registered");
                for (int index = 0; index < 4; index++) {
                    world.setBlockState(new BlockPos(8 + index, PLAYER_Y, 4), rubyOre.getDefaultState(), 3);
                }
                clearInventory(player.getInventory());
                player.setHealth(player.getMaxHealth());
                player.getHungerManager().setFoodLevel(20);
                if (!VerificationApi.teleport(player, world, 0.5, PLAYER_Y, 0.5, 0.0F, 0.0F)) {
                    throw new IllegalStateException("could not teleport verifier player to the fixture spawn");
                }
                player.currentScreenHandler.sendContentUpdates();
                scheduled.complete((long) server.getTicks());
            } catch (Throwable throwable) {
                scheduled.completeExceptionally(throwable);
            }
        });
    }

    private static void clearInventory(PlayerInventory inventory) {
        inventory.clear();
        inventory.markDirty();
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
        if (activeCase == null || state == State.CAPTURING) return;
        if (latestSnapshot == null) return;
        int observed = latestSnapshot.count(activeItem);
        boolean targetReached = observed >= activeCount && requireEngine().status().startsWith("idle")
            && (state != State.CRAFTING_WOOD_PICK || serverTableOpened)
            && (state != State.SMELTING_IRON || serverFurnaceOpened)
            && (state != State.CUSTOM_CONTENT || serverTableOpenings > activeTableOpeningsAtStart);
        if (targetReached && state == State.GATHERING_FOOD
            && (latestSnapshot.foodLevel <= activeFoodLevelAtStart
                || latestSnapshot.count(VerificationContentInitializer.BREAD_ID) >= activeBreadCountAtStart)) {
            fail("food-use case reached its log target without server-confirmed bread consumption and hunger recovery");
        } else if (targetReached) {
            String screenshot = capture(activeCase);
            String detail = switch (state) {
                case CUSTOM_CONTENT -> "server inventory reached the custom recipe output after opening the server crafting table";
                case GATHERING_FOOD -> "server inventory reached the log target; one bread was consumed and hunger rose from "
                    + activeFoodLevelAtStart + " to " + latestSnapshot.foodLevel;
                default -> "server inventory reached target and engine returned idle";
            };
            addResult(true, observed, detail, screenshot);
            if (state == State.GATHERING_WOOD) {
                startCraftingTableCommand();
            } else if (state == State.CRAFTING_TABLE) {
                startCraftingSticksCommand();
            } else if (state == State.CRAFTING_STICKS) {
                startAdditionalCase(State.CRAFTING_WOOD_PICK, "craft_wooden_pickaxe", "minecraft:wooden_pickaxe");
            } else if (state == State.CRAFTING_WOOD_PICK) {
                startAdditionalCase(State.CRAFTING_STONE_PICK, "craft_stone_pickaxe", "minecraft:stone_pickaxe");
            } else if (state == State.CRAFTING_STONE_PICK) {
                startAdditionalCase(State.CRAFTING_FURNACE, "craft_furnace", "minecraft:furnace");
            } else if (state == State.CRAFTING_FURNACE) {
                startAdditionalCase(State.SMELTING_IRON, "smelt_iron_ingot", "minecraft:iron_ingot");
            } else if (state == State.SMELTING_IRON) {
                startCustomContentCase();
            } else if (state == State.CUSTOM_CONTENT) {
                beginFoodFixtureSetup();
            } else {
                state = State.CAPTURING;
                captureStartedAtTick = clientTicks;
            }
        } else if (requireEngine().status().startsWith("paused")) {
            fail("automation paused during " + activeCase + ": " + requireEngine().status());
        } else if (clientTicks - caseStartedAtTick > MAX_RUN_TICKS) {
            addResult(false, observed, "case timed out; engine status=" + requireEngine().status(), null);
            fail("case timed out: " + activeCase);
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
        activeCase = name; activeItem = item; activeCount = 1;
        activeRequiresEmpty = false; activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock(); sendCommand("!lk get " + item + " 1"); state = next;
    }

    private void startCustomContentCase() {
        activeCase = "custom_ruby_gear";
        activeItem = VerificationContentInitializer.RUBY_GEAR_ID;
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get " + activeItem);
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
        IntegratedServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                ServerWorld world = server.getOverworld();
                server.setDifficulty(Difficulty.NORMAL, true);
                player.getHungerManager().setFoodLevel(7);
                player.getHungerManager().setSaturationLevel(0.0F);
                if (!player.getInventory().insertStack(new ItemStack(Items.BREAD))) {
                    throw new IllegalStateException("could not add the single verifier bread to the inventory");
                }
                BlockPos extraLog = new BlockPos(6, PLAYER_Y, 0);
                if (!world.getBlockState(extraLog).isReplaceable()) {
                    throw new IllegalStateException("the extra oak-log fixture position is occupied");
                }
                world.setBlockState(extraLog, Blocks.OAK_LOG.getDefaultState(), 3);
                player.currentScreenHandler.sendContentUpdates();
                scheduled.complete((long) server.getTicks());
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
        caseStartedAtWorldTime = client.world == null ? 0 : client.world.getTime();
        activeFoodLevelAtStart = latestSnapshot == null ? 0 : latestSnapshot.foodLevel;
        activeBreadCountAtStart = latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        activeTableOpeningsAtStart = serverTableOpenings;
    }

    private void sendCommand(String command) {
        ClientPlayNetworkHandler network = client.getNetworkHandler();
        if (network == null) throw new IllegalStateException("Integrated client is not connected");
        network.sendChatMessage(command);
    }

    private void requestObservation() {
        if (observationFuture != null || client.player == null || !client.isIntegratedServerRunning()) return;
        IntegratedServer server = requireServer();
        CompletableFuture<ServerSnapshot> capture = new CompletableFuture<>();
        observationFuture = capture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                Map<String, Integer> inventory = inventoryCounts(player.getInventory());
                ServerWorld world = server.getOverworld();
                ServerSnapshot snapshot = new ServerSnapshot(server.getTicks(), world.getTime(), inventory,
                    player.getHealth(), player.getHungerManager().getFoodLevel(), world.getDifficulty().name(),
                    player.getX(), player.getY(), player.getZ());
                capture.complete(snapshot);
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

    private static Map<String, Integer> inventoryCounts(PlayerInventory inventory) {
        Map<String, Integer> result = new HashMap<>();
        countStacks(inventory.main, result);
        countStacks(inventory.armor, result);
        countStacks(inventory.offHand, result);
        return Map.copyOf(result);
    }

    private static void countStacks(Iterable<ItemStack> stacks, Map<String, Integer> result) {
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) result.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }

    private ServerPlayerEntity requireServerPlayer(IntegratedServer server) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Integrated server player is not present");
        return player;
    }

    private IntegratedServer requireServer() {
        IntegratedServer server = client.getServer();
        if (server == null) throw new IllegalStateException("Isolated integrated server is not running");
        return server;
    }

    private AutomationEngine requireEngine() {
        if (LodekeeperClient.engine == null) throw new IllegalStateException("Lodekeeper client initializer has not completed");
        return LodekeeperClient.engine;
    }

    private void engineTerrainChanged() {
        requireEngine().terrain.changed();
    }

    private void addResult(boolean passed, int observed, String detail, String screenshot) {
        long gameTicks = client.world == null ? 0 : Math.max(0, client.world.getTime() - caseStartedAtWorldTime);
        results.add(new CaseResult(activeCase, activeItem, activeCount, observed, activeStartedEmpty,
            passed && (!activeRequiresEmpty || activeStartedEmpty), clientTicks - caseStartedAtTick, gameTicks,
            requireEngine().status(), detail, screenshot, latestSnapshot == null ? 0 : latestSnapshot.health,
            latestSnapshot == null ? "unknown" : latestSnapshot.difficulty,
            latestSnapshot != null && serverTableOpenings > activeTableOpeningsAtStart,
            activeFoodLevelAtStart, latestSnapshot == null ? 0 : latestSnapshot.foodLevel,
            activeBreadCountAtStart, latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID),
            latestSnapshot == null ? 0 : latestSnapshot.x, latestSnapshot == null ? 0 : latestSnapshot.y,
            latestSnapshot == null ? 0 : latestSnapshot.z));
    }

    private String capture(String name) {
        String fileName = "lodekeeper-" + runId + "-" + name + ".png";
        try {
            screenshotWritesPending++;
            ScreenshotRecorder.saveScreenshot(evidenceDirectory.toFile(), fileName, client.getFramebuffer(), text -> {
                System.out.println("[Lodekeeper verification] " + text.getString());
                client.execute(() -> screenshotWritesPending = Math.max(0, screenshotWritesPending - 1));
            });
            return "screenshots/" + fileName;
        } catch (Exception exception) {
            screenshotWritesPending = Math.max(0, screenshotWritesPending - 1);
            System.err.println("[Lodekeeper verification] Screenshot failed for " + name + ": " + exception.getMessage());
            return null;
        }
    }

    private void finishRun() {
        state = State.COMPLETE;
        boolean allPassed = !results.isEmpty() && results.stream().allMatch(CaseResult::passed);
        writeEvidence(allPassed ? "passed" : "failed");
        System.out.println("[Lodekeeper verification] Finished " + results.size() + " server-observed cases; evidence: " + evidenceDirectory);
        client.scheduleStop();
    }

    private void fail(String reason) {
        if (state == State.FAILED || state == State.COMPLETE) return;
        failure = reason;
        if (activeCase != null && results.stream().noneMatch(result -> result.name.equals(activeCase))) {
            int observed = latestSnapshot == null || activeItem == null ? 0 : latestSnapshot.count(activeItem);
            addResult(false, observed, reason, capture("failed-" + activeCase));
        }
        state = State.FAILED;
        try {
            if (LodekeeperClient.engine != null) LodekeeperClient.engine.stop();
            writeEvidence("failed");
        } catch (Exception exception) {
            System.err.println("[Lodekeeper verification] Could not write evidence: " + exception.getMessage());
        }
        System.err.println("[Lodekeeper verification] FAILED: " + reason);
        if (client != null && client.isRunning()) client.scheduleStop();
    }

    private void writeEvidence(String status) {
        try {
            Files.createDirectories(evidenceDirectory);
            String json = toJson(status);
            Path output = evidenceDirectory.resolve("run-" + runId + ".json");
            Files.writeString(output, json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("[Lodekeeper verification] Evidence write failed: " + exception.getMessage());
        }
    }

    private String toJson(String status) {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\n  \"runId\":\"").append(escape(runId)).append("\",\n")
            .append("  \"status\":\"").append(escape(status)).append("\",\n")
            .append("  \"minecraftVersion\":\"").append(escape(VerificationApi.minecraftVersion())).append("\",\n")
            .append("  \"worldKind\":\"isolated_superflat_fixture\",\n")
            .append("  \"evidenceAuthority\":\"integrated_server_inventory\",\n")
            .append("  \"elapsedMillis\":").append((System.nanoTime() - startedAtNanos) / 1_000_000L).append(",\n")
            .append("  \"clientTicks\":").append(clientTicks).append(",\n")
            .append("  \"failure\":\"").append(escape(failure)).append("\",\n")
            .append("  \"serverTableOpened\":").append(serverTableOpened).append(",\n")
            .append("  \"serverFurnaceOpened\":").append(serverFurnaceOpened).append(",\n")
            .append("  \"serverTableOpenings\":").append(serverTableOpenings).append(",\n")
            .append("  \"cases\":[\n");
        for (int index = 0; index < results.size(); index++) {
            CaseResult result = results.get(index);
            json.append("    {\"name\":\"").append(escape(result.name)).append("\",\"item\":\"").append(escape(result.item))
                .append("\",\"expected\":").append(result.expected).append(",\"serverObserved\":").append(result.observed)
                .append(",\"inventoryEmptyAtStart\":").append(result.inventoryEmptyAtStart)
                .append(",\"passed\":").append(result.passed).append(",\"clientTicks\":").append(result.clientTicks)
                .append(",\"worldTicks\":").append(result.worldTicks).append(",\"engineStatus\":\"").append(escape(result.engineStatus))
                .append("\",\"detail\":\"").append(escape(result.detail)).append("\",\"serverHealth\":").append(result.health)
                .append(",\"serverDifficulty\":\"").append(escape(result.difficulty)).append("\",\"serverCraftingTableOpenedDuringCase\":").append(result.tableOpenedDuringCase)
                .append(",\"serverFoodLevelAtStart\":").append(result.foodLevelAtStart).append(",\"serverFoodLevelObserved\":").append(result.foodLevelObserved)
                .append(",\"serverBreadAtStart\":").append(result.breadAtStart).append(",\"serverBreadObserved\":").append(result.breadObserved)
                .append(",\"serverPosition\":[").append(result.x).append(',').append(result.y).append(',').append(result.z).append(']')
                .append(",\"screenshot\":").append(result.screenshot == null ? "null" : "\"" + escape(result.screenshot) + "\"").append('}')
                .append(index + 1 == results.size() ? "\n" : ",\n");
        }
        return json.append("  ]\n}\n").toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private record ServerSnapshot(int serverTick, long worldTime, Map<String, Integer> inventory,
                                  float health, int foodLevel, String difficulty, double x, double y, double z) {
        int count(String id) { return inventory.getOrDefault(id, 0); }
        boolean inventoryEmpty() { return inventory.isEmpty(); }
    }

    private record CaseResult(String name, String item, int expected, int observed, boolean inventoryEmptyAtStart,
                              boolean passed, int clientTicks, long worldTicks, String engineStatus,
                              String detail, String screenshot, float health, String difficulty, boolean tableOpenedDuringCase,
                              int foodLevelAtStart, int foodLevelObserved, int breadAtStart, int breadObserved,
                              double x, double y, double z) {}
}
