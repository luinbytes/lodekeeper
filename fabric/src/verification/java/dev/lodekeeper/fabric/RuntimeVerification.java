package dev.lodekeeper.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import dev.lodekeeper.core.AcquisitionPlanner;
import dev.lodekeeper.core.AcquisitionSource;
import dev.lodekeeper.core.BlockedReason;
import dev.lodekeeper.core.CatalogSnapshot;
import dev.lodekeeper.core.InventorySnapshot;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.PlanResult;
import dev.lodekeeper.core.PlanStep;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.resource.DataConfiguration;

import java.io.IOException;
import java.lang.reflect.Field;
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
    private static final boolean EXPLORATION_MODE = Boolean.getBoolean("lodekeeper.verify.exploration");
    private static final boolean DIAMOND_BOOTSTRAP_MODE = Boolean.getBoolean("lodekeeper.verify.diamondBoots");
    private static final boolean IRON_PICKAXE_MODE = Boolean.getBoolean("lodekeeper.verify.ironPickaxe");
    private static final boolean COAL_RECOVERY_MODE = Boolean.getBoolean("lodekeeper.verify.coalRecovery");
    private static final String COAL_START_SURFACE = System.getProperty("lodekeeper.verify.coalStartSurface", "full");
    private static final String NAVIGATION_COURSE = System.getProperty("lodekeeper.verify.navigationCourse");
    private static final boolean MIXED_NAVIGATION_COURSE = "mixed".equals(NAVIGATION_COURSE);
    private double coalInitialServerFeetY = Double.NaN;
    private static final boolean BULK_WOOD_MODE = Boolean.getBoolean("lodekeeper.verify.bulkWood");
    private static final boolean WOOD_TOOLS_MODE = Boolean.getBoolean("lodekeeper.verify.woodTools");
    private static final String COOKING_STATION_MODE = System.getProperty("lodekeeper.verify.cookingStation");
    private static final boolean COOKING_MODE = COOKING_STATION_MODE != null;
    private static final boolean STONECUTTING_MODE = Boolean.getBoolean("lodekeeper.verify.stonecutting");
    private static final boolean STONECUTTING_DRAIN_MODE = Boolean.getBoolean("lodekeeper.verify.stonecuttingDrain");
    private static final boolean PROCESSING_MODE = COOKING_MODE || STONECUTTING_MODE;
    private static final String PROCESSING_STATION_MODE = STONECUTTING_MODE ? "stonecutter" : COOKING_STATION_MODE;
    private static final String IRON_PICKAXE_ID = "minecraft:iron_pickaxe";
    private static final String OAK_LOG_ID = "minecraft:oak_log";
    private static final String WOODEN_AXE_ID = "minecraft:wooden_axe";
    private static final String RAW_PORKCHOP_ID = "minecraft:porkchop";
    private static final String COOKED_PORKCHOP_ID = "minecraft:cooked_porkchop";
    private static final String RAW_IRON_ID = "minecraft:raw_iron";
    private static final String IRON_INGOT_ID = "minecraft:iron_ingot";
    private static final int STONECUTTING_DRAIN_COMMAND_TARGET = 144;
    private static final int IRON_PICKAXE_PROBE_TIMEOUT_TICKS = 200;
    private static final int IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT = 4;
    private static final Field ENGINE_MOVEMENT_FIELD = findField(AutomationEngine.class, "movement");
    private static final Field MOVEMENT_PATH_FIELD = findField("dev.lodekeeper.fabric.MovementController", "path");
    private static final Field MOVEMENT_PATH_INDEX_FIELD = findField("dev.lodekeeper.fabric.MovementController", "pathIndex");
    private static final Field MOVEMENT_VALIDATED_PATH_INDEX_FIELD = findField("dev.lodekeeper.fabric.MovementController", "validatedPathIndex");
    private boolean resourceInitiallyLoaded;
    private static final int MAX_RUN_TICKS = COOKING_MODE ? 10_000 : 6_000;
    private static final long MAX_RUN_WALL_NANOS = COOKING_MODE ? 500_000_000_000L : 300_000_000_000L;
    private static final int OBSERVE_EVERY_TICKS = 20;
    private static final int FIXTURE_FLOOR_Y = 63;
    private static final int PLAYER_Y = FIXTURE_FLOOR_Y + 1;
    private static final BlockPos COAL_RECOVERY_ENCASED_ORE = new BlockPos(6, PLAYER_Y, 2);
    private static final BlockPos COAL_RECOVERY_ACCESSIBLE_ORE = new BlockPos(16, PLAYER_Y, 2);
    private static final List<CoalNavigationCheckpoint> COAL_NAVIGATION_CHECKPOINTS = List.of(
        new CoalNavigationCheckpoint(1, 64 * 16 + 8), new CoalNavigationCheckpoint(2, 65 * 16),
        new CoalNavigationCheckpoint(4, 64 * 16 + 15), new CoalNavigationCheckpoint(5, 64 * 16 + 15),
        new CoalNavigationCheckpoint(6, 64 * 16 + 8), new CoalNavigationCheckpoint(7, 65 * 16),
        new CoalNavigationCheckpoint(8, 65 * 16 + 2), new CoalNavigationCheckpoint(9, 65 * 16 + 6),
        new CoalNavigationCheckpoint(10, 65 * 16 + 14), new CoalNavigationCheckpoint(11, 66 * 16),
        new CoalNavigationCheckpoint(12, 67 * 16), new CoalNavigationCheckpoint(13, 67 * 16),
        new CoalNavigationCheckpoint(14, 66 * 16 + 15), new CoalNavigationCheckpoint(15, 67 * 16),
        new CoalNavigationCheckpoint(16, 68 * 16));

    private enum State { DISABLED, OPENING_WORLD, WAITING_FOR_WORLD, SETTING_UP, WAITING_FOR_EMPTY_SNAPSHOT,
        GATHERING_WOOD, CRAFTING_TABLE, CRAFTING_STICKS, CRAFTING_WOOD_PICK, CRAFTING_STONE_PICK, CRAFTING_FURNACE,
        SMELTING_IRON, COOKING, CUSTOM_CONTENT, SETTING_UP_FOOD, WAITING_FOR_FOOD_FIXTURE, GATHERING_FOOD,
        GATHERING_COAL_RECOVERY, CAPTURING, COMPLETE, FAILED }

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
    private long caseStartedAtNanos;
    private long fixtureReadyServerTick;
    private int readyTicks;
    private CompletableFuture<Long> setupFuture;
    private CompletableFuture<ServerSnapshot> observationFuture;
    private ServerSnapshot latestSnapshot;
    private volatile boolean coalNavigationCourseCommandStarted;
    private volatile float coalNavigationCourseMinimumHealth = 20.0F;
    private int coalNavigationCourseObservedMask;
    private final int[] coalNavigationCourseCheckpointServerTicks = newCoalNavigationCheckpointTicks();
    private Map<BlockPos, BlockState> coalNavigationExpectedStates = Map.of();
    private JsonObject coalNavigationFenceGeometryEvidence;
    private JsonArray coalNavigationRouteDiagnostics;
    private double coalNavigationInitialServerFeetX = Double.NaN, coalNavigationInitialServerFeetZ = Double.NaN;
    private boolean coalNavigationStairEdgeCompleted;
    private String coalNavigationStairEdgeMovement = "unobserved";
    private int coalNavigationStairEdgePathIndex = -1;
    private boolean coalNavigationLedgeEdgeCompleted;
    private String coalNavigationLedgeEdgeMovement = "unobserved";
    private int coalNavigationLedgeEdgePathIndex = -1;
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
    private int activeFurnaceOpeningsAtStart;
    private int activeSmokerOpeningsAtStart;
    private int activeBlastFurnaceOpeningsAtStart;
    private int activeStonecutterOpeningsAtStart;
    private boolean stonecuttingDrainStopInjected, stonecuttingDrainStopAttempted;
    private int stonecuttingDrainInputCountAtStop;
    private int stonecuttingDrainStopClientTick = -1, stonecuttingDrainStopServerTick = -1;
    private long observationRequestSequence, latestObservationRequestSequence;
    private long stonecuttingDrainRequiredObservationSequence = -1;
    private GameCatalog ironPickaxeProbeCatalog;
    private JsonObject ironPickaxePlannerProbeEvidence;
    private int ironPickaxeProbeStartedAtTick = -1;
    private boolean ironPickaxeProbeStarted, ironPickaxeProbeFinished;
    private volatile int serverStonecutterOpenings;
    private Map<String, Integer> activeInitialResources = Map.of();
    private int foodBreadCountBeforeSetup;
    private String failure = "";
    private volatile boolean serverTableOpened, serverFurnaceOpened;
    private volatile int serverTableOpenings;
    private volatile int serverFurnaceOpenings;
    private volatile boolean serverSmokerOpened, serverBlastFurnaceOpened;
    private volatile int serverSmokerOpenings, serverBlastFurnaceOpenings;
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
            if (COOKING_MODE && !isSupportedCookingStationMode()) {
                failure = "lodekeeper.verify.cookingStation must be exactly smoker or blast_furnace";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (STONECUTTING_DRAIN_MODE && !STONECUTTING_MODE) {
                failure = "lodekeeper.verify.stonecuttingDrain requires lodekeeper.verify.stonecutting=true";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if ((!COAL_START_SURFACE.equals("full") && !COAL_START_SURFACE.equals("dirt_path") && !COAL_START_SURFACE.equals("farmland"))
                    || (!COAL_START_SURFACE.equals("full") && !COAL_RECOVERY_MODE)) {
                state = State.FAILED;
                failure = "coalStartSurface must be full, dirt_path, or farmland; fractional surfaces require coalRecovery=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (NAVIGATION_COURSE != null && !MIXED_NAVIGATION_COURSE) {
                state = State.FAILED;
                failure = "navigationCourse must be exactly mixed when specified";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (MIXED_NAVIGATION_COURSE && !COAL_RECOVERY_MODE) {
                state = State.FAILED;
                failure = "navigationCourse=mixed requires coalRecovery=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (MIXED_NAVIGATION_COURSE && !COAL_START_SURFACE.equals("full")) {
                state = State.FAILED;
                failure = "navigationCourse=mixed requires coalStartSurface=full";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (MIXED_NAVIGATION_COURSE && !navigationMovementReflectionAvailable()) {
                state = State.FAILED;
                failure = "navigation course cannot inspect active validated route movement (expected AutomationEngine.movement and MovementController.path/pathIndex/validatedPathIndex)";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
            if (selectedFixtureModes() > 1) {
                failure = "lodekeeper.verify.exploration, lodekeeper.verify.diamondBoots, lodekeeper.verify.ironPickaxe, lodekeeper.verify.coalRecovery, lodekeeper.verify.bulkWood, lodekeeper.verify.cookingStation, and lodekeeper.verify.stonecutting are mutually exclusive; stonecuttingDrain is a stonecutting submode";
                state = State.FAILED;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.scheduleStop();
                return;
            }
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
            ClientTickEvents.END_CLIENT_TICK.register(mc -> observeCoalNavigationMovementAfterEngineTick());
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
                    if (handler instanceof net.minecraft.screen.FurnaceScreenHandler) {
                        serverFurnaceOpened = true;
                        serverFurnaceOpenings++;
                    }
                    if (handler instanceof SmokerScreenHandler) {
                        serverSmokerOpened = true;
                        serverSmokerOpenings++;
                    }
                    if (handler.getClass() == net.minecraft.screen.StonecutterScreenHandler.class) serverStonecutterOpenings++;
                    if (handler instanceof BlastFurnaceScreenHandler) {
                        serverBlastFurnaceOpened = true;
                        serverBlastFurnaceOpenings++;
                    }
                    lastServerScreenHandler = handler;
                }
                observeCoalNavigationCheckpoint(player, server.getTicks());
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
        if (clientTicks > MAX_RUN_TICKS || System.nanoTime() - startedAtNanos > MAX_RUN_WALL_NANOS) {
            fail(COOKING_MODE ? "verification exceeded the 500-second cooking-mode limit"
                : "verification exceeded the five-minute limit");
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
                    if (IRON_PICKAXE_MODE) startIronPickaxePlannerProbe();
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
                if (COAL_RECOVERY_MODE) {
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    boolean startingStockObserved = latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick
                        && latestSnapshot.health == 20.0F
                        && latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1))
                        && latestSnapshot.coalRecoveryEncasedOreRemaining == 1
                        && latestSnapshot.coalRecoveryAccessibleOreRemaining == 1
                        && latestSnapshot.coalStartSurfaceRemaining == 9
                        && (!MIXED_NAVIGATION_COURSE || (Math.abs(latestSnapshot.x - 0.25) < 0.0001
                            && Math.abs(latestSnapshot.z - 0.75) < 0.0001))
                        && (!MIXED_NAVIGATION_COURSE || (latestSnapshot.coalNavigationCourseMismatchCount == 0
                            && latestSnapshot.coalNavigationCourseObservedMask == 0))
                        && Math.abs(latestSnapshot.y - (COAL_START_SURFACE.equals("full") ? PLAYER_Y : PLAYER_Y - 0.0625)) < 0.0001;
                    if (startingStockObserved) {
                        if (++readyTicks >= 20 && client.player.getY() > PLAYER_Y - 1
                                && client.world.getBlockState(new BlockPos(0, FIXTURE_FLOOR_Y, 0)).isOf(coalStartFloor())) {
                            startCoalRecoveryCommand();
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (IRON_PICKAXE_MODE) {
                    if (clientTicks % OBSERVE_EVERY_TICKS == 0) requestObservation();
                    tickIronPickaxePlannerProbe();
                    if (state == State.FAILED) return;
                    boolean startingStockObserved = latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick
                        && latestSnapshot.health == 20.0F
                        && latestSnapshot.inventory.equals(Map.of("minecraft:crafting_table", 1));
                    if (startingStockObserved && ironPickaxeProbeFinished) {
                        if (++readyTicks >= 20 && client.player.getY() > PLAYER_Y - 1
                                && client.world.getBlockState(new BlockPos(0, FIXTURE_FLOOR_Y, 0)).isOf(Blocks.BEDROCK)) {
                            startIronPickaxeCommand();
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (PROCESSING_MODE) {
                    boolean startingResourcesObserved = latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick
                        && latestSnapshot.inventory.equals(cookingProvidedStock())
                        && latestSnapshot.count(cookingOutputId()) == 0;
                    if (startingResourcesObserved) {
                        readyTicks++;
                        if (readyTicks >= 20 && client.player.getY() > PLAYER_Y - 1
                                && client.world.getBlockState(new BlockPos(0, FIXTURE_FLOOR_Y, 0)).isOf(Blocks.BEDROCK)) {
                            startCookingCommand();
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick && latestSnapshot.inventoryEmpty()) {
                    readyTicks++;
                    boolean fixtureVisible = EXPLORATION_MODE
                        ? client.world.getBlockState(new BlockPos(0,FIXTURE_FLOOR_Y,0)).isOf(Blocks.BEDROCK)
                        : client.world.getBlockState(new BlockPos(6,PLAYER_Y,0)).isOf(Blocks.OAK_LOG);
                    if (readyTicks >= 20 && client.player.getY() > 63 && fixtureVisible) startGatherCommand();
                }
                return;
            }
            maybeInjectStonecuttingDrainStop();
            if (clientTicks % OBSERVE_EVERY_TICKS == 0
                    || (stonecuttingDrainStopInjected && requireEngine().status().startsWith("idle")
                        && latestObservationRequestSequence < stonecuttingDrainRequiredObservationSequence)) {
                requestObservation();
            }
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
        engine.config.searchRadius = BULK_WOOD_MODE ? 96 : 48;
        engine.config.scanBlocksPerTick = 512;
        engine.config.pathNodesPerTick = 128;
        engine.config.pathNodeLimit = 16_000;
        engine.config.pathMillisPerTick = 2;
        engine.config.actionTimeoutTicks = 1_200;
        engine.config.pauseBelowHealth = 6.0F;
        engine.config.allowBreaking = true;
        engine.config.allowBuilding = !MIXED_NAVIGATION_COURSE;
        engine.config.allowParkour = false;
        engine.config.autoEat = true;
        if (BULK_WOOD_MODE) engine.config.optimizeWoodTools = WOOD_TOOLS_MODE;
    }

    private void observeCoalNavigationCheckpoint(ServerPlayerEntity player, int serverTick) {
        if (!MIXED_NAVIGATION_COURSE || !coalNavigationCourseCommandStarted) return;
        coalNavigationCourseMinimumHealth = Math.min(coalNavigationCourseMinimumHealth, player.getHealth());
        if (!player.isOnGround()) return;
        int xCell = (int) Math.floor(player.getX());
        int zCell = (int) Math.floor(player.getZ());
        int feetY16 = (int) Math.round(player.getY() * 16.0);
        if (zCell != 0) return;
        for (int index = 0; index < COAL_NAVIGATION_CHECKPOINTS.size(); index++) {
            CoalNavigationCheckpoint checkpoint = COAL_NAVIGATION_CHECKPOINTS.get(index);
            if (xCell == checkpoint.xCell && feetY16 == checkpoint.feetY16) {
                coalNavigationCourseObservedMask |= 1 << index;
                if (coalNavigationCourseCheckpointServerTicks[index] < 0) {
                    coalNavigationCourseCheckpointServerTicks[index] = serverTick;
                }
            }
        }
    }

    private void observeCoalNavigationMovementAfterEngineTick() {
        if (state == State.FAILED || state == State.COMPLETE
                || !MIXED_NAVIGATION_COURSE || !coalNavigationCourseCommandStarted || client.player == null) return;
        try {
            Object movement = ENGINE_MOVEMENT_FIELD.get(requireEngine());
            dev.lodekeeper.nav.Path path = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
            if (path == null) return;
            int pathIndex = MOVEMENT_PATH_INDEX_FIELD.getInt(movement);
            int completedIndex = pathIndex - 1;
            int validatedPathIndex = MOVEMENT_VALIDATED_PATH_INDEX_FIELD.getInt(movement);
            if (completedIndex < 1 || completedIndex >= path.length() || validatedPathIndex != completedIndex
                    || !client.player.isOnGround()) return;

            dev.lodekeeper.nav.Path.Step source = path.step(completedIndex - 1);
            dev.lodekeeper.nav.Path.Step destination = path.step(completedIndex);
            int xCell = client.player.getBlockX();
            int zCell = client.player.getBlockZ();
            int feetY16 = (int) Math.round(client.player.getY() * 16.0);
            if (xCell != destination.x || zCell != 0 || feetY16 != destination.feetY16) return;

            if (source.x == 11 && source.z == 0 && source.feetY16 == 66 * 16
                    && destination.x == 12 && destination.z == 0 && destination.feetY16 == 67 * 16) {
                coalNavigationStairEdgeCompleted = true;
                coalNavigationStairEdgeMovement = destination.movement.name();
                coalNavigationStairEdgePathIndex = completedIndex;
            }
            if (source.x == 15 && source.z == 0 && source.feetY16 == 67 * 16
                    && destination.x == 16 && destination.z == 0 && destination.feetY16 == 68 * 16) {
                coalNavigationLedgeEdgeCompleted = true;
                coalNavigationLedgeEdgeMovement = destination.movement.name();
                coalNavigationLedgeEdgePathIndex = completedIndex;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("could not inspect completed active navigation edge: " + exception.getMessage());
        }
    }

    private static BlockPos coalRecoveryAccessibleOrePosition() {
        // Interaction candidates extend two cells: x18 forces the earliest valid stance
        // onto x16 at height 68, beyond the jump ledge. x17 could be mined from x15.
        return MIXED_NAVIGATION_COURSE ? new BlockPos(18, PLAYER_Y + 4, 0) : COAL_RECOVERY_ACCESSIBLE_ORE;
    }

    private Map<BlockPos, BlockState> setupMixedCoalNavigationCourse(ServerWorld world) {
        Map<BlockPos, BlockState> expected = new HashMap<>();
        for (int x = -12; x <= 18; x++) {
            for (int z = -6; z <= 6; z++) {
                boolean solidFloor = (x >= -1 && x <= 1 && z >= -1 && z <= 1)
                    || (x >= 1 && x <= 18 && z == 0) || (x == 5 && z == -2);
                setCourseBlock(world, expected, new BlockPos(x, FIXTURE_FLOOR_Y, z),
                    solidFloor ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState());
            }
        }
        for (int x = 1; x <= 16; x++) {
            for (int y = 64; y <= 70; y++) {
                for (int z : new int[]{-1, 1}) {
                    setCourseBlock(world, expected, new BlockPos(x, y, z), Blocks.BEDROCK.getDefaultState());
                }
            }
        }
        setCourseBlock(world, expected, new BlockPos(1, 64, 0),
            Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        setCourseBlock(world, expected, new BlockPos(2, 64, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(3, 64, 0), Blocks.BEDROCK.getDefaultState());
        setCourseBlock(world, expected, new BlockPos(4, 64, 0), Blocks.DIRT_PATH.getDefaultState());
        setCourseBlock(world, expected, new BlockPos(5, 64, 0), Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7));
        setCourseBlock(world, expected, new BlockPos(6, 64, 0),
            Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        setCourseBlock(world, expected, new BlockPos(7, 64, 0), bottomEastStairs());
        for (int x = 8; x <= 10; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 64, 0), Blocks.BEDROCK.getDefaultState());
        }
        setCourseBlock(world, expected, new BlockPos(8, 65, 0), Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 2));
        setCourseBlock(world, expected, new BlockPos(9, 65, 0), Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 4));
        setCourseBlock(world, expected, new BlockPos(10, 65, 0), Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, 8));
        setCourseBlock(world, expected, new BlockPos(11, 65, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(12, 66, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(13, 66, 0),
            Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        setCourseBlock(world, expected, new BlockPos(14, 66, 0), Blocks.DIRT_PATH.getDefaultState());
        for (int x = 15; x <= 18; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 66, 0), Blocks.BEDROCK.getDefaultState());
        }
        for (int x = 16; x <= 18; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 67, 0), Blocks.BEDROCK.getDefaultState());
        }

        // Catch randomized native ore drops after the required one-cell jump corridor.
        // A one-cell ledge lets items bounce off and fall 128 blocks to superflat ground.
        for (int x = 17; x <= 21; x++) for (int z = -2; z <= 2; z++) {
            setCourseBlock(world, expected, new BlockPos(x, 67, z), Blocks.BEDROCK.getDefaultState());
        }

        BlockPos water = new BlockPos(5, 64, -2);
        setCourseBlock(world, expected, water, Blocks.WATER.getDefaultState());
        for (BlockPos surround : List.of(water.up(), water.down(), water.north(), water.south(), water.east(), water.west())) {
            setCourseBlock(world, expected, surround, Blocks.BEDROCK.getDefaultState());
        }
        setCourseBlock(world, expected, new BlockPos(-8, 64, 0), Blocks.OAK_FENCE.getDefaultState());
        return expected;
    }

    private static BlockState bottomEastStairs() {
        return Blocks.STONE_BRICK_STAIRS.getDefaultState()
            .with(StairsBlock.FACING, Direction.EAST)
            .with(StairsBlock.HALF, BlockHalf.BOTTOM);
    }

    private static void setCourseBlock(ServerWorld world, Map<BlockPos, BlockState> expected, BlockPos position, BlockState state) {
        world.setBlockState(position, state, 3);
        expected.put(position, state);
    }

    private Map<BlockPos, BlockState> mixedCoalNavigationExpectedStates(Map<BlockPos, BlockState> course) {
        Map<BlockPos, BlockState> expected = new HashMap<>(course);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    expected.put(COAL_RECOVERY_ENCASED_ORE.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState());
                }
            }
        }
        expected.put(COAL_RECOVERY_ENCASED_ORE, Blocks.COAL_ORE.getDefaultState());
        return Map.copyOf(expected);
    }

    private int coalNavigationCourseMismatchCount(ServerWorld world) {
        int mismatches = 0;
        for (Map.Entry<BlockPos, BlockState> entry : coalNavigationExpectedStates.entrySet()) {
            if (!world.getBlockState(entry.getKey()).equals(entry.getValue())) mismatches++;
        }
        return mismatches;
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
                for (int x = -12; x <= (PROCESSING_MODE || BULK_WOOD_MODE ? 100 : EXPLORATION_MODE ? 96 : DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE ? 30 : 18); x++) {
                    for (int z = -6; z <= 6; z++) world.setBlockState(new BlockPos(x, FIXTURE_FLOOR_Y, z), Blocks.BEDROCK.getDefaultState(), 3);
                }
                if (COAL_RECOVERY_MODE) {
                    coalNavigationCourseCommandStarted = false;
                    coalNavigationCourseObservedMask = 0;
                    coalNavigationExpectedStates = Map.of();
                    for (int index = 0; index < coalNavigationCourseCheckpointServerTicks.length; index++) {
                        coalNavigationCourseCheckpointServerTicks[index] = -1;
                    }
                    Map<BlockPos, BlockState> mixedCourseStates = null;
                    if (MIXED_NAVIGATION_COURSE) {
                        mixedCourseStates = setupMixedCoalNavigationCourse(world);
                    } else {
                        if (COAL_START_SURFACE.equals("farmland")) {
                            world.setBlockState(new BlockPos(-3, FIXTURE_FLOOR_Y, 0), Blocks.WATER.getDefaultState(), 3);
                        }
                        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                            world.setBlockState(new BlockPos(x, FIXTURE_FLOOR_Y, z), coalStartFloor().getDefaultState(), 3);
                        }
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                world.setBlockState(COAL_RECOVERY_ENCASED_ORE.add(dx, dy, dz), Blocks.BEDROCK.getDefaultState(), 3);
                            }
                        }
                    }
                    world.setBlockState(COAL_RECOVERY_ENCASED_ORE, Blocks.COAL_ORE.getDefaultState(), 3);
                    world.setBlockState(coalRecoveryAccessibleOrePosition(), Blocks.COAL_ORE.getDefaultState(), 3);
                    coalNavigationExpectedStates = MIXED_NAVIGATION_COURSE
                        ? mixedCoalNavigationExpectedStates(mixedCourseStates) : Map.of();
                } else if (!PROCESSING_MODE) {
                    for (int index = 0; index < (BULK_WOOD_MODE ? 80 : 8); index++) {
                        world.setBlockState(new BlockPos((BULK_WOOD_MODE ? 6 : EXPLORATION_MODE ? 80 : 6) + index, PLAYER_Y, 0), Blocks.OAK_LOG.getDefaultState(), 3);
                    }
                    if (!BULK_WOOD_MODE) {
                        for (int x = 6; x <= (DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE ? 25 : 17); x++) {
                            world.setBlockState(new BlockPos(x, PLAYER_Y, 2), Blocks.STONE.getDefaultState(), 3);
                        }
                        if (DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE) {
                            world.setBlockState(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(17, PLAYER_Y, 4), Blocks.COAL_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(18, PLAYER_Y, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(16, PLAYER_Y + 1, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(17, PLAYER_Y + 1, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            for (int x = 20; x <= 23; x++) {
                                world.setBlockState(new BlockPos(x, PLAYER_Y, 4), Blocks.DIAMOND_ORE.getDefaultState(), 3);
                            }
                            if (IRON_PICKAXE_MODE) {
                                for (int index = 0; index < IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT; index++) {
                                    world.setBlockState(new BlockPos(16 + index, 20, 4), Blocks.DEEPSLATE.getDefaultState(), 3);
                                }
                            }
                        } else {
                            world.setBlockState(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.getDefaultState(), 3);
                            world.setBlockState(new BlockPos(17, PLAYER_Y, 4), Blocks.IRON_ORE.getDefaultState(), 3);
                            Block rubyOre = Registries.BLOCK.get(GameApi.identifier(VerificationContentInitializer.RUBY_ORE_ID));
                            if (rubyOre == Blocks.AIR) throw new IllegalStateException("verifier ruby ore was not registered");
                            for (int index = 0; index < 4; index++) {
                                world.setBlockState(new BlockPos(8 + index, PLAYER_Y, 4), rubyOre.getDefaultState(), 3);
                            }
                        }
                    }
                }
                clearInventory(player.getInventory());
                if (PROCESSING_MODE) seedCookingInventory(player);
                if (COAL_RECOVERY_MODE && !player.getInventory().insertStack(new ItemStack(Items.STONE_PICKAXE))) {
                    throw new IllegalStateException("could not seed the single coal-recovery verifier stone pickaxe");
                }
                if (IRON_PICKAXE_MODE && !player.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE))) {
                    throw new IllegalStateException("could not seed the single iron-pickaxe verifier crafting table");
                }
                player.setHealth(player.getMaxHealth());
                player.getHungerManager().setFoodLevel(20);
                if (!VerificationApi.teleport(player, world, MIXED_NAVIGATION_COURSE ? 0.25 : 0.5, PLAYER_Y, MIXED_NAVIGATION_COURSE ? 0.75 : 0.5, 0.0F, 0.0F)) {
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

    private static boolean isSupportedCookingStationMode() {
        return "smoker".equals(COOKING_STATION_MODE) || "blast_furnace".equals(COOKING_STATION_MODE);
    }

    private static Item cookingRawItem() {
        return STONECUTTING_MODE ? Items.STONE : "smoker".equals(COOKING_STATION_MODE) ? Items.PORKCHOP : Items.RAW_IRON;
    }

    private static Item cookingStationItem() {
        return STONECUTTING_MODE ? Items.STONECUTTER : "smoker".equals(COOKING_STATION_MODE) ? Items.SMOKER : Items.BLAST_FURNACE;
    }

    private static String cookingRawItemId() {
        return STONECUTTING_MODE ? "minecraft:stone" : "smoker".equals(COOKING_STATION_MODE) ? RAW_PORKCHOP_ID : RAW_IRON_ID;
    }

    private static String cookingOutputId() {
        return STONECUTTING_MODE ? "minecraft:stone_slab" : "smoker".equals(COOKING_STATION_MODE) ? COOKED_PORKCHOP_ID : IRON_INGOT_ID;
    }

    private static String cookingRecipeType() {
        if (STONECUTTING_MODE) return "stonecutting";
        return switch (COOKING_STATION_MODE == null ? "" : COOKING_STATION_MODE) {
            case "smoker" -> "smoking";
            case "blast_furnace" -> "blasting";
            default -> "unsupported";
        };
    }

    private static String cookingOutputCommandName() {
        return STONECUTTING_MODE ? "stone_slab" : "smoker".equals(COOKING_STATION_MODE) ? "cooked_porkchop" : "iron_ingot";
    }

    private static Map<String, Integer> cookingProvidedStock() {
        if (STONECUTTING_MODE) return Map.of("minecraft:stone", 128, "minecraft:stonecutter", 1);
        return Map.of(cookingRawItemId(), 128, "minecraft:coal", 9,
            "minecraft:" + PROCESSING_STATION_MODE, 1);
    }

    private static void seedCookingInventory(ServerPlayerEntity player) {
        for (int stack = 0; stack < 2; stack++) {
            if (!player.getInventory().insertStack(new ItemStack(cookingRawItem(), 64))) {
                throw new IllegalStateException("could not seed two 64-item raw cooking stacks");
            }
        }
        if (!STONECUTTING_MODE && !player.getInventory().insertStack(new ItemStack(Items.COAL, 9))) {
            throw new IllegalStateException("could not seed nine verifier coal");
        }
        if (!player.getInventory().insertStack(new ItemStack(cookingStationItem(), 1))) {
            throw new IllegalStateException("could not seed the verifier cooking station item");
        }
    }

    private void startCookingCommand() {
        activeCase = STONECUTTING_DRAIN_MODE ? "native_stonecutter_drain_after_insertion"
            : STONECUTTING_MODE ? "native_stonecutter_144_slabs" : "native_" + COOKING_STATION_MODE + "_72";
        activeItem = cookingOutputId();
        activeCount = STONECUTTING_DRAIN_MODE ? 0 : STONECUTTING_MODE ? 144 : 72;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        beginCaseClock();
        sendCommand("!lk get " + cookingOutputCommandName() + " "
            + (STONECUTTING_DRAIN_MODE ? STONECUTTING_DRAIN_COMMAND_TARGET : activeCount));
        state = State.COOKING;
    }

    private void startIronPickaxePlannerProbe() {
        if (ironPickaxeProbeStarted) return;
        ironPickaxeProbeStarted = true;
        ironPickaxeProbeStartedAtTick = clientTicks;
        ironPickaxeProbeCatalog = new GameCatalog(client);
        ironPickaxePlannerProbeEvidence = new JsonObject();
        ironPickaxePlannerProbeEvidence.addProperty("status", "loading");
        ironPickaxePlannerProbeEvidence.addProperty("planningPreferences", "none");
        ironPickaxePlannerProbeEvidence.addProperty("isGameplayEvidence", false);
        ironPickaxePlannerProbeEvidence.addProperty("inventory", "minecraft:crafting_table x1 only");
        try {
            ironPickaxeProbeCatalog.load();
        } catch (RuntimeException exception) {
            completeIronPickaxeProbeUnavailable("catalog load failed: " + exception);
        }
    }

    private void tickIronPickaxePlannerProbe() {
        if (!IRON_PICKAXE_MODE || !ironPickaxeProbeStarted || ironPickaxeProbeFinished) return;
        if (ironPickaxeProbeCatalog != null && ironPickaxeProbeCatalog.ready()) {
            try {
                CatalogSnapshot catalogSnapshot = ironPickaxeProbeCatalog.snapshot();
                InventorySnapshot inventory = new InventorySnapshot(Map.of(ItemId.parse("minecraft:crafting_table"), 1));
                AcquisitionPlanner planner = new AcquisitionPlanner();
                PlanResult ironPickaxe = planner.planFast(catalogSnapshot, inventory,
                    ItemId.parse("minecraft:iron_pickaxe"), 1);
                PlanResult stonePickaxe = planner.planFast(catalogSnapshot, inventory,
                    ItemId.parse("minecraft:stone_pickaxe"), 1);
                ironPickaxePlannerProbeEvidence = ironPickaxePlannerEvidence(catalogSnapshot, inventory, ironPickaxe, stonePickaxe);
                ironPickaxeProbeFinished = true;
                writeIronPickaxeCatalogSnapshot(catalogSnapshot);
                logPlannerProbeResult("iron_pickaxe", ironPickaxe);
                logPlannerProbeResult("stone_pickaxe", stonePickaxe);
            } catch (RuntimeException exception) {
                completeIronPickaxeProbeUnavailable("planner probe failed: " + exception);
            }
        } else if (clientTicks - ironPickaxeProbeStartedAtTick >= IRON_PICKAXE_PROBE_TIMEOUT_TICKS) {
            completeIronPickaxeProbeUnavailable("catalog was not ready within "
                + IRON_PICKAXE_PROBE_TIMEOUT_TICKS + " client ticks");
        }
    }

    private void completeIronPickaxeProbeUnavailable(String reason) {
        if (ironPickaxePlannerProbeEvidence == null) ironPickaxePlannerProbeEvidence = new JsonObject();
        ironPickaxePlannerProbeEvidence.addProperty("status", "unavailable");
        ironPickaxePlannerProbeEvidence.addProperty("failure", reason);
        ironPickaxePlannerProbeEvidence.addProperty("planningPreferences", "none");
        ironPickaxePlannerProbeEvidence.addProperty("isGameplayEvidence", false);
        ironPickaxeProbeFinished = true;
        System.err.println("[Lodekeeper verification] Iron-pickaxe planner probe unavailable: " + reason);
    }

    private JsonObject ironPickaxePlannerEvidence(CatalogSnapshot catalogSnapshot, InventorySnapshot inventory,
                                                   PlanResult ironPickaxe, PlanResult stonePickaxe) {
        JsonObject probe = new JsonObject();
        probe.addProperty("status", "complete");
        probe.addProperty("catalogReady", true);
        probe.addProperty("catalogGeneration", ironPickaxeProbeCatalog.generation());
        probe.addProperty("planningPreferences", "none");
        probe.addProperty("isGameplayEvidence", false);
        JsonObject inventoryItems = new JsonObject();
        inventory.counts().forEach((item, count) -> inventoryItems.addProperty(item.toString(), count));
        probe.add("inventoryCounts", inventoryItems);
        probe.add("ironPickaxe", plannerResultEvidence(ironPickaxe));
        probe.add("stonePickaxe", plannerResultEvidence(stonePickaxe));
        return probe;
    }

    private void writeIronPickaxeCatalogSnapshot(CatalogSnapshot catalogSnapshot) {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject export = new JsonObject();
        export.addProperty("format", "lodekeeper.catalog_snapshot.v1");
        export.addProperty("minecraftVersion", VerificationApi.minecraftVersion());
        export.addProperty("catalogGeneration", ironPickaxeProbeCatalog.generation());
        JsonArray items = new JsonArray();
        ironPickaxeProbeCatalog.items.stream().sorted().forEach(item -> items.add(item.toString()));
        export.add("items", items);
        JsonArray definitions = new JsonArray();
        catalogSnapshot.itemDefinitions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject definition = new JsonObject();
            definition.addProperty("item", entry.getKey().toString());
            definition.addProperty("maximumDurability", entry.getValue().maximumDurability());
            definition.addProperty("fuelBurnTicks", entry.getValue().fuelBurnTicks());
            JsonArray aliases = new JsonArray();
            entry.getValue().aliases().forEach(aliases::add);
            definition.add("aliases", aliases);
            definitions.add(definition);
        });
        export.add("itemDefinitions", definitions);
        JsonArray tags = new JsonArray();
        ironPickaxeProbeCatalog.tags.keySet().stream().sorted().forEach(tag -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("tag", tag.toString());
            JsonArray members = new JsonArray();
            ironPickaxeProbeCatalog.tags.get(tag).stream().sorted().forEach(item -> members.add(item.toString()));
            entry.add("items", members);
            tags.add(entry);
        });
        export.add("tags", tags);
        JsonArray sources = new JsonArray();
        ironPickaxeProbeCatalog.sources.stream().sorted((left, right) -> left.sourceId().compareTo(right.sourceId())).forEach(source -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("sourceId", source.sourceId());
            entry.addProperty("sourceType", source.sourceType());
            entry.addProperty("output", source.output().toString());
            entry.addProperty("outputCount", source.outputCount());
            entry.addProperty("definition", source.toString());
            entry.addProperty("definitionType", source.getClass().getSimpleName());
            entry.add("definitionJson", gson.toJsonTree(source));
            sources.add(entry);
        });
        export.add("sources", sources);
        JsonArray unsupported = new JsonArray();
        ironPickaxeProbeCatalog.unsupported.forEach(unsupported::add);
        export.add("unsupported", unsupported);
        String fileName = "iron-pickaxe-catalog-" + runId + ".json";
        try {
            Files.writeString(evidenceDirectory.resolve(fileName),
                gson.toJson(export), StandardCharsets.UTF_8);
            ironPickaxePlannerProbeEvidence.addProperty("catalogSnapshotFile", fileName);
            ironPickaxePlannerProbeEvidence.addProperty("knownItemCount", ironPickaxeProbeCatalog.items.size());
            ironPickaxePlannerProbeEvidence.addProperty("sourceCount", ironPickaxeProbeCatalog.sources.size());
            ironPickaxePlannerProbeEvidence.addProperty("tagCount", ironPickaxeProbeCatalog.tags.size());
        } catch (IOException exception) {
            ironPickaxePlannerProbeEvidence.addProperty("catalogExportFailure", exception.toString());
        }
    }

    private static JsonObject plannerResultEvidence(PlanResult result) {
        JsonObject plan = new JsonObject();
        plan.addProperty("target", result.target().toString());
        plan.addProperty("requestedCount", result.requestedCount());
        plan.addProperty("success", result.success());
        plan.addProperty("optimal", result.optimal());
        plan.addProperty("expandedNodes", result.expandedNodes());
        plan.addProperty("elapsedNanos", result.elapsedNanos());
        JsonArray blockedReasons = new JsonArray();
        for (BlockedReason reason : result.blockedReasons()) {
            JsonObject item = new JsonObject();
            item.addProperty("code", reason.code().name());
            if (reason.item() != null) item.addProperty("item", reason.item().toString());
            item.addProperty("detail", reason.detail());
            JsonArray path = new JsonArray();
            reason.dependencyPath().forEach(dependency -> path.add(dependency.toString()));
            item.add("dependencyPath", path);
            blockedReasons.add(item);
        }
        plan.add("blockedReasons", blockedReasons);
        JsonArray steps = new JsonArray();
        for (PlanStep step : result.steps()) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", step.kind().name());
            item.addProperty("sourceId", step.sourceId());
            if (step.output() != null) item.addProperty("output", step.output().toString());
            item.addProperty("outputCount", step.outputCount());
            item.addProperty("operationCount", step.operationCount());
            if (step.station() != null) item.addProperty("station", step.station().toString());
            JsonArray requirements = new JsonArray();
            step.requirements().forEach(requirement -> requirements.add(requirement.toString()));
            item.add("requirements", requirements);
            steps.add(item);
        }
        plan.add("steps", steps);
        return plan;
    }

    private static void logPlannerProbeResult(String name, PlanResult result) {
        System.out.println("[Lodekeeper verification] Planner probe " + name + " success=" + result.success()
            + ", expandedNodes=" + result.expandedNodes() + ", elapsedNanos=" + result.elapsedNanos()
            + ", blockedReasons=" + result.blockedReasons());
        result.blockedReasons().forEach(reason -> System.err.println("[Lodekeeper verification] Planner probe "
            + name + " blocked " + reason.code() + " at " + reason.item() + ": " + reason.detail()
            + " path=" + reason.dependencyPath()));
    }

    private void startIronPickaxeCommand() {
        activeCase = "iron_pickaxe_from_crafting_table_only";
        activeItem = IRON_PICKAXE_ID;
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        beginCaseClock();
        sendCommand("!lk get iron_pickaxe");
        state = State.GATHERING_WOOD;
    }

    private void maybeInjectStonecuttingDrainStop() {
        if (!STONECUTTING_DRAIN_MODE || state != State.COOKING || stonecuttingDrainStopAttempted
                || client.player == null || client.player.currentScreenHandler == null
                || client.player.currentScreenHandler.getClass() != net.minecraft.screen.StonecutterScreenHandler.class) return;
        net.minecraft.screen.StonecutterScreenHandler handler =
            (net.minecraft.screen.StonecutterScreenHandler) client.player.currentScreenHandler;
        ItemStack ownedInput = handler.getSlot(0).getStack();
        if (ownedInput.isEmpty() || !ownedInput.isOf(Items.STONE) || !handler.getCursorStack().isEmpty()) return;
        if (ClientAccess.main(client.player.getInventory()).stream().anyMatch(stack -> stack.isOf(Items.STONE_SLAB))) {
            fail("stonecutting drain stop was not injected before the first slab output");
            return;
        }

        stonecuttingDrainStopAttempted = true;
        stonecuttingDrainInputCountAtStop = ownedInput.getCount();
        stonecuttingDrainStopClientTick = clientTicks;
        stonecuttingDrainStopServerTick = latestSnapshot == null ? -1 : latestSnapshot.serverTick;
        stonecuttingDrainRequiredObservationSequence = observationRequestSequence + 1;
        sendCommand("!lk stop");
        stonecuttingDrainStopInjected = true;
        System.out.println("[Lodekeeper verification] Injected !lk stop with " + stonecuttingDrainInputCountAtStop
            + " owned stone in the native stonecutter at client tick " + stonecuttingDrainStopClientTick);
    }

    private void startGatherCommand() {
        if (BULK_WOOD_MODE) {
            activeCase = "bulk_wood_64";
            activeItem = OAK_LOG_ID;
            activeCount = 64;
            activeRequiresEmpty = true;
            activeStartedEmpty = latestSnapshot.inventoryEmpty();
            beginCaseClock();
            sendCommand("!lk get wood 64");
            state = State.GATHERING_WOOD;
            return;
        }
        if (DIAMOND_BOOTSTRAP_MODE) {
            activeCase = "bootstrap_diamond_boots";
            activeItem = "minecraft:diamond_boots";
            activeCount = 1;
            activeRequiresEmpty = true;
            activeStartedEmpty = latestSnapshot.inventoryEmpty();
            beginCaseClock();
            sendCommand("!lk get diamond_boots");
            state = State.GATHERING_WOOD;
            return;
        }
        activeCase = EXPLORATION_MODE ? "explore_to_unloaded_wood_8" : "gather_wood_8";
        if (EXPLORATION_MODE) {
            resourceInitiallyLoaded = client.world.getChunkManager().getChunk(5,0,
                net.minecraft.world.chunk.ChunkStatus.FULL,false) != null;
            if (resourceInitiallyLoaded) { fail("far resource chunk was already loaded at goal start"); return; }
            if (!requireEngine().config.allowExploration) { fail("exploration policy was disabled in verifier config"); return; }
        }
        activeItem = "minecraft:oak_log";
        activeCount = 8;
        activeRequiresEmpty = true;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        beginCaseClock();
        sendCommand("!lk get wood 8");
        state = State.GATHERING_WOOD;
    }

    private boolean verifyFenceGeometry() {
        GameTerrain terrain = new GameTerrain(client, requireEngine().config);
        terrain.beginSearch();
        dev.lodekeeper.nav.StanceProbe probe = new dev.lodekeeper.nav.StanceProbe();
        dev.lodekeeper.nav.StanceProbe empty = new dev.lodekeeper.nav.StanceProbe().clear();
        BlockPos fence = new BlockPos(-8, 64, 0);
        terrain.probeStance(-8, 65, 0, probe);
        boolean loadedFence = probe.loaded && client.world.getBlockState(fence).isOf(Blocks.OAK_FENCE);
        boolean bodyBlocked = loadedFence && !probe.bodyClear;
        boolean pointBlocked = !terrain.isMotionClear(-7.5, 65, 0.5, -7.5, 65, 0.5, 0.0, empty);
        boolean sweepBlocked = !terrain.isMotionClear(-6.5, 65, 0.5, -7.5, 65, 0.5, 0.0, empty);
        coalNavigationFenceGeometryEvidence = new JsonObject();
        coalNavigationFenceGeometryEvidence.addProperty("block", "minecraft:oak_fence");
        coalNavigationFenceGeometryEvidence.addProperty("position", "-8,64,0");
        coalNavigationFenceGeometryEvidence.addProperty("queryFeetY", 65);
        coalNavigationFenceGeometryEvidence.addProperty("loadedFenceObserved", loadedFence);
        coalNavigationFenceGeometryEvidence.addProperty("belowFeetCollisionBlockedStanceBody", bodyBlocked);
        coalNavigationFenceGeometryEvidence.addProperty("belowFeetCollisionBlockedPoint", pointBlocked);
        coalNavigationFenceGeometryEvidence.addProperty("belowFeetCollisionBlockedSweep", sweepBlocked);
        boolean verified = loadedFence && bodyBlocked && pointBlocked && sweepBlocked;
        coalNavigationFenceGeometryEvidence.addProperty("verified", verified);
        if (!verified) fail("Native below-feet fence geometry preflight failed: " + coalNavigationFenceGeometryEvidence);
        return verified;
    }

    private static Object readTerrainDiagnosticField(GameTerrain terrain, String name) {
        try {
            Field field = GameTerrain.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(terrain);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect native navigation diagnostic " + name, failure);
        }
    }

    private void recordMixedNavigationDiagnostics() {
        GameTerrain terrain = new GameTerrain(client, requireEngine().config);
        terrain.beginSearch();
        int[] heights = {1024, 1032, 1040, 1040, 1039, 1039, 1032, 1040,
            1042, 1046, 1054, 1056, 1072, 1072, 1071, 1072, 1088};
        dev.lodekeeper.nav.StanceProbe previous = new dev.lodekeeper.nav.StanceProbe();
        dev.lodekeeper.nav.StanceProbe current = new dev.lodekeeper.nav.StanceProbe();
        dev.lodekeeper.nav.GroundedStanceBuffer candidates = new dev.lodekeeper.nav.GroundedStanceBuffer();
        coalNavigationRouteDiagnostics = new JsonArray();
        for (int x = 0; x < heights.length; x++) {
            terrain.probeStance16(x, heights[x], 0, current);
            JsonObject row = new JsonObject();
            row.addProperty("x", x);
            row.addProperty("feetY16", heights[x]);
            row.addProperty("loaded", current.loaded);
            row.addProperty("bodyClear", current.bodyClear);
            row.addProperty("supported", current.hasGroundSupport());
            row.addProperty("hazard", current.hazard);
            row.addProperty("breakCount", current.breakCount);
            terrain.collectGroundedStances(x, heights[x], 0, candidates);
            JsonArray candidateArray = new JsonArray();
            for (int i = 0; i < candidates.size(); i++) candidateArray.add(candidates.get(i));
            row.add("candidateFeetY16", candidateArray);
            row.addProperty("candidatesComplete", candidates.isComplete());
            if (x > 0) {
                int[] resolved = (int[]) readTerrainDiagnosticField(terrain, "walkResolvedHeights");
                java.util.Arrays.fill(resolved, Integer.MIN_VALUE);
                boolean clear = terrain.isGroundedWalkClear(x - 0.5, heights[x - 1], 0.5,
                    x + 0.5, heights[x], 0.5, previous, current);
                row.addProperty("incomingGroundedWalkClear", clear);
                if (!clear) {
                    dev.lodekeeper.nav.MotionEventBuffer events =
                        (dev.lodekeeper.nav.MotionEventBuffer) readTerrainDiagnosticField(terrain, "walkEvents");
                    int count = events.size();
                    JsonArray profile = new JsonArray();
                    for (int i = 0; i < count; i++) {
                        JsonObject point = new JsonObject();
                        point.addProperty("t", events.get(i));
                        point.addProperty("resolvedY16", resolved[i]);
                        profile.add(point);
                    }
                    row.add("failedWalkProfile", profile);
                    row.addProperty("shapeIncomplete", (Boolean) readTerrainDiagnosticField(terrain, "shapeIncomplete"));
                }
            }
            if (x == 1) row.addProperty("actualStartGroundedWalkClear",
                terrain.isGroundedWalkClear(client.player.getX(), heights[0], client.player.getZ(),
                    1.5, heights[1], 0.5, previous, current));
            coalNavigationRouteDiagnostics.add(row);
            terrain.probeStance16(x, heights[x], 0, previous);
        }
        System.out.println("[Lodekeeper verification] Native mixed route diagnostics: " + coalNavigationRouteDiagnostics);
    }

    private void startCoalRecoveryCommand() {
        if (MIXED_NAVIGATION_COURSE && !verifyFenceGeometry()) return;
        if (MIXED_NAVIGATION_COURSE) recordMixedNavigationDiagnostics();
        coalInitialServerFeetY = latestSnapshot.y;
        coalNavigationInitialServerFeetX = latestSnapshot.x;
        coalNavigationInitialServerFeetZ = latestSnapshot.z;
        coalNavigationCourseMinimumHealth = latestSnapshot.health;
        coalNavigationCourseObservedMask = 0;
        coalNavigationStairEdgeCompleted = false;
        coalNavigationStairEdgeMovement = "unobserved";
        coalNavigationStairEdgePathIndex = -1;
        coalNavigationLedgeEdgeCompleted = false;
        coalNavigationLedgeEdgeMovement = "unobserved";
        coalNavigationLedgeEdgePathIndex = -1;
        activeCase = MIXED_NAVIGATION_COURSE ? "coal_recovery_mixed_navigation_course"
            : "coal_recovery_" + COAL_START_SURFACE + "_reject_encased_nearer_resource";
        activeItem = "minecraft:coal";
        activeCount = 1;
        activeRequiresEmpty = false;
        activeStartedEmpty = false;
        beginCaseClock();
        coalNavigationCourseCommandStarted = MIXED_NAVIGATION_COURSE;
        sendCommand("!lk get coal");
        state = State.GATHERING_COAL_RECOVERY;
    }

    private static Block coalStartFloor() {
        return switch (COAL_START_SURFACE) {
            case "dirt_path" -> Blocks.DIRT_PATH;
            case "farmland" -> Blocks.FARMLAND;
            default -> Blocks.BEDROCK;
        };
    }

    private static int countCoalStartSurface(ServerWorld world) {
        int count = 0;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (world.getBlockState(new BlockPos(x, FIXTURE_FLOOR_Y, z)).isOf(coalStartFloor())) count++;
        }
        return count;
    }

    private boolean coalRecoveryTargetRejected() {
        return LodekeeperClient.engine != null && LodekeeperClient.engine.resourceRejected(COAL_RECOVERY_ENCASED_ORE);
    }

    private boolean coalRecoveryOutcomeObserved() {
        return latestSnapshot != null
            && latestSnapshot.inventory.equals(Map.of("minecraft:stone_pickaxe", 1, "minecraft:coal", 1))
            && latestSnapshot.health == 20.0F
            && latestSnapshot.coalRecoveryEncasedOreRemaining == 1
            && latestSnapshot.coalRecoveryAccessibleOreRemaining == 0
            && latestSnapshot.coalStartSurfaceRemaining == 9
            && (!MIXED_NAVIGATION_COURSE || (coalNavigationFenceGeometryEvidence != null
                && coalNavigationFenceGeometryEvidence.get("verified").getAsBoolean()
                && latestSnapshot.coalNavigationCourseMismatchCount == 0
                && latestSnapshot.coalNavigationCourseObservedMask == coalNavigationExpectedMask()
                && latestSnapshot.coalNavigationCourseMinimumHealth == 20.0F
                && coalNavigationStairEdgeCompleted && "WALK".equals(coalNavigationStairEdgeMovement)
                && coalNavigationLedgeEdgeCompleted && "JUMP".equals(coalNavigationLedgeEdgeMovement)))
            && coalRecoveryTargetRejected()
            && requireEngine().status().startsWith("idle");
    }

    private static int coalNavigationExpectedMask() {
        return (1 << COAL_NAVIGATION_CHECKPOINTS.size()) - 1;
    }

    private static int[] newCoalNavigationCheckpointTicks() {
        int[] ticks = new int[COAL_NAVIGATION_CHECKPOINTS.size()];
        java.util.Arrays.fill(ticks, -1);
        return ticks;
    }

    private List<Integer> coalNavigationCheckpointServerTicksSnapshot() {
        List<Integer> ticks = new ArrayList<>(coalNavigationCourseCheckpointServerTicks.length);
        for (int tick : coalNavigationCourseCheckpointServerTicks) ticks.add(tick);
        return List.copyOf(ticks);
    }

    private void evaluateCurrentCase() {
        if (activeCase == null || state == State.CAPTURING) return;
        if (latestSnapshot == null) return;
        if (STONECUTTING_DRAIN_MODE && state == State.COOKING && !stonecuttingDrainStopInjected
                && latestSnapshot.count(cookingOutputId()) > 0) {
            fail("stonecutting drain stop was not injected before the first slab output");
            return;
        }
        int observed = latestSnapshot.count(activeItem);
        boolean targetReached = (COAL_RECOVERY_MODE || BULK_WOOD_MODE || PROCESSING_MODE || IRON_PICKAXE_MODE ? observed == activeCount : observed >= activeCount)
            && requireEngine().status().startsWith("idle")
            && (state != State.CRAFTING_WOOD_PICK || serverTableOpened)
            && (state != State.SMELTING_IRON || serverFurnaceOpened)
            && (!PROCESSING_MODE || (state == State.COOKING
                && latestSnapshot.health == 20.0F
                && correctCookingStationMenuOpened()
                && (STONECUTTING_DRAIN_MODE ? stonecuttingDrainOutcomeObserved()
                    : latestSnapshot.count(cookingRawItemId()) == 56
                        && latestSnapshot.count("minecraft:coal") == 0
                        && (!STONECUTTING_MODE || latestSnapshot.inventory.equals(
                            Map.of("minecraft:stone", 56, "minecraft:stone_slab", 144))))))
            && (!DIAMOND_BOOTSTRAP_MODE || (state == State.GATHERING_WOOD
                && serverTableOpened && serverTableOpenings > activeTableOpeningsAtStart
                && serverFurnaceOpened && serverFurnaceOpenings > activeFurnaceOpeningsAtStart
                && latestSnapshot.count(IRON_PICKAXE_ID) >= 1))
            && (!IRON_PICKAXE_MODE || (state == State.GATHERING_WOOD
                && latestSnapshot.health == 20.0F
                && serverTableOpened && serverTableOpenings > activeTableOpeningsAtStart
                && serverFurnaceOpened && serverFurnaceOpenings > activeFurnaceOpeningsAtStart))
            && (!COAL_RECOVERY_MODE || coalRecoveryOutcomeObserved())
            && (!BULK_WOOD_MODE || (latestSnapshot.count(OAK_LOG_ID) == 64
                && (WOOD_TOOLS_MODE
                    ? serverTableOpened && serverTableOpenings > activeTableOpeningsAtStart
                        && latestSnapshot.count(WOODEN_AXE_ID) >= 2
                    : !serverTableOpened && serverTableOpenings == 0
                        && latestSnapshot.count(WOODEN_AXE_ID) == 0)))
            && (state != State.CUSTOM_CONTENT || serverTableOpenings > activeTableOpeningsAtStart);
        if (targetReached && EXPLORATION_MODE && state == State.GATHERING_WOOD
                && (requireEngine().explorationAttemptsMade() == 0 || latestSnapshot.x <= 48)) {
            fail("far resource goal completed without observed bounded exploration travel"); return;
        }
        if (targetReached && state == State.GATHERING_FOOD
            && (latestSnapshot.foodLevel <= activeFoodLevelAtStart
                || latestSnapshot.count(VerificationContentInitializer.BREAD_ID) >= activeBreadCountAtStart)) {
            fail("food-use case reached its log target without server-confirmed bread consumption and hunger recovery");
        } else if (targetReached) {
            String screenshot = capture(activeCase);
            String detail = STONECUTTING_DRAIN_MODE
                ? "sent ordinary !lk stop at client tick " + stonecuttingDrainStopClientTick + " with "
                    + stonecuttingDrainInputCountAtStop + " owned stone in the native stonecutter; fresh server tick "
                    + latestSnapshot.serverTick + " observed exact starting stock restored with zero slabs (command target 144, expected output 0)"
                : PROCESSING_MODE
                ? "server inventory reached " + activeCount + " " + cookingOutputId() + " with 56 inputs remaining after opening the native " + PROCESSING_STATION_MODE + " menu"
                : BULK_WOOD_MODE
                ? (WOOD_TOOLS_MODE
                    ? "server inventory reached exactly 64 oak logs after opening the crafting table and acquiring at least two wooden axes"
                    : "server inventory reached exactly 64 oak logs with no wooden axes or crafting table opening")
                : IRON_PICKAXE_MODE
                ? "server inventory reached one iron pickaxe at full health after native crafting-table and furnace menu openings"
                : COAL_RECOVERY_MODE
                ? "server inventory reached exactly one coal at full health after rejecting the nearer encased ore and mining the accessible ore"
                : DIAMOND_BOOTSTRAP_MODE
                ? "server inventory reached diamond boots after the integrated server observed crafting table and furnace menus and an iron pickaxe"
                : switch (state) {
                case CUSTOM_CONTENT -> "server inventory reached the custom recipe output after opening the server crafting table";
                case GATHERING_FOOD -> "server inventory reached the log target; one bread was consumed and hunger rose from "
                    + activeFoodLevelAtStart + " to " + latestSnapshot.foodLevel;
                default -> "server inventory reached target and engine returned idle";
            };
            addResult(true, observed, detail, screenshot);
            if (state == State.GATHERING_WOOD && (EXPLORATION_MODE || DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE || BULK_WOOD_MODE)) {
                state = State.CAPTURING; captureStartedAtTick = clientTicks;
            } else if (state == State.GATHERING_WOOD) {
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
        } else if (COAL_RECOVERY_MODE && state == State.GATHERING_COAL_RECOVERY && observed >= activeCount
                && requireEngine().status().startsWith("idle") && !coalRecoveryOutcomeObserved()) {
            fail("coal output was observed without the required encased-target rejection and accessible-ore fixture proof");
        } else if (requireEngine().status().startsWith("paused")) {
            fail(STONECUTTING_DRAIN_MODE && !stonecuttingDrainStopInjected
                ? "stonecutting drain stop was never injected before automation paused: " + requireEngine().status()
                : "automation paused during " + activeCase + ": " + requireEngine().status());
        } else if (clientTicks - caseStartedAtTick > MAX_RUN_TICKS) {
            addResult(false, observed, "case timed out; engine status=" + requireEngine().status(), null);
            fail(STONECUTTING_DRAIN_MODE && !stonecuttingDrainStopInjected
                ? "stonecutting drain stop was never injected because owned input with an empty cursor was not observed"
                : "case timed out: " + activeCase);
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
        caseStartedAtNanos = System.nanoTime();
        caseStartedAtWorldTime = client.world == null ? 0 : client.world.getTime();
        activeFoodLevelAtStart = latestSnapshot == null ? 0 : latestSnapshot.foodLevel;
        activeBreadCountAtStart = latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID);
        activeTableOpeningsAtStart = serverTableOpenings;
        activeFurnaceOpeningsAtStart = serverFurnaceOpenings;
        activeSmokerOpeningsAtStart = serverSmokerOpenings;
        activeBlastFurnaceOpeningsAtStart = serverBlastFurnaceOpenings;
        activeStonecutterOpeningsAtStart = serverStonecutterOpenings;
    }

    private boolean correctCookingStationMenuOpened() {
        if (STONECUTTING_MODE) return serverStonecutterOpenings > activeStonecutterOpeningsAtStart;
        return "smoker".equals(COOKING_STATION_MODE)
            ? serverSmokerOpened && serverSmokerOpenings > activeSmokerOpeningsAtStart
            : serverBlastFurnaceOpened && serverBlastFurnaceOpenings > activeBlastFurnaceOpeningsAtStart;
    }

    private boolean stonecuttingDrainOutcomeObserved() {
        return stonecuttingDrainStopInjected && stonecuttingDrainInputCountAtStop > 0
            && stonecuttingDrainStopClientTick >= caseStartedAtTick
            && stonecuttingDrainRequiredObservationSequence > 0
            && latestObservationRequestSequence >= stonecuttingDrainRequiredObservationSequence
            && latestSnapshot.serverTick > stonecuttingDrainStopServerTick
            && latestSnapshot.inventory.equals(Map.of("minecraft:stone", 128))
            && latestSnapshot.count("minecraft:stone_slab") == 0
            && latestSnapshot.count("minecraft:coal") == 0
            && latestSnapshot.count("minecraft:stonecutter") == 0;
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
        long requestSequence = ++observationRequestSequence;
        observationFuture = capture;
        server.execute(() -> {
            try {
                ServerPlayerEntity player = requireServerPlayer(server);
                ServerInventorySnapshot inventory = inventorySnapshot(player);
                ServerWorld world = server.getOverworld();
                ServerSnapshot snapshot = new ServerSnapshot(server.getTicks(), world.getTime(), inventory.counts(),
                    inventory.woodenAxeRemainingDurability(),
                    IRON_PICKAXE_MODE ? countIronPickaxeDeepslate(world) : -1,
                    COAL_RECOVERY_MODE && world.getBlockState(COAL_RECOVERY_ENCASED_ORE).isOf(Blocks.COAL_ORE) ? 1 : COAL_RECOVERY_MODE ? 0 : -1,
                    COAL_RECOVERY_MODE && world.getBlockState(coalRecoveryAccessibleOrePosition()).isOf(Blocks.COAL_ORE) ? 1 : COAL_RECOVERY_MODE ? 0 : -1,
                    COAL_RECOVERY_MODE ? countCoalStartSurface(world) : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseMismatchCount(world) : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseObservedMask : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseMinimumHealth : -1.0F,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCheckpointServerTicksSnapshot() : List.of(),
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
            latestObservationRequestSequence = requestSequence;
        }));
    }

    private static ServerInventorySnapshot inventorySnapshot(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        Map<String, Integer> counts = new HashMap<>();
        List<Integer> woodenAxes = new ArrayList<>();
        countStacks(ClientAccess.main(inventory), counts, woodenAxes);
        countStacks(List.of(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET),
            player.getEquippedStack(net.minecraft.entity.EquipmentSlot.OFFHAND)), counts, woodenAxes);
        return new ServerInventorySnapshot(counts, woodenAxes);
    }

    private static int countIronPickaxeDeepslate(ServerWorld world) {
        int remaining = 0;
        for (int index = 0; index < IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT; index++) {
            if (world.getBlockState(new BlockPos(16 + index, 20, 4)).isOf(Blocks.DEEPSLATE)) remaining++;
        }
        return remaining;
    }

    private static void countStacks(Iterable<ItemStack> stacks, Map<String, Integer> counts, List<Integer> woodenAxes) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            if (stack.isOf(Items.WOODEN_AXE)) woodenAxes.add(stack.getMaxDamage() - stack.getDamage());
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
            activeRequiresEmpty, passed && (!activeRequiresEmpty || activeStartedEmpty), clientTicks - caseStartedAtTick, gameTicks,
            Math.max(0, (System.nanoTime() - caseStartedAtNanos) / 1_000_000L),
            requireEngine().status(), detail, screenshot, latestSnapshot == null ? 0 : latestSnapshot.health,
            latestSnapshot == null ? "unknown" : latestSnapshot.difficulty,
            latestSnapshot != null && serverTableOpenings > activeTableOpeningsAtStart,
            serverFurnaceOpenings > activeFurnaceOpeningsAtStart,
            latestSnapshot == null ? 0 : latestSnapshot.count(IRON_PICKAXE_ID),
            activeFoodLevelAtStart, latestSnapshot == null ? 0 : latestSnapshot.foodLevel,
            activeBreadCountAtStart, latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID),
            latestSnapshot == null ? 0 : latestSnapshot.x, latestSnapshot == null ? 0 : latestSnapshot.y,
            latestSnapshot == null ? 0 : latestSnapshot.z,
            latestSnapshot == null ? Map.of() : latestSnapshot.inventory,
            latestSnapshot == null ? List.of() : latestSnapshot.woodenAxeRemainingDurability,
            clientTicks, latestSnapshot == null ? 0 : latestSnapshot.worldTime,
            PROCESSING_MODE ? PROCESSING_STATION_MODE : "", PROCESSING_MODE ? cookingRecipeType() : "",
            PROCESSING_MODE || IRON_PICKAXE_MODE ? activeInitialResources : Map.of(), PROCESSING_MODE && correctCookingStationMenuOpened(),
            PROCESSING_MODE ? activeInitialResources.getOrDefault(cookingRawItemId(), 0) : 0,
            PROCESSING_MODE && latestSnapshot != null ? latestSnapshot.count(cookingRawItemId()) : 0,
            PROCESSING_MODE ? activeInitialResources.getOrDefault("minecraft:coal", 0) : 0,
            latestSnapshot == null ? 0 : latestSnapshot.count("minecraft:coal"),
            PROCESSING_MODE ? activeInitialResources.getOrDefault("minecraft:" + PROCESSING_STATION_MODE, 0) : 0,
            latestSnapshot == null || !PROCESSING_MODE ? 0
                : latestSnapshot.count("minecraft:" + PROCESSING_STATION_MODE)));
    }

    private String capture(String name) {
        String fileName = "lodekeeper-" + runId + "-" + name + ".png";
        try {
            screenshotWritesPending++;
            VerificationApi.screenshot(evidenceDirectory.toFile(), fileName, client, text -> {
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
        int expectedCases = EXPLORATION_MODE || DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE || COAL_RECOVERY_MODE || BULK_WOOD_MODE || PROCESSING_MODE ? 1 : 9;
        boolean allPassed = results.size() == expectedCases && results.stream().allMatch(CaseResult::passed);
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
            .append("  \"verificationMode\":\"").append(verificationMode()).append("\",\n")
            .append("  \"elapsedMillis\":").append((System.nanoTime() - startedAtNanos) / 1_000_000L).append(",\n")
            .append("  \"clientTicks\":").append(clientTicks).append(",\n")
            .append("  \"failure\":\"").append(escape(failure)).append("\",\n")
            .append("  \"serverTableOpened\":").append(serverTableOpened).append(",\n")
            .append("  \"serverFurnaceOpened\":").append(serverFurnaceOpened).append(",\n")
            .append("  \"serverTableOpenings\":").append(serverTableOpenings).append(",\n")
            .append("  \"serverFurnaceOpenings\":").append(serverFurnaceOpenings).append(",\n");
        if (COOKING_MODE) {
            json.append("  \"serverSmokerOpened\":").append(serverSmokerOpened).append(",\n")
                .append("  \"serverSmokerOpenings\":").append(serverSmokerOpenings).append(",\n")
                .append("  \"serverBlastFurnaceOpened\":").append(serverBlastFurnaceOpened).append(",\n")
                .append("  \"serverBlastFurnaceOpenings\":").append(serverBlastFurnaceOpenings).append(",\n")
                .append("  \"cookingStationProperty\":\"").append(escape(COOKING_STATION_MODE)).append("\",\n")
                .append("  \"cookingFixtureProvidedStock\":");
            appendStringIntMap(json, isSupportedCookingStationMode() ? cookingProvidedStock() : Map.of());
            json.append(",\n");
        }
        json.append("  \"cases\":[\n");
        for (int index = 0; index < results.size(); index++) {
            CaseResult result = results.get(index);
            json.append("    {\"name\":\"").append(escape(result.name)).append("\",\"item\":\"").append(escape(result.item))
                .append("\",\"expected\":").append(result.expected).append(",\"serverObserved\":").append(result.observed)
                .append(",\"inventoryEmptyAtStart\":").append(result.inventoryEmptyAtStart)
                .append(",\"passed\":").append(result.passed).append(",\"clientTicks\":").append(result.clientTicks)
                .append(",\"worldTicks\":").append(result.worldTicks).append(",\"engineStatus\":\"").append(escape(result.engineStatus))
                .append("\",\"detail\":\"").append(escape(result.detail)).append("\",\"serverHealth\":").append(result.health)
                .append(",\"serverDifficulty\":\"").append(escape(result.difficulty)).append("\",\"serverCraftingTableOpenedDuringCase\":").append(result.tableOpenedDuringCase)
                .append(",\"serverFurnaceOpenedDuringCase\":").append(result.furnaceOpenedDuringCase)
                .append(",\"serverIronPickaxeCount\":").append(result.ironPickaxeCount)
                .append(",\"serverFoodLevelAtStart\":").append(result.foodLevelAtStart).append(",\"serverFoodLevelObserved\":").append(result.foodLevelObserved)
                .append(",\"serverBreadAtStart\":").append(result.breadAtStart).append(",\"serverBreadObserved\":").append(result.breadObserved)
                .append(",\"serverPosition\":[").append(result.x).append(',').append(result.y).append(',').append(result.z).append(']')
                .append(",\"screenshot\":").append(result.screenshot == null ? "null" : "\"" + escape(result.screenshot) + "\"");
            if (BULK_WOOD_MODE) {
                json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                    .append(",\"completionClientTick\":").append(result.completionClientTick)
                    .append(",\"completionWorldTick\":").append(result.completionWorldTick)
                    .append(",\"completionHealth\":").append(result.health)
                    .append(",\"finalOakLogCount\":").append(result.serverInventory.getOrDefault(OAK_LOG_ID, 0))
                    .append(",\"fullServerInventory\":{");
                int inventoryIndex = 0;
                for (Map.Entry<String, Integer> entry : result.serverInventory.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    if (inventoryIndex++ > 0) json.append(',');
                    json.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
                }
                json.append("},\"woodenAxeRemainingDurabilityPerStack\":[");
                for (int axeIndex = 0; axeIndex < result.woodenAxeRemainingDurability.size(); axeIndex++) {
                    if (axeIndex > 0) json.append(',');
                    json.append(result.woodenAxeRemainingDurability.get(axeIndex));
                }
                json.append(']');
            }
                if (IRON_PICKAXE_MODE) {
                    json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                        .append(",\"completionClientTick\":").append(result.completionClientTick)
                        .append(",\"completionWorldTick\":").append(result.completionWorldTick)
                        .append(",\"completionHealth\":").append(result.health)
                        .append(",\"finalDeepslateFixtureBlockCount\":")
                        .append(latestSnapshot == null ? -1 : latestSnapshot.ironPickaxeDeepslateRemaining)
                        .append(",\"deepslateFixtureBlocksMined\":")
                        .append(latestSnapshot == null ? -1 : IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT - latestSnapshot.ironPickaxeDeepslateRemaining)
                        .append(",\"fullServerInventory\":");
                    appendStringIntMap(json, result.serverInventory);
                    json.append(",\"initialResources\":");
                    appendStringIntMap(json, result.initialResources);
                }
            if (PROCESSING_MODE) {
                json.append(",\"elapsedMillisFromCommand\":").append(result.elapsedMillis)
                    .append(",\"completionClientTick\":").append(result.completionClientTick)
                    .append(",\"completionWorldTick\":").append(result.completionWorldTick)
                    .append(",\"completionHealth\":").append(result.health)
                    .append(",\"fullServerInventory\":");
                appendStringIntMap(json, result.serverInventory);
                json.append(",\"requiresEmptyAtStart\":").append(result.requiresEmptyAtStart)
                    .append(STONECUTTING_MODE ? ",\"processingStation\":\"" : ",\"cookingStation\":\"").append(escape(result.cookingStation)).append('\"')
                    .append(STONECUTTING_MODE ? ",\"nativeRecipeType\":\"" : ",\"nativeCookingRecipeType\":\"").append(escape(result.cookingRecipeType)).append('\"')
                    .append(",\"initialResources\":");
                appendStringIntMap(json, result.initialResources);
                json.append(",\"serverCorrectStationMenuOpenedDuringCase\":")
                    .append(result.correctCookingStationMenuOpenedDuringCase)
                    .append(",\"initialRawInputCount\":").append(result.initialRawInputCount)
                    .append(",\"finalRawInputCount\":").append(result.finalRawInputCount)
                    .append(",\"initialCoalCount\":").append(result.initialCoalCount)
                    .append(",\"finalCoalCount\":").append(result.finalCoalCount)
                    .append(",\"initialStationItemCount\":").append(result.initialStationItemCount)
                    .append(",\"finalStationItemCount\":").append(result.finalStationItemCount);
            }
            json.append('}').append(index + 1 == results.size() ? "\n" : ",\n");
        }
        json.append("  ],\n  \"explorationFixture\":").append(EXPLORATION_MODE)
            .append(",\n  \"diamondBootsFixture\":").append(DIAMOND_BOOTSTRAP_MODE)
            .append(",\n  \"ironPickaxeFixture\":").append(IRON_PICKAXE_MODE)
            .append(",\n  \"coalRecoveryFixture\":").append(COAL_RECOVERY_MODE)
            .append(",\n  \"bulkWoodFixtureLogCount\":").append(BULK_WOOD_MODE ? 80 : 0)
            .append(",\n  \"woodToolsFlag\":").append(WOOD_TOOLS_MODE)
            .append(",\n  \"woodToolsEnabled\":").append(BULK_WOOD_MODE && WOOD_TOOLS_MODE);
        if (IRON_PICKAXE_MODE) {
            json.append(",\n  \"ironPickaxeFixtureProvidedStock\":{\"minecraft:crafting_table\":1}")
                .append(",\n  \"ironPickaxeGoalCommand\":\"!lk get iron_pickaxe\"")
                .append(",\n  \"ironPickaxeExpectedOutput\":1")
                .append(",\n  \"ironPickaxeNativeTableOpenings\":").append(serverTableOpenings)
                .append(",\n  \"ironPickaxeNativeFurnaceOpenings\":").append(serverFurnaceOpenings)
                .append(",\n  \"ironPickaxeDeepslateFixtureInitialBlockCount\":4")
                .append(",\n  \"ironPickaxeDeepslateFixturePositions\":[\"16,20,4\",\"17,20,4\",\"18,20,4\",\"19,20,4\"]")
                .append(",\n  \"ironPickaxeDeepslateFixtureRemainingBlockCount\":")
                .append(latestSnapshot == null ? -1 : latestSnapshot.ironPickaxeDeepslateRemaining)
                .append(",\n  \"ironPickaxeDeepslateFixtureMinedBlockCount\":")
                .append(latestSnapshot == null ? -1 : 4 - latestSnapshot.ironPickaxeDeepslateRemaining)
                .append(",\n  \"ironPickaxePlannerProbe\":")
                .append(ironPickaxePlannerProbeEvidence == null ? "null" : ironPickaxePlannerProbeEvidence.toString());
        }
        if (COAL_RECOVERY_MODE) {
            BlockPos accessibleCoal = coalRecoveryAccessibleOrePosition();
            json.append(",\n  \"coalStartSurface\":\"").append(COAL_START_SURFACE).append("\"")
                .append(",\n  \"coalStartSurfaceInitialServerFeetY\":").append(Double.isFinite(coalInitialServerFeetY) ? Double.toString(coalInitialServerFeetY) : "null")
                .append(",\n  \"coalStartSurfaceInitialBlockCount\":9")
                .append(",\n  \"coalStartSurfaceRemainingBlockCount\":").append(latestSnapshot == null ? -1 : latestSnapshot.coalStartSurfaceRemaining);
            json.append(",\n  \"coalRecoveryFixtureProvidedStock\":{\"minecraft:stone_pickaxe\":1}")
                .append(",\n  \"coalRecoveryGoalCommand\":\"!lk get coal\"")
                .append(",\n  \"coalRecoveryExpectedOutput\":1")
                .append(",\n  \"coalRecoveryEncasedOrePosition\":\"6,").append(PLAYER_Y).append(",2\"")
                .append(",\n  \"coalRecoveryAccessibleOrePosition\":\"").append(blockPosition(accessibleCoal)).append("\"")
                .append(",\n  \"coalRecoveryEncasedOreInitialBlockCount\":1")
                .append(",\n  \"coalRecoveryEncasedOreRemainingBlockCount\":")
                .append(latestSnapshot == null ? -1 : latestSnapshot.coalRecoveryEncasedOreRemaining)
                .append(",\n  \"coalRecoveryBlockedTargetRejected\":").append(coalRecoveryTargetRejected())
                .append(",\n  \"coalRecoveryAccessibleOreInitialBlockCount\":1")
                .append(",\n  \"coalRecoveryAccessibleOreRemainingBlockCount\":")
                .append(latestSnapshot == null ? -1 : latestSnapshot.coalRecoveryAccessibleOreRemaining)
                .append(",\n  \"coalRecoveryAccessibleOreMined\":")
                .append(latestSnapshot != null && latestSnapshot.coalRecoveryAccessibleOreRemaining == 0)
                .append(",\n  \"coalRecoveryCompletionHealth\":")
                .append(latestSnapshot == null ? -1.0F : latestSnapshot.health)
                .append(",\n  \"coalRecoveryCompletionInventory\":");
            appendStringIntMap(json, latestSnapshot == null ? Map.of() : latestSnapshot.inventory);
            json.append(",\n  \"coalRecoveryOutcomeVerified\":").append(coalRecoveryOutcomeObserved());
            if (MIXED_NAVIGATION_COURSE) {
                int observedMask = latestSnapshot == null ? coalNavigationCourseObservedMask
                    : latestSnapshot.coalNavigationCourseObservedMask;
                int mismatchCount = latestSnapshot == null ? -1 : latestSnapshot.coalNavigationCourseMismatchCount;
                json.append(",\n  \"navigationCourse\":\"mixed\"")
                    .append(",\n  \"coalNavigationCourseInitialServerFeetX\":").append(Double.isFinite(coalNavigationInitialServerFeetX) ? Double.toString(coalNavigationInitialServerFeetX) : "null")
                    .append(",\n  \"coalNavigationCourseInitialServerFeetZ\":").append(Double.isFinite(coalNavigationInitialServerFeetZ) ? Double.toString(coalNavigationInitialServerFeetZ) : "null")
                    .append(",\n  \"coalNavigationFenceGeometry\":").append(coalNavigationFenceGeometryEvidence == null ? "null" : coalNavigationFenceGeometryEvidence.toString())
                    .append(",\n  \"coalNavigationRouteDiagnostics\":").append(coalNavigationRouteDiagnostics == null ? "null" : coalNavigationRouteDiagnostics.toString())
                    .append(",\n  \"coalNavigationCourseAccessibleOrePosition\":\"").append(blockPosition(accessibleCoal)).append("\"")
                    .append(",\n  \"coalNavigationCourseCheckpointExpectedMask\":").append(coalNavigationExpectedMask())
                    .append(",\n  \"coalNavigationCourseCheckpointObservedMask\":").append(observedMask)
                    .append(",\n  \"coalNavigationCourseAllCheckpointsVisited\":").append(observedMask == coalNavigationExpectedMask())
                    .append(",\n  \"coalNavigationCourseProtectedStateCount\":").append(coalNavigationExpectedStates.size())
                    .append(",\n  \"coalNavigationCourseProtectedStatesPreserved\":")
                    .append(mismatchCount < 0 ? -1 : coalNavigationExpectedStates.size() - mismatchCount)
                    .append(",\n  \"coalNavigationCourseStateMismatches\":").append(mismatchCount)
                    .append(",\n  \"coalNavigationCourseMinimumServerHealth\":")
                    .append(latestSnapshot == null ? coalNavigationCourseMinimumHealth : latestSnapshot.coalNavigationCourseMinimumHealth)
                    .append(",\n  \"coalNavigationCourseAllowBreaking\":true")
                    .append(",\n  \"coalNavigationCourseAllowBuilding\":false")
                    .append(",\n  \"coalNavigationCourseAllowParkour\":false")
                    .append(",\n  \"coalNavigationCourseStairEdgeCompleted\":").append(coalNavigationStairEdgeCompleted)
                    .append(",\n  \"coalNavigationCourseStairEdgeMovement\":\"").append(escape(coalNavigationStairEdgeMovement)).append("\"")
                    .append(",\n  \"coalNavigationCourseStairEdgeCompletedPathIndex\":").append(coalNavigationStairEdgePathIndex)
                    .append(",\n  \"coalNavigationCourseStairEdgeTraversalCount\":").append(coalNavigationStairEdgeCompleted ? 1 : 0)
                    .append(",\n  \"coalNavigationCourseLedgeEdgeCompleted\":").append(coalNavigationLedgeEdgeCompleted)
                    .append(",\n  \"coalNavigationCourseLedgeEdgeMovement\":\"").append(escape(coalNavigationLedgeEdgeMovement)).append("\"")
                    .append(",\n  \"coalNavigationCourseLedgeEdgeCompletedPathIndex\":").append(coalNavigationLedgeEdgePathIndex)
                    .append(",\n  \"coalNavigationCourseLedgeEdgeTraversalCount\":").append(coalNavigationLedgeEdgeCompleted ? 1 : 0)
                    .append(",\n  \"coalNavigationCourseCheckpoints\":[");
                for (int checkpointIndex = 0; checkpointIndex < COAL_NAVIGATION_CHECKPOINTS.size(); checkpointIndex++) {
                    if (checkpointIndex > 0) json.append(',');
                    CoalNavigationCheckpoint checkpoint = COAL_NAVIGATION_CHECKPOINTS.get(checkpointIndex);
                    json.append("{\"xCell\":").append(checkpoint.xCell)
                        .append(",\"zCell\":0,\"feetY16\":").append(checkpoint.feetY16)
                        .append(",\"feetY\":").append(checkpoint.feetY16 / 16.0)
                        .append(",\"firstServerTick\":").append(latestSnapshot == null
                            || latestSnapshot.coalNavigationCourseCheckpointServerTicks.size() <= checkpointIndex
                                ? -1 : latestSnapshot.coalNavigationCourseCheckpointServerTicks.get(checkpointIndex)).append('}');
                }
                json.append("]")
                    .append(",\n  \"coalNavigationCourseStairFlight\":{\"lower\":\"11,65,0;feet=66\",\"upper\":\"12,66,0;feet=67\",\"expectedMethod\":\"walk_up_stairs\"}")
                    .append(",\n  \"coalNavigationCourseOneBlockJumpLedge\":{\"approach\":\"15,66,0;feet=67\",\"landing\":\"16,67,0;feet=68\",\"upperBlocks\":[\"16,67,0\",\"17,67,0\",\"18,67,0\"]}")
                    .append(",\n  \"coalNavigationCourseHydrationWater\":\"5,64,-2\"")
                    .append(",\n  \"coalNavigationCourseWaterBedrockShell\":[\"5,65,-2\",\"5,63,-2\",\"5,64,-3\",\"5,64,-1\",\"6,64,-2\",\"4,64,-2\"]");
            }
        }
        if (STONECUTTING_MODE) {
            json.append(",\n  \"stonecuttingFixture\":true,\n  \"nativeRecipeType\":\"stonecutting\",\n  \"processingStation\":\"stonecutter\",\n  \"serverStonecutterOpenings\":")
                .append(serverStonecutterOpenings).append(",\n  \"stonecuttingFixtureProvidedStock\":");
            appendStringIntMap(json, cookingProvidedStock());
            if (STONECUTTING_DRAIN_MODE) {
                json.append(",\n  \"stonecuttingDrainStopAttempted\":").append(stonecuttingDrainStopAttempted)
                    .append(",\n  \"stonecuttingDrainStopInjected\":").append(stonecuttingDrainStopInjected)
                    .append(",\n  \"stonecuttingDrainOriginalCommandTarget\":").append(STONECUTTING_DRAIN_COMMAND_TARGET)
                    .append(",\n  \"stonecuttingDrainExpectedOutput\":0")
                    .append(",\n  \"stonecuttingDrainInputCountAtStop\":").append(stonecuttingDrainInputCountAtStop)
                    .append(",\n  \"stonecuttingDrainStopClientTick\":").append(stonecuttingDrainStopClientTick)
                    .append(",\n  \"stonecuttingDrainServerObservationTickBeforeStop\":").append(stonecuttingDrainStopServerTick)
                    .append(",\n  \"stonecuttingDrainFreshServerObservationTick\":")
                    .append(latestSnapshot == null ? -1 : latestSnapshot.serverTick)
                    .append(",\n  \"stonecuttingDrainRequiredObservationSequence\":").append(stonecuttingDrainRequiredObservationSequence)
                    .append(",\n  \"stonecuttingDrainLatestObservationSequence\":").append(latestObservationRequestSequence);
            }
        }
        if (COOKING_MODE) json.append(",\n  \"cookingStationFixture\":true,\n  \"cookingRecipeType\":\"")
            .append(cookingRecipeType()).append('\"');
        json.append(",\n  \"resourceInitiallyLoaded\":").append(resourceInitiallyLoaded)
            .append(",\n  \"explorationAttempts\":")
            .append(LodekeeperClient.engine == null ? 0 : LodekeeperClient.engine.explorationAttemptsMade());
        return json.append("\n}\n").toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static void appendStringIntMap(StringBuilder json, Map<String, Integer> values) {
        json.append('{');
        int index = 0;
        for (Map.Entry<String, Integer> entry : values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (index++ > 0) json.append(',');
            json.append('\"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
        }
        json.append('}');
    }

    private static String blockPosition(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findField(String ownerName, String name) {
        try {
            return findField(Class.forName(ownerName), name);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean navigationMovementReflectionAvailable() {
        return ENGINE_MOVEMENT_FIELD != null && MOVEMENT_PATH_FIELD != null
            && MOVEMENT_PATH_INDEX_FIELD != null && MOVEMENT_VALIDATED_PATH_INDEX_FIELD != null;
    }

    private static String verificationMode() {
        if (selectedFixtureModes() > 1) return "invalid_conflicting_modes";
        if (NAVIGATION_COURSE != null && !MIXED_NAVIGATION_COURSE) return "invalid_navigation_course";
        if (MIXED_NAVIGATION_COURSE && !COAL_RECOVERY_MODE) return "invalid_navigation_course_requires_coal_recovery";
        if (MIXED_NAVIGATION_COURSE && !COAL_START_SURFACE.equals("full")) return "invalid_navigation_course_requires_full_surface";
        if (COOKING_MODE && !isSupportedCookingStationMode()) return "invalid_cooking_station";
        if (STONECUTTING_DRAIN_MODE && !STONECUTTING_MODE) return "invalid_stonecutting_drain";
        if (STONECUTTING_DRAIN_MODE) return "stonecutting_drain";
        if (STONECUTTING_MODE) return "stonecutting";
        if (COOKING_MODE) return "cooking_" + COOKING_STATION_MODE;
        if (BULK_WOOD_MODE) return "bulk_wood";
        if (IRON_PICKAXE_MODE) return "iron_pickaxe";
        if (COAL_RECOVERY_MODE) return MIXED_NAVIGATION_COURSE ? "coal_recovery_mixed_navigation" : "coal_recovery";
        if (DIAMOND_BOOTSTRAP_MODE) return "diamond_boots";
        return EXPLORATION_MODE ? "exploration" : "default";
    }

    private static int selectedFixtureModes() {
        return (EXPLORATION_MODE ? 1 : 0) + (DIAMOND_BOOTSTRAP_MODE ? 1 : 0)
            + (IRON_PICKAXE_MODE ? 1 : 0) + (COAL_RECOVERY_MODE ? 1 : 0) + (BULK_WOOD_MODE ? 1 : 0)
            + (COOKING_MODE ? 1 : 0) + (STONECUTTING_MODE ? 1 : 0);
    }

    private record ServerInventorySnapshot(Map<String, Integer> counts, List<Integer> woodenAxeRemainingDurability) {
        private ServerInventorySnapshot {
            counts = Map.copyOf(counts);
            woodenAxeRemainingDurability = List.copyOf(woodenAxeRemainingDurability);
        }
    }

    private record CoalNavigationCheckpoint(int xCell, int feetY16) { }

    private record ServerSnapshot(int serverTick, long worldTime, Map<String, Integer> inventory,
                                  List<Integer> woodenAxeRemainingDurability, int ironPickaxeDeepslateRemaining,
                                  int coalRecoveryEncasedOreRemaining, int coalRecoveryAccessibleOreRemaining, int coalStartSurfaceRemaining,
                                  int coalNavigationCourseMismatchCount, int coalNavigationCourseObservedMask,
                                  float coalNavigationCourseMinimumHealth,
                                  List<Integer> coalNavigationCourseCheckpointServerTicks,
                                  float health, int foodLevel,
                                  String difficulty, double x, double y, double z) {
        private ServerSnapshot {
            inventory = Map.copyOf(inventory);
            woodenAxeRemainingDurability = List.copyOf(woodenAxeRemainingDurability);
            coalNavigationCourseCheckpointServerTicks = List.copyOf(coalNavigationCourseCheckpointServerTicks);
        }
        int count(String id) { return inventory.getOrDefault(id, 0); }
        boolean inventoryEmpty() { return inventory.isEmpty(); }
    }

    private record CaseResult(String name, String item, int expected, int observed, boolean inventoryEmptyAtStart,
                              boolean requiresEmptyAtStart, boolean passed, int clientTicks, long worldTicks, long elapsedMillis, String engineStatus,
                              String detail, String screenshot, float health, String difficulty, boolean tableOpenedDuringCase,
                              boolean furnaceOpenedDuringCase, int ironPickaxeCount,
                              int foodLevelAtStart, int foodLevelObserved, int breadAtStart, int breadObserved,
                              double x, double y, double z, Map<String, Integer> serverInventory,
                              List<Integer> woodenAxeRemainingDurability, int completionClientTick,
                              long completionWorldTick, String cookingStation, String cookingRecipeType,
                              Map<String, Integer> initialResources, boolean correctCookingStationMenuOpenedDuringCase,
                              int initialRawInputCount, int finalRawInputCount, int initialCoalCount, int finalCoalCount,
                              int initialStationItemCount, int finalStationItemCount) {
        private CaseResult {
            serverInventory = Map.copyOf(serverInventory);
            woodenAxeRemainingDurability = List.copyOf(woodenAxeRemainingDurability);
            initialResources = Map.copyOf(initialResources);
        }
    }
}
