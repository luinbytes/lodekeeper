package dev.lodekeeper.fabric.modern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.lodekeeper.core.AcquisitionPlanner;
import dev.lodekeeper.core.AcquisitionSource;
import dev.lodekeeper.core.BlockedReason;
import dev.lodekeeper.core.CatalogSnapshot;
import dev.lodekeeper.core.InventorySnapshot;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.PlanResult;
import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.nav.LaunchApproach;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private static final boolean BARITONE_MODE = Boolean.getBoolean("lodekeeper.verify.baritone");
    private boolean baritoneMiningObserved;

    private static final boolean NEARBY_WOOD_MODE = Boolean.getBoolean("lodekeeper.verify.nearbyWood");
    private static final boolean GEOMETRY_EPOCH_MODE = Boolean.getBoolean("lodekeeper.verify.geometryEpoch");
    private GeometryEpochVerification geometryEpoch;
    private boolean geometryEpochComplete;
    private static final String NEARBY_WOOD_GOAL = System.getProperty("lodekeeper.verify.nearbyWoodGoal", "wood");
    private static final String NEARBY_WOOD_TERRAIN = System.getProperty("lodekeeper.verify.nearbyWoodTerrain", "flat");
    private static final boolean MEADOW_BENCHMARK = NEARBY_WOOD_MODE && "meadow".equals(NEARBY_WOOD_TERRAIN);
    private static final String COOKING_STATION_MODE = System.getProperty("lodekeeper.verify.cookingStation");
    private static final boolean COOKING_MODE = COOKING_STATION_MODE != null;
    private static final boolean STONECUTTING_MODE = Boolean.getBoolean("lodekeeper.verify.stonecutting");
    private static final boolean STONECUTTING_DRAIN_MODE = Boolean.getBoolean("lodekeeper.verify.stonecuttingDrain");
    private static final boolean PROCESSING_MODE = COOKING_MODE || STONECUTTING_MODE;
    private static final String PROCESSING_STATION_MODE = STONECUTTING_MODE ? "stonecutter" : COOKING_STATION_MODE;
    private static final int STONECUTTING_DRAIN_COMMAND_TARGET = 144;
    private static final int MAX_RUN_TICKS = COOKING_MODE ? 10_000 : 6_000;
    private static final long MAX_RUN_WALL_NANOS = COOKING_MODE ? 500_000_000_000L : 300_000_000_000L;
    private static final int OBSERVE_EVERY_TICKS = 20;
    private static final boolean EXPLORATION_MODE = Boolean.getBoolean("lodekeeper.verify.exploration");
    private static final boolean DIAMOND_BOOTSTRAP_MODE = Boolean.getBoolean("lodekeeper.verify.diamondBoots");
    private static final boolean IRON_PICKAXE_MODE = Boolean.getBoolean("lodekeeper.verify.ironPickaxe");
    private static final boolean IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE =
        Boolean.getBoolean("lodekeeper.verify.ironPickaxeEmptyDistantWood");
    private static final boolean COAL_RECOVERY_MODE = Boolean.getBoolean("lodekeeper.verify.coalRecovery");
    private static final String COAL_START_SURFACE = System.getProperty("lodekeeper.verify.coalStartSurface", "full");
    private static final String NAVIGATION_COURSE = System.getProperty("lodekeeper.verify.navigationCourse");
    private static final boolean MIXED_NAVIGATION_COURSE = "mixed".equals(NAVIGATION_COURSE);
    private double coalInitialServerFeetY = Double.NaN;
    private static final boolean BULK_WOOD_MODE = Boolean.getBoolean("lodekeeper.verify.bulkWood");
    private static final boolean WOOD_TOOLS_MODE = Boolean.getBoolean("lodekeeper.verify.woodTools");
    private static final String IRON_PICKAXE_ID = "minecraft:iron_pickaxe";
    private static final String OAK_LOG_ID = "minecraft:oak_log";
    private static final int IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X = 20;
    private static final int IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT = 8;
    private static final String WOODEN_AXE_ID = "minecraft:wooden_axe";
    private static final String RAW_PORKCHOP_ID = "minecraft:porkchop";
    private static final String COOKED_PORKCHOP_ID = "minecraft:cooked_porkchop";
    private static final String RAW_IRON_ID = "minecraft:raw_iron";
    private static final String IRON_INGOT_ID = "minecraft:iron_ingot";
    private static final int IRON_PICKAXE_PROBE_TIMEOUT_TICKS = 200;
    private static final int IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT = 4;
    private static final int MAX_NEARBY_WOOD_LAUNCH_HANDOFF_OBSERVATIONS = 32;
    private static final double FORCED_PREPHYSICS_HANDOFF_SPEED = .015;
    private static final Field ENGINE_MOVEMENT_FIELD = findField(AutomationEngine.class, "movement");
    private static final Field MOVEMENT_PATH_FIELD = findField("dev.lodekeeper.fabric.modern.MovementController", "path");
    private static final Field MOVEMENT_PATH_INDEX_FIELD = findField("dev.lodekeeper.fabric.modern.MovementController", "pathIndex");
    private static final Field MOVEMENT_VALIDATED_PATH_INDEX_FIELD = findField("dev.lodekeeper.fabric.modern.MovementController", "validatedPathIndex");
    private boolean resourceInitiallyLoaded;
    private static final int FLOOR_Y = 63;
    private static final int PLAYER_Y = FLOOR_Y + 1;
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

    private enum State {
        DISABLED, OPENING_WORLD, WAITING_FOR_WORLD, SETTING_UP, WAITING_FOR_EMPTY_SNAPSHOT,
        GATHERING_WOOD, CRAFTING_TABLE, CRAFTING_STICKS, CRAFTING_WOOD_PICK, CRAFTING_STONE_PICK,
        CRAFTING_FURNACE, SMELTING_IRON, CUSTOM_CONTENT, SETTING_UP_FOOD, WAITING_FOR_FOOD_FIXTURE,
        GATHERING_FOOD, COOKING, GATHERING_COAL_RECOVERY, CAPTURING, COMPLETE, FAILED
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
    private long caseStartedAtNanos;
    private long fixtureReadyServerTick;
    private int readyTicks;
    private int captureStartedAtTick;
    private int screenshotWritesPending;
    private int firstRouteTick = -1;
    private volatile long firstMovementMillis = -1;
    private volatile MovementClock movementClock;
    private Object nearbyWoodLaunchObservedPath;
    private int nearbyWoodLaunchPreviousPathIndex = -1;
    private int nearbyWoodLaunchPathGeneration = -1;
    private int nearbyWoodLaunchHandoffCount;
    private int nearbyWoodLaunchJumpHandoffCount;
    private int nearbyWoodLaunchSettledHandoffCount;
    private int nearbyWoodLaunchUnsettledHandoffCount;
    private double nearbyWoodLaunchMaximumHorizontalSpeed;
    private final List<NearbyWoodLaunchHandoffObservation> nearbyWoodLaunchHandoffs = new ArrayList<>();
    private boolean nearbyWoodLaunchObserverRegistered;
    private String liveRouteScreenshot;
    private JsonArray routeBenchmark;
    private boolean worldLaunchQueued;
    private boolean worldCreationStarted;
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
    private Map<String, Integer> ironPickaxeFirstVerifiedServerStock;
    private int ironPickaxeFirstVerifiedServerStockTick = -1;
    private int ironPickaxeProbeStartedAtTick = -1;
    private boolean ironPickaxeProbeStarted, ironPickaxeProbeFinished;
    private volatile int serverStonecutterOpenings;
    private Map<String, Integer> activeInitialResources = Map.of();
    private int foodBreadCountBeforeSetup;
    private String failure = "";
    private volatile boolean serverTableOpened;
    private volatile boolean serverFurnaceOpened;
    private volatile int serverTableOpenings;
    private volatile int serverFurnaceOpenings;
    private volatile boolean serverSmokerOpened;
    private volatile boolean serverBlastFurnaceOpened;
    private volatile int serverSmokerOpenings;
    private volatile int serverBlastFurnaceOpenings;
    private AbstractContainerMenu lastServerMenu;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        if (System.getProperty("lodekeeper.verify.naturalGoal") != null) {
            NaturalWorldVerification.start(Minecraft.getInstance());
            return;
        }
        client = Minecraft.getInstance();
        startedAtNanos = System.nanoTime();
        try {
            prepareIsolatedPaths();
            String nearbyWoodFailure = invalidNearbyWoodMode();
            if (nearbyWoodFailure != null) {
                state = State.FAILED;
                failure = nearbyWoodFailure;
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (GEOMETRY_EPOCH_MODE && !NEARBY_WOOD_MODE) {
                failure = "geometryEpoch requires nearbyWood=true";
                state = State.FAILED;
                writeEvidence("failed");
                client.stop();
                return;
            }
            if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE && !IRON_PICKAXE_MODE) {
                state = State.FAILED;
                failure = "lodekeeper.verify.ironPickaxeEmptyDistantWood requires lodekeeper.verify.ironPickaxe=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (COOKING_MODE && !isSupportedCookingStationMode()) {
                state = State.FAILED;
                failure = "lodekeeper.verify.cookingStation must be exactly smoker or blast_furnace";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (STONECUTTING_DRAIN_MODE && !STONECUTTING_MODE) {
                state = State.FAILED;
                failure = "lodekeeper.verify.stonecuttingDrain requires lodekeeper.verify.stonecutting=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if ((!COAL_START_SURFACE.equals("full") && !COAL_START_SURFACE.equals("dirt_path") && !COAL_START_SURFACE.equals("farmland"))
                    || (!COAL_START_SURFACE.equals("full") && !COAL_RECOVERY_MODE)) {
                state = State.FAILED;
                failure = "coalStartSurface must be full, dirt_path, or farmland; fractional surfaces require coalRecovery=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (NAVIGATION_COURSE != null && !MIXED_NAVIGATION_COURSE) {
                state = State.FAILED;
                failure = "navigationCourse must be exactly mixed when specified";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (MIXED_NAVIGATION_COURSE && !COAL_RECOVERY_MODE) {
                state = State.FAILED;
                failure = "navigationCourse=mixed requires coalRecovery=true";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (MIXED_NAVIGATION_COURSE && !COAL_START_SURFACE.equals("full")) {
                state = State.FAILED;
                failure = "navigationCourse=mixed requires coalStartSurface=full";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (!BARITONE_MODE && MIXED_NAVIGATION_COURSE && !navigationMovementReflectionAvailable()) {
                state = State.FAILED;
                failure = "navigation course cannot inspect active validated route movement (expected AutomationEngine.movement and MovementController.path/pathIndex/validatedPathIndex)";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (!BARITONE_MODE && (NEARBY_WOOD_MODE || IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE)
                    && !navigationMovementReflectionAvailable()) {
                state = State.FAILED;
                failure = "wood route verification cannot inspect active route movement (expected AutomationEngine.movement and MovementController.path/pathIndex/validatedPathIndex)";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
            if (selectedFixtureModes() > 1) {
                state = State.FAILED;
                failure = "lodekeeper.verify.exploration, lodekeeper.verify.diamondBoots, lodekeeper.verify.nearbyWood, lodekeeper.verify.ironPickaxe, lodekeeper.verify.coalRecovery, lodekeeper.verify.bulkWood, lodekeeper.verify.cookingStation, and lodekeeper.verify.stonecutting are mutually exclusive; nearbyWoodGoal, nearbyWoodTerrain, ironPickaxeEmptyDistantWood, and stonecuttingDrain are submodes";
                writeEvidence("failed");
                System.err.println("[Lodekeeper verification] Refusing to start: " + failure);
                client.stop();
                return;
            }
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
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!BARITONE_MODE || mc.player == null) return;
            var bot = baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone();
            if (bot.getMineProcess().isActive()) baritoneMiningObserved = true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> observeCoalNavigationMovementAfterEngineTick());
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
        registerNearbyWoodMeadowLaunchObserver();
        if (state == State.FAILED) return;
        clientTicks++;
        if (clientTicks % 100 == 0) {
            System.out.println("[Lodekeeper verification] state=" + state + ", engine=" + requireEngine().status()
                + ", server=" + latestSnapshot);
        }
        if (clientTicks > MAX_RUN_TICKS || System.nanoTime() - startedAtNanos > MAX_RUN_WALL_NANOS) {
            fail(COOKING_MODE ? "verification exceeded the 500-second cooking-mode limit"
                : "verification exceeded the five-minute limit");
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
                    if (IRON_PICKAXE_MODE) startIronPickaxePlannerProbe();
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
                        if (++readyTicks >= 20 && client.player.getY() > FLOOR_Y
                                && client.level.getBlockState(new BlockPos(0, FLOOR_Y, 0)).is(coalStartFloor())) {
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
                    Map<String, Integer> expectedStartingStock = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                        ? Map.of() : Map.of("minecraft:crafting_table", 1);
                    boolean startingStockObserved = latestSnapshot != null
                        && latestSnapshot.serverTick >= fixtureReadyServerTick
                        && latestSnapshot.health == 20.0F
                        && latestSnapshot.inventory.equals(expectedStartingStock);
                    if (startingStockObserved && IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                            && ironPickaxeFirstVerifiedServerStock == null) {
                        ironPickaxeFirstVerifiedServerStock = Map.copyOf(latestSnapshot.inventory);
                        ironPickaxeFirstVerifiedServerStockTick = latestSnapshot.serverTick;
                    }
                    if (startingStockObserved && ironPickaxeProbeFinished) {
                        if (++readyTicks >= 20 && client.player.getY() > FLOOR_Y
                                && client.level.getBlockState(new BlockPos(0, FLOOR_Y, 0)).is(Blocks.BEDROCK)) {
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
                        if (readyTicks >= 20 && client.player.getY() > FLOOR_Y
                                && client.level.getBlockState(new BlockPos(0, FLOOR_Y, 0)).is(Blocks.BEDROCK)) {
                            startCookingCommand();
                        }
                    } else {
                        readyTicks = 0;
                    }
                    return;
                }
                if (latestSnapshot != null && latestSnapshot.serverTick >= fixtureReadyServerTick && latestSnapshot.inventoryEmpty()) {
                    readyTicks++;
                    int logFixtureX = NEARBY_WOOD_MODE ? IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X : 6;
                    if (readyTicks >= 20 && client.player.getY() > FLOOR_Y
                        && (EXPLORATION_MODE
                            ? client.level.getBlockState(new BlockPos(0, FLOOR_Y, 0)).is(Blocks.BEDROCK)
                            : client.level.getBlockState(new BlockPos(logFixtureX,
                                PLAYER_Y + (MEADOW_BENCHMARK ? 3 : 0), 0)).is(Blocks.OAK_LOG))) {
                        startGatherCommand();
                    }
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

    private void beginFixtureSetup() {
        state = State.SETTING_UP;
        MinecraftServer server = requireServer();
        setupFuture = new CompletableFuture<>();
        CompletableFuture<Long> scheduled = setupFuture;
        server.execute(() -> {
            try {
                ServerPlayer player = requireServerPlayer(server);
                ServerLevel world = server.overworld();
                for (int x = -12; x <= (PROCESSING_MODE || BULK_WOOD_MODE ? 100 : EXPLORATION_MODE ? 96
                        : DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE || NEARBY_WOOD_MODE ? 30 : 18); x++) {
                    for (int z = -6; z <= 6; z++) world.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z), Blocks.BEDROCK.defaultBlockState());
                }
                if (MEADOW_BENCHMARK) {
                    for (int x = -12; x <= 30; x++) for (int z = -6; z <= 6; z++) {
                        int rise = Math.max(0, Math.min(3, x / 6));
                        for (int y = FLOOR_Y; y < FLOOR_Y + rise; y++) {
                            world.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState());
                        }
                        boolean path = z == 0 && x >= 8 && x <= 10;
                        world.setBlockAndUpdate(new BlockPos(x, FLOOR_Y + rise, z),
                            (path ? Blocks.DIRT_PATH : Blocks.GRASS_BLOCK).defaultBlockState());
                        if (!path && x > 2 && x < 18 && Math.floorMod(x + z, 3) == 0) {
                            world.setBlockAndUpdate(new BlockPos(x, PLAYER_Y + rise, z), Blocks.DANDELION.defaultBlockState());
                        }
                    }
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
                            world.setBlockAndUpdate(new BlockPos(-3, FLOOR_Y, 0), Blocks.WATER.defaultBlockState());
                        }
                        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                            world.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z), coalStartFloor().defaultBlockState());
                        }
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                world.setBlockAndUpdate(COAL_RECOVERY_ENCASED_ORE.offset(dx, dy, dz), Blocks.BEDROCK.defaultBlockState());
                            }
                        }
                    }
                    world.setBlockAndUpdate(COAL_RECOVERY_ENCASED_ORE, Blocks.COAL_ORE.defaultBlockState());
                    world.setBlockAndUpdate(coalRecoveryAccessibleOrePosition(), Blocks.COAL_ORE.defaultBlockState());
                    coalNavigationExpectedStates = MIXED_NAVIGATION_COURSE
                        ? mixedCoalNavigationExpectedStates(mixedCourseStates) : Map.of();
                } else if (!PROCESSING_MODE) {
                    int oakLogStartX = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                        ? IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X
                        : BULK_WOOD_MODE ? 6 : EXPLORATION_MODE ? 80 : NEARBY_WOOD_MODE ? 20 : 6;
                    int oakLogCount = BULK_WOOD_MODE ? 80
                        : IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE ? IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT : 8;
                    for (int index = 0; index < oakLogCount; index++) {
                        world.setBlockAndUpdate(new BlockPos(oakLogStartX + index,
                            PLAYER_Y + (MEADOW_BENCHMARK ? 3 : 0), 0), Blocks.OAK_LOG.defaultBlockState());
                    }
                    if (!BULK_WOOD_MODE) {
                        for (int x = 6; x <= (DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE ? 25 : 17); x++) {
                            world.setBlockAndUpdate(new BlockPos(x, PLAYER_Y, 2), Blocks.STONE.defaultBlockState());
                        }
                        if (DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE) {
                            world.setBlockAndUpdate(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.defaultBlockState());
                            world.setBlockAndUpdate(new BlockPos(17, PLAYER_Y, 4), Blocks.COAL_ORE.defaultBlockState());
                            world.setBlockAndUpdate(new BlockPos(18, PLAYER_Y, 4), Blocks.IRON_ORE.defaultBlockState());
                            world.setBlockAndUpdate(new BlockPos(16, PLAYER_Y + 1, 4), Blocks.IRON_ORE.defaultBlockState());
                            world.setBlockAndUpdate(new BlockPos(17, PLAYER_Y + 1, 4), Blocks.IRON_ORE.defaultBlockState());
                            for (int x = 20; x <= 23; x++) {
                                world.setBlockAndUpdate(new BlockPos(x, PLAYER_Y, 4), Blocks.DIAMOND_ORE.defaultBlockState());
                            }
                            if (IRON_PICKAXE_MODE) {
                                for (int index = 0; index < IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT; index++) {
                                    world.setBlockAndUpdate(new BlockPos(16 + index, 20, 4), Blocks.DEEPSLATE.defaultBlockState());
                                }
                            }
                        } else {
                            world.setBlockAndUpdate(new BlockPos(16, PLAYER_Y, 4), Blocks.COAL_ORE.defaultBlockState());
                            world.setBlockAndUpdate(new BlockPos(17, PLAYER_Y, 4), Blocks.IRON_ORE.defaultBlockState());
                            Block rubyOre = BuiltInRegistries.BLOCK.getValue(Identifier.parse(VerificationContentInitializer.RUBY_ORE_ID));
                            if (rubyOre == Blocks.AIR) throw new IllegalStateException("verifier ruby ore was not registered");
                            for (int index = 0; index < 4; index++) {
                                world.setBlockAndUpdate(new BlockPos(8 + index, PLAYER_Y, 4), rubyOre.defaultBlockState());
                            }
                        }
                    }
                }
                if (!BULK_WOOD_MODE && !PROCESSING_MODE) {
                    long coalTicks = GameApi.fuelTicks(world, new ItemStack(Items.COAL));
                    long plankTicks = GameApi.fuelTicks(world, new ItemStack(Items.OAK_PLANKS));
                    if (coalTicks != 1600 || plankTicks != 300)
                        throw new IllegalStateException("standard furnace fuel snapshot differs: coal=" + coalTicks + ", oak_planks=" + plankTicks);
                }
                clearInventory(player);
                if (PROCESSING_MODE) seedCookingInventory(player);
                if (COAL_RECOVERY_MODE && !player.getInventory().add(new ItemStack(Items.STONE_PICKAXE))) {
                    throw new IllegalStateException("could not seed the single coal-recovery verifier stone pickaxe");
                }
                if (IRON_PICKAXE_MODE && !IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                        && !player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE))) {
                    throw new IllegalStateException("could not seed the single iron-pickaxe verifier crafting table");
                }
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.teleportTo(MIXED_NAVIGATION_COURSE ? 0.25 : 0.5, PLAYER_Y, MIXED_NAVIGATION_COURSE ? 0.75 : 0.5);
                if (GEOMETRY_EPOCH_MODE) GeometryEpochVerification.prepareServerFixture(world);
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

    private static boolean isSupportedCookingStationMode() {
        return "smoker".equals(COOKING_STATION_MODE) || "blast_furnace".equals(COOKING_STATION_MODE);
    }

    private static ItemStack cookingRawStack() {
        return new ItemStack(STONECUTTING_MODE ? Items.STONE : "smoker".equals(COOKING_STATION_MODE) ? Items.PORKCHOP : Items.RAW_IRON, 64);
    }

    private static ItemStack cookingStationStack() {
        return new ItemStack(STONECUTTING_MODE ? Items.STONECUTTER : "smoker".equals(COOKING_STATION_MODE) ? Items.SMOKER : Items.BLAST_FURNACE, 1);
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

    private static void seedCookingInventory(ServerPlayer player) {
        for (int stack = 0; stack < 2; stack++) {
            if (!player.getInventory().add(cookingRawStack())) {
                throw new IllegalStateException("could not seed two 64-item raw cooking stacks");
            }
        }
        if (!STONECUTTING_MODE && !player.getInventory().add(new ItemStack(Items.COAL, 9))) {
            throw new IllegalStateException("could not seed nine verifier coal");
        }
        if (!player.getInventory().add(cookingStationStack())) {
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

    private void maybeInjectStonecuttingDrainStop() {
        if (!STONECUTTING_DRAIN_MODE || state != State.COOKING || stonecuttingDrainStopAttempted
                || client.player == null || client.player.containerMenu == null
                || client.player.containerMenu.getClass() != net.minecraft.world.inventory.StonecutterMenu.class) return;
        net.minecraft.world.inventory.StonecutterMenu menu =
            (net.minecraft.world.inventory.StonecutterMenu) client.player.containerMenu;
        ItemStack ownedInput = menu.getSlot(0).getItem();
        if (ownedInput.isEmpty() || !ownedInput.is(Items.STONE) || !menu.getCarried().isEmpty()) return;
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.STONE_SLAB)) {
                fail("stonecutting drain stop was not injected before the first slab output");
                return;
            }
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

    private void startIronPickaxePlannerProbe() {
        if (ironPickaxeProbeStarted) return;
        ironPickaxeProbeStarted = true;
        ironPickaxeProbeStartedAtTick = clientTicks;
        ironPickaxeProbeCatalog = new GameCatalog(client);
        ironPickaxePlannerProbeEvidence = new JsonObject();
        ironPickaxePlannerProbeEvidence.addProperty("status", "loading");
        ironPickaxePlannerProbeEvidence.addProperty("planningPreferences", "none");
        ironPickaxePlannerProbeEvidence.addProperty("isGameplayEvidence", false);
        ironPickaxePlannerProbeEvidence.addProperty("inventory", IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
            ? "empty" : "minecraft:crafting_table x1 only");
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
                Map<ItemId, Integer> plannerInventory = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                    ? Map.of() : Map.of(ItemId.parse("minecraft:crafting_table"), 1);
                InventorySnapshot inventory = new InventorySnapshot(plannerInventory);
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
        activeCase = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
            ? "iron_pickaxe_from_empty_inventory_with_oak_logs_x"
                + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + "_through_x"
                + (IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT - 1)
            : "iron_pickaxe_from_crafting_table_only";
        activeItem = IRON_PICKAXE_ID;
        activeCount = 1;
        activeRequiresEmpty = IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE;
        activeStartedEmpty = latestSnapshot.inventoryEmpty();
        activeInitialResources = Map.copyOf(latestSnapshot.inventory);
        beginCaseClock();
        sendCommand("!lk get iron_pickaxe");
        state = State.GATHERING_WOOD;
    }

    private static int routeBenchmarkIntProperty(String name, int fallback, int minimum, int maximum) {
        String value = System.getProperty("lodekeeper.verify." + name, Integer.toString(fallback));
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
    }

    private static String routePathFingerprint(dev.lodekeeper.nav.Path path) {
        if (path == null) return "none";
        StringBuilder encoded = new StringBuilder().append(path.cost).append('/').append(path.placementsReserved);
        for (int index = 0; index < path.length(); index++) {
            dev.lodekeeper.nav.Path.Step step = path.step(index);
            encoded.append('|').append(step.x).append(',').append(step.feetY16).append(',')
                .append(step.z).append(',').append(step.movement);
            for (int actionIndex = 0; actionIndex < step.actionCount(); actionIndex++) {
                dev.lodekeeper.nav.Action action = step.action(actionIndex);
                encoded.append(';').append(action.type).append(',').append(action.x).append(',')
                    .append(action.y).append(',').append(action.z).append(',').append(action.token);
            }
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(encoded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 provider is unavailable", exception);
        }
    }

    /** Same native terrain, policy and target across builds; timing is evidence, not a flaky pass threshold. */
    private void benchmarkNativeRoute() {
        routeBenchmark = new JsonArray();
        int samples = routeBenchmarkIntProperty("routeBenchmarkSamples", 4, 4, 16);
        int warmups = routeBenchmarkIntProperty("routeBenchmarkWarmups", 1, 1, 4);
        if (warmups > samples - 2) throw new IllegalArgumentException("routeBenchmarkWarmups must leave at least two measured samples");
        var cpuClock = java.lang.management.ManagementFactory.getThreadMXBean();
        boolean cpuClockAvailable = cpuClock.isCurrentThreadCpuTimeSupported() && cpuClock.isThreadCpuTimeEnabled();
        boolean comparePruning = Boolean.getBoolean("lodekeeper.verify.compareTransitionPruning");
        if (comparePruning && (samples - warmups < 10 || (samples & 1) != 0 || (warmups & 1) != 0))
            throw new IllegalArgumentException("Paired pruning benchmark requires even warmups and samples, with at least five measured samples per side");
        for (int sample = 0; sample < samples; sample++) {
            GameTerrain terrain = new GameTerrain(client, requireEngine().config);
            terrain.beginSearch();
            long initialVoxelQueries = terrain.voxelQueries, initialReadMisses = terrain.readMisses;
            long initialShapeMisses = terrain.shapeMisses, initialChunkQueries = terrain.chunkQueries;
            long initialChunkMisses = terrain.chunkMisses;
            var options = new dev.lodekeeper.nav.Planner.Options().maxNodes(16000).maxDrop(3).allowBreaking(true);
            options.pruneDominatedTransitions = !comparePruning || (sample & 1) != 0;
            long startedCpu = cpuClockAvailable ? cpuClock.getCurrentThreadCpuTime() : -1L;
            long started = System.nanoTime();
            dev.lodekeeper.nav.Planner planner = new dev.lodekeeper.nav.Planner(terrain, 0, PLAYER_Y, -3,
                dev.lodekeeper.nav.Goal.exact(20, PLAYER_Y + (MEADOW_BENCHMARK ? 3 : 0), -3),
                options);
            int calls = 0;
            while (planner.getStatus() == dev.lodekeeper.nav.NavStatus.IN_PROGRESS && calls < 10_000
                    && System.nanoTime() - started < 5_000_000_000L) {
                planner.advance(128, 2_000_000L);
                calls++;
            }
            long elapsedNanos = System.nanoTime() - started;
            long threadCpuNanos = startedCpu < 0L ? -1L : cpuClock.getCurrentThreadCpuTime() - startedCpu;
            JsonObject row = new JsonObject();
            row.addProperty("warmup", sample < warmups);
            row.addProperty("sampleIndex", sample);
            row.addProperty("transitionPruning", options.pruneDominatedTransitions);
            row.addProperty("elapsedNanos", elapsedNanos);
            row.addProperty("threadCpuNanos", threadCpuNanos);
            row.addProperty("groundedCollectionRequests", planner.getGroundedCollectionRequests());
            row.addProperty("groundedCollectionCalls", planner.getGroundedCollectionCalls());
            row.addProperty("dominatedTransitionsSkipped", planner.getDominatedTransitionsSkipped());
            row.addProperty("voxelQueries", terrain.voxelQueries - initialVoxelQueries);
            row.addProperty("readMisses", terrain.readMisses - initialReadMisses);
            row.addProperty("shapeMisses", terrain.shapeMisses - initialShapeMisses);
            row.addProperty("chunkQueries", terrain.chunkQueries - initialChunkQueries);
            row.addProperty("chunkMisses", terrain.chunkMisses - initialChunkMisses);
            row.addProperty("pathFingerprintSha256", routePathFingerprint(planner.getPath()));
            row.addProperty("advanceCalls", calls);
            row.addProperty("expanded", planner.getExpandedNodes());
            row.addProperty("discovered", planner.getDiscoveredNodes());
            row.addProperty("status", planner.getStatus().name());
            row.addProperty("pathSteps", planner.getPath() == null ? 0 : planner.getPath().length());
            routeBenchmark.add(row);
            if (planner.getStatus() != dev.lodekeeper.nav.NavStatus.FOUND) {
                fail("Native 20-block benchmark route failed: " + row);
                return;
            }
        }
        System.out.println("[Lodekeeper verification] Native route benchmark: " + routeBenchmark);
    }

    private void observeServerMenu(MinecraftServer server) {
        if (playerId == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        observeFirstServerMovement(player);
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != lastServerMenu) {
            if (menu instanceof CraftingMenu) {
                serverTableOpened = true;
                serverTableOpenings++;
            }
            if (menu instanceof FurnaceMenu) {
                serverFurnaceOpened = true;
                serverFurnaceOpenings++;
            }
            if (menu instanceof SmokerMenu) {
                serverSmokerOpened = true;
                serverSmokerOpenings++;
            }
            if (menu.getClass() == net.minecraft.world.inventory.StonecutterMenu.class) serverStonecutterOpenings++;
            if (menu instanceof BlastFurnaceMenu) {
                serverBlastFurnaceOpened = true;
                serverBlastFurnaceOpenings++;
            }
            lastServerMenu = menu;
        }
        observeCoalNavigationCheckpoint(player, server.getTickCount());
    }

    private void observeCoalNavigationCheckpoint(ServerPlayer player, int serverTick) {
        if (!MIXED_NAVIGATION_COURSE || !coalNavigationCourseCommandStarted) return;
        coalNavigationCourseMinimumHealth = Math.min(coalNavigationCourseMinimumHealth, player.getHealth());
        if (!player.onGround()) return;
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
                    || !client.player.onGround()) return;

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

    private void registerNearbyWoodMeadowLaunchObserver() {
        if (!MEADOW_BENCHMARK || BARITONE_MODE || nearbyWoodLaunchObserverRegistered) return;
        if (isNearbyWoodMeadowLaunchHandoffSettled(LaunchApproach.ARRIVAL_RADIUS / 2,
                FORCED_PREPHYSICS_HANDOFF_SPEED, true)) {
            fail("nearbyWood meadow pure handoff probe accepted speed " + FORCED_PREPHYSICS_HANDOFF_SPEED);
            return;
        }
        ClientTickEvents.START_CLIENT_TICK.register(mc -> observeNearbyWoodMeadowLaunchHandoffAtEngineStart());
        nearbyWoodLaunchObserverRegistered = true;
    }

    private void observeNearbyWoodMeadowLaunchHandoffAtEngineStart() {
        if (!MEADOW_BENCHMARK) return;
        if (state != State.GATHERING_WOOD || client.player == null) {
            resetNearbyWoodMeadowLaunchPathObservation();
            return;
        }
        try {
            Object movement = ENGINE_MOVEMENT_FIELD.get(requireEngine());
            dev.lodekeeper.nav.Path path = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
            if (path == null) {
                resetNearbyWoodMeadowLaunchPathObservation();
                return;
            }
            int pathIndex = MOVEMENT_PATH_INDEX_FIELD.getInt(movement);
            if (path != nearbyWoodLaunchObservedPath) {
                nearbyWoodLaunchObservedPath = path;
                nearbyWoodLaunchPreviousPathIndex = pathIndex;
                nearbyWoodLaunchPathGeneration++;
                return;
            }

            int previousPathIndex = nearbyWoodLaunchPreviousPathIndex;
            nearbyWoodLaunchPreviousPathIndex = pathIndex;
            int indexAdvance = pathIndex - previousPathIndex;
            if (previousPathIndex < 0 || indexAdvance <= 0) {
                if (indexAdvance < 0) nearbyWoodLaunchPathGeneration++;
                return;
            }
            if (indexAdvance > 2) {
                nearbyWoodLaunchPathGeneration++;
                return;
            }

            int reachedPathIndex = pathIndex - 1;
            int sourcePathIndex = reachedPathIndex - 1;
            if (sourcePathIndex < 0 || pathIndex >= path.length()) return;
            dev.lodekeeper.nav.Path.Step reached = path.step(reachedPathIndex);
            dev.lodekeeper.nav.Path.Step outgoing = path.step(pathIndex);
            int validatedPathIndex = MOVEMENT_VALIDATED_PATH_INDEX_FIELD.getInt(movement);
            if (validatedPathIndex != reachedPathIndex
                    || reached.movement != dev.lodekeeper.nav.Path.Movement.WALK
                    || !isNearbyWoodStrictLaunchMovement(outgoing.movement)) return;

            double playerX = client.player.getX();
            double playerY = client.player.getY();
            double playerZ = client.player.getZ();
            var velocity = client.player.getDeltaMovement();
            double offsetX = playerX - (reached.x + .5);
            double offsetY = playerY - reached.feetY();
            double offsetZ = playerZ - (reached.z + .5);
            double centerDistanceXZ = Math.hypot(offsetX, offsetZ);
            double horizontalSpeed = Math.hypot(velocity.x, velocity.z);
            boolean grounded = client.player.onGround();
            boolean settled = isNearbyWoodMeadowLaunchHandoffSettled(centerDistanceXZ, horizontalSpeed, grounded);

            nearbyWoodLaunchHandoffCount++;
            if (outgoing.movement == dev.lodekeeper.nav.Path.Movement.JUMP) nearbyWoodLaunchJumpHandoffCount++;
            if (settled) nearbyWoodLaunchSettledHandoffCount++;
            else nearbyWoodLaunchUnsettledHandoffCount++;
            if (Double.isFinite(horizontalSpeed)) {
                nearbyWoodLaunchMaximumHorizontalSpeed = Math.max(nearbyWoodLaunchMaximumHorizontalSpeed, horizontalSpeed);
            }
            if (nearbyWoodLaunchHandoffs.size() < MAX_NEARBY_WOOD_LAUNCH_HANDOFF_OBSERVATIONS) {
                nearbyWoodLaunchHandoffs.add(new NearbyWoodLaunchHandoffObservation(
                    nearbyWoodLaunchPathGeneration, previousPathIndex, pathIndex, sourcePathIndex,
                    reachedPathIndex, pathIndex, validatedPathIndex, clientTicks + 1,
                    reached.movement.name(), outgoing.movement.name(), playerX, playerY, playerZ,
                    velocity.x, velocity.z, offsetX, offsetY, offsetZ, centerDistanceXZ,
                    horizontalSpeed, grounded, settled));
            }
            if (!settled) {
                fail("nearbyWood meadow WALK-to-" + outgoing.movement.name()
                    + " handoff was unsettled before launch (centerDistanceXZ=" + centerDistanceXZ
                    + ", horizontalSpeed=" + horizontalSpeed + ", grounded=" + grounded
                    + "; required distance < " + LaunchApproach.ARRIVAL_RADIUS
                    + " and speed < " + LaunchApproach.SETTLED_SPEED + " with grounded=true)");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            fail("could not inspect nearbyWood meadow launch handoff: " + exception.getMessage());
        }
    }

    private void resetNearbyWoodMeadowLaunchPathObservation() {
        nearbyWoodLaunchObservedPath = null;
        nearbyWoodLaunchPreviousPathIndex = -1;
    }

    private static boolean isNearbyWoodStrictLaunchMovement(dev.lodekeeper.nav.Path.Movement movement) {
        return movement == dev.lodekeeper.nav.Path.Movement.JUMP
            || movement == dev.lodekeeper.nav.Path.Movement.DROP
            || movement == dev.lodekeeper.nav.Path.Movement.PARKOUR
            || movement == dev.lodekeeper.nav.Path.Movement.BRIDGE;
    }

    private static boolean isNearbyWoodMeadowLaunchHandoffSettled(double centerDistanceXZ,
                                                                   double horizontalSpeed,
                                                                   boolean grounded) {
        return grounded && LaunchApproach.isSettled(centerDistanceXZ, horizontalSpeed, 0);
    }

    private static BlockPos coalRecoveryAccessibleOrePosition() {
        // Interaction candidates extend two cells: x18 forces the earliest valid stance
        // onto x16 at height 68, beyond the jump ledge. x17 could be mined from x15.
        return MIXED_NAVIGATION_COURSE ? new BlockPos(18, PLAYER_Y + 4, 0) : COAL_RECOVERY_ACCESSIBLE_ORE;
    }

    private Map<BlockPos, BlockState> setupMixedCoalNavigationCourse(ServerLevel world) {
        Map<BlockPos, BlockState> expected = new HashMap<>();
        for (int x = -12; x <= 18; x++) {
            for (int z = -6; z <= 6; z++) {
                boolean solidFloor = (x >= -1 && x <= 1 && z >= -1 && z <= 1)
                    || (x >= 1 && x <= 18 && z == 0) || (x == 5 && z == -2);
                setCourseBlock(world, expected, new BlockPos(x, FLOOR_Y, z),
                    solidFloor ? Blocks.BEDROCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = 1; x <= 16; x++) {
            for (int y = 64; y <= 70; y++) {
                for (int z : new int[]{-1, 1}) {
                    setCourseBlock(world, expected, new BlockPos(x, y, z), Blocks.BEDROCK.defaultBlockState());
                }
            }
        }
        setCourseBlock(world, expected, new BlockPos(1, 64, 0),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        setCourseBlock(world, expected, new BlockPos(2, 64, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(3, 64, 0), Blocks.BEDROCK.defaultBlockState());
        setCourseBlock(world, expected, new BlockPos(4, 64, 0), Blocks.DIRT_PATH.defaultBlockState());
        setCourseBlock(world, expected, new BlockPos(5, 64, 0), Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7));
        setCourseBlock(world, expected, new BlockPos(6, 64, 0),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        setCourseBlock(world, expected, new BlockPos(7, 64, 0), bottomEastStairs());
        for (int x = 8; x <= 10; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 64, 0), Blocks.BEDROCK.defaultBlockState());
        }
        setCourseBlock(world, expected, new BlockPos(8, 65, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2));
        setCourseBlock(world, expected, new BlockPos(9, 65, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 4));
        setCourseBlock(world, expected, new BlockPos(10, 65, 0), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8));
        setCourseBlock(world, expected, new BlockPos(11, 65, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(12, 66, 0), bottomEastStairs());
        setCourseBlock(world, expected, new BlockPos(13, 66, 0),
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        setCourseBlock(world, expected, new BlockPos(14, 66, 0), Blocks.DIRT_PATH.defaultBlockState());
        for (int x = 15; x <= 18; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 66, 0), Blocks.BEDROCK.defaultBlockState());
        }
        for (int x = 16; x <= 18; x++) {
            setCourseBlock(world, expected, new BlockPos(x, 67, 0), Blocks.BEDROCK.defaultBlockState());
        }

        // Catch randomized native ore drops after the required one-cell jump corridor.
        // A one-cell ledge lets items bounce off and fall 128 blocks to superflat ground.
        for (int x = 17; x <= 21; x++) for (int z = -2; z <= 2; z++) {
            setCourseBlock(world, expected, new BlockPos(x, 67, z), Blocks.BEDROCK.defaultBlockState());
        }

        BlockPos water = new BlockPos(5, 64, -2);
        setCourseBlock(world, expected, water, Blocks.WATER.defaultBlockState());
        for (BlockPos surround : List.of(water.above(), water.below(), water.north(), water.south(), water.east(), water.west())) {
            setCourseBlock(world, expected, surround, Blocks.BEDROCK.defaultBlockState());
        }
        setCourseBlock(world, expected, new BlockPos(-8, 64, 0), Blocks.OAK_FENCE.defaultBlockState());
        return expected;
    }

    private static BlockState bottomEastStairs() {
        return Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.EAST)
            .setValue(StairBlock.HALF, Half.BOTTOM);
    }

    private static void setCourseBlock(ServerLevel world, Map<BlockPos, BlockState> expected, BlockPos position, BlockState state) {
        world.setBlockAndUpdate(position, state);
        expected.put(position, state);
    }

    private Map<BlockPos, BlockState> mixedCoalNavigationExpectedStates(Map<BlockPos, BlockState> course) {
        Map<BlockPos, BlockState> expected = new HashMap<>(course);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    expected.put(COAL_RECOVERY_ENCASED_ORE.offset(dx, dy, dz), Blocks.BEDROCK.defaultBlockState());
                }
            }
        }
        expected.put(COAL_RECOVERY_ENCASED_ORE, Blocks.COAL_ORE.defaultBlockState());
        return Map.copyOf(expected);
    }

    private int coalNavigationCourseMismatchCount(ServerLevel world) {
        int mismatches = 0;
        for (Map.Entry<BlockPos, BlockState> entry : coalNavigationExpectedStates.entrySet()) {
            if (!world.getBlockState(entry.getKey()).equals(entry.getValue())) mismatches++;
        }
        return mismatches;
    }

    private void startGatherCommand() {
        if (GEOMETRY_EPOCH_MODE && !geometryEpochComplete) {
            if (geometryEpoch == null) geometryEpoch = new GeometryEpochVerification();
            try {
                JsonObject result = geometryEpoch.advance(client, requireEngine().config);
                if (result == null) return;
                geometryEpochComplete = true;
            } catch (RuntimeException exception) {
                geometryEpoch.markFailure(exception.getMessage());
                fail("Geometry epoch verification failed: " + exception.getMessage());
                return;
            }
        }
        if (NEARBY_WOOD_MODE) {
            if (!BARITONE_MODE && !GEOMETRY_EPOCH_MODE) {
                benchmarkNativeRoute();
                if (state == State.FAILED) return;
            }
            activeCase = "nearby_" + NEARBY_WOOD_GOAL + "_20_blocks" + (MEADOW_BENCHMARK ? "_meadow" : "");
            activeItem = NEARBY_WOOD_GOAL.equals("wood") ? OAK_LOG_ID : "minecraft:crafting_table";
            activeCount = 1;
            activeRequiresEmpty = true;
            activeStartedEmpty = latestSnapshot.inventoryEmpty();
            beginCaseClock();
            sendCommand("!lk get " + NEARBY_WOOD_GOAL + " 1");
            state = State.GATHERING_WOOD;
            return;
        }
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
            resourceInitiallyLoaded = client.level.getChunkSource().getChunk(5, 0,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null;
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
        boolean loadedFence = probe.loaded && client.level.getBlockState(fence).is(Blocks.OAK_FENCE);
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

    private static int countCoalStartSurface(ServerLevel world) {
        int count = 0;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (world.getBlockState(new BlockPos(x, FLOOR_Y, z)).is(coalStartFloor())) count++;
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
        if (activeCase == null || state == State.CAPTURING || latestSnapshot == null) return;
        if (STONECUTTING_DRAIN_MODE && state == State.COOKING && !stonecuttingDrainStopInjected
                && latestSnapshot.count(cookingOutputId()) > 0) {
            fail("stonecutting drain stop was not injected before the first slab output");
            return;
        }
        if (!BARITONE_MODE && (NEARBY_WOOD_MODE || IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE)) {
            if (liveRouteScreenshot == null) {
                try {
                    Object movement = ENGINE_MOVEMENT_FIELD.get(requireEngine());
                    dev.lodekeeper.nav.Path route = (dev.lodekeeper.nav.Path) MOVEMENT_PATH_FIELD.get(movement);
                    int nextStep = MOVEMENT_PATH_INDEX_FIELD.getInt(movement);
                    if (route != null && nextStep >= 2 && nextStep < route.length()
                            && requireEngine().status().startsWith("route ")) {
                        if (firstRouteTick < 0) firstRouteTick = clientTicks;
                        if (clientTicks - firstRouteTick >= 8) liveRouteScreenshot = capture(activeCase + "-active-route");
                    }
                } catch (ReflectiveOperationException exception) {
                    fail("Cannot inspect active route for capture: " + exception);
                    return;
                }
            }
        }
        if (BARITONE_MODE && liveRouteScreenshot == null
                && baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().hasPath()) {
            if (firstRouteTick < 0) firstRouteTick = clientTicks;
            if (clientTicks - firstRouteTick >= 8) liveRouteScreenshot = capture(activeCase + "-baritone-route");
        }
        int observed = latestSnapshot.count(activeItem);
        if (MEADOW_BENCHMARK && !BARITONE_MODE && state == State.GATHERING_WOOD && observed >= activeCount
                && requireEngine().status().startsWith("idle") && nearbyWoodLaunchJumpHandoffCount == 0) {
            fail("nearbyWood meadow reached its item target without a client-observed WALK-to-JUMP handoff");
            return;
        }
        boolean targetReached = (COAL_RECOVERY_MODE || BULK_WOOD_MODE || PROCESSING_MODE || IRON_PICKAXE_MODE ? observed == activeCount : observed >= activeCount)
            && requireEngine().status().startsWith("idle")
            && (BARITONE_MODE || !MEADOW_BENCHMARK || (nearbyWoodLaunchJumpHandoffCount > 0
                && nearbyWoodLaunchUnsettledHandoffCount == 0))
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
            && (state != State.CUSTOM_CONTENT || serverTableOpenings > activeTableOpeningsAtStart)
            && (!BARITONE_MODE || (baritoneMiningObserved
                && !baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()
                && !baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().hasPath()
                && baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().getInProgress().isEmpty()));
        if (targetReached && EXPLORATION_MODE && state == State.GATHERING_WOOD
                && (requireEngine().explorationAttemptsMade() == 0 || latestSnapshot.x <= 48)) {
            fail("far resource goal completed without observed bounded exploration travel"); return;
        }
        if (targetReached && state == State.GATHERING_FOOD
            && (latestSnapshot.foodLevel <= activeFoodLevelAtStart
                || latestSnapshot.count(VerificationContentInitializer.BREAD_ID) >= activeBreadCountAtStart)) {
            fail("food-use goal completed without server-confirmed bread consumption and hunger recovery");
        } else if (targetReached) {
            String detail = STONECUTTING_DRAIN_MODE
                ? "sent ordinary !lk stop at client tick " + stonecuttingDrainStopClientTick + " with "
                    + stonecuttingDrainInputCountAtStop + " owned stone in the native stonecutter; fresh server tick "
                    + latestSnapshot.serverTick + " observed exact starting stock restored with zero slabs (command target 144, expected output 0)"
                : PROCESSING_MODE
                ? "server inventory reached " + activeCount + " " + cookingOutputId() + " with 56 inputs remaining after opening the native " + PROCESSING_STATION_MODE + " menu"
                : MEADOW_BENCHMARK
                ? "server inventory reached the meadow nearby-wood target after "
                    + nearbyWoodLaunchJumpHandoffCount + " client-observed settled WALK-to-JUMP handoffs"
                : BULK_WOOD_MODE
                ? (WOOD_TOOLS_MODE
                    ? "server inventory reached exactly 64 oak logs after opening the crafting table and acquiring at least two wooden axes"
                    : "server inventory reached exactly 64 oak logs with no wooden axes or crafting table opening")
                : IRON_PICKAXE_MODE
                ? IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
                    ? "server inventory reached one iron pickaxe at full health from empty inventory with oak logs at x="
                        + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + " through x="
                        + (IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT - 1)
                        + " after native crafting-table and furnace menu openings"
                    : "server inventory reached one iron pickaxe at full health after native crafting-table and furnace menu openings"
                : NEARBY_WOOD_MODE
                ? "server inventory reached one " + activeItem + " from empty inventory with oak logs 20 blocks away"
                : COAL_RECOVERY_MODE
                ? "server inventory reached exactly one coal at full health after rejecting the nearer encased ore and mining the accessible ore"
                : DIAMOND_BOOTSTRAP_MODE
                ? "server inventory reached diamond boots after the integrated server observed crafting table and furnace menus and an iron pickaxe"
                : switch (state) {
                case CUSTOM_CONTENT -> "server inventory reached the custom recipe output after opening the server crafting table";
                case GATHERING_FOOD -> "server inventory reached the log target; bread was consumed and hunger rose from "
                    + activeFoodLevelAtStart + " to " + latestSnapshot.foodLevel;
                default -> "server inventory reached target and the engine returned idle";
            };
            addResult(true, observed, detail);
            if (state == State.GATHERING_WOOD && (EXPLORATION_MODE || DIAMOND_BOOTSTRAP_MODE || IRON_PICKAXE_MODE || BULK_WOOD_MODE || NEARBY_WOOD_MODE)) {
                state = State.CAPTURING; captureStartedAtTick = clientTicks;
            } else if (state == State.GATHERING_WOOD) startCraftingTableCommand();
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
        } else if (COAL_RECOVERY_MODE && state == State.GATHERING_COAL_RECOVERY && observed >= activeCount
                && requireEngine().status().startsWith("idle") && !coalRecoveryOutcomeObserved()) {
            fail("coal output was observed without the required encased-target rejection and accessible-ore fixture proof");
        } else if (requireEngine().status().startsWith("paused")) {
            fail(STONECUTTING_DRAIN_MODE && !stonecuttingDrainStopInjected
                ? "stonecutting drain stop was never injected before automation paused: " + requireEngine().status()
                : "automation paused during " + activeCase + ": " + requireEngine().status());
        } else if (clientTicks - caseStartedAtTick > MAX_RUN_TICKS) {
            fail(STONECUTTING_DRAIN_MODE && !stonecuttingDrainStopInjected
                ? "stonecutting drain stop was never injected because owned input with an empty cursor was not observed"
                : "case timed out: " + activeCase + "; engine status=" + requireEngine().status());
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
        caseStartedAtNanos = System.nanoTime();
        firstMovementMillis = -1;
        movementClock = latestSnapshot == null ? null : new MovementClock(
            caseStartedAtNanos, latestSnapshot.x, latestSnapshot.z);
        caseStartedAtWorldTime = client.level == null ? 0 : client.level.getGameTime();
        firstRouteTick = -1;
        liveRouteScreenshot = null;
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
        if (client.getConnection() == null) throw new IllegalStateException("integrated client is not connected");
        client.getConnection().sendChat(command);
    }

    private void requestObservation() {
        if (observationFuture != null || client.player == null || client.getSingleplayerServer() == null) return;
        MinecraftServer server = requireServer();
        CompletableFuture<ServerSnapshot> capture = new CompletableFuture<>();
        long requestSequence = ++observationRequestSequence;
        observationFuture = capture;
        server.execute(() -> {
            try {
                ServerPlayer player = requireServerPlayer(server);
                ServerInventorySnapshot inventory = inventorySnapshot(player);
                ServerLevel world = player.level();
                capture.complete(new ServerSnapshot(server.getTickCount(), world.getGameTime(), inventory.counts(),
                    inventory.woodenAxeRemainingDurability(),
                    IRON_PICKAXE_MODE ? countIronPickaxeDeepslate(world) : -1,
                    COAL_RECOVERY_MODE && world.getBlockState(COAL_RECOVERY_ENCASED_ORE).is(Blocks.COAL_ORE) ? 1 : COAL_RECOVERY_MODE ? 0 : -1,
                    COAL_RECOVERY_MODE && world.getBlockState(coalRecoveryAccessibleOrePosition()).is(Blocks.COAL_ORE) ? 1 : COAL_RECOVERY_MODE ? 0 : -1,
                    COAL_RECOVERY_MODE ? countCoalStartSurface(world) : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseMismatchCount(world) : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseObservedMask : -1,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCourseMinimumHealth : -1.0F,
                    MIXED_NAVIGATION_COURSE ? coalNavigationCheckpointServerTicksSnapshot() : List.of(),
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
            latestObservationRequestSequence = requestSequence;
        }));
    }

    private static ServerInventorySnapshot inventorySnapshot(ServerPlayer player) {
        Map<String, Integer> counts = new HashMap<>();
        List<Integer> woodenAxes = new ArrayList<>();
        player.getInventory().getNonEquipmentItems().forEach(stack -> countStack(stack, counts, woodenAxes));
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND)) {
            countStack(player.getItemBySlot(slot), counts, woodenAxes);
        }
        return new ServerInventorySnapshot(counts, woodenAxes);
    }

    private static int countIronPickaxeDeepslate(ServerLevel world) {
        int remaining = 0;
        for (int index = 0; index < IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT; index++) {
            if (world.getBlockState(new BlockPos(16 + index, 20, 4)).is(Blocks.DEEPSLATE)) remaining++;
        }
        return remaining;
    }

    private static void countStack(ItemStack stack, Map<String, Integer> counts, List<Integer> woodenAxes) {
        if (stack.isEmpty()) return;
        counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        if (stack.getItem() == Items.WOODEN_AXE) woodenAxes.add(stack.getMaxDamage() - stack.getDamageValue());
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
            activeRequiresEmpty, passed && (!activeRequiresEmpty || activeStartedEmpty), clientTicks - caseStartedAtTick, gameTicks,
            Math.max(0, (System.nanoTime() - caseStartedAtNanos) / 1_000_000L),
            requireEngine().status(), detail, latestSnapshot == null ? 0 : latestSnapshot.health,
            latestSnapshot == null ? "unknown" : latestSnapshot.difficulty,
            latestSnapshot != null && serverTableOpenings > activeTableOpeningsAtStart,
            serverFurnaceOpenings > activeFurnaceOpeningsAtStart,
            latestSnapshot == null ? 0 : latestSnapshot.count(IRON_PICKAXE_ID),
            latestSnapshot == null ? 0 : activeFoodLevelAtStart, latestSnapshot == null ? 0 : latestSnapshot.foodLevel,
            latestSnapshot == null ? 0 : activeBreadCountAtStart,
            latestSnapshot == null ? 0 : latestSnapshot.count(VerificationContentInitializer.BREAD_ID),
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

    private void observeFirstServerMovement(ServerPlayer player) {
        MovementClock clock = movementClock;
        if (!(NEARBY_WOOD_MODE || IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE)
                || clock == null || firstMovementMillis >= 0) return;
        if (Math.abs(player.getX() - clock.x) > 0.1 || Math.abs(player.getZ() - clock.z) > 0.1) {
            firstMovementMillis = Math.max(0, (System.nanoTime() - clock.startedAtNanos) / 1_000_000L);
        }
    }

    private record MovementClock(long startedAtNanos, double x, double z) { }

    private void finishRun() {
        if (NEARBY_WOOD_MODE || IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) {
            if (firstMovementMillis < 0) {
                fail("Server movement timestamp was not recorded");
                return;
            }
            try {
                if (liveRouteScreenshot == null || !Files.isRegularFile(evidenceDirectory.resolve(liveRouteScreenshot))
                        || Files.size(evidenceDirectory.resolve(liveRouteScreenshot)) == 0
                        || javax.imageio.ImageIO.read(evidenceDirectory.resolve(liveRouteScreenshot).toFile()) == null) {
                    fail("Active-route screenshot was not saved");
                    return;
                }
            } catch (java.io.IOException exception) {
                fail("Cannot verify saved active-route screenshot: " + exception.getMessage());
                return;
            }
        }
        state = State.COMPLETE;
        int expectedCases = EXPLORATION_MODE || DIAMOND_BOOTSTRAP_MODE || NEARBY_WOOD_MODE
            || IRON_PICKAXE_MODE || COAL_RECOVERY_MODE || BULK_WOOD_MODE || PROCESSING_MODE ? 1 : 9;
        boolean passed = results.size() == expectedCases && results.stream().allMatch(CaseResult::passed);
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

    private JsonObject nearbyWoodMeadowLaunchHandoffEvidence() {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("evidenceAuthority", "client_player_position_velocity_and_ground_state");
        evidence.addProperty("serverPhysicsTimingIncluded", false);
        evidence.addProperty("sampleRegistrationPhase", "first_end_client_tick");
        evidence.addProperty("samplePhase", "start_client_tick_after_lodekeeper_engine_callback_before_client_physics");
        evidence.addProperty("observerRegistered", nearbyWoodLaunchObserverRegistered);
        JsonObject forcedProbe = new JsonObject();
        forcedProbe.addProperty("kind", "pure_settled_predicate");
        forcedProbe.addProperty("horizontalSpeed", FORCED_PREPHYSICS_HANDOFF_SPEED);
        forcedProbe.addProperty("grounded", true);
        forcedProbe.addProperty("settled", isNearbyWoodMeadowLaunchHandoffSettled(
            LaunchApproach.ARRIVAL_RADIUS / 2, FORCED_PREPHYSICS_HANDOFF_SPEED, true));
        evidence.add("forcedHandoffProbe", forcedProbe);
        evidence.addProperty("observationLimit", MAX_NEARBY_WOOD_LAUNCH_HANDOFF_OBSERVATIONS);
        evidence.addProperty("handoffCount", nearbyWoodLaunchHandoffCount);
        evidence.addProperty("jumpHandoffCount", nearbyWoodLaunchJumpHandoffCount);
        evidence.addProperty("settledHandoffCount", nearbyWoodLaunchSettledHandoffCount);
        evidence.addProperty("unsettledHandoffCount", nearbyWoodLaunchUnsettledHandoffCount);
        evidence.addProperty("maximumHorizontalSpeed", nearbyWoodLaunchMaximumHorizontalSpeed);
        JsonObject contract = new JsonObject();
        contract.addProperty("centerDistanceXZLessThan", LaunchApproach.ARRIVAL_RADIUS);
        contract.addProperty("horizontalSpeedLessThan", LaunchApproach.SETTLED_SPEED);
        contract.addProperty("grounded", true);
        evidence.add("settledContract", contract);
        evidence.addProperty("recordedObservationCount", nearbyWoodLaunchHandoffs.size());
        JsonArray observations = new JsonArray();
        for (NearbyWoodLaunchHandoffObservation observation : nearbyWoodLaunchHandoffs) {
            JsonObject item = new JsonObject();
            item.addProperty("pathGeneration", observation.pathGeneration);
            item.addProperty("previousPathIndex", observation.previousPathIndex);
            item.addProperty("pathIndex", observation.pathIndex);
            item.addProperty("sourcePathIndex", observation.sourcePathIndex);
            item.addProperty("reachedPathIndex", observation.reachedPathIndex);
            item.addProperty("outgoingPathIndex", observation.outgoingPathIndex);
            item.addProperty("validatedPathIndex", observation.validatedPathIndex);
            item.addProperty("clientTick", observation.clientTick);
            item.addProperty("incomingMovement", observation.incomingMovement);
            item.addProperty("outgoingMovement", observation.outgoingMovement);
            JsonArray position = new JsonArray();
            addFiniteJsonNumber(position, observation.playerX);
            addFiniteJsonNumber(position, observation.playerY);
            addFiniteJsonNumber(position, observation.playerZ);
            item.add("playerPosition", position);
            JsonArray velocity = new JsonArray();
            addFiniteJsonNumber(velocity, observation.velocityX);
            addFiniteJsonNumber(velocity, observation.velocityZ);
            item.add("horizontalVelocity", velocity);
            JsonArray centerOffset = new JsonArray();
            addFiniteJsonNumber(centerOffset, observation.offsetX);
            addFiniteJsonNumber(centerOffset, observation.offsetY);
            addFiniteJsonNumber(centerOffset, observation.offsetZ);
            item.add("reachedCenterOffset", centerOffset);
            item.add("centerDistanceXZ", finiteJsonNumber(observation.centerDistanceXZ));
            item.add("horizontalSpeed", finiteJsonNumber(observation.horizontalSpeed));
            item.addProperty("grounded", observation.grounded);
            item.addProperty("settled", observation.settled);
            observations.add(item);
        }
        evidence.add("observations", observations);
        return evidence;
    }

    private static com.google.gson.JsonElement finiteJsonNumber(double value) {
        return Double.isFinite(value) ? new com.google.gson.JsonPrimitive(value) : com.google.gson.JsonNull.INSTANCE;
    }

    private static void addFiniteJsonNumber(JsonArray array, double value) {
        array.add(finiteJsonNumber(value));
    }

    private void writeEvidence(String status) {
        if (evidenceDirectory == null || runId == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("runId", runId);
            root.addProperty("status", status);
            root.addProperty("minecraftVersion", VerificationApi.minecraftVersion());
            root.addProperty("navigationBackend", BARITONE_MODE ? "baritone" : "original");
            root.addProperty("baritoneMiningObserved", baritoneMiningObserved);
            root.addProperty("worldKind", "isolated_superflat_fixture");
            root.addProperty("evidenceAuthority", "integrated_server_inventory_menu_and_hunger");
            root.addProperty("verificationMode", verificationMode());
            if (GEOMETRY_EPOCH_MODE) root.add("geometryEpoch", geometryEpoch == null
                ? com.google.gson.JsonNull.INSTANCE : geometryEpoch.evidence());
            root.addProperty("runDirectory", client.gameDirectory.toPath().toRealPath().toString());
            root.addProperty("worldId", worldId);
            root.addProperty("elapsedMillis", (System.nanoTime() - startedAtNanos) / 1_000_000L);
            root.addProperty("clientTicks", clientTicks);
            root.addProperty("failure", failure);
            root.addProperty("serverTableOpened", serverTableOpened);
            root.addProperty("serverFurnaceOpened", serverFurnaceOpened);
            root.addProperty("serverTableOpenings", serverTableOpenings);
            root.addProperty("serverFurnaceOpenings", serverFurnaceOpenings);
            root.addProperty("ironPickaxeFixture", IRON_PICKAXE_MODE);
            root.addProperty("ironPickaxeEmptyDistantWoodFlag", IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE);
            root.addProperty("nearbyWoodFlag", NEARBY_WOOD_MODE);
            root.addProperty("nearbyWoodGoal", NEARBY_WOOD_GOAL);
            root.addProperty("nearbyWoodTerrain", NEARBY_WOOD_TERRAIN);
            if (NEARBY_WOOD_MODE) root.add("nativeRouteBenchmark", routeBenchmark == null
                ? com.google.gson.JsonNull.INSTANCE : routeBenchmark.deepCopy());
            if (MEADOW_BENCHMARK) {
                root.add("nearbyWoodMeadowLaunchHandoffEvidence", nearbyWoodMeadowLaunchHandoffEvidence());
            }
            if (IRON_PICKAXE_MODE) {
                JsonObject initialStock = new JsonObject();
                if (!IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) initialStock.addProperty("minecraft:crafting_table", 1);
                root.add("ironPickaxeFixtureProvidedStock", initialStock);
                root.addProperty("ironPickaxeGoalCommand", "!lk get iron_pickaxe");
                root.addProperty("ironPickaxeExpectedOutput", 1);
                root.addProperty("ironPickaxeNativeTableOpenings", serverTableOpenings);
                root.addProperty("ironPickaxeNativeFurnaceOpenings", serverFurnaceOpenings);
                root.addProperty("ironPickaxeDeepslateFixtureInitialBlockCount", IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT);
                JsonArray deepslatePositions = new JsonArray();
                for (int index = 0; index < IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT; index++) {
                    deepslatePositions.add((16 + index) + ",20,4");
                }
                root.add("ironPickaxeDeepslateFixturePositions", deepslatePositions);
                root.addProperty("ironPickaxeDeepslateFixtureRemainingBlockCount",
                    latestSnapshot == null ? -1 : latestSnapshot.ironPickaxeDeepslateRemaining);
                root.addProperty("ironPickaxeDeepslateFixtureMinedBlockCount", latestSnapshot == null ? -1
                    : IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT - latestSnapshot.ironPickaxeDeepslateRemaining);
                if (ironPickaxePlannerProbeEvidence != null) root.add("ironPickaxePlannerProbe", ironPickaxePlannerProbeEvidence);
                if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) {
                    root.addProperty("ironPickaxeEmptyDistantWoodFixture", true);
                    if (ironPickaxeFirstVerifiedServerStock == null) {
                        root.add("ironPickaxeFirstVerifiedServerStock", com.google.gson.JsonNull.INSTANCE);
                        root.add("ironPickaxeFirstVerifiedServerStockTick", com.google.gson.JsonNull.INSTANCE);
                    } else {
                        JsonObject firstStock = new JsonObject();
                        ironPickaxeFirstVerifiedServerStock.forEach(firstStock::addProperty);
                        root.add("ironPickaxeFirstVerifiedServerStock", firstStock);
                        root.addProperty("ironPickaxeFirstVerifiedServerStockTick", ironPickaxeFirstVerifiedServerStockTick);
                    }
                    JsonArray logPositions = new JsonArray();
                    for (int index = 0; index < IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_COUNT; index++) {
                        logPositions.add((IRON_PICKAXE_EMPTY_DISTANT_WOOD_LOG_START_X + index) + ","
                            + PLAYER_Y + ",0");
                    }
                    root.add("ironPickaxeFixtureOakLogPositions", logPositions);
                }
            }
            root.addProperty("coalRecoveryFixture", COAL_RECOVERY_MODE);
            if (COAL_RECOVERY_MODE) {
                root.addProperty("coalStartSurface", COAL_START_SURFACE);
                if (Double.isFinite(coalInitialServerFeetY)) root.addProperty("coalStartSurfaceInitialServerFeetY", coalInitialServerFeetY);
                else root.add("coalStartSurfaceInitialServerFeetY", com.google.gson.JsonNull.INSTANCE);
                root.addProperty("coalStartSurfaceInitialBlockCount", 9);
                root.addProperty("coalStartSurfaceRemainingBlockCount", latestSnapshot == null ? -1 : latestSnapshot.coalStartSurfaceRemaining);
            }
            if (COAL_RECOVERY_MODE) {
                BlockPos accessibleCoal = coalRecoveryAccessibleOrePosition();
                JsonObject initialStock = new JsonObject();
                initialStock.addProperty("minecraft:stone_pickaxe", 1);
                root.add("coalRecoveryFixtureProvidedStock", initialStock);
                root.addProperty("coalRecoveryGoalCommand", "!lk get coal");
                root.addProperty("coalRecoveryExpectedOutput", 1);
                root.addProperty("coalRecoveryEncasedOrePosition", "6," + PLAYER_Y + ",2");
                root.addProperty("coalRecoveryAccessibleOrePosition", blockPosition(accessibleCoal));
                root.addProperty("coalRecoveryEncasedOreInitialBlockCount", 1);
                root.addProperty("coalRecoveryEncasedOreRemainingBlockCount",
                    latestSnapshot == null ? -1 : latestSnapshot.coalRecoveryEncasedOreRemaining);
                root.addProperty("coalRecoveryBlockedTargetRejected", coalRecoveryTargetRejected());
                root.addProperty("coalRecoveryAccessibleOreInitialBlockCount", 1);
                root.addProperty("coalRecoveryAccessibleOreRemainingBlockCount",
                    latestSnapshot == null ? -1 : latestSnapshot.coalRecoveryAccessibleOreRemaining);
                root.addProperty("coalRecoveryAccessibleOreMined",
                    latestSnapshot != null && latestSnapshot.coalRecoveryAccessibleOreRemaining == 0);
                root.addProperty("coalRecoveryCompletionHealth", latestSnapshot == null ? -1.0F : latestSnapshot.health);
                JsonObject completionInventory = new JsonObject();
                if (latestSnapshot != null) latestSnapshot.inventory.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> completionInventory.addProperty(entry.getKey(), entry.getValue()));
                root.add("coalRecoveryCompletionInventory", completionInventory);
                root.addProperty("coalRecoveryOutcomeVerified", coalRecoveryOutcomeObserved());
                if (MIXED_NAVIGATION_COURSE) {
                    int observedMask = latestSnapshot == null ? coalNavigationCourseObservedMask
                        : latestSnapshot.coalNavigationCourseObservedMask;
                    int mismatchCount = latestSnapshot == null ? -1 : latestSnapshot.coalNavigationCourseMismatchCount;
                    root.addProperty("navigationCourse", "mixed");
                    if (Double.isFinite(coalNavigationInitialServerFeetX)) root.addProperty("coalNavigationCourseInitialServerFeetX", coalNavigationInitialServerFeetX);
                    if (Double.isFinite(coalNavigationInitialServerFeetZ)) root.addProperty("coalNavigationCourseInitialServerFeetZ", coalNavigationInitialServerFeetZ);
                    if (coalNavigationFenceGeometryEvidence != null) root.add("coalNavigationFenceGeometry", coalNavigationFenceGeometryEvidence);
                    if (coalNavigationRouteDiagnostics != null) root.add("coalNavigationRouteDiagnostics", coalNavigationRouteDiagnostics);
                    root.addProperty("coalNavigationCourseAccessibleOrePosition", blockPosition(accessibleCoal));
                    root.addProperty("coalNavigationCourseCheckpointExpectedMask", coalNavigationExpectedMask());
                    root.addProperty("coalNavigationCourseCheckpointObservedMask", observedMask);
                    root.addProperty("coalNavigationCourseAllCheckpointsVisited", observedMask == coalNavigationExpectedMask());
                    root.addProperty("coalNavigationCourseProtectedStateCount", coalNavigationExpectedStates.size());
                    root.addProperty("coalNavigationCourseProtectedStatesPreserved",
                        mismatchCount < 0 ? -1 : coalNavigationExpectedStates.size() - mismatchCount);
                    root.addProperty("coalNavigationCourseStateMismatches", mismatchCount);
                    root.addProperty("coalNavigationCourseMinimumServerHealth",
                        latestSnapshot == null ? coalNavigationCourseMinimumHealth : latestSnapshot.coalNavigationCourseMinimumHealth);
                    root.addProperty("coalNavigationCourseAllowBreaking", true);
                    root.addProperty("coalNavigationCourseAllowBuilding", false);
                    root.addProperty("coalNavigationCourseAllowParkour", false);
                    root.addProperty("coalNavigationCourseStairEdgeCompleted", coalNavigationStairEdgeCompleted);
                    root.addProperty("coalNavigationCourseStairEdgeMovement", coalNavigationStairEdgeMovement);
                    root.addProperty("coalNavigationCourseStairEdgeCompletedPathIndex", coalNavigationStairEdgePathIndex);
                    root.addProperty("coalNavigationCourseStairEdgeTraversalCount", coalNavigationStairEdgeCompleted ? 1 : 0);
                    root.addProperty("coalNavigationCourseLedgeEdgeCompleted", coalNavigationLedgeEdgeCompleted);
                    root.addProperty("coalNavigationCourseLedgeEdgeMovement", coalNavigationLedgeEdgeMovement);
                    root.addProperty("coalNavigationCourseLedgeEdgeCompletedPathIndex", coalNavigationLedgeEdgePathIndex);
                    root.addProperty("coalNavigationCourseLedgeEdgeTraversalCount", coalNavigationLedgeEdgeCompleted ? 1 : 0);
                    JsonArray checkpoints = new JsonArray();
                    for (int checkpointIndex = 0; checkpointIndex < COAL_NAVIGATION_CHECKPOINTS.size(); checkpointIndex++) {
                        CoalNavigationCheckpoint checkpoint = COAL_NAVIGATION_CHECKPOINTS.get(checkpointIndex);
                        JsonObject cell = new JsonObject();
                        cell.addProperty("xCell", checkpoint.xCell);
                        cell.addProperty("zCell", 0);
                        cell.addProperty("feetY16", checkpoint.feetY16);
                        cell.addProperty("feetY", checkpoint.feetY16 / 16.0);
                        cell.addProperty("firstServerTick", latestSnapshot == null
                            || latestSnapshot.coalNavigationCourseCheckpointServerTicks.size() <= checkpointIndex
                                ? -1 : latestSnapshot.coalNavigationCourseCheckpointServerTicks.get(checkpointIndex));
                        checkpoints.add(cell);
                    }
                    root.add("coalNavigationCourseCheckpoints", checkpoints);
                    JsonObject stairFlight = new JsonObject();
                    stairFlight.addProperty("lower", "11,65,0;feet=66");
                    stairFlight.addProperty("upper", "12,66,0;feet=67");
                    stairFlight.addProperty("expectedMethod", "walk_up_stairs");
                    root.add("coalNavigationCourseStairFlight", stairFlight);
                    JsonObject ledge = new JsonObject();
                    ledge.addProperty("kind", "one_block_full_cube_jump_ledge");
                    ledge.addProperty("approach", "15,66,0;feet=67");
                    ledge.addProperty("landing", "16,67,0;feet=68");
                    JsonArray upperBlocks = new JsonArray();
                    upperBlocks.add("16,67,0");
                    upperBlocks.add("17,67,0");
                    upperBlocks.add("18,67,0");
                    ledge.add("upperBlocks", upperBlocks);
                    root.add("coalNavigationCourseOneBlockJumpLedge", ledge);
                    root.addProperty("coalNavigationCourseHydrationWater", "5,64,-2");
                    JsonArray waterShell = new JsonArray();
                    for (String position : List.of("5,65,-2", "5,63,-2", "5,64,-3", "5,64,-1", "6,64,-2", "4,64,-2")) {
                        waterShell.add(position);
                    }
                    root.add("coalNavigationCourseWaterBedrockShell", waterShell);
                }
            }
            if (PROCESSING_MODE) {
                root.addProperty("serverSmokerOpened", serverSmokerOpened);
                root.addProperty("serverSmokerOpenings", serverSmokerOpenings);
                root.addProperty("serverBlastFurnaceOpened", serverBlastFurnaceOpened);
                root.addProperty("serverBlastFurnaceOpenings", serverBlastFurnaceOpenings);
                root.addProperty(STONECUTTING_MODE ? "processingStation" : "cookingStationProperty", PROCESSING_STATION_MODE);
                root.addProperty("serverStonecutterOpenings", serverStonecutterOpenings);
                root.addProperty(STONECUTTING_MODE ? "nativeRecipeType" : "cookingRecipeType", cookingRecipeType());
                root.addProperty(STONECUTTING_MODE ? "stonecuttingFixture" : "cookingStationFixture", true);
                JsonObject providedStock = new JsonObject();
                if (STONECUTTING_MODE || isSupportedCookingStationMode()) {
                cookingProvidedStock().forEach(providedStock::addProperty);
                }
                root.add(STONECUTTING_MODE ? "stonecuttingFixtureProvidedStock" : "cookingFixtureProvidedStock", providedStock);
                if (STONECUTTING_DRAIN_MODE) {
                    root.addProperty("stonecuttingDrainStopAttempted", stonecuttingDrainStopAttempted);
                    root.addProperty("stonecuttingDrainStopInjected", stonecuttingDrainStopInjected);
                    root.addProperty("stonecuttingDrainOriginalCommandTarget", STONECUTTING_DRAIN_COMMAND_TARGET);
                    root.addProperty("stonecuttingDrainExpectedOutput", 0);
                    root.addProperty("stonecuttingDrainInputCountAtStop", stonecuttingDrainInputCountAtStop);
                    root.addProperty("stonecuttingDrainStopClientTick", stonecuttingDrainStopClientTick);
                    root.addProperty("stonecuttingDrainServerObservationTickBeforeStop", stonecuttingDrainStopServerTick);
                    root.addProperty("stonecuttingDrainFreshServerObservationTick",
                        latestSnapshot == null ? -1 : latestSnapshot.serverTick);
                    root.addProperty("stonecuttingDrainRequiredObservationSequence", stonecuttingDrainRequiredObservationSequence);
                    root.addProperty("stonecuttingDrainLatestObservationSequence", latestObservationRequestSequence);
                }
            }
            JsonArray cases = new JsonArray();
            for (CaseResult result : results) {
                JsonObject item = new JsonObject();
                item.addProperty("name", result.name);
                item.addProperty("item", result.item);
                item.addProperty("expected", result.expected);
                item.addProperty("serverObserved", result.observed);
                item.addProperty("inventoryEmptyAtStart", result.inventoryEmptyAtStart);
                if (PROCESSING_MODE) item.addProperty("requiresEmptyAtStart", result.requiresEmptyAtStart);
                item.addProperty("passed", result.passed);
                item.addProperty("clientTicks", result.clientTicks);
                item.addProperty("worldTicks", result.worldTicks);
                item.addProperty("engineStatus", result.engineStatus);
                item.addProperty("detail", result.detail);
                item.addProperty("serverHealth", result.health);
                item.addProperty("serverDifficulty", result.difficulty);
                item.addProperty("serverCraftingTableOpenedDuringCase", result.tableOpenedDuringCase);
                item.addProperty("serverFurnaceOpenedDuringCase", result.furnaceOpenedDuringCase);
                item.addProperty("serverIronPickaxeCount", result.ironPickaxeCount);
                item.addProperty("serverFoodLevelAtStart", result.foodLevelAtStart);
                item.addProperty("serverFoodLevelObserved", result.foodLevelObserved);
                item.addProperty("serverBreadAtStart", result.breadAtStart);
                item.addProperty("serverBreadObserved", result.breadObserved);
                item.addProperty("serverPosition", result.x + "," + result.y + "," + result.z);
                if (NEARBY_WOOD_MODE) {
                    item.addProperty("elapsedMillisFromCommand", result.elapsedMillis);
                    item.addProperty("firstServerMovementMillisFromCommand", firstMovementMillis);
                    item.addProperty("liveRouteScreenshot", liveRouteScreenshot);
                    item.add("nativeRouteBenchmark", routeBenchmark == null
                        ? com.google.gson.JsonNull.INSTANCE : routeBenchmark.deepCopy());
                    item.addProperty("requiresEmptyAtStart", result.requiresEmptyAtStart);
                }
                if (BULK_WOOD_MODE) {
                    item.addProperty("elapsedMillisFromCommand", result.elapsedMillis);
                    item.addProperty("completionClientTick", result.completionClientTick);
                    item.addProperty("completionWorldTick", result.completionWorldTick);
                    item.addProperty("completionHealth", result.health);
                    item.addProperty("finalOakLogCount", result.serverInventory.getOrDefault(OAK_LOG_ID, 0));
                    JsonObject inventory = new JsonObject();
                    result.serverInventory.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> inventory.addProperty(entry.getKey(), entry.getValue()));
                    item.add("fullServerInventory", inventory);
                    JsonArray axeDurability = new JsonArray();
                    for (int remaining : result.woodenAxeRemainingDurability) axeDurability.add(remaining);
                    item.add("woodenAxeRemainingDurabilityPerStack", axeDurability);
                }
                if (PROCESSING_MODE) {
                    item.addProperty("elapsedMillisFromCommand", result.elapsedMillis);
                    item.addProperty("completionClientTick", result.completionClientTick);
                    item.addProperty("completionWorldTick", result.completionWorldTick);
                    item.addProperty("completionHealth", result.health);
                    JsonObject inventory = new JsonObject();
                    result.serverInventory.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> inventory.addProperty(entry.getKey(), entry.getValue()));
                    item.add("fullServerInventory", inventory);
                    item.addProperty(STONECUTTING_MODE ? "processingStation" : "cookingStation", result.cookingStation);
                    item.addProperty(STONECUTTING_MODE ? "nativeRecipeType" : "nativeCookingRecipeType", result.cookingRecipeType);
                    JsonObject initialResources = new JsonObject();
                    result.initialResources.forEach(initialResources::addProperty);
                    item.add("initialResources", initialResources);
                    item.addProperty("serverCorrectStationMenuOpenedDuringCase", result.correctCookingStationMenuOpenedDuringCase);
                    item.addProperty("initialRawInputCount", result.initialRawInputCount);
                    item.addProperty("finalRawInputCount", result.finalRawInputCount);
                    item.addProperty("initialCoalCount", result.initialCoalCount);
                    item.addProperty("finalCoalCount", result.finalCoalCount);
                    item.addProperty("initialStationItemCount", result.initialStationItemCount);
                    item.addProperty("finalStationItemCount", result.finalStationItemCount);
                }
                if (IRON_PICKAXE_MODE) {
                    item.addProperty("elapsedMillisFromCommand", result.elapsedMillis);
                    item.addProperty("completionClientTick", result.completionClientTick);
                    item.addProperty("completionWorldTick", result.completionWorldTick);
                    item.addProperty("completionHealth", result.health);
                    JsonObject inventory = new JsonObject();
                    result.serverInventory.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> inventory.addProperty(entry.getKey(), entry.getValue()));
                    item.add("fullServerInventory", inventory);
                    JsonObject initialResources = new JsonObject();
                    result.initialResources.forEach(initialResources::addProperty);
                    item.add("initialResources", initialResources);
                    item.addProperty("deepslateFixtureBlocksMined", latestSnapshot == null ? -1
                        : IRON_PICKAXE_DEEPSLATE_FIXTURE_BLOCK_COUNT - latestSnapshot.ironPickaxeDeepslateRemaining);
                    if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE) {
                        item.addProperty("requiresEmptyAtStart", result.requiresEmptyAtStart);
                        item.addProperty("firstServerMovementMillisFromCommand", firstMovementMillis);
                        item.addProperty("liveRouteScreenshot", liveRouteScreenshot);
                    }
                }
                cases.add(item);
            }
            root.add("cases", cases);
            root.addProperty("explorationFixture", EXPLORATION_MODE);
            root.addProperty("diamondBootsFixture", DIAMOND_BOOTSTRAP_MODE);
            root.addProperty("bulkWoodFixtureLogCount", BULK_WOOD_MODE ? 80 : 0);
            root.addProperty("woodToolsFlag", WOOD_TOOLS_MODE);
            root.addProperty("woodToolsEnabled", BULK_WOOD_MODE && WOOD_TOOLS_MODE);
            root.addProperty("resourceInitiallyLoaded", resourceInitiallyLoaded);
            root.addProperty("explorationAttempts",
                LodekeeperClient.engine == null ? 0 : LodekeeperClient.engine.explorationAttemptsMade());
            Files.writeString(evidenceDirectory.resolve("run-" + runId + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            System.err.println("[Lodekeeper verification] Evidence write failed: " + exception.getMessage());
        }
    }

    private String capture(String name) {
        String fileName = "lodekeeper-" + runId + "-" + name + ".png";
        try {
            screenshotWritesPending++;
            Screenshot.grab(evidenceDirectory.toFile(), fileName, mainRenderTarget(), 1, message -> {
                System.out.println("[Lodekeeper verification] " + message.getString());
                client.execute(() -> screenshotWritesPending = Math.max(0, screenshotWritesPending - 1));
            });
            return "screenshots/" + fileName;
        } catch (Exception exception) {
            screenshotWritesPending = Math.max(0, screenshotWritesPending - 1);
            System.err.println("[Lodekeeper verification] Screenshot failed for " + name + ": " + exception.getMessage());
            return null;
        }
    }

    private RenderTarget mainRenderTarget() throws ReflectiveOperationException {
        try {
            Method getter = Minecraft.class.getDeclaredMethod("getMainRenderTarget");
            getter.setAccessible(true);
            return (RenderTarget) getter.invoke(client);
        } catch (NoSuchMethodException ignored) {
            Field rendererField = Minecraft.class.getDeclaredField("gameRenderer");
            rendererField.setAccessible(true);
            Object renderer = rendererField.get(client);
            Method getter = renderer.getClass().getDeclaredMethod("mainRenderTarget");
            getter.setAccessible(true);
            return (RenderTarget) getter.invoke(renderer);
        }
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

    private static String invalidNearbyWoodMode() {
        if (!(NEARBY_WOOD_GOAL.equals("wood") || NEARBY_WOOD_GOAL.equals("crafting_table"))
                || !NEARBY_WOOD_MODE && !NEARBY_WOOD_GOAL.equals("wood")) {
            return "nearbyWoodGoal must be wood or crafting_table and requires nearbyWood=true";
        }
        if (!(NEARBY_WOOD_TERRAIN.equals("flat") || NEARBY_WOOD_TERRAIN.equals("meadow"))
                || !NEARBY_WOOD_MODE && !NEARBY_WOOD_TERRAIN.equals("flat")) {
            return "nearbyWoodTerrain must be flat or meadow and meadow requires nearbyWood=true";
        }
        return null;
    }

    private static String verificationMode() {
        if (IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE && !IRON_PICKAXE_MODE) {
            return "invalid_iron_pickaxe_empty_distant_wood_requires_iron_pickaxe";
        }
        if (invalidNearbyWoodMode() != null) return "invalid_nearby_wood_configuration";
        if (selectedFixtureModes() > 1) return "invalid_conflicting_modes";
        if (NAVIGATION_COURSE != null && !MIXED_NAVIGATION_COURSE) return "invalid_navigation_course";
        if (MIXED_NAVIGATION_COURSE && !COAL_RECOVERY_MODE) return "invalid_navigation_course_requires_coal_recovery";
        if (MIXED_NAVIGATION_COURSE && !COAL_START_SURFACE.equals("full")) return "invalid_navigation_course_requires_full_surface";
        if (COOKING_MODE && !isSupportedCookingStationMode()) return "invalid_cooking_station";
        if (STONECUTTING_DRAIN_MODE && !STONECUTTING_MODE) return "invalid_stonecutting_drain";
        if (STONECUTTING_DRAIN_MODE) return "stonecutting_drain";
        if (IRON_PICKAXE_MODE) return IRON_PICKAXE_EMPTY_DISTANT_WOOD_MODE
            ? "iron_pickaxe_empty_distant_wood" : "iron_pickaxe";
        if (COAL_RECOVERY_MODE) return MIXED_NAVIGATION_COURSE ? "coal_recovery_mixed_navigation" : "coal_recovery";
        if (STONECUTTING_MODE) return "stonecutting";
        if (COOKING_MODE) return "cooking_" + COOKING_STATION_MODE;
        if (NEARBY_WOOD_MODE) return "nearby_wood";
        if (BULK_WOOD_MODE) return "bulk_wood";
        if (DIAMOND_BOOTSTRAP_MODE) return "diamond_boots";
        return EXPLORATION_MODE ? "exploration" : "default";
    }

    private static int selectedFixtureModes() {
        return (EXPLORATION_MODE ? 1 : 0) + (DIAMOND_BOOTSTRAP_MODE ? 1 : 0)
            + (NEARBY_WOOD_MODE ? 1 : 0) + (IRON_PICKAXE_MODE ? 1 : 0)
            + (COAL_RECOVERY_MODE ? 1 : 0) + (BULK_WOOD_MODE ? 1 : 0)
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

    private record NearbyWoodLaunchHandoffObservation(int pathGeneration, int previousPathIndex,
                                                       int pathIndex, int sourcePathIndex,
                                                       int reachedPathIndex, int outgoingPathIndex,
                                                       int validatedPathIndex, int clientTick,
                                                       String incomingMovement, String outgoingMovement,
                                                       double playerX, double playerY, double playerZ,
                                                       double velocityX, double velocityZ,
                                                       double offsetX, double offsetY, double offsetZ,
                                                       double centerDistanceXZ, double horizontalSpeed,
                                                       boolean grounded, boolean settled) { }

    private record CaseResult(String name, String item, int expected, int observed, boolean inventoryEmptyAtStart,
                              boolean requiresEmptyAtStart, boolean passed, int clientTicks, long worldTicks, long elapsedMillis, String engineStatus, String detail,
                              float health, String difficulty, boolean tableOpenedDuringCase,
                              boolean furnaceOpenedDuringCase,
                              int ironPickaxeCount,
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
