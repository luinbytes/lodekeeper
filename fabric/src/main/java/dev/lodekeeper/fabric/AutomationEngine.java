package dev.lodekeeper.fabric;

import dev.lodekeeper.core.*;
import dev.lodekeeper.nav.ActionMovementProgress;
import dev.lodekeeper.nav.ExplorationFrontier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates one goal/action at a time. Pure planning runs on a single bounded worker. */
final class AutomationEngine {
    private static final List<EquipmentSlot> GOAL_EQUIPMENT_SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);
    private static final int MAX_FOREGROUND_QUEUE = 32;
    private static final int MAX_MAINTENANCE_QUEUE = 32;
    private static final int MAX_PROJECT_RECONCILIATIONS = 3;
    private static final int INVENTORY_SAMPLE_INTERVAL_TICKS = 10;
    private static final int MAX_STATION_PLACEMENT_ATTEMPTS = 24;
    private static final int MAX_LOCAL_LOG_POSITIONS = 512;
    private static final int LOCAL_REACH_RADIUS = 6;
    private static final int LOCAL_REACH_MAX_PROBES_PER_ADVANCE = 256;
    private static final long DISCOVERY_BUDGET_NANOS = 1_000_000L;
    private static final long LOCAL_NEGATIVE_TTL_NANOS = 1_000_000_000L;
    private static final int MIN_FREE_SLOTS_FOR_WOOD_TOOL_OFFER = 4;
    private static final int WOOD_TOOL_CRAFT_TICKS = 100;
    private static final int WOOD_TOOL_STATION_TICKS = 100;
    private static final int WOOD_TOOL_MINIMUM_SAVING_TICKS = 100;
    private static final ItemId WOODEN_AXE = ItemId.parse("minecraft:wooden_axe");
    private static final ItemId STONE_SWORD = ItemId.parse("minecraft:stone_sword");
    private static final ItemId STICK = ItemId.parse("minecraft:stick");
    private static final ItemId CRAFTING_TABLE_ITEM = ItemId.parse("minecraft:crafting_table");
    private static final StationId CRAFTING_TABLE = StationId.parse("minecraft:crafting_table");
    private static final StationId FURNACE = StationId.parse("minecraft:furnace");
    private static final StationId SMOKER = StationId.parse("minecraft:smoker");
    private static final StationId BLAST_FURNACE = StationId.parse("minecraft:blast_furnace");
    private static final TagId LOGS_TAG = TagId.parse("minecraft:logs");
    private static final TagId PLANKS_TAG = TagId.parse("minecraft:planks");
    private static final List<SelectedToolRequirement> COMBAT_PREPARATION_PICKAXES = List.of(
            new SelectedToolRequirement(ItemId.parse("minecraft:wooden_pickaxe"), 2, "ore mining"),
            new SelectedToolRequirement(ItemId.parse("minecraft:stone_pickaxe"), 2, "ore mining"),
            new SelectedToolRequirement(ItemId.parse("minecraft:iron_pickaxe"), 2, "ore mining"),
            new SelectedToolRequirement(ItemId.parse("minecraft:diamond_pickaxe"), 2, "ore mining"),
            new SelectedToolRequirement(ItemId.parse("minecraft:netherite_pickaxe"), 2, "ore mining"));
    private static final Map<String, ItemId> COMBAT_GATHER_OUTPUTS = Map.of(
            "minecraft:stone", ItemId.parse("minecraft:cobblestone"),
            "minecraft:cobblestone", ItemId.parse("minecraft:cobblestone"),
            "minecraft:deepslate", ItemId.parse("minecraft:cobbled_deepslate"),
            "minecraft:cobbled_deepslate", ItemId.parse("minecraft:cobbled_deepslate"),
            "minecraft:blackstone", ItemId.parse("minecraft:blackstone"));
    private static final Set<String> COMBAT_PREPARATION_ORES = Set.of(
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore",
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore");

    record JobContext(long jobToken, UUID issuer, long grantEpoch, WorldScope scope,
                      Object world, Object player, Object network, Object connection,
                      dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                      dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session nativeSession,
                      OwnedStationLedger.Session provenanceSession, long acceptedNanos, long deadlineNanos) {
        JobContext {
            if (jobToken <= 0 || grantEpoch < 0 || deadlineNanos - acceptedNanos <= 0
                    || deadlineNanos - acceptedNanos > 600_000_000_000L)
                throw new IllegalArgumentException("Invalid job token or bounded deadline");
            Objects.requireNonNull(issuer); Objects.requireNonNull(scope); Objects.requireNonNull(world);
            Objects.requireNonNull(player); Objects.requireNonNull(network); Objects.requireNonNull(connection);
            Objects.requireNonNull(owner); Objects.requireNonNull(nativeSession); Objects.requireNonNull(provenanceSession);
        }
    }
    private sealed interface Request permits AcquireRequest, TravelRequest {
        String name(); JobContext context();
        default long jobToken() { return context().jobToken(); }
    }
    private record AcquireRequest(String name, ItemId item, int count, boolean anyLogs,
                                  String maintenanceTaskId, ProjectRun project, JobContext context) implements Request {
        AcquireRequest {
            Objects.requireNonNull(name); Objects.requireNonNull(item); Objects.requireNonNull(context);
            if (count <= 0) throw new IllegalArgumentException("Acquisition count must be positive");
        }
        boolean maintained() { return maintenanceTaskId != null; }
    }
    record TravelRequest(String name, TravelGoal goal, JobContext context) implements Request {
        TravelRequest { Objects.requireNonNull(name); Objects.requireNonNull(goal); Objects.requireNonNull(context); }
    }
    private record AcquisitionDemand(ItemId item, int totalStockTarget, boolean anyLogs) { }
    private record AcquisitionScope(Request parent, AcquireRequest view, long phaseGeneration, AcquisitionDemand demand) { }
    private record PlanIdentity(AcquisitionScope scope, long serial, JobContext context, GameCatalog catalog,
                                long catalogGeneration, long preferencesVersion, long policyGeneration) { }
    private AcquisitionScope acquisitionScope;
    private long phaseGeneration, planSerial;
    private PlanIdentity pendingPlanIdentity, stepPlanIdentity;
    private Request cancelledRequest;
    private NativeRun nativeRun;
    private Request nativeRunParent;
    private AcquisitionScope nativeRunScope;
    private TravelAction travelAction;
    private TravelRequest travelActionParent;
    private TravelRequest travelFoodPreflightParent;
    private AcquisitionScope travelFoodScope;
    private FoodController.Preparation travelFoodPreparation;
    private TravelFoodOffer travelFoodOffer;
    private TravelFoodDemand travelFoodDemand;
    private boolean travelFoodInvalidated;
    private TravelRequest travelShieldPreflightParent;
    private AcquisitionScope travelShieldScope;
    private final WaypointStore waypoints = new WaypointStore(net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("lodekeeper-waypoints.json"));
    private AcquireRequest acquisitionView() { return acquisitionScope == null ? null : acquisitionScope.view(); }
    private ProjectRun activeProject() { return acquisitionView() == null ? null : acquisitionView().project(); }
    private boolean activeMaintained() { return acquisitionView() != null && acquisitionView().maintained(); }
    private String activeMaintenanceId() { return acquisitionView() == null ? null : acquisitionView().maintenanceTaskId(); }
    private AcquisitionScope acquisition() { return acquisitionScope; }

    private record MovementDemand(PlanKind kind, ItemId output, StationId station, String customType) { }

    private static final class MovementProgressScope {
        final Request request;
        final AcquisitionScope acquisition;
        final Object world, player;
        final OwnedStationLedger.Session session;
        final ActionMovementProgress progress = new ActionMovementProgress();
        MovementDemand demand;
        int sampledTableCount = -1, tableGainFrom = -1;
        boolean stationStockReconsidered;

        MovementProgressScope(AcquisitionScope acquisition, Object world, Object player, OwnedStationLedger.Session session,
                              MovementDemand demand) {
            this.acquisition = acquisition;
            this.request = acquisition.parent();
            this.world = world;
            this.player = player;
            this.session = session;
            this.demand = demand;
        }

        boolean matches(AcquisitionScope acquisition, Object world, Object player, OwnedStationLedger.Session session) {
            return this.acquisition == acquisition && acquisition != null && this.request == acquisition.parent() && this.world == world && this.player == player
                    && this.session.equals(session);
        }
    }

    private enum LocalReachPurpose { LOG_CHOICE, RECIPE_WOOD, GATHER }

    private record PlanningOutcome(PlanResult result, boolean explorationProven, boolean auxiliaryInvestment,
                                   PlanStep unbatchedGather, ShieldPlanningIdentity shieldIdentity) {
        PlanningOutcome(PlanResult result, boolean explorationProven, boolean auxiliaryInvestment) {
            this(result, explorationProven, auxiliaryInvestment, null, null);
        }
    }
    private record PendingGoals(Map<ItemId, Integer> targets, boolean unresolvedLogs) {
        PendingGoals { targets = Map.copyOf(targets); }
    }
    private record ShieldOptions(boolean autoDefend, boolean autoUseShield, boolean autoCraftShield,
                                 boolean allowBreaking, boolean allowBuilding, int ironFloor, int plankFloor) { }
    private record ShieldPlanningIdentity(AcquisitionScope acquisition, long jobToken, PendingGoals goals, Object world, Object dimension,
                                          java.util.Optional<OwnedStationLedger.Session> session,
                                          long catalogGeneration, long preferencesVersion, ShieldOptions options,
                                          Set<String> unavailableSources) {
        ShieldPlanningIdentity { unavailableSources = Set.copyOf(unavailableSources); }
    }
    private record GatherLimit(ItemId output, int localPositions) { }
    private record LocalLogEvidence(Map<String, GatherLimit> sources, Set<ItemId> outputs,
                                    List<BlockState> states, int highestHandTicks, int leastSavingTicks) { }
    private record HarvestOffer(CatalogSnapshot catalog, InventorySnapshot inventory,
                                HarvestInvestment.ToolDemand demand, Map<String, GatherLimit> localSources,
                                Set<ItemId> localOutputs, Set<ItemId> goalLogItems, Set<String> nativeCraftSources,
                                Set<String> allowedAxeCraftSources, HarvestInvestment.TickEstimates estimates) { }
    private record ShieldPreparationOffer(CatalogSnapshot catalog, InventorySnapshot knownInventory, Set<ItemId> planks,
                                          int ironFloor, int plankFloor, boolean canPlaceStations,
                                          CatalogSnapshot workCatalog, InventorySnapshot workInventory,
                                          ProjectSpec pendingProject, ShieldPlanningIdentity identity) { }
    private record CombatPreparationOffer(CatalogSnapshot catalog, InventorySnapshot inventory,
                                          InventorySnapshot knownInventory, boolean canPlaceStations) { }

    private static final class LocalReachScan {
        private final Object world;
        private final Object dimension;
        private final AcquisitionScope acquisition;
        private final long generation;
        private final Set<Block> blocks;
        private final Set<String> sources;
        private final Set<BlockPos> rejected;
        private final BlockPos origin;
        private final List<BlockPos> positions;
        private int cursor;
        private BlockPos result;
        private boolean complete;
        private long completedAtNanos;

        LocalReachScan(Object world, Object dimension, AcquisitionScope acquisition, long generation,
                       Set<Block> blocks, Set<String> sources, Set<BlockPos> rejected,
                       BlockPos origin, double eyeX, double eyeY, double eyeZ) {
            this.world = world;
            this.dimension = dimension;
            this.acquisition = acquisition;
            this.generation = generation;
            this.blocks = Set.copyOf(blocks);
            this.sources = Set.copyOf(sources);
            this.rejected = Set.copyOf(rejected);
            this.origin = origin.toImmutable();
            BlockPos eyeBlock = new BlockPos((int) Math.floor(eyeX), (int) Math.floor(eyeY), (int) Math.floor(eyeZ));
            List<BlockPos> candidates = new ArrayList<>(LOCAL_REACH_RADIUS * LOCAL_REACH_RADIUS * LOCAL_REACH_RADIUS * 4);
            for (int x = -LOCAL_REACH_RADIUS; x <= LOCAL_REACH_RADIUS; x++) {
                for (int y = -LOCAL_REACH_RADIUS; y <= LOCAL_REACH_RADIUS; y++) {
                    for (int z = -LOCAL_REACH_RADIUS; z <= LOCAL_REACH_RADIUS; z++) {
                        BlockPos position = eyeBlock.add(x, y, z);
                        double nearX = Math.max(position.getX(), Math.min(eyeX, position.getX() + 1));
                        double nearY = Math.max(position.getY(), Math.min(eyeY, position.getY() + 1));
                        double nearZ = Math.max(position.getZ(), Math.min(eyeZ, position.getZ() + 1));
                        double dx = nearX - eyeX, dy = nearY - eyeY, dz = nearZ - eyeZ;
                        if (dx * dx + dy * dy + dz * dz <= LOCAL_REACH_RADIUS * LOCAL_REACH_RADIUS) candidates.add(position);
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble((BlockPos position) -> distanceSquared(position, eyeX, eyeY, eyeZ))
                    .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            positions = List.copyOf(candidates);
        }

        boolean matches(Object world, Object dimension, AcquisitionScope acquisition, long generation,
                        Set<Block> blocks, Set<String> sources, Set<BlockPos> rejected, BlockPos origin) {
            return this.world == world && Objects.equals(this.dimension, dimension) && this.acquisition == acquisition
                    && this.generation == generation && this.blocks.equals(blocks) && this.sources.equals(sources)
                    && this.rejected.equals(rejected) && this.origin.equals(origin);
        }

        BlockPos advance(AutomationEngine engine, long deadlineNanos) {
            if (complete) return null;
            int probes = 0;
            while (cursor < positions.size() && probes++ < LOCAL_REACH_MAX_PROBES_PER_ADVANCE
                    && System.nanoTime() < deadlineNanos) {
                BlockPos position = positions.get(cursor++);
                if (rejected.contains(position) || !engine.hasLoadedChunk(position)) continue;
                if (blocks.contains(engine.client.world.getBlockState(position).getBlock())
                        && engine.actions.hit(position) != null) {
                    result = position;
                    return result;
                }
            }
            if (!complete && cursor == positions.size()) {
                complete = true;
                completedAtNanos = System.nanoTime();
            }
            return null;
        }

        private static double distanceSquared(BlockPos position, double x, double y, double z) {
            double dx = Math.max(position.getX(), Math.min(x, position.getX() + 1)) - x;
            double dy = Math.max(position.getY(), Math.min(y, position.getY() + 1)) - y;
            double dz = Math.max(position.getZ(), Math.min(z, position.getZ() + 1)) - z;
            return dx * dx + dy * dy + dz * dz;
        }

        BlockPos result() { return result; }
        boolean complete() { return complete; }
        boolean negativeExpired(long nowNanos) {
            return complete && result == null && nowNanos - completedAtNanos >= LOCAL_NEGATIVE_TTL_NANOS;
        }
        long progressToken() { return cursor; }
        String progressDescription() { return Math.min(cursor, positions.size()) + "/" + positions.size() + " positions"; }
        void restart() { cursor = 0; result = null; complete = false; completedAtNanos = 0; }
        void skipResult() { result = null; }
    }

    private record LocalReachHint(Object world, Object dimension, AcquisitionScope acquisition, long generation,
                                  Set<BlockPos> rejected, BlockPos origin, String sourceId,
                                  BlockPos position, Block block) { }

    private static final class ProjectRun {
        final ProjectSpec spec;
        final long jobToken;
        final Set<ItemId> pending = new TreeSet<>();
        int reconciliationPasses;
        boolean aborted;

        ProjectRun(ProjectSpec spec, long jobToken) { this.spec = spec; this.jobToken = jobToken; }
        @Override public String toString() { return spec.name(); }
    }

    private long lastJobToken;
    private final MinecraftClient client;
    final LodekeeperConfig config;
    final WorldProtection protection;
    private final PlayerActions actions;
    private final BotInput input;
    private final FoodController food;
    private final ThreatResponseAction threats;
    private final AutoEquipmentAction equipment;
    private final StationRoomAction stationRoom;
    private final AnimalHarvestAction animalAcquisition;
    private final CropHarvestAction cropAcquisition;
    private final Map<Long, CropHarvestAction.JobBudget> cropBudgets = new HashMap<>();
    private enum CropDebtKind { REPAIR, CLEANUP }
    private record CropDebtPermission(CropDebtKind kind, AcquisitionScope scope, PlanIdentity plan,
                                      JobContext context, UUID issuer, long grantEpoch) { }
    private record CropAuthority(AcquisitionScope scope, Request parent, AcquireRequest view,
                                 PlanIdentity plan, JobContext context, PlanStep step, GameCatalog catalog,
                                 long phaseGeneration, long planSerial, long catalogGeneration,
                                 long protectionEpoch, long policyGeneration,
                                 CropDebtPermission repair, CropDebtPermission cleanup) { }
    private CropAuthority cropAuthority;
    private final PlacementProvenance placementProvenance;
    private final BackfillController backfill;
    private final Map<ItemId, Integer> requestedBackfillStock = new HashMap<>();
    private final OwnedStationRecoveryAction stationRecovery;
    private record StationPlacementWait(AcquisitionScope acquisition, Request request, BlockPos position, Block block,
                                        OwnedStationLedger.Session session, boolean sent, PlayerActions.StationPlacementHand hand) { }
    private StationPlacementWait stationPlacementWait;
    private PlayerActions.StationPlacementHand stationPlacementHand;
    private record StationHandAdmission(AcquisitionScope acquisition, PlanIdentity planning, Request request, PlanStep plannedStep, Object player, Object world,
                                        Object network, Object connection, Object menu, Object expectedInput,
                                        OwnedStationLedger.Session session, GameCatalog catalog, long catalogGeneration,
                                        dev.lodekeeper.navigation.kernel.OwnedKernelRuntime nativeOwner,
                                        dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session nativeSession,
                                        long policyGeneration) { }

    private enum CleanupPurpose { FINISH_JOB, CARRY_TABLE, PREVIOUS_COMMAND_TABLE }
    private static final class CleanupRun {
        final CleanupPurpose purpose;
        final AcquireRequest request;
        final AcquisitionScope acquisition;
        final OwnedStationLedger.Session session;
        final Deque<OwnedStationLedger.StationRecord> remaining;
        final long startedNanos;
        OwnedStationLedger.StationRecord current;
        boolean incomplete, restorationRevoked;
        String incompleteReason;
        CleanupRun(CleanupPurpose purpose, AcquisitionScope acquisition, AcquireRequest request, OwnedStationLedger.Session session,
                   List<OwnedStationLedger.StationRecord> records, long startedNanos) {
            this.purpose = purpose;
            this.startedNanos = startedNanos;
            this.request = request;
            this.acquisition = acquisition;
            this.session = session;
            remaining = new ArrayDeque<>(records);
        }
    }
    private record CleanupBudget(long jobToken, OwnedStationLedger.Session session, long startedNanos) { }
    private CleanupBudget cleanupBudget;
    private CleanupRun cleanupRun;
    private OwnedStationLedger.StationRecord pendingStationPickup;
    private final Deque<OwnedStationLedger.StationRecord> deferredStationCleanup = new ArrayDeque<>();
    private final Set<OwnedStationLedger.StationRecord> completedStandaloneTables = new LinkedHashSet<>();
    private record PreviousCommandTableCheck(AcquisitionScope acquisition, long jobToken, OwnedStationLedger.StationRecord selected) { }
    private PreviousCommandTableCheck previousCommandTableCheck;
    private long cleanedJobToken;
    private AcquisitionScope cleanedAcquisitionScope;
    private long stationCleanupIncompleteJobToken = -1;
    private String stationCleanupIncompleteReason;
    final GameTerrain terrain;
    private final MovementController movement;
    private final ExecutorService plannerWorker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lodekeeper-planner"); t.setDaemon(true); return t; });
    private final Deque<Request> queue = new ArrayDeque<>();
    private final Deque<AcquireRequest> maintenanceQueue = new ArrayDeque<>();
    private final Set<ProjectRun> projects = new LinkedHashSet<>();
    private final ProjectCatalog projectCatalog = ProjectCatalog.standard();
    private final MaintainedDemandModel maintained = new MaintainedDemandModel();
    private final Map<StationId, BlockPos> knownStations = new HashMap<>();
    private final Set<BlockPos> rejectedStationSites = new HashSet<>();
    private AcquisitionScope stationPlacementOwner;
    private StationId stationPlacementStation;
    private final Set<BlockPos> unreachableStations = new HashSet<>();
    private final Set<String> unavailableSources = new HashSet<>();
    private final Map<String, List<BlockPos>> discoveredSources = new LinkedHashMap<>();
    private final AcquisitionPlanner planner = new AcquisitionPlanner();
    private final NearbyResources nearbyResources;
    private final NearbyStations nearbyStations;
    private long pendingPlanPreferencesVersion, stepPreferencesVersion;
    private int preferenceRefreshCooldown;
    private ClientWorld world;
    private GameCatalog catalog;
    private Request active;
    private record MiningContinuation(Object world, Object dimension, AcquisitionScope acquisition, String sourceId,
                                      Set<BlockPos> rejectedPositions) {
        MiningContinuation { rejectedPositions = Set.copyOf(rejectedPositions); }
        boolean matches(Object currentWorld, Object currentDimension, AcquisitionScope currentAcquisition, String currentSource) {
            return world == currentWorld && java.util.Objects.equals(dimension, currentDimension)
                    && acquisition == currentAcquisition && sourceId.equals(currentSource);
        }
    }
    private MiningContinuation miningContinuation;
    private record HealthRecovery(long startedNanos) { }
    private HealthRecovery healthRecovery;
    private final AirRecoveryAction airRecovery;
    private CompletableFuture<PlanningOutcome> pendingPlan;
    private record NativePlanningIdentity(Request request, Object world, Object player,
                                          OwnedStationLedger.Session session, long offerGeneration) { }
    private NativePlanningIdentity pendingNativeIdentity;
    private static final class AnimalQuota {
        int attempts;
        long activeNanos;
    }
    private final Map<Long, AnimalQuota> animalQuotas = new HashMap<>();
    private long animalQuotaTickNanos;

    private record StationStockHint(CompletableFuture<PlanningOutcome> future, PlanStep step,
                                    MovementProgressScope scope, long generation) { }
    private StationStockHint stationStockHint;
    private boolean previewPending;
    private long pendingPlanGeneration;
    private long stepCatalogGeneration;
    private PlanStep step;
    private boolean stepAuxiliaryInvestment;
    private ShieldPlanningIdentity stepShieldIdentity;
    private boolean deferShieldPreparation;
    private BlockSearch scan;
    private BlockSearch logScan;
    private LocalReachScan localLogReachScan, localIngredientWoodReachScan, localGatherReachScan;
    private LocalReachHint localReachHint;
    private BlockSearch ingredientWoodScan;
    private AcquisitionScope ingredientWoodRequest;
    private long ingredientWoodGeneration = -1;
    private BlockPos ingredientWoodOrigin;
    private int ingredientWoodRejectedCount;
    private final Map<Block, List<GatherSource>> ingredientWoodSources = new LinkedHashMap<>();
    private final Map<Block, List<GatherSource>> localIngredientWoodSources = new LinkedHashMap<>();
    private final Set<String> ingredientWoodSourceIds = new HashSet<>();
    private final Set<String> localIngredientWoodSourceIds = new HashSet<>();
    private final Set<BlockPos> ingredientWoodExamined = new HashSet<>();
    private final Map<String, BlockPos> ingredientWoodPublished = new HashMap<>();
    private long logScanGeneration;
    private final Map<Block,GatherSource> logSources = new HashMap<>();
    private final Map<Block,GatherSource> localLogSources = new HashMap<>();
    private final Deque<ItemId> logCandidates = new ArrayDeque<>();
    private final Set<ItemId> attemptedLogCandidates = new HashSet<>();
    private AcquisitionScope logScanRequest;
    private ItemId localLogCandidateOutput;
    private String localLogCandidateSourceId;
    private boolean logScanComplete;
    private ItemId droppedLogCandidate;
    private ExplorationFrontier frontier;
    private boolean exploring, explorationMoving;
    private int explorationTicks;
    private final Set<BlockPos> rejectedResources = new HashSet<>();
    private String lastResourceFailure;
    private BlockPos target;
    private BlockPos gatherMineTarget;
    private Block gatherMineBlock;
    private long lastMovementProgressToken;
    private MovementProgressScope parentMovementScope, auxiliaryMovementScope;
    private CraftingAction crafting;
    private static final StationId STONECUTTER = StationId.parse("minecraft:stonecutter");
    private StonecuttingAction stonecutting;
    private SmeltingAction smelting;
    private boolean moving, movingPickup, paused, openingStation, foregroundYieldPending, stopAfterStep;
    private ScreenHandler ownedStationHandler, stationOpeningFrom;
    private final Set<ItemId> unmaintainAfterStep = new TreeSet<>();
    private final Map<ItemId, Integer> maintainAfterStep = new TreeMap<>();
    private int planningRetries;
    private boolean pendingPreferencePlan;
    private BlockPos stationApproachTarget;
    private int stationPlacementFailures;
    private boolean stationDiscoveryDone;
    private int actionTicks, baseline, verifyTicks, lastObservedCount;
    private int stationOpenTicks;
    private int inventorySampleTicks, foodCooldown;
    private boolean foodReplanPending, animalAcquisitionPending;
    private Boolean loggedFoodPreparationDeferred;
    private int animalAcquisitionCooldown, stationAccessFailures;
    private boolean inventoryFingerprintInitialized;
    private volatile boolean recipeRefreshPending;
    private long lastInventoryFingerprint;
    private long discoveryDeadlineNanos;
    private boolean discoveryBudgetStarted;
    private Map<ItemId, Integer> observedInventory = Map.of();
    private long lastSmeltProgress;
    private String status = "idle";
    AutomationEngine(MinecraftClient client, LodekeeperConfig config) {
        this.client = client; this.config = config;
        protection = new WorldProtection(client, config, new ClaimStore(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("lodekeeper-claims.json")));
        nearbyResources = new NearbyResources(client);
        nearbyStations = new NearbyStations(client);
        placementProvenance = new PlacementProvenance(client, protection);
        actions = new PlayerActions(client, protection, placementProvenance); input = new BotInput(client); terrain = new GameTerrain(client, config);
        movement = new MovementController(client, config, actions, input, terrain);
        food = new FoodController(client, actions);
        airRecovery = new AirRecoveryAction(client, config, movement, input);
        equipment = new AutoEquipmentAction(client);
        stationRoom = new StationRoomAction(client, actions);
        threats = new ThreatResponseAction(client, actions, movement);
        animalAcquisition = new AnimalHarvestAction(client, config, actions, movement, protection, placementProvenance);
        cropAcquisition = new CropHarvestAction(client, config, actions, movement, protection, placementProvenance);
        backfill = new BackfillController(client, config, protection, actions, placementProvenance,
                () -> active == null || paused || client.currentScreen != null || !movement.backfillInputClear()
                        ? 0 : active.jobToken());
        placementProvenance.observeServerBlocks(receipt -> {
            backfill.serverBlock(receipt);
            if (nativeRun == cropAcquisition) cropAcquisition.serverBlock(receipt);
        });
        stationRecovery = new OwnedStationRecoveryAction(client, config, actions, movement,
                this::mayRecoverOwnedStation,
                position -> cleanupRun != null && cleanupRun.current != null
                        && position.equals(stationPosition(cleanupRun.current))
                        ? placementProvenance.confirmedStationRemovalSequence(cleanupRun.current)
                        : java.util.OptionalLong.empty(),
                item -> placementProvenance.confirmedInventoryReceipt(
                        ItemId.parse(Registries.ITEM.getId(item).toString())),
                placementProvenance::session);
    }
    boolean prepareAutomatedBreak(BlockPos position) { return movement.prepareAutomatedBreak(position); }
    private long allocateJobToken() {
        if (lastJobToken == Long.MAX_VALUE) throw new IllegalStateException("Job token capacity reached");
        return ++lastJobToken;
    }

    private AcquireRequest request(String name, ItemId item, int count, boolean anyLogs,
                            String maintenanceTaskId, ProjectRun project) {
        requestedBackfillStock.merge(item, count, Math::max);
        if (project != null) project.spec.goals().forEach((goal, targetCount) ->
                requestedBackfillStock.merge(goal, targetCount, Math::max));
        return new AcquireRequest(name, item, count, anyLogs, maintenanceTaskId, project,
                captureContext(project == null ? allocateJobToken() : project.jobToken, 600));
    }

    void claim(CommandParser.ClaimCommand command) { message(protection.execute(command)); }
    void dispose() {
        abandonStationPlacementHand();
        animalAcquisition.abandonSession(); abandonCropSession();
        try { stopNow(false); }
        finally { placementProvenance.dispose(); plannerWorker.shutdownNow(); movement.shutdownOwnedNavigation(); }
    }

    void tick() {
        try {
        protection.sync();
        placementProvenance.tick();
        backfill.tick();
        discoveryBudgetStarted = false;
        discoveryDeadlineNanos = 0;
        syncMovementProgress();
        if (active != null && contextReplaced(active.context())) abandonReplacedContext(active);
        if (client.world != world) {
            abandonTravelSession();
            abandonStationPlacementHand();
            animalAcquisition.abandonSession(); abandonCropSession();
            nativeRun = null; cropBudgets.clear(); animalQuotas.clear();
            stopNow(false); requestedBackfillStock.clear(); world = client.world; knownStations.clear(); unreachableStations.clear(); unavailableSources.clear(); discoveredSources.clear(); catalog = null;
            localLogReachScan = localIngredientWoodReachScan = localGatherReachScan = null; localReachHint = null;
            recipeRefreshPending = false;
            nearbyResources.reset(); nearbyStations.reset();
            inventorySampleTicks = 0; inventoryFingerprintInitialized = false; observedInventory = Map.of();
        }
        if (catalog != null && (recipeRefreshPending || !catalog.usesCurrentProvider())) {
            recipeRefreshPending = false;
            try { catalog.load(); }
            catch (RuntimeException exception) {
                status = "recipe catalog update failed: " + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                message("Recipe catalog update failed; automation will wait: " + status.substring("recipe catalog update failed: ".length()));
            }
        }
        if (active != null && contextReplaced(active.context())) abandonReplacedContext(active);
        if (tickStationPlacementHand()) return;
        if (!paused && active instanceof TravelRequest && client.player != null
                && (manualStationInput() || editingSettings()
                    || acquisitionScope == null && (client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler
                        || !client.player.currentScreenHandler.getCursorStack().isEmpty() || client.player.isUsingItem())
                    || acquisitionScope != null && !hasOwnedStationHandlerOpen() && cleanupRun == null
                        && (client.player.currentScreenHandler != client.player.playerScreenHandler || !client.player.currentScreenHandler.getCursorStack().isEmpty()))) {
            pause("Travel yielded to player input or an unsafe screen/menu"); return;
        }
        if (tickCleanupReturnObserver()) return;
        if (tickTravelObserver()) return;
        if (stopAfterStep && active == null) { stopNow(true); return; }
        if (active != null && !newEffectCurrent(active)) {
            if (!contextBound(active.context())) {
                paused = true;
                if (travelAction != null && nativeRun == travelAction) travelAction.pause();
                status = "connection unavailable; held work awaits its original context";
                input.release();
                return;
            }
            cancelledRequest = active;
        }
        if (cropAcquisition.active() && tickCropRun()) return;
        if (animalAcquisition.active() && tickAnimalRun()) return;
        if (client.player == null || client.world == null) { cancelStationPlacement(); stopStationCleanup(); stationRoom.stop(); threats.stop(); equipment.stop(); animalAcquisition.stop(); food.stop(); movement.suspend(); input.release(); return; }
        if (editingSettings()) {
            if (stationPlacementWait != null || cleanupRun != null) {
                pause("Station placement or recovery yielded to automation settings");
                return;
            }
            cancelStationPlacement();
            stopStationCleanup();
            healthRecovery = null;
            airRecovery.stop(); stationRoom.stop(); threats.stop(); equipment.stop();
            animalAcquisition.stop(); food.stop(); movement.suspend(); input.release();
            if (active != null && !paused) status = "Editing automation settings";
            return;
        }
        if (active != null && !paused && !client.player.isAlive()) { pause("player is no longer alive"); return; }
        if (!paused && (stationPlacementWait != null || cleanupRun != null) && manualStationInput()) {
            placementProvenance.manualTakeover();
            pause("Station placement or recovery yielded to player input");
            return;
        }
        if (!paused && stationPlacementWait != null && (client.currentScreen != null
                || client.player.currentScreenHandler != client.player.playerScreenHandler)) {
            placementProvenance.manualTakeover();
            pause("Station placement interrupted by an inventory screen; ownership was not assumed");
            return;
        }
        pruneCompletedStandaloneTables();
        if (!paused && cleanupRun != null && cleanupRun.purpose == CleanupPurpose.PREVIOUS_COMMAND_TABLE
                && System.nanoTime() - cleanupRun.startedNanos >= 20_000_000_000L) {
            cleanupRun.incomplete = true;
            cleanupRun.incompleteReason = "the 20 second previous command table limit was reached";
            message("Owned station recovery reached its 20 second limit; continuing the command");
            completeStationCleanup(cleanupRun);
            return;
        }
        if (!paused && (active != null || airRecovery.active())) {
            if (cleanupRun != null && (airRecovery.active() || airRecovery.ready())) revokeCleanupRestoration();
            if (recoverAirIfNeeded()) return;
        }
        if (healthRecovery != null && !paused
                && System.nanoTime() - healthRecovery.startedNanos() >= 40_000_000_000L) {
            pause("Health did not recover within 40 seconds");
            return;
        }
        if (!airRecovery.active() && tickActiveThreat()) return;
        if (!movement.finishCancellation()) {
            if (!paused) status = "finishing movement before inventory actions";
            return;
        }
        nearbyResources.tick(catalog, config.scanBlocksPerTick);
        if (active != null || !queue.isEmpty()) nearbyStations.advance(protection.capture(), catalog);
        if (preferenceRefreshCooldown > 0) preferenceRefreshCooldown--;
        if (foodCooldown > 0) foodCooldown--;
        if (animalAcquisitionCooldown > 0) animalAcquisitionCooldown--;
        if (++inventorySampleTicks >= INVENTORY_SAMPLE_INTERVAL_TICKS) {
            inventorySampleTicks = 0;
            observeInventory();
            sampleStationStock();
        }
            if (cancelledRequest == active && active != null && tickCancelledRequest()) return;
            if (foregroundYieldPending) {
                if (!activeMaintained()) foregroundYieldPending = false;
                else if (canYieldMaintenanceNow()) yieldActiveMaintenance();
                else requestActiveTransactionDrain();
            }
            if (active == null && !paused) startNextRequest();
            if (active == null || paused) {
                stationRoom.stop(); threats.stop(); equipment.stop(); animalAcquisition.stop(); food.stop();
                movement.suspend(); input.release();
                if (config.backfill && !paused && queue.isEmpty() && maintenanceQueue.isEmpty() && projects.isEmpty()
                        && step == null && pendingPlan == null && !openingStation && !transactionInProgress()
                        && !airRecovery.active() && healthRecovery == null && !threats.ready()
                        && movement.backfillIdle() && catalog != null && catalog.ready()
                        && client.player.getHealth() > config.pauseBelowHealth)
                    backfill.restoreIdle(backfillStock());
                return;
            }
            if (!client.player.isAlive()) { pause("player is no longer alive"); return; }
            if (config.pauseOnScreen && client.currentScreen != null && cleanupRun == null && crafting == null && stonecutting == null && smelting == null && !openingStation) { healthRecovery = null; stationRoom.stop(); threats.stop(); equipment.stop(); animalAcquisition.stop(); food.stop(); movement.suspend(); input.release(); return; }
            if (travelAction != null && nativeRun == travelAction && !travelAction.suspended()
                    && (airRecovery.ready() || client.player.getHealth() <= config.pauseBelowHealth
                        || config.autoDefend && threats.ready() || config.autoEat && food.ready()
                        || config.autoEquipArmor && equipment.active())) {
                travelAction.requestDrain(NativeRun.DrainReason.PREEMPT);
                if (tickTravelObserver()) return;
            }
            threats.useShield(config.autoUseShield);
            threats.updateProtection(foodReservations());
            if (!airRecovery.active() && config.autoDefend && !stopAfterStep && !transactionInProgress() && !openingStation
                    && !hasOwnedStationHandlerOpen() && !food.active() && !equipment.active() && !cleanupReturnBarrier() && threats.ready()) {
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                resetAction();
                if (cleanupReturnBarrier()) return;
                refreshNavigationProtection();
                threats.useShield(config.autoUseShield);
                threats.updateProtection(foodReservations());
                if (threats.begin()) status = threats.status();
                else continueActiveRequest();
                return;
            }
            food.updateProtection(foodReservations());
            if (recoverHealthIfNeeded()) return;
            if (stationPlacementWait != null) {
                placeStation();
                return;
            }
            if (cleanupRun != null) {
                tickStationCleanup();
                return;
            }

            if (foregroundYieldPending && !transactionInProgress() && !openingStation && !canYieldMaintenanceNow()) {
                input.release(); status = "foreground queued; waiting for inventory screen and cursor to be safe"; return;
            }
            food.updateProtection(foodReservations());
            if (food.active()) {
                input.release(); status = "eating before continuing " + active.name();
                if (!config.autoEat || client.currentScreen != null) { food.stop(); continueActiveRequest(); }
                else if (food.tick()) continueActiveRequest();
                return;
            }
            if (animalAcquisitionPending) {
                if (config.autoEat && beginCarryTable()) return;
                animalAcquisitionPending = false;
                animalAcquisition.updateProtection(foodReservations());
                if (config.autoEat && animalAcquisition.begin(healthRecovery != null, active.jobToken(), catalog.generation(), this::reserveAnimalAttempt)) { holdAnimalRun(); status = animalAcquisition.status(); }
                else { animalAcquisitionCooldown = 200; continueActiveRequest(); }
                return;
            }
            if (foodReplanPending) { continueActiveRequest(); return; }
            if (acquisitionView() != null && !exploring && pendingPlan == null && step == null) {
                if (!catalog.ready()) { status = "waiting for recipe catalog"; return; }
                continueActiveRequest();
            }
            if (config.autoEat && foodCooldown == 0 && !stopAfterStep && !transactionInProgress()
                    && !openingStation && !hasOwnedStationHandlerOpen() && food.ready()) {
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                resetAction();
                if (!movement.finishCancellation()) { foodReplanPending = true; return; }
                foodCooldown = 100;
                if (food.begin()) { foodReplanPending = true; status = "eating before continuing " + active.name(); }
                else continueActiveRequest();
                return;
            }
            if (config.autoEat && animalAcquisitionCooldown == 0 && !stopAfterStep
                    && !transactionInProgress() && !openingStation && !hasOwnedStationHandlerOpen()
                    && client.currentScreen == null && !food.ready()
                    && (client.player.getHungerManager().getFoodLevel() <= 14
                        || healthRecovery != null || needsMiningFoodStock())
                    && animalAcquisition.ready(healthRecovery != null)) {
                if (beginCarryTable()) return;
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                resetAction();
                animalAcquisitionPending = true;
                status = "finishing movement before acquiring food";
                return;
            }
            if (config.autoEquipArmor && (nativeRun != travelAction || travelAction == null || travelAction.suspended())
                    && !stopAfterStep && !transactionInProgress() && !openingStation
                    && !hasOwnedStationHandlerOpen() && !food.active() && !nativeAcquisitionActive()
                    && !stationRecovery.active() && !stationRoom.active() && client.currentScreen == null) {
                try {
                    if (equipment.tick()) {
                        status = equipment.status();
                        observeInventory();
                        return;
                    }
                } catch (RuntimeException failure) {
                    equipment.stop();
                    pause("Armor transfer needs inspection: " + failure.getMessage());
                    return;
                }
            }
            if (active instanceof TravelRequest && acquisitionScope == null) {
                continueActiveRequest();
                tickTravelRun();
                return;
            }
            if (exploring) { explore(); return; }
            invalidateStationStockHint();
            if (stationStockHint != null && stationStockHint.future().isDone()) {
                StationStockHint hint = stationStockHint;
                PlanningOutcome outcome = null;
                try { outcome = hint.future().join(); }
                catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException ignored) { }
                stationStockHint = null;
                pendingPlan = null;
                pendingPreferencePlan = false;
                PlanResult result = outcome == null ? null : outcome.result();
                String nextSource = result != null && result.success() && !result.steps().isEmpty()
                        ? result.steps().get(0).sourceId() : null;
                boolean changed = nextSource != null && !nextSource.equals(step.sourceId());
                if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] STATION_STOCK_HINT job={} scope={} source={} success={} next={} changed={}",
                        active.jobToken(), System.identityHashCode(hint.scope()), step.sourceId(),
                        result != null && result.success(), nextSource, changed);
                if (changed) {
                    movement.checkAirRecoveryOwnership();
                    movement.stop();
                    resetAction();
                    if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                            "[Lodekeeper] STATION_STOCK_HANDOFF job={} scope={} planning=fresh-after-cancellation",
                            hint.scope().request.jobToken(), System.identityHashCode(hint.scope()));
                    return;
                }
            }
            if (acquisitionScope != null && acquisitionScope == travelFoodScope && !travelFoodDemandCurrent(acquisitionScope)) {
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                requestActiveTransactionDrain();
                if (!transactionInProgress() && !openingStation && stationPlacementHand == null) {
                    finishAcquisitionPhase(acquisitionScope); return;
                }
            }
            if (pendingPlan != null && (pendingPlan.isDone() || !pendingPreferencePlan || step == null)) {
                if (!pendingPlan.isDone()) return;
                if (!nativePlanningCurrent()) {
                    pendingPlan.cancel(false); pendingPlan = null; continueActiveRequest(); return;
                }
                if (acquisitionScope == travelFoodScope && travelFoodOffer != null && !travelFoodOfferCurrent(travelFoodOffer)) {
                    pendingPlan.cancel(false); pendingPlan = null; travelFoodOffer = null; continueActiveRequest(); return;
                }
                PlanningOutcome outcome = pendingPlan.join();
                if (acquisitionScope == travelFoodScope && travelFoodOffer != null) {
                    travelFoodDemand = travelFoodOffer.demand();
                    travelFoodOffer = null;
                }
                if (outcome == null && (acquisitionScope == travelShieldScope || acquisitionScope == travelFoodScope)) {
                    pendingPlan = null; finishAcquisitionPhase(acquisitionScope); return;
                }
                if (outcome.shieldIdentity() != null && !shieldIdentityCurrent(outcome.shieldIdentity())) {
                    deferShieldPreparation = true;
                    pendingPlan = null;
                    pendingPreferencePlan = false;
                    continueActiveRequest();
                    return;
                }
                if (pendingPreferencePlan && step != null) {
                    pendingPlan = null;
                    pendingPreferencePlan = false;
                    stepPreferencesVersion = pendingPlanPreferencesVersion;
                    PlanResult preferred = outcome.result();
                    if (preferred.success() && !preferred.steps().isEmpty()
                            && (outcome.auxiliaryInvestment() && !stepAuxiliaryInvestment
                                || !preferred.steps().get(0).sourceId().equals(step.sourceId()))) {
                        resetAction();
                        continueActiveRequest();
                        return;
                    }
                } else {
                    PlanResult result = outcome.result();
                    long resultGeneration = pendingPlanGeneration;
                    pendingPlan = null;
                    if (!catalog.ready() || resultGeneration != catalog.generation()) {
                        if (catalog.ready()) continueActiveRequest();
                        else status = "waiting for recipe catalog";
                        return;
                    }
                    if (!result.success() && pendingPlanPreferencesVersion != nearbyResources.version()) {
                        continueActiveRequest();
                        return;
                    }
                    if (outcome.auxiliaryInvestment() && result.steps().isEmpty()) { continueActiveRequest(); return; }
                    if (!nativeAcquisitionActive() && goalCount() >= acquisitionView().count) { finishGoal(); return; }
                    if (!result.success() && planningRetries++ < 4 && result.blockedReasons().stream().anyMatch(r -> r.code() == BlockedReason.Code.TIME_LIMIT)) { continueActiveRequest(); return; }
                    if (!result.success() && tryNextLogPlan(result)) return;
                    if (!result.success() && canExplore(outcome)) { beginExploration(); return; }
                    if (!result.success()) { failActive("No plan: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(3).toList()); return; }
                    if (result.steps().isEmpty()) { finishGoal(); return; }
                    PlanStep first = result.steps().get(0);
                    if (Boolean.parseBoolean(first.attributes().getOrDefault("shieldPreparation", "false"))
                            && (!config.autoDefend || !config.autoUseShield || !config.autoCraftShield)) { continueActiveRequest(); return; }
                    if (isFoodPreparation(first) && !config.autoEat) { continueActiveRequest(); return; }
                    if (first.kind() == PlanKind.GATHER) {
                        if (beginPreviousCommandTable(first)) return;
                        if (result.steps().stream().flatMap(planned -> planned.requirements().stream())
                                .anyMatch(requirement -> requirement instanceof SelectedStationRequirement station
                                        && CRAFTING_TABLE.equals(station.station()))
                                && beginCarryTable()) return;
                        int capacity = gatherCapacity(first.output());
                        if (outcome.unbatchedGather() != null && first.outputCount() > capacity)
                            first = outcome.unbatchedGather();
                        if (first.outputCount() > capacity) {
                            pause("Inventory has room for " + capacity + " more " + first.output()
                                    + "; the next gather needs " + first.outputCount() + ". Free inventory space, then resume");
                            return;
                        }
                    }
                    if (first.kind() == PlanKind.SMELT
                            && Boolean.parseBoolean(first.attributes().getOrDefault("ordinaryInputOnly", "false"))
                            && first.outputCount() > gatherCapacity(first.output())) {
                        continueActiveRequest();
                        return;
                    }
                    begin(first, resultGeneration, outcome.auxiliaryInvestment(), pendingPlanPreferencesVersion, outcome.shieldIdentity());
                }
            }
            if (step == null) return;
            if (!planIdentityCurrent(stepPlanIdentity)) {
                if (transactionInProgress() || openingStation) requestActiveTransactionDrain();
                else { resetAction(); continueActiveRequest(); return; }
            }
            if (openingStation && ++stationOpenTicks >= 80 && !stationReady()) {
                if (stationAccessFailures++ >= 3)
                    throw new IllegalStateException("Required station did not acknowledge opening after repeated attempts");
                BlockPos unavailable = knownStations.remove(step.station());
                if (unavailable != null) unreachableStations.add(unavailable);
                message("Station did not open; trying another station or placement");
                resetAction();
                continueActiveRequest();
                return;
            }
            if (!shieldStepCurrent()) {
                deferShieldPreparation = true;
                if (crafting != null) crafting.requestDrain();
                else if (stationPlacementWait == null || !stationPlacementWait.sent()) {
                    if (openingStation && !stationReady()) return;
                    resetAction();
                    continueActiveRequest();
                    return;
                }
            }
            if (isFoodPreparation(step) && !config.autoEat) {
                if (transactionInProgress()) requestActiveTransactionDrain();
                else {
                    if (openingStation && !stationReady()) return;
                    resetAction();
                    continueActiveRequest();
                    return;
                }
            }
            if (stepCatalogGeneration != catalog.generation() || !catalog.ready()) {
                if (crafting != null || stonecutting != null || smelting != null) {
                    // The action owns an immutable RecipeWork snapshot. Let it finish the
                    // in-flight transfer and safely drain before replanning against new data.
                    requestActiveTransactionDrain();
                } else {
                    if (openingStation && !stationReady()) return;
                    resetAction();
                    if (catalog.ready()) continueActiveRequest();
                    else status = "waiting for recipe catalog";
                    return;
                }
            }
            if (step.kind() == PlanKind.GATHER && target == null && scan != null
                    && stepPreferencesVersion != nearbyResources.version() && preferenceRefreshCooldown == 0) {
                preferenceRefreshCooldown = 20;
                resetAction();
                continueActiveRequest();
                return;
            }
            if (moving && pendingPlan == null && step.kind() == PlanKind.GATHER && preferenceRefreshCooldown == 0
                    && movement.canReconsiderMiningSource() && !nearbyResources.hasLiveLogSource(step.sourceId())
                    && stepPreferencesVersion != nearbyResources.version()
                    && catalog.tags.getOrDefault(LOGS_TAG, List.of()).contains(step.output())) {
                NearbyResources.LogObservation nearby = nearbyResources.bestLogObservation();
                if (nearby != null && !nearby.source().sourceId().equals(step.sourceId())
                        && !unavailableSources.contains(nearby.source().sourceId())) {
                    preferenceRefreshCooldown = 40;
                    if (acquisitionView().anyLogs) resetLogDiscovery();
                    continueActiveRequest();
                    pendingPreferencePlan = pendingPlan != null;
                    if (step == null) return;
                }
            }
            if (moving && pendingPlan == null && acquisitionView().anyLogs && config.optimizeWoodTools && !stepAuxiliaryInvestment
                    && step.kind() == PlanKind.GATHER && preferenceRefreshCooldown == 0) {
                preferenceRefreshCooldown = 40;
                rememberNativeLogTargets();
                if (captureHarvestOffer(acquisitionView().count - goalCount()) != null) {
                    continueActiveRequest();
                    pendingPreferencePlan = pendingPlan != null;
                    if (step == null) return;
                }
            }
            MovementProgressScope stockScope = projectLogGatherScope();
            if (pendingPlan == null && stockScope != null && !stockScope.stationStockReconsidered
                    && stockScope.tableGainFrom >= 0 && canReconsiderStationStock()
                    && actions.count(GameCatalog.item(CRAFTING_TABLE_ITEM)) > stockScope.tableGainFrom) {
                Request owner = active;
                PlanStep gathering = step;
                movement.checkAirRecoveryOwnership();
                continueActiveRequest();
                if (pendingPlan != null && active == owner && step == gathering
                        && parentMovementScope == stockScope && canReconsiderStationStock()) {
                    stockScope.stationStockReconsidered = true;
                    pendingPreferencePlan = true;
                    stationStockHint = new StationStockHint(pendingPlan, gathering, stockScope, pendingPlanGeneration);
                    if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                            "[Lodekeeper] STATION_STOCK_DISPATCH job={} scope={} source={} tableBefore={} tableNow={}",
                            owner.jobToken(), System.identityHashCode(stockScope), gathering.sourceId(),
                            stockScope.tableGainFrom, stockScope.sampledTableCount);
                }
                if (step == null || active != owner || paused) return;
            }
            movement.observeConfirmedProgress();
            observeGatherRemoval();
            long movementProgress = movement.progressToken();
            if (movementProgress != lastMovementProgressToken) {
                lastMovementProgressToken = movementProgress;
                actionTicks = 0;
            }
            int observed = step.output() == null ? 0 : actions.count(GameCatalog.item(step.output()));
            if (observed != lastObservedCount) { lastObservedCount = observed; actionTicks = 0; }
            if (smelting != null && smelting.progressToken() != lastSmeltProgress) { lastSmeltProgress = smelting.progressToken(); actionTicks = 0; }
            if (++actionTicks > config.actionTimeoutTicks) {
                if (shouldRejectTimedOutGather()) {
                    rejectResource("Gathering made no confirmed progress before its timeout");
                    return;
                }
                throw new IllegalStateException("Action timeout: " + step.sourceId()
                        + " · " + status + (target == null ? "" : " · target " + target.getX() + "," + target.getY() + "," + target.getZ()));
            }
            if (step.kind() != PlanKind.NATIVE && step.output() != null && crafting == null && stonecutting == null && smelting == null
                    && !openingStation
                    && actions.count(GameCatalog.item(step.output())) >= baseline + step.outputCount()) {
                movement.stop();
                if (!movement.finishCancellation()) { status = "finishing movement before confirming collected stock"; return; }
                input.idle();
                if (++verifyTicks >= 8) completeStep();
                return;
            }
            verifyTicks = 0;
            if (moving) {
                status = movement.status();
                try { if (movement.tick()) { moving = false; movingPickup = false; movement.stop(); } }
                catch (MovementController.NavigationFailure blocked) {
                    if (step.kind() == PlanKind.GATHER) {
                        if (blocked.kind == MovementController.NavigationFailure.Kind.REQUEST_LIMIT) {
                            MovementController.MiningRequestLimit limit = blocked.requestLimit;
                            int currentCount = actions.count(GameCatalog.item(step.output()));
                            int collected = Math.max(0, currentCount - baseline);
                            MiningContinuation continuation = new MiningContinuation(client.world, client.world.getRegistryKey(),
                                    acquisitionScope, step.sourceId(), limit.rejectedPositions());
                            if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                                    "[Lodekeeper] MINING_REQUEST_YIELD source={} output={} initialCount={} currentCount={} collected={} cause={} requestTicks={} maximumTicks={} elapsedMillis={} distance={} maximumDistance={} retainedRejects={}",
                                    step.sourceId(), step.output(), baseline, currentCount, collected, limit.cause(),
                                    limit.requestTicks(), limit.maximumTicks(), limit.elapsedMillis(), limit.distance(),
                                    limit.maximumDistance(), limit.rejectedPositions().size());
                            if (collected == 0) {
                                miningContinuation = continuation;
                                pause("Mining request reached its " + limit.cause()
                                        + " limit without confirmed collected output for " + step.output()
                                        + " (initial " + baseline + ", current " + currentCount + ")");
                                return;
                            }
                            message("Mining collected " + collected + " " + step.output()
                                    + " before its " + limit.cause() + " limit; replanning");
                            resetAction();
                            miningContinuation = continuation;
                            continueActiveRequest();
                            return;
                        }
                        message("Mining is replanning: " + blocked.getMessage());
                        if (blocked.kind != MovementController.NavigationFailure.Kind.TOOL)
                            unavailableSources.add(step.sourceId());
                        resetAction(); continueActiveRequest();
                    } else if (step.kind() == PlanKind.PLACE_STATION && target != null
                            && stationPlacementFailures < MAX_STATION_PLACEMENT_ATTEMPTS) {
                        movement.stop();
                        moving = false;
                        rejectStationSite(target);
                    } else if ((step.kind() == PlanKind.CRAFT || step.kind() == PlanKind.SMELT)
                            && step.station() != null && stationAccessFailures++ < 3) {
                        BlockPos unreachable = knownStations.remove(step.station());
                        if (unreachable != null) unreachableStations.add(unreachable);
                        message("Station access is replanning: " + blocked.getMessage());
                        resetAction(); continueActiveRequest();
                    } else throw blocked;
                }
                return;
            }
            switch (step.kind()) {
                case GATHER -> gather();
                case PLACE_STATION -> placeStation();
                case CRAFT -> craft();
                case SMELT -> smelt();
                case NATIVE -> beginNativeStep();
                case CUSTOM -> throw new IllegalStateException("No executor registered for " + step.customType());
            }
        } catch (RuntimeException ex) {
            if (ex instanceof MovementController.NavigationFailure navigation
                    && navigation.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST)
                pauseAfterOwnershipLoss(navigation);
            else failActive(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }
    private JobContext captureContext(long token, int seconds) {
        protection.sync();
        if (client.player == null || client.world == null || client.getNetworkHandler() == null
                || client.getNetworkHandler().getConnection() == null
                || !client.getNetworkHandler().getConnection().isOpen())
            throw new IllegalStateException("Live job context is unavailable");
        var runtime = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var nativeSession = runtime == null ? null : runtime.captureSession();
        var provenance = placementProvenance.session().orElse(null);
        var scope = protection.capture().scope();
        if (runtime == null || !runtime.isCurrent(nativeSession) || nativeSession.world() != client.world
                || provenance == null || scope == null) throw new IllegalStateException("Owned job session is unavailable");
        long now = System.nanoTime();
        return new JobContext(token, client.player.getUuid(), 0, scope, client.world, client.player,
                client.getNetworkHandler(), client.getNetworkHandler().getConnection(), runtime, nativeSession,
                provenance, now, Math.addExact(now, Math.multiplyExact((long) seconds, 1_000_000_000L)));
    }
    private boolean contextBound(JobContext context) {
        return context != null && context.world() == client.world && context.player() == client.player
                && context.network() == client.getNetworkHandler() && client.getNetworkHandler() != null
                && context.connection() == client.getNetworkHandler().getConnection()
                && client.getNetworkHandler().getConnection().isOpen()
                && Objects.equals(context.scope(), protection.capture().scope())
                && dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() == context.owner()
                && context.owner().isCurrent(context.nativeSession())
                && context.nativeSession().world() == client.world
                && placementProvenance.session().filter(context.provenanceSession()::equals).isPresent();
    }
    private boolean contextReplaced(JobContext context) {
        return context.world() != client.world || context.player() != client.player
                || context.network() != client.getNetworkHandler() || client.getNetworkHandler() == null
                || context.connection() != client.getNetworkHandler().getConnection()
                || !Objects.equals(context.scope(), protection.capture().scope())
                || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != context.owner()
                || !context.owner().isCurrent(context.nativeSession())
                || !placementProvenance.session().filter(context.provenanceSession()::equals).isPresent();
    }
    private void abandonReplacedContext(Request expired) {
        if (!contextReplaced(expired.context())) return;
        abandonStationPlacementHand();
        animalAcquisition.abandonSession(); abandonCropSession();
        stationRecovery.abandonSession();
        abandonTravelSession();
        movement.abandonRequestContext();
        if (pendingPlan != null) pendingPlan.cancel(false);
        pendingPlan = null; pendingPlanIdentity = stepPlanIdentity = null;
        crafting = null; stonecutting = null; smelting = null;
        cleanupRun = null; cleanupBudget = null; cleanedAcquisitionScope = null; pendingStationPickup = null;
        deferredStationCleanup.clear(); completedStandaloneTables.clear(); previousCommandTableCheck = null;
        stationPlacementOwner = null; stationPlacementStation = null;
        step = null; moving = movingPickup = exploring = explorationMoving = openingStation = false;
        ownedStationHandler = null; stationOpeningFrom = null;
        animalAcquisitionPending = foodReplanPending = false;
        nativeRun = null; nativeRunParent = null; nativeRunScope = null;
        active = null; acquisitionScope = null; cancelledRequest = null;
        queue.removeIf(request -> contextReplaced(request.context()));
        maintenanceQueue.removeIf(request -> contextReplaced(request.context()));
        for (ProjectRun run : new ArrayList<>(projects))
            if (run == (expired instanceof AcquireRequest acquire ? acquire.project() : null)) abortProject(run);
        input.release();
        paused = true;
        status = "job context replaced; old cleanup abandoned and unproved";
        message(status + "; resume to use newly admitted requests");
    }
    private boolean newEffectCurrent(Request request) {
        return request != null && request != cancelledRequest && !stopAfterStep && contextBound(request.context())
                && System.nanoTime() - request.context().deadlineNanos() < 0;
    }
    private boolean scopeCurrent(AcquisitionScope scope) {
        return scope != null && scope == acquisitionScope && scope.parent() == active && newEffectCurrent(active);
    }
    private boolean planIdentityCurrent(PlanIdentity identity) {
        return identity != null && active != null
                && (identity.scope() == null ? acquisitionScope == null && active instanceof TravelRequest && newEffectCurrent(active)
                        : scopeCurrent(identity.scope())) && identity.context() == active.context()
                && identity.serial() == planSerial && identity.catalog() == catalog && catalog != null
                && catalog.ready() && identity.catalogGeneration() == catalog.generation()
                && identity.preferencesVersion() == nearbyResources.version()
                && identity.policyGeneration() == identity.context().owner().policyGeneration();
    }
    private AcquisitionScope createAcquisitionScope(Request parent, AcquireRequest view) {
        if (phaseGeneration == Long.MAX_VALUE) throw new IllegalStateException("Acquisition phase capacity reached");
        return new AcquisitionScope(parent, view, ++phaseGeneration,
                new AcquisitionDemand(view.item(), view.count(), view.anyLogs()));
    }
    private ItemId previewAcquisitionItem() {
        if (acquisitionView() != null) return acquisitionView().item();
        for (Request request : queue) if (request instanceof AcquireRequest acquire) return acquire.item();
        return maintenanceQueue.isEmpty() ? null : maintenanceQueue.peekFirst().item();
    }
    private void appendRequest(Request request) {
        if (!newEffectCurrent(request)) throw new IllegalStateException("Request context expired before admission");
        if (queue.size() >= MAX_FOREGROUND_QUEUE) throw new IllegalStateException("Foreground queue limit is 32 goals");
        queue.addLast(request);
        yieldMaintenanceForForeground();
    }
    void enqueueTravel(TravelGoal goal) {
        ensureNotStopping();
        ensureCatalog();
        if (!GameApi.supportsTravel()) throw new IllegalStateException("Player travel is unsupported in this game family");
        if (!GameApi.travelPose(client)) throw new IllegalStateException("Travel needs a live supported player pose");
        if (goal instanceof TravelGoal.ExploreGoal && !config.allowExploration)
            throw new IllegalStateException("allowExploration is disabled");
        if (goal instanceof TravelGoal.PointGoal point) {
            BlockPos feet = new BlockPos(point.x(), point.feetY(), point.z());
            long dx = (long) feet.getX() - client.player.getBlockPos().getX(), dz = (long) feet.getZ() - client.player.getBlockPos().getZ();
            if (!GameApi.travelBounds(client, feet) || Math.abs(dx) > 256 || Math.abs(dz) > 256 || dx * dx + dz * dz > 65536)
                throw new IllegalArgumentException("Coordinates exceed current height, border or 256 block admission radius");
        } else if (goal instanceof TravelGoal.FollowGoal follow && GameApi.loadedTravelPlayer(client, follow.target()) == null)
            throw new IllegalArgumentException("Pinned follow target is unavailable");
        int seconds = goal instanceof TravelGoal.FollowGoal follow ? follow.seconds() : 120;
        JobContext context = captureContext(allocateJobToken(), seconds);
        TravelRequest request = new TravelRequest(travelName(goal), goal, context);
        appendRequest(request);
        message("Queued job " + request.jobToken() + " " + request.name());
    }
    private static String travelName(TravelGoal goal) {
        if (goal instanceof TravelGoal.PointGoal point) return "goto " + point.x() + " " + point.feetY() + " " + point.z();
        if (goal instanceof TravelGoal.ExploreGoal explore) return "explore " + explore.radius() + " " + explore.segments();
        TravelGoal.FollowGoal follow = (TravelGoal.FollowGoal) goal;
        return "follow " + follow.target() + " for " + follow.seconds() + " seconds";
    }
    void followPlayer(String selector, int seconds) {
        UUID target = GameApi.resolveTravelPlayer(client, selector);
        enqueueTravel(new TravelGoal.FollowGoal(target, seconds));
    }
    void waypoint(CommandParser.WaypointCommand command) throws java.io.IOException {
        if (!GameApi.supportsTravel()) throw new IllegalStateException("Waypoints are unsupported in this game family");
        protection.sync();
        WorldScope scope = protection.capture().scope();
        if (scope == null) throw new IllegalStateException("World/dimension scope is unavailable");
        switch (command.action()) {
            case LIST -> message("Waypoints in this world/dimension " + waypoints.list(scope));
            case REMOVE -> { waypoints.remove(scope, command.label()); message("Removed waypoint " + command.label()); }
            case GOTO -> enqueueTravel(waypoints.require(scope, command.label()));
            case SET -> {
                if (!GameApi.travelPose(client) || !client.player.isOnGround()
                        || Math.abs(client.player.getY() - Math.rint(client.player.getY())) > 1.0e-6)
                    throw new IllegalStateException("Waypoint set requires a supported integer feet stance");
                BlockPos feet = client.player.getBlockPos();
                var probe = new dev.lodekeeper.nav.StanceProbe();
                terrain.probeCurrentStance(client.player.getX(), feet.getY() * 16, client.player.getZ(), probe);
                if (!GameApi.travelBounds(client, feet) || !probe.loaded || !probe.bodyClear || !probe.hasGroundSupport()
                        || probe.hazard || probe.water || probe.climbable || probe.breakCount != 0)
                    throw new IllegalStateException("Waypoint stance is not loaded and safe");
                waypoints.set(scope, command.label(), new TravelGoal.PointGoal(feet.getX(), feet.getY(), feet.getZ()));
                message("Saved scoped waypoint " + command.label());
            }
        }
    }
    void cache(CommandParser.CacheCommand command) {
        if (!GameApi.supportsTravel()) throw new IllegalStateException("Travel forecasts are unsupported in this game family");
        if (command.action() == CommandParser.CacheAction.STATUS) { message(terrain.travelCacheStatus()); return; }
        if (active != null || nativeRun != null || transactionInProgress() || cleanupRun != null
                || stationRecovery.active() || stationRecovery.pickupRetained() || openingStation
                || pendingPlan != null || airRecovery.active() || threats.active() || equipment.active() || food.active()
                || client.player == null || client.currentScreen != null
                || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.currentScreenHandler.getCursorStack().isEmpty())
            throw new IllegalStateException("Travel cache clear is busy; retry after owned work is released");
        movement.checkAirRecoveryOwnership();
        if (!movement.travelReleased()) throw new IllegalStateException("Travel cache clear needs released movement and settings");
        frontier = null;
        terrain.clearTravelForecasts();
        message("Cleared source travel forecasts and terrain query hints; waypoints and native region storage retained");
    }
    private void continueActiveRequest() {
        if (paused || active == null || cancelledRequest == active || stopAfterStep) return;
        if (!newEffectCurrent(active)) { cancelledRequest = active; return; }
        if (nativeAcquisitionActive() || stationPlacementHand != null || cleanupRun != null
                || airRecovery.active() || otherTransactionInProgress() || openingStation) return;
        if (cleanupReturnBarrier() || !inventoryAdmissionSafe()) return;
        if (acquisitionScope != null) { requestPlan(acquisitionScope); return; }
        if (active instanceof AcquireRequest acquire) {
            acquisitionScope = createAcquisitionScope(active, acquire);
            requestPlan(acquisitionScope);
            return;
        }
        TravelRequest travel = (TravelRequest) active;
        if (travelActionParent != travel) {
            travelAction = new TravelAction(client, config, movement, terrain, travel, () -> !contextReplaced(travel.context()),
                    () -> travelEffectCurrent(travel));
            travelActionParent = travel;
        }
        if (prepareTravelFood(travel)) return;
        if (prepareTravelShield(travel)) return;
        if (nativeRun == null) { nativeRun = travelAction; nativeRunParent = active; nativeRunScope = null; }
    }
    private boolean travelEffectCurrent(TravelRequest travel) {
        return active == travel && newEffectCurrent(travel) && !paused && !stopAfterStep
                && GameApi.travelPose(client) && client.player.getHealth() > config.pauseBelowHealth
                && !editingSettings() && !manualStationInput() && client.currentScreen == null
                && client.player.currentScreenHandler == client.player.playerScreenHandler
                && client.player.currentScreenHandler.getCursorStack().isEmpty()
                && !client.player.isUsingItem() && !airRecovery.active() && !airRecovery.ready()
                && !threats.active() && !food.active() && !equipment.active() && !nativeAcquisitionActive()
                && !stationRecovery.active() && !cleanupReturnBarrier() && cleanupRun == null
                && stationPlacementHand == null && !otherTransactionInProgress() && !openingStation;
    }
    private PlanIdentity capturePlanIdentity(AcquisitionScope scope) {
        if (planSerial == Long.MAX_VALUE) throw new IllegalStateException("Planning serial capacity reached");
        return new PlanIdentity(scope, ++planSerial, active.context(), catalog,
                catalog.generation(), nearbyResources.version(), active.context().owner().policyGeneration());
    }
    private static final PlannerLimits TRAVEL_FOOD_LIMITS = new PlannerLimits(8, 512, 5, 8, 3, 64);
    private record TravelFoodDemand(PendingGoals goals, List<AcquireRequest> queued,
                                    List<ProjectSpec> projects, Map<ItemId, Integer> maintained) { }
    private TravelFoodDemand captureTravelFoodDemand() {
        return new TravelFoodDemand(pendingGoals(false), queue.stream().filter(AcquireRequest.class::isInstance)
                .map(AcquireRequest.class::cast).toList(), projects.stream().filter(run -> !run.aborted)
                .map(run -> run.spec).toList(), Map.copyOf(maintained.reservedCounts()));
    }
    private boolean travelFoodDemandCurrent(AcquisitionScope scope) {
        if (scope == null || scope != travelFoodScope || scope != acquisitionScope || scope.parent() != active) return false;
        if (!config.autoEat || travelFoodDemand == null || !travelFoodDemand.equals(captureTravelFoodDemand()))
            travelFoodInvalidated = true;
        return !travelFoodInvalidated;
    }

    private record TravelFoodOffer(CatalogSnapshot catalog, InventorySnapshot stock,
                                   FoodController.Preparation preparation, boolean canPlace,
                                   CatalogSnapshot workCatalog, InventorySnapshot workInventory,
                                   ProjectSpec pendingProject, PendingGoals goals, Set<String> unavailable, TravelFoodDemand demand) { }

    private Map<ItemId, Integer> ordinaryTravelFoodCounts() {
        Map<ItemId, Integer> ordinary = new HashMap<>();
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (!stack.isEmpty() && !stack.hasEnchantments() && !GameApi.hasCustomName(stack)
                    && GameApi.canCombine(stack, new ItemStack(stack.getItem())))
                ordinary.merge(GameCatalog.id(stack.getItem()), stack.getCount(), Math::addExact);
        }
        return Map.copyOf(ordinary);
    }
    private TravelFoodOffer captureTravelFoodOffer(FoodController.Preparation preparation) {
        if (!config.autoEat || preparation == null || preparation.targetCount() > 64
                || preparation.targetCount() <= actions.count(GameCatalog.item(preparation.item()))
                || preparation.targetCount() - actions.count(GameCatalog.item(preparation.item())) > gatherCapacity(preparation.item())) return null;
        CatalogSnapshot full = planningCatalog(false);
        List<AcquisitionSource> cooking = preparationSources(full, preparation, true);
        if (cooking.isEmpty()) return null;
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        full.itemDefinitions().values().forEach(builder::item);
        catalog.tags.forEach(builder::tag);
        cooking.stream().filter(source -> !unavailableSources.contains(source.sourceId())).forEach(builder::source);
        Set<ItemId> stationItems = Set.of(ItemId.parse("minecraft:furnace"), ItemId.parse("minecraft:smoker"),
                ItemId.parse("minecraft:blast_furnace"));
        int inspected = 0;
        long deadline = System.nanoTime() + 1_000_000L;
        for (ItemId stationItem : stationItems.stream().sorted().toList()) {
            for (AcquisitionSource source : full.sourcesFor(stationItem)) {
                if (++inspected > 128 || System.nanoTime() - deadline >= 0) return null;
                if (!(source instanceof CraftingSource crafting) || !stationItems.contains(crafting.output())
                        || unavailableSources.contains(source.sourceId()) || crafting.outputCount() != 1) continue;
                var recipe = catalog.recipes.get(source.sourceId());
                if (recipe == null || !(recipe.kind() == RecipeWork.Kind.SHAPED_CRAFTING
                        || recipe.kind() == RecipeWork.Kind.SHAPELESS_CRAFTING)) continue;
                ItemStack output = recipe.outputPerOperation();
                if (output.getCount() != 1 || !GameApi.canCombine(output, new ItemStack(GameCatalog.item(source.output())))) continue;
                builder.source(source);
            }
        }
        if (System.nanoTime() - deadline >= 0) return null;
        InventorySnapshot work = inventorySnapshot(null);
        InventorySnapshot protectedStock = protectedFoodInventory(work);
        InventorySnapshot known = withKnownStations(work);
        Map<ItemId, Integer> ordinary = ordinaryTravelFoodCounts();
        Map<ItemId, Integer> floors = new HashMap<>(protectedStock.protectedCounts());
        floors.replaceAll((item, count) -> Math.min(count, ordinary.getOrDefault(item, 0)));
        floors.values().removeIf(count -> count == 0);
        InventorySnapshot stock = new InventorySnapshot(ordinary, known.availableStations(), Map.of(), floors);
        PendingGoals goals = pendingGoals(false);
        if (goals.unresolvedLogs() || goals.targets().size() > ProjectSpec.MAX_GOALS) return null;
        ProjectSpec pending = goals.targets().isEmpty() ? null : remainingProject(new ProjectSpec("pending_goals",
                "Pending inventory targets", goals.targets(), ProjectSpec.Purpose.INVENTORY_GOALS), work);
        return new TravelFoodOffer(builder.build(), stock, preparation, config.allowBuilding, full, known, pending, goals, Set.copyOf(unavailableSources), captureTravelFoodDemand());
    }
    private boolean travelFoodOfferCurrent(TravelFoodOffer offer) {
        if (offer == null || !config.autoEat || config.allowBuilding != offer.canPlace()
                || !offer.unavailable().equals(unavailableSources)
                || !offer.demand().equals(captureTravelFoodDemand())
                || !ordinaryTravelFoodCounts().equals(offer.stock().counts())) return false;
        InventorySnapshot now = protectedFoodInventory(inventorySnapshot(null));
        Map<ItemId, Integer> floors = new HashMap<>(now.protectedCounts());
        floors.replaceAll((item, count) -> Math.min(count, offer.stock().count(item)));
        floors.values().removeIf(count -> count == 0);
        return floors.equals(offer.stock().protectedCounts())
                && withKnownStations(now).availableStations().equals(offer.stock().availableStations());
    }
    private PlanningOutcome onHandTravelFoodOutcome(TravelFoodOffer offer, PlanningPreferences preferences) {
        if (offer == null) return null;
        try {
            InventorySnapshot stock = offer.stock();
            if (offer.pendingProject() != null) {
                ProjectPlanResult pending = planner.planProjectFast(offer.workCatalog(), offer.workInventory(),
                        offer.pendingProject(), PlannerLimits.DEFAULT, preferences);
                if (!pending.success()) return null;
                stock = ProjectMaterialReservations.protectOptionalWork(stock, pending);
            }
            PlanResult plan = planner.planFast(offer.catalog(), stock, offer.preparation().item(),
                    offer.preparation().targetCount(), TRAVEL_FOOD_LIMITS, preferences);
            if (!plan.success() || plan.steps().isEmpty() || plan.steps().size() > 3) return null;
            int smelts = 0;
            Set<ItemId> stationItems = Set.of(ItemId.parse("minecraft:furnace"), ItemId.parse("minecraft:smoker"),
                    ItemId.parse("minecraft:blast_furnace"));
            for (PlanStep candidate : plan.steps()) {
                if (candidate.requirements().stream().anyMatch(SelectedToolRequirement.class::isInstance)) return null;
                if (candidate.kind() == PlanKind.SMELT) {
                    if (++smelts != 1 || !offer.preparation().item().equals(candidate.output())
                            || !isSupportedCookingStation(candidate.station())) return null;
                    for (SelectedRequirement requirement : candidate.requirements()) {
                        if (requirement instanceof SelectedToolRequirement) return null;
                        if (requirement instanceof SelectedItemRequirement input && "smelting input".equals(input.purpose())
                                && !offer.preparation().raw().equals(input.item())) return null;
                    }
                } else if (candidate.kind() == PlanKind.PLACE_STATION) {
                    if (!offer.canPlace() || !isSupportedCookingStation(candidate.station())) return null;
                } else if (candidate.kind() == PlanKind.CRAFT) {
                    if (!stationItems.contains(candidate.output()) || candidate.operationCount() != 1
                            || candidate.outputCount() != 1) return null;
                } else return null;
            }
            if (smelts != 1) return null;
            List<PlanStep> marked = new ArrayList<>();
            for (int index = 0; index < plan.steps().size(); index++) {
                PlanStep candidate = plan.steps().get(index);
                Map<String, String> attributes = new HashMap<>(candidate.attributes());
                attributes.put("foodPreparation", "true");
                attributes.put("travelFoodPreparation", "true");
                attributes.put("ordinaryInputOnly", "true");
                attributes.put("ordinaryFuelOnly", "true");
                stock.protectedCounts().forEach((item, count) -> attributes.put("travelFoodReserved:" + item, Integer.toString(count)));
                Map<ItemId, Integer> future = new HashMap<>(), produced = new HashMap<>();
                if (candidate.kind() == PlanKind.CRAFT || candidate.kind() == PlanKind.SMELT)
                    produced.put(candidate.output(), candidate.outputCount());
                for (PlanStep later : plan.steps().subList(index + 1, plan.steps().size())) {
                    for (SelectedRequirement requirement : later.requirements()) {
                        if (requirement instanceof SelectedItemRequirement input && input.consumed()) {
                            int supplied = Math.min(input.count(), produced.getOrDefault(input.item(), 0));
                            produced.computeIfPresent(input.item(), (item, count) -> count - supplied);
                            if (input.count() > supplied) future.merge(input.item(), input.count() - supplied, Math::addExact);
                        }
                    }
                    if (later.kind() == PlanKind.CRAFT || later.kind() == PlanKind.SMELT)
                        produced.merge(later.output(), later.outputCount(), Math::addExact);
                }
                future.forEach((item, count) -> attributes.put("travelFoodFuture:" + item, Integer.toString(count)));
                marked.add(new PlanStep(candidate.kind(), candidate.sourceId(), candidate.output(), candidate.outputCount(),
                        candidate.operationCount(), candidate.requirements(), candidate.candidateBlocks(), candidate.recipeType(),
                        candidate.recipeWidth(), candidate.recipeHeight(), candidate.station(), candidate.customType(), attributes, candidate.nativeWork()));
            }
            return new PlanningOutcome(new PlanResult(plan.target(), plan.requestedCount(), marked, plan.blockedReasons(),
                    plan.optimal(), plan.expandedNodes(), plan.elapsedNanos()), false, true);
        } catch (RuntimeException unavailable) { return null; }
    }
    private boolean prepareTravelFood(TravelRequest travel) {
        if (travelFoodPreflightParent == travel) return false;
        if (nativeRun == travelAction) { travelAction.requestDrain(NativeRun.DrainReason.PREEMPT); return true; }
        if (catalog == null || !catalog.ready()) { status = "waiting for optional food recipe catalog"; return true; }
        if (pendingPlan != null) {
            if (travelFoodOffer == null) return false;
            if (!pendingPlan.isDone()) { status = "checking optional on-hand travel food"; return true; }
            if (!planIdentityCurrent(pendingPlanIdentity) || !travelFoodOfferCurrent(travelFoodOffer)) {
                pendingPlan.cancel(false); pendingPlan = null; travelFoodOffer = null; return true;
            }
            PlanningOutcome selected;
            try { selected = pendingPlan.join(); }
            catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failure) { selected = null; }
            pendingPlan = null;
            travelFoodPreflightParent = travel;
            if (selected == null) { travelFoodOffer = null; return false; }
            travelFoodPreparation = travelFoodOffer.preparation();
            travelFoodDemand = travelFoodOffer.demand();
            travelFoodInvalidated = false;
            AcquireRequest child = new AcquireRequest("travel food preparation", travelFoodPreparation.item(),
                    travelFoodPreparation.targetCount(), false, null, null, travel.context());
            acquisitionScope = createAcquisitionScope(travel, child);
            travelFoodScope = acquisitionScope;
            pendingPlanIdentity = capturePlanIdentity(acquisitionScope);
            pendingPlanPreferencesVersion = nearbyResources.version(); pendingPlanGeneration = catalog.generation();
            pendingNativeIdentity = new NativePlanningIdentity(active, client.world, client.player,
                    placementProvenance.session().orElse(null), catalog.generation());
            travelFoodOffer = null;
            pendingPlan = CompletableFuture.completedFuture(selected);
            pendingPreferencePlan = false;
            return true;
        }
        food.updateProtection(foodReservations());
        travelFoodOffer = captureTravelFoodOffer(foodPreparationOffer());
        if (travelFoodOffer == null) { travelFoodPreflightParent = travel; return false; }
        TravelFoodOffer captured = travelFoodOffer;
        PlanningPreferences preferences = nearbyResources.snapshot();
        pendingPlanIdentity = capturePlanIdentity(null);
        pendingPlan = CompletableFuture.supplyAsync(() -> onHandTravelFoodOutcome(captured, preferences), plannerWorker);
        status = "checking optional on-hand travel food";
        return true;
    }
    private void requestTravelFoodPlan(AcquisitionScope scope) {
        if (!travelFoodDemandCurrent(scope)) { finishAcquisitionPhase(scope); return; }
        if (!scopeCurrent(scope) || paused || cleanupRun != null || stationPlacementHand != null
                || animalAcquisition.active() || airRecovery.active() || otherTransactionInProgress() || openingStation
                || !inventoryAdmissionSafe()) return;
        if (goalCount() >= scope.view().count()) { finishAcquisitionPhase(scope); return; }
        if (pendingPlan != null) return;
        if (catalog == null || !catalog.ready()) { status = "waiting for food recipe catalog"; return; }
        travelFoodOffer = captureTravelFoodOffer(travelFoodPreparation);
        if (travelFoodOffer == null) { finishAcquisitionPhase(scope); return; }
        TravelFoodOffer captured = travelFoodOffer;
        PlanningPreferences preferences = nearbyResources.snapshot();
        pendingPlanIdentity = capturePlanIdentity(scope);
        pendingPlanPreferencesVersion = nearbyResources.version(); pendingPlanGeneration = catalog.generation();
        pendingNativeIdentity = new NativePlanningIdentity(active, client.world, client.player,
                placementProvenance.session().orElse(null), catalog.generation());
        pendingPlan = CompletableFuture.supplyAsync(() -> onHandTravelFoodOutcome(captured, preferences), plannerWorker);
        status = "planning the retained on-hand food child";
    }
    private boolean optionalPreparationCurrent() {
        return acquisitionScope == travelFoodScope && travelFoodScope != null
                ? travelFoodDemandCurrent(travelFoodScope) && scopeCurrent(travelFoodScope)
                    && planIdentityCurrent(stepPlanIdentity) && !paused && !stopAfterStep
                : shieldStepCurrent();
    }
    private java.util.function.BooleanSupplier optionalPreparationAuthority() {
        boolean travelFood = isTravelFoodPreparation(step);
        boolean shieldPreparation = step != null
                && Boolean.parseBoolean(step.attributes().getOrDefault("shieldPreparation", "false"));
        if (!travelFood && !shieldPreparation) return this::optionalPreparationCurrent;
        AcquisitionScope originalScope = acquisitionScope;
        PlanIdentity originalPlan = stepPlanIdentity;
        PlanStep originalStep = step;
        Request originalParent = active;
        JobContext originalContext = originalParent == null ? null : originalParent.context();
        ShieldPlanningIdentity originalShield = stepShieldIdentity;
        return () -> originalScope != null && originalParent != null && originalPlan != null
                && active == originalParent && active.context() == originalContext
                && originalScope.parent() == originalParent && acquisitionScope == originalScope
                && originalPlan.scope() == originalScope && originalPlan.context() == originalContext
                && step == originalStep && stepPlanIdentity == originalPlan
                && newEffectCurrent(originalParent) && planIdentityCurrent(originalPlan) && !paused && !stopAfterStep
                && (travelFood ? travelFoodDemandCurrent(originalScope)
                        : originalShield != null && stepShieldIdentity == originalShield
                            && shieldIdentityCurrent(originalShield)
                            && config.autoDefend && config.autoUseShield && config.autoCraftShield);
    }
    private static boolean isTravelFoodPreparation(PlanStep planned) {
        return planned != null && Boolean.parseBoolean(planned.attributes().getOrDefault("travelFoodPreparation", "false"));
    }

    private boolean prepareTravelShield(TravelRequest travel) {
        if (travelShieldPreflightParent == travel) return false;
        if (nativeRun == travelAction) { travelAction.requestDrain(NativeRun.DrainReason.PREEMPT); return true; }
        if (catalog == null || !catalog.ready()) { status = "waiting for optional shield recipe catalog"; return true; }
        if (pendingPlan != null) {
            if (!pendingPlan.isDone()) { status = "checking optional on-hand travel shield"; return true; }
            if (!planIdentityCurrent(pendingPlanIdentity)) {
                pendingPlan.cancel(false); pendingPlan = null; return true;
            }
            PlanningOutcome selected;
            try { selected = pendingPlan.join(); }
            catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failure) { selected = null; }
            pendingPlan = null;
            travelShieldPreflightParent = travel;
            if (selected == null) return false;
            ItemId shield = ItemId.parse("minecraft:shield");
            AcquireRequest child = new AcquireRequest("travel shield preparation", shield,
                    Math.addExact(actions.heldCount(GameCatalog.item(shield)), 1), false, null, null, travel.context());
            acquisitionScope = createAcquisitionScope(travel, child);
            travelShieldScope = acquisitionScope;
            installTravelShieldOutcome(selected, acquisitionScope);
            return true;
        }
        InventorySnapshot inventory = inventorySnapshot(null);
        ShieldPreparationOffer offer = captureShieldOffer(planningCatalog(config.allowBreaking), inventory,
                withKnownStations(inventory), true);
        if (offer == null) { travelShieldPreflightParent = travel; return false; }
        PlanningPreferences preferences = nearbyResources.snapshot();
        pendingPlanIdentity = capturePlanIdentity(null);
        pendingPlan = CompletableFuture.supplyAsync(() -> onHandShieldOutcome(offer, preferences), plannerWorker);
        status = "checking optional on-hand travel shield";
        return true;
    }
    private void installTravelShieldOutcome(PlanningOutcome selected, AcquisitionScope scope) {
        if (!scopeCurrent(scope)) return;
        pendingPlanPreferencesVersion = nearbyResources.version();
        pendingPlanGeneration = catalog.generation();
        pendingPlanIdentity = capturePlanIdentity(scope);
        pendingNativeIdentity = new NativePlanningIdentity(active, client.world, client.player,
                placementProvenance.session().orElse(null), catalog.generation());
        PlanningOutcome bound = new PlanningOutcome(selected.result(), false, true, null, shieldPlanningIdentity());
        pendingPlan = CompletableFuture.completedFuture(bound);
        pendingPreferencePlan = false;
    }
    private void requestTravelShieldPlan(AcquisitionScope scope) {
        if (!scopeCurrent(scope) || paused || animalAcquisition.active() || stationPlacementHand != null
                || cleanupRun != null || airRecovery.active() || otherTransactionInProgress() || openingStation) return;
        if (goalCount() >= scope.view().count()) { finishAcquisitionPhase(scope); return; }
        if (pendingPlan != null) return;
        if (catalog == null || !catalog.ready()) { status = "waiting for shield recipe catalog"; return; }
        InventorySnapshot inventory = inventorySnapshot(null);
        ShieldPreparationOffer offer = captureShieldOffer(planningCatalog(config.allowBreaking), inventory,
                withKnownStations(inventory), true);
        if (offer == null) { finishAcquisitionPhase(scope); return; }
        PlanningPreferences preferences = nearbyResources.snapshot();
        pendingPlanIdentity = capturePlanIdentity(scope);
        pendingPlanPreferencesVersion = nearbyResources.version(); pendingPlanGeneration = catalog.generation();
        pendingNativeIdentity = new NativePlanningIdentity(active, client.world, client.player,
                placementProvenance.session().orElse(null), catalog.generation());
        pendingPlan = CompletableFuture.supplyAsync(() -> {
            PlanningOutcome selected = onHandShieldOutcome(offer, preferences);
            return selected;
        }, plannerWorker);
        status = "planning the retained on-hand shield child";
    }

    private void finishAcquisitionPhase(AcquisitionScope scope) {
        if (!scopeCurrent(scope)) return;
        if (cleanupRun != null || cleanupReturnBarrier()) return;
        if (nativeAcquisitionActive() || stationPlacementHand != null || transactionInProgress() || openingStation) {
            requestActiveTransactionDrain(); return;
        }
        if (beginStationCleanup(scope.view())) return;
        resetAction();
        if (cleanupReturnBarrier()) return;
        if (!movement.finishCancellation()) return;
        if (pendingPlan != null) pendingPlan.cancel(false);
        pendingPlan = null; pendingPlanIdentity = stepPlanIdentity = null;
        acquisitionScope = null; travelShieldScope = null; travelFoodScope = null; travelFoodOffer = null; travelFoodPreparation = null; travelFoodDemand = null; travelFoodInvalidated = false;
        if (travelAction != null) travelAction.resume();
        continueActiveRequest();
    }
    private boolean tickTravelObserver() {
        if (travelAction != null && nativeRun == null && acquisitionScope == null && active == travelActionParent
                && System.nanoTime() - travelActionParent.context().deadlineNanos() >= 0) {
            nativeRun = travelAction; nativeRunParent = active;
        }
        if (nativeRun != travelAction || travelAction == null) return false;
        if (paused || editingSettings()) travelAction.pause();
        if (cancelledRequest == active) travelAction.requestDrain(NativeRun.DrainReason.STOP);
        if (contextReplaced(travelActionParent.context())) {
            travelAction.abandonSession(); movement.abandonRequestContext();
        }
        if (System.nanoTime() - travelActionParent.context().deadlineNanos() >= 0 || travelAction.observingDrain())
            return tickTravelRun();
        return paused;
    }
    private boolean tickTravelRun() {
        if (nativeRun != travelAction || travelAction == null) return false;
        NativeRun.Outcome outcome = travelAction.tick();
        status = travelAction.status();
        if (outcome instanceof NativeRun.Outcome.Blocked blocked) {
            nativeRun = null; nativeRunParent = null; nativeRunScope = null;
            pause(blocked.reason()); return true;
        }
        if (!travelAction.safeToRelease()) return true;
        nativeRun = null; nativeRunParent = null; nativeRunScope = null;
        if (outcome instanceof NativeRun.Outcome.TravelFinished finished) {
            if (active != travelActionParent) { pause("Travel receipt belonged to a retired parent"); return true; }
            lastTravelCompletion = new TravelCompletionObservation(travelActionParent, finished.receipt(),
                    travelActionParent.context().acceptedNanos(), travelActionParent.context().deadlineNanos(), System.nanoTime());
            message("Job " + finished.receipt().jobToken() + " " + finished.receipt().result()
                    + "; " + finished.receipt().reason() + "; observed legs " + finished.receipt().arrivedSegments());
            active = null; acquisitionScope = null; cancelledRequest = null; travelAction = null; travelActionParent = null; travelShieldPreflightParent = null; travelFoodPreflightParent = null; travelShieldScope = null; travelFoodScope = null; travelFoodOffer = null; travelFoodPreparation = null; travelFoodDemand = null; travelFoodInvalidated = false;
            if (stopAfterStep) stopNow(true);
            else status = "idle";
            return true;
        }
        return false;
    }
    private void abandonTravelSession() {
        if (travelAction != null) {
            travelAction.abandonSession();
            if (nativeRun == travelAction) movement.abandonRequestContext();
        }
        if (nativeRun instanceof TravelAction) { nativeRun = null; nativeRunParent = null; nativeRunScope = null; }
        travelAction = null; travelActionParent = null; travelShieldPreflightParent = null; travelFoodPreflightParent = null; travelShieldScope = null; travelFoodScope = null; travelFoodOffer = null; travelFoodPreparation = null; travelFoodDemand = null; travelFoodInvalidated = false;
    }
    void cancelRequest(long token) {
        Set<ProjectRun> matching = new HashSet<>();
        for (Request request : queue) if (request.jobToken() == token && request instanceof AcquireRequest acquire
                && acquire.project() != null) matching.add(acquire.project());
        if (active != null && active.jobToken() == token && activeProject() != null) matching.add(activeProject());
        List<AcquireRequest> cancelledMaintenance = maintenanceQueue.stream()
                .filter(request -> request.jobToken() == token).toList();
        queue.removeIf(request -> request.jobToken() == token);
        maintenanceQueue.removeIf(request -> request.jobToken() == token);
        for (AcquireRequest request : cancelledMaintenance)
            scheduleMaintenanceRequests(maintained.fail(request.maintenanceTaskId(), observedInventory));
        matching.forEach(this::abortProject);
        if (active != null && active.jobToken() == token) cancelledRequest = active;
        message("Cancellation requested for job " + token);
    }
    private void revokeRequests(UUID issuer, long epoch) {
        queue.removeIf(request -> request.context().grantEpoch() != 0 && request.context().issuer().equals(issuer)
                && request.context().grantEpoch() == epoch);
        if (active != null && epoch != 0 && active.context().issuer().equals(issuer)
                && active.context().grantEpoch() == epoch) cancelledRequest = active;
    }
    private boolean tickCancelledRequest() {
        if (active == null || active != cancelledRequest) return false;
        if (pendingPlan != null) pendingPlan.cancel(false);
        pendingPlan = null;
        requestActiveTransactionDrain();
        if (nativeRun instanceof TravelAction) return tickTravelRun();
        if (stationPlacementHand != null || nativeAcquisitionActive()) return true;
        if (cleanupRun != null) { tickStationCleanup(); return true; }
        if (stopAfterStep && !cleanupReturnBarrier() && !transactionInProgress() && !openingStation) { stopNow(true); return true; }
        if (cleanupReturnBarrier()) { status = "cancelled job retains its original station RETURN debt"; return true; }
        if (crafting != null || stonecutting != null || smelting != null) {
            if (crafting != null && crafting.tick()) completeStep();
            else if (stonecutting != null && stonecutting.tick()) completeStep();
            else if (smelting != null && smelting.tick()) completeStep();
            return true;
        }
        if (openingStation) {
            if (!stationReady()) { status = "cancelled job waiting for its issued station menu"; return true; }
            openingStation = false;
        }
        movement.checkAirRecoveryOwnership(); movement.stop();
        if (!movement.finishCancellation()) { status = "draining cancelled job movement"; return true; }
        Request finished = active;
        resetAction();
        if (cleanupReturnBarrier()) return true;
        if (stationPlacementHand != null || nativeAcquisitionActive()) return true;
        if (client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            status = "cancelled job retains its menu/cursor restoration debt"; return true;
        }
        if (stopAfterStep) { stopNow(true); return true; }
        if (finished instanceof TravelRequest && travelActionParent == finished && travelAction != null) {
            acquisitionScope = null; travelShieldScope = null; travelFoodScope = null; travelFoodOffer = null; travelFoodPreparation = null; travelFoodDemand = null; travelFoodInvalidated = false;
            pendingPlanIdentity = stepPlanIdentity = null;
            if (System.nanoTime() - finished.context().deadlineNanos() < 0)
                travelAction.requestDrain(NativeRun.DrainReason.STOP);
            nativeRun = travelAction; nativeRunParent = finished; nativeRunScope = null;
            return tickTravelRun();
        }
        active = null; acquisitionScope = null; cancelledRequest = null;
        nativeRun = null; nativeRunParent = null; nativeRunScope = null;
        if (finished instanceof AcquireRequest acquire && acquire.maintained())
            scheduleMaintenanceRequests(maintained.fail(acquire.maintenanceTaskId(), observedInventory));
        message("Cancelled job " + finished.jobToken() + "; unrelated queued work retained");
        status = "idle";
        return true;
    }

    void enqueue(String name, int count) {
        ensureNotStopping();
        ensureCatalog();
        boolean logs = name.equalsIgnoreCase("wood") || name.equalsIgnoreCase("logs");
        ItemId item = logs ? ItemId.parse("minecraft:oak_log") : resolve(name);
        if (queue.size() >= MAX_FOREGROUND_QUEUE) throw new IllegalStateException("Foreground queue limit is " + MAX_FOREGROUND_QUEUE + " goals");
        appendRequest(request(name, item, count, logs, null, null));
        message("Queued " + count + " × " + name);
    }

    void enqueueProject(String name) {
        ensureNotStopping();
        ensureCatalog();
        ProjectSpec spec = projectCatalog.require(name);
        if (queue.size() + spec.goals().size() > MAX_FOREGROUND_QUEUE) {
            throw new IllegalStateException("Project needs " + spec.goals().size() + " foreground slots; queue capacity is " + MAX_FOREGROUND_QUEUE);
        }
        CatalogSnapshot snapshot = catalog.snapshot();
        for (ItemId item : spec.goals().keySet()) {
            if (!snapshot.knownItems().contains(item)) throw new IllegalStateException("Project item is unavailable in this game version: " + item);
        }
        ProjectRun run = new ProjectRun(spec, allocateJobToken());
        projects.add(run);
        Set<ItemId> gatheringTools = new HashSet<>();
        for (GatherSource source : snapshot.gatherSources())
            for (Requirement requirement : source.requirements()) if (requirement instanceof ToolRequirement tool)
                tool.tools().alternatives().forEach(selector -> gatheringTools.addAll(snapshot.expand(selector)));
        spec.goals().entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<ItemId, Integer> entry) -> gatheringTools.contains(entry.getKey()) ? 0 : 1)
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> {
            ItemId item = entry.getKey(); int count = entry.getValue();
            run.pending.add(item);
            appendRequest(request("project " + spec.name() + " · " + item, item, count, false, null, run));
        });
        message("Queued project " + spec.name() + " (" + spec.goals().size() + " inventory targets; " + spec.description() + ")");
    }

    void listProjects() {
        projectCatalog.projects().forEach(project -> message("project " + project.name() + " — " + project.description()));
    }

    void maintainItem(String name, int count) {
        ensureNotStopping();
        ensureCatalog();
        ItemId item = resolve(name);
        if (isActiveMaintenanceTransaction(item)) {
            unmaintainAfterStep.add(item);
            maintainAfterStep.put(item, count);
            requestActiveTransactionDrain();
            message("Will update " + item + " to " + count + " after the current safe crafting/smelting step");
            return;
        }
        unmaintainAfterStep.remove(item);
        maintainAfterStep.remove(item);
        cancelMaintenanceTasks(maintained.maintain(item, count));
        observeInventory();
        // Repeating maintain is an explicit retry request, even when the observed stock is unchanged.
        scheduleMaintenanceRequests(maintained.onInventoryChanged(observedInventory));
        message("Maintaining " + item + " at " + count + " items");
    }

    void unmaintain(String itemOrAll) {
        ensureNotStopping();
        ensureCatalog();
        List<String> cancelled = new ArrayList<>();
        ItemId deferredItem = null;
        if (itemOrAll.equals("all")) {
            for (MaintainedDemandModel.Status target : maintained.statuses()) {
                ItemId item = target.item();
                maintainAfterStep.remove(item);
                if (isActiveMaintenanceTransaction(item)) unmaintainAfterStep.add(item);
                else cancelled.addAll(maintained.unmaintain(item));
            }
            for (ItemId pending : new ArrayList<>(unmaintainAfterStep)) maintainAfterStep.remove(pending);
        } else {
            ItemId item = resolve(itemOrAll);
            maintainAfterStep.remove(item);
            if (isActiveMaintenanceTransaction(item)) { unmaintainAfterStep.add(item); deferredItem = item; }
            else {
                unmaintainAfterStep.remove(item);
                cancelled.addAll(maintained.unmaintain(item));
            }
        }
        if (active != null && activeMaintained() && unmaintainAfterStep.contains(acquisitionView().item())) requestActiveTransactionDrain();
        cancelMaintenanceTasks(cancelled);
        if (itemOrAll.equals("all")) message(unmaintainAfterStep.isEmpty() ? "Cleared all maintained targets" : "Cleared maintained targets after the active safe step");
        else message(deferredItem != null ? "Will stop maintaining after the current safe step" : "No longer maintaining " + itemOrAll);
    }

    void showMaintained() {
        ensureCatalog();
        observeInventory();
        List<MaintainedDemandModel.Status> statuses = maintained.statuses();
        if (statuses.isEmpty()) { message("No maintained inventory targets"); return; }
        for (MaintainedDemandModel.Status target : statuses) {
            message(target.item() + " " + target.actualCount() + "/" + target.targetCount()
                    + " · " + target.state().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + (target.activeReservedCount() > 0 ? " · queued " + target.activeReservedCount() : "")
                    + (unmaintainAfterStep.contains(target.item()) ? " · stopping after safe step" : "")
                    + (maintainAfterStep.containsKey(target.item()) ? " · updating to " + maintainAfterStep.get(target.item()) + " after safe step" : ""));
        }
    }

    private ItemId resolve(String name) {
        ItemResolution resolution = catalog.snapshot().resolveItem(name);
        if (!resolution.found()) throw new IllegalArgumentException(resolution.ambiguous() ? "Ambiguous item: " + resolution.candidates() : "Unknown item: " + name);
        return resolution.item();
    }
    void preview(String name, int count) {
        if (previewPending) { message("A plan preview is already in progress"); return; }
        ensureCatalog();
        if (!catalog.ready()) { message("Recipe catalog is loading; try plan again shortly"); return; }
        if (!nearbyResources.ready()) { message("Indexing local resource options; try plan again shortly"); return; }
        observeInventory();
        ItemId item = name == null ? previewAcquisitionItem() : resolve(name);
        if (item == null) { message("No active or queued goal"); return; }
        CatalogSnapshot snapshot = planningCatalog(config.allowBreaking); InventorySnapshot inventory = inventorySnapshot(item);
        InventorySnapshot knownInventory = withKnownStations(inventory);
        var previewWorld = client.world;
        PlanningPreferences preferences = nearbyResources.snapshot();
        long previewGeneration = catalog.generation();
        previewPending = true;
        CompletableFuture.supplyAsync(() -> previewPlan(snapshot, inventory, knownInventory, item, count, preferences), plannerWorker).whenComplete((result, failure) -> client.execute(() -> {
            previewPending = false;
            if (client.world != previewWorld || catalog == null) return;
            if (!catalog.ready() || catalog.generation() != previewGeneration) { message("Recipe catalog changed; try plan again"); return; }
            if (failure != null) { message("Plan preview failed; try again"); return; }
            if (!result.success()) message("Blocked: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(4).toList());
            else {
                message((result.optimal() ? "Plan: " : "Feasible plan: ") + result.steps().size() + " steps, " + result.expandedNodes() + " branches, " + String.format(Locale.ROOT, "%.2f", result.elapsedNanos() / 1_000_000d) + " ms");
                result.steps().stream().limit(12).forEach(s -> message(s.kind() + " " + (s.output() == null ? s.station() : s.outputCount() + " × " + s.output())));
            }
        }));
    }
    private PlanResult previewPlan(CatalogSnapshot snapshot, InventorySnapshot inventory, InventorySnapshot knownInventory, ItemId item, int count, PlanningPreferences preferences) {
        PlanResult result = planWithStationFallback(snapshot, inventory, knownInventory, item, count, preferences);
        for (int retry = 0; retry < 4 && !result.success()
                && result.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.TIME_LIMIT); retry++) {
            result = planWithStationFallback(snapshot, inventory, knownInventory, item, count, preferences);
        }
        return result;
    }
    private void ensureNotStopping() {
        if (stopAfterStep) throw new IllegalStateException("Finishing the current safe transaction before stopping; retry after it completes");
    }
    private void ensureCatalog() {
        if (client.world == null || client.player == null) throw new IllegalStateException("Join a world first");
        if (catalog == null) { catalog = new GameCatalog(client); catalog.load(); recipeRefreshPending = false; }
    }
    void recipeSynchronizationReceived(ClientWorld packetWorld, Object provider) {
        if (packetWorld == client.world && packetWorld == world && catalog != null && catalog.usesProvider(provider)) {
            recipeRefreshPending = true;
        }
    }
    void recipeDisplaysChanged(ClientWorld packetWorld) {
        if (client.getServer() == null && packetWorld == client.world && packetWorld == world && catalog != null) {
            recipeRefreshPending = true;
        }
    }
    private boolean nativePlanningCurrent() {
        NativePlanningIdentity identity = pendingNativeIdentity;
        return identity != null && planIdentityCurrent(pendingPlanIdentity) && active == identity.request() && identity.world() == client.world
                && identity.player() == client.player && catalog != null && catalog.ready()
                && identity.offerGeneration() == catalog.generation()
                && Objects.equals(identity.session(), placementProvenance.session().orElse(null));
    }

    private int ordinaryCount(ItemId item) {
        return placementProvenance.confirmedOrdinaryInventoryReceipt(item).map(receipt -> receipt.count()).orElse(0);
    }
    private boolean ordinaryShearsRequired(ItemId target) {
        if (!GameApi.supportsAnimalHarvest() || target == null) return false;
        CatalogSnapshot snapshot = catalog.snapshot();
        Deque<ItemId> pending = new ArrayDeque<>(); pending.add(target);
        if (active != null && activeProject() != null) pending.addAll(activeProject().spec.goals().keySet());
        Set<ItemId> visited = new HashSet<>(); int sources = 0;
        while (!pending.isEmpty() && visited.size() < 256 && sources < 1_024) {
            ItemId item = pending.removeFirst();
            if (!visited.add(item)) continue;
            for (AcquisitionSource source : snapshot.sourcesFor(item)) {
                if (++sources > 1_024) return true;
                if (source instanceof AnimalHarvestSource animal && animal.work().method() == NativeWork.HarvestMethod.SHEAR) return true;
                List<Ingredient> ingredients = new ArrayList<>();
                if (source instanceof CraftingSource craft) craft.slots().forEach(slot -> ingredients.add(slot.ingredient()));
                if (source instanceof SmeltingSource smelt) ingredients.add(smelt.input());
                for (Requirement requirement : source.requirements())
                    if (requirement instanceof ItemRequirement material) ingredients.add(material.ingredient());
                for (Ingredient ingredient : ingredients) for (ItemSelector selector : ingredient.alternatives())
                    for (ItemId candidate : snapshot.expand(selector)) if (!visited.contains(candidate) && pending.size() < 256)
                        pending.addLast(candidate);
            }
        }
        return !pending.isEmpty();
    }
    private boolean reserveAnimalAttempt() {
        if (active == null || paused || stopAfterStep) return false;
        Set<Long> live = new HashSet<>(); live.add(active.jobToken());
        queue.forEach(request -> live.add(request.jobToken()));
        maintenanceQueue.forEach(request -> live.add(request.jobToken()));
        projects.forEach(run -> live.add(run.jobToken));
        animalQuotas.keySet().removeIf(job -> !live.contains(job));
        AnimalQuota quota = animalQuotas.computeIfAbsent(active.jobToken(), ignored -> new AnimalQuota());
        if (quota.attempts >= 32 || quota.activeNanos >= 600_000_000_000L) return false;
        quota.attempts++; animalQuotaTickNanos = System.nanoTime(); return true;
    }
    private boolean nativeAcquisitionActive() { return animalAcquisition.active() || cropAcquisition.active(); }
    private void requestNativeDrain(NativeRun.DrainReason reason) {
        animalAcquisition.requestDrain(reason); cropAcquisition.requestDrain(reason);
    }
    private boolean ordinaryNativeCommodity(ItemId item) {
        return AnimalHarvestAction.ordinaryCommodity(item) || CropHarvestAction.ordinaryCommodity(item);
    }
    private void beginNativeStep() {
        if (step.nativeWork() instanceof NativeWork.CropHarvest) { beginCropStep(); return; }
        if (nativeAcquisitionActive()) throw new IllegalStateException("A retained native actor still owns acquisition");
        beginAnimalStep();
    }
    private void beginAnimalStep() {
        if (!nativePlanningCurrent() || stepCatalogGeneration != catalog.generation()) { resetAction(); continueActiveRequest(); return; }
        if (!(step.nativeWork() instanceof NativeWork.AnimalHarvest))
            throw new IllegalStateException("No native actor for " + step.nativeWork());
        animalAcquisition.updateProtection(foodReservations());
        if (!animalAcquisition.begin(step, false, false, active.jobToken(), stepCatalogGeneration, this::reserveAnimalAttempt)) {
            pause(animalAcquisition.status());
        } else holdAnimalRun();
    }
    private void holdAnimalRun() {
        if (nativeRun != null && nativeRun != animalAcquisition)
            throw new IllegalStateException("Another retained native actor still owns the request");
        nativeRun = animalAcquisition; nativeRunParent = active; nativeRunScope = acquisitionScope;
    }
    private void beginCropStep() {
        if (!nativePlanningCurrent() || catalog == null || stepCatalogGeneration != catalog.generation()) {
            resetAction(); continueActiveRequest(); return;
        }
        if (nativeRun != null || nativeAcquisitionActive())
            throw new IllegalStateException("A retained native actor still owns acquisition");
        AcquisitionScope originalScope = acquisitionScope;
        PlanIdentity originalPlan = stepPlanIdentity;
        Request originalParent = active;
        if (originalScope == null || originalPlan == null || originalParent == null
                || originalScope.parent() != originalParent || originalPlan.scope() != originalScope
                || originalPlan.context() != originalParent.context() || !planIdentityCurrent(originalPlan)
                || !(step.nativeWork() instanceof NativeWork.CropHarvest))
            throw new IllegalStateException("Original crop acquisition and plan are unavailable");
        JobContext originalContext = originalParent.context();
        if (originalContext.grantEpoch() != 0 || !originalContext.issuer().equals(client.player.getUuid()))
            throw new IllegalStateException("Crop repair and cleanup require the original local issuer");
        var originalProtection = protection.capture();
        if (!contextBound(originalContext) || !newEffectCurrent(originalParent)
                || originalProtection.locked() || !originalProtection.allowBreak() || !originalProtection.allowPlace())
            throw new IllegalStateException("Original crop permission is unavailable");

        CropDebtPermission repair = new CropDebtPermission(CropDebtKind.REPAIR, originalScope, originalPlan,
                originalContext, originalContext.issuer(), originalContext.grantEpoch());
        CropDebtPermission cleanup = new CropDebtPermission(CropDebtKind.CLEANUP, originalScope, originalPlan,
                originalContext, originalContext.issuer(), originalContext.grantEpoch());
        CropAuthority captured = new CropAuthority(originalScope, originalParent, originalScope.view(),
                originalPlan, originalContext, step, catalog, originalScope.phaseGeneration(),
                originalPlan.serial(), stepCatalogGeneration, originalProtection.epoch(),
                originalContext.owner().policyGeneration(), repair, cleanup);
        Set<Long> liveJobs = new HashSet<>(); liveJobs.add(originalContext.jobToken());
        queue.forEach(request -> liveJobs.add(request.jobToken()));
        maintenanceQueue.forEach(request -> liveJobs.add(request.jobToken()));
        projects.forEach(project -> liveJobs.add(project.jobToken));
        cropBudgets.keySet().removeIf(job -> !liveJobs.contains(job));
        CropHarvestAction.JobBudget budget = cropBudgets.computeIfAbsent(originalContext.jobToken(),
                ignored -> new CropHarvestAction.JobBudget());
        cropAuthority = captured;
        nativeRun = cropAcquisition; nativeRunParent = originalParent; nativeRunScope = originalScope;
        cropAcquisition.updateProtection(foodReservations());
        var admitted = new CropHarvestAction.Admission(captured.step(), captured.catalog(), captured.catalogGeneration(),
                originalContext.jobToken(), originalPlan, originalContext.owner(), originalContext.nativeSession(),
                originalContext.provenanceSession(), () -> cropNewEffects(captured),
                () -> cropDebtEffects(captured, repair, CropDebtKind.REPAIR), () -> cropOriginalAuthority(captured),
                () -> cropDebtEffects(captured, cleanup, CropDebtKind.CLEANUP), captured.protectionEpoch(),
                captured.policyGeneration(), airRecovery, budget);
        if (!cropAcquisition.begin(admitted)) {
            nativeRun = null; nativeRunParent = null; nativeRunScope = null; cropAuthority = null;
            pause(cropAcquisition.status());
        }
    }
    private boolean cropOriginalAuthority(CropAuthority original) {
        return original != null && cropAuthority == original && nativeRun == cropAcquisition
                && nativeRunParent == original.parent() && nativeRunScope == original.scope()
                && active == original.parent() && acquisitionScope == original.scope()
                && original.scope().parent() == original.parent() && original.scope().view() == original.view()
                && original.scope().phaseGeneration() == original.phaseGeneration()
                && stepPlanIdentity == original.plan() && original.plan().scope() == original.scope()
                && original.plan().context() == original.context() && original.plan().serial() == original.planSerial()
                && planSerial == original.planSerial() && step == original.step()
                && original.parent().context() == original.context() && catalog == original.catalog()
                && catalog.generation() == original.catalogGeneration() && stepCatalogGeneration == original.catalogGeneration()
                && original.plan().catalog() == original.catalog()
                && original.plan().catalogGeneration() == original.catalogGeneration()
                && original.context().grantEpoch() == 0 && client.player == original.context().player()
                && original.context().issuer().equals(client.player.getUuid()) && !contextReplaced(original.context());
    }
    private boolean cropRuntimeAvailable(CropAuthority original) {
        return original.catalog().ready() && original.catalog().usesCurrentProvider()
                && contextBound(original.context()) && !paused && !editingSettings()
                && !airRecovery.active() && !airRecovery.ready() && healthRecovery == null
                && client.player != null && client.player.isAlive() && client.player.getHealth() > config.pauseBelowHealth
                && !threats.active() && !(config.autoDefend && threats.ready()) && !food.active()
                && !equipment.active() && !stationRecovery.active() && !stationRoom.active()
                && !otherTransactionInProgress() && !openingStation && cleanupRun == null && !cleanupReturnBarrier()
                && stationPlacementHand == null && stationPlacementWait == null && client.currentScreen == null
                && client.player.currentScreenHandler == client.player.playerScreenHandler;
    }
    private boolean cropNewEffects(CropAuthority original) {
        return cropOriginalAuthority(original) && newEffectCurrent(original.parent())
                && planIdentityCurrent(original.plan()) && !foregroundYieldPending && cropRuntimeAvailable(original)
                && !(config.autoEat && client.player.getHungerManager().getFoodLevel() <= 14 && food.ready());
    }
    private boolean cropDebtEffects(CropAuthority original, CropDebtPermission permission, CropDebtKind kind) {
        if (permission == null || permission.kind() != kind || !cropOriginalAuthority(original)) return false;
        CropDebtPermission captured = kind == CropDebtKind.REPAIR ? original.repair() : original.cleanup();
        return permission == captured && permission.scope() == original.scope() && permission.plan() == original.plan()
                && permission.context() == original.context() && permission.issuer().equals(original.context().issuer())
                && permission.grantEpoch() == original.context().grantEpoch() && permission.grantEpoch() == 0
                && cropRuntimeAvailable(original);
    }
    private void abandonCropSession() {
        cropAcquisition.abandonSession();
        if (nativeRun == cropAcquisition) { nativeRun = null; nativeRunParent = null; nativeRunScope = null; }
        cropAuthority = null;
    }
    private boolean tickCropRun() {
        CropAuthority original = cropAuthority;
        boolean currentRun = original != null && nativeRun == cropAcquisition
                && nativeRunParent == original.parent() && nativeRunScope == original.scope();
        if (!cropAcquisition.originalSessionCurrent()) { abandonCropSession(); return false; }
        if (!currentRun) cropAcquisition.requestDrain(NativeRun.DrainReason.PREEMPT);
        if (paused || editingSettings()) {
            airRecovery.stop();
            if (cropAcquisition.airObserver() && movement.finishCancellation()) cropAcquisition.finishAirObservation();
            cropAcquisition.pause(); input.release(); return true;
        }
        if (stopAfterStep && airRecovery.active()) airRecovery.stop();
        if (!stopAfterStep && (airRecovery.active() || airRecovery.ready()) && cropAcquisition.handOffMovementForAir()) {
            cropAcquisition.tick(); recoverAirIfNeeded(); return true;
        }
        if (cropAcquisition.airObserver()) {
            if (!movement.finishCancellation()) {
                cropAcquisition.tick(); status = "finishing air cancellation with retained crop evidence"; return true;
            }
            cropAcquisition.finishAirObservation();
        }
        if (!currentRun || !cropNewEffects(original)) cropAcquisition.requestDrain(NativeRun.DrainReason.PREEMPT);
        cropAcquisition.updateProtection(foodReservations());
        NativeRun.Outcome outcome = cropAcquisition.tick(); status = cropAcquisition.status();
        if (!cropAcquisition.safeToRelease()) {
            if (outcome instanceof NativeRun.Outcome.Blocked blocked) pause(blocked.reason());
            return true;
        }
        boolean originalAuthority = currentRun && cropOriginalAuthority(original);
        boolean originalNewEffects = originalAuthority && newEffectCurrent(original.parent());
        nativeRun = null; nativeRunParent = null; nativeRunScope = null; cropAuthority = null;
        if (!originalAuthority) { status = "retired crop acquisition drained without installing its result"; return true; }
        if (outcome instanceof NativeRun.Outcome.Delivered)
            throw new IllegalStateException("Crop stock provides no recipient or collection receipt");
        NativeRun.ObservedStock observed = outcome instanceof NativeRun.Outcome.Yielded yielded ? yielded.stock() : null;
        if (observed != null && (observed.jobToken() != original.context().jobToken()
                || observed.offerGeneration() != original.catalogGeneration() || !observed.item().equals(original.step().output())
                || observed.count() < 0 || observed.count() != ordinaryCount(observed.item())
                || !observed.session().equals(original.context().provenanceSession())
                || !placementProvenance.session().filter(original.context().provenanceSession()::equals).isPresent())) {
            resetAction(); pause("Crop stock does not match its original acquisition census"); return true;
        }
        observeInventory();
        if (stopAfterStep) { stopNow(true); return true; }
        if (cancelledRequest == original.parent() || !originalNewEffects) {
            cancelledRequest = original.parent(); tickCancelledRequest(); return true;
        }
        if (outcome instanceof NativeRun.Outcome.Blocked blocked) { resetAction(); pause(blocked.reason()); return true; }
        resetAction();
        if (goalCount() >= original.scope().demand().totalStockTarget()) finishGoal();
        else continueActiveRequest();
        return true;
    }

    private boolean tickAnimalRun() {
        boolean currentRun = nativeRun == animalAcquisition && nativeRunParent == active && nativeRunScope == acquisitionScope;
        if (!currentRun) animalAcquisition.requestDrain(NativeRun.DrainReason.PREEMPT);
        if (cancelledRequest == active && active != null) animalAcquisition.requestDrain(NativeRun.DrainReason.STOP);
        if (client.player == null || client.world == null) { animalAcquisition.abandonSession(); return false; }
        long now = System.nanoTime();
        AnimalQuota quota = animalQuotas.get(animalAcquisition.jobToken());
        if (quota != null && !paused && animalAcquisition.quotaClockActive()) {
            quota.activeNanos = Math.min(600_000_000_000L,
                    quota.activeNanos + Math.max(0, now - animalQuotaTickNanos));
            if (quota.activeNanos >= 600_000_000_000L)
                animalAcquisition.requestDrain(NativeRun.DrainReason.FAILURE);
        }
        animalQuotaTickNanos = now;
        if (paused || editingSettings()) {
            airRecovery.stop();
            if (animalAcquisition.airObserver() && movement.finishCancellation()) animalAcquisition.finishAirObservation();
            animalAcquisition.pause(); input.release(); return true;
        }
        if (stopAfterStep && airRecovery.active()) airRecovery.stop();
        if (!stopAfterStep && (airRecovery.active() || airRecovery.ready())
                && animalAcquisition.handOffMovementForAir()) {
            // The animal observes its retained receipt; AIR alone owns movement until its cancellation finishes.
            animalAcquisition.tick();
            recoverAirIfNeeded();
            return true;
        }
        if (animalAcquisition.airObserver()) {
            if (!movement.finishCancellation()) {
                animalAcquisition.tick(); status = "finishing air cancellation with retained animal evidence"; return true;
            }
            animalAcquisition.finishAirObservation();
        }
        if (active == null || active.jobToken() != animalAcquisition.jobToken() || stopAfterStep
                || foregroundYieldPending || catalog == null || !catalog.ready()
                || catalog.generation() != animalAcquisition.offerGeneration() || client.currentScreen != null
                || airRecovery.ready() || client.player.getHealth() <= config.pauseBelowHealth
                    && (!animalAcquisition.urgent() || healthRecovery == null)
                || animalAcquisition.urgent() && !config.autoEat || config.autoDefend && threats.ready()
                || !animalAcquisition.urgent() && config.autoEat && client.player.getHungerManager().getFoodLevel() <= 14 && food.ready())
            animalAcquisition.requestDrain(NativeRun.DrainReason.PREEMPT);
        animalAcquisition.updateProtection(foodReservations());
        NativeRun.Outcome outcome = animalAcquisition.tick();
        status = animalAcquisition.status();
        if (!animalAcquisition.safeToRelease()) {
            if (outcome instanceof NativeRun.Outcome.Blocked blocked) pause(blocked.reason());
            return true;
        }
        nativeRun = null; nativeRunParent = null; nativeRunScope = null;
        if (!currentRun) { status = "retired native acquisition drained without installing its result"; return true; }
        if (outcome instanceof NativeRun.Outcome.Blocked blocked) {
            if (stopAfterStep) { stopNow(true); return true; }
            pause(blocked.reason()); return true;
        }
        NativeRun.ObservedStock observed = outcome instanceof NativeRun.Outcome.Delivered delivered ? delivered.stock()
                : outcome instanceof NativeRun.Outcome.Yielded yielded ? yielded.stock() : null;
        if (observed != null && (active == null || active.jobToken() != observed.jobToken()
                || !placementProvenance.session().filter(observed.session()::equals).isPresent())) {
            resetAction(); pause("Native animal result belonged to an expired request or session"); return true;
        }
        observeInventory();
        if (stopAfterStep) { stopNow(true); return true; }
        if (cancelledRequest == active && active != null) { tickCancelledRequest(); return true; }
        if (quota != null && quota.activeNanos >= 600_000_000_000L) {
            resetAction(); pause("Animal job reached ten minutes of active work; acquired stock is preserved"); return true;
        }
        if (animalAcquisition.urgent()) animalAcquisitionCooldown = 20;
        completeStep();
        return true;
    }

    private InventorySnapshot inventorySnapshot(ItemId activeTarget) {
        Map<ItemId, Integer> counts = new HashMap<>(), durability = new HashMap<>();
        actions.inventory().forEach((name, count) -> counts.put(ItemId.parse(name), count));
        counts.replaceAll((item, count) -> ordinaryNativeCommodity(item) ? ordinaryCount(item) : count);
        boolean ordinaryShears = ordinaryShearsRequired(activeTarget);

        Map<ItemId, List<Integer>> durabilityLots = new HashMap<>();
        Map<ItemId, List<InventoryToolLot>> toolLots = new HashMap<>();
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (!stack.isEmpty() && stack.isDamageable()) {
            ItemId item = GameCatalog.id(stack.getItem());
            if (ordinaryShears && stack.isOf(Items.SHEARS) && !AnimalHarvestAction.ordinary(stack)) continue;
            int remaining = stack.getMaxDamage() - stack.getDamage();
            durability.merge(item, remaining, Math::max);
            durabilityLots.computeIfAbsent(item, ignored -> new ArrayList<>()).add(remaining);
            toolLots.computeIfAbsent(item, ignored -> new ArrayList<>())
                    .add(new InventoryToolLot(remaining, GameApi.hasSilkTouch(stack)));
        }
        Map<BlockPos, StationId> stationObservations = new HashMap<>(nearbyStations.observations());
        stationObservations.putAll(nearbyStations.preferredObservations());
        stationObservations.forEach((pos, id) -> {
            if (unreachableStations.contains(pos) || rejectedStationSites.contains(pos)) return;
            BlockPos known = knownStations.get(id);
            boolean preferred = nearbyStations.isPreferred(pos);
            boolean knownPreferred = known != null && nearbyStations.isPreferred(known);
            if (known == null || unreachableStations.contains(known) || rejectedStationSites.contains(known) || !hasLoadedChunk(known)
                    || client.world.getBlockState(known).getBlock() != client.world.getBlockState(pos).getBlock()
                    || preferred && !knownPreferred
                    || preferred == knownPreferred && pos.getSquaredDistance(client.player.getBlockPos()) < known.getSquaredDistance(client.player.getBlockPos()))
                knownStations.put(id, pos);
        });
        Set<StationId> stations = new HashSet<>();
        knownStations.forEach((id, pos) -> { if (client.world.getBlockState(pos).getBlock() == Registries.BLOCK.get(GameApi.identifier(id.toString()))) stations.add(id); });
        Map<ItemId, Integer> protectedCounts = new HashMap<>(protectedCounts(counts, activeTarget));
        backfill.inFlightReservationCounts().forEach((item, count) -> protectedCounts.merge(item,
                Math.min(count, counts.getOrDefault(item, 0)), (floor, held) ->
                        Math.min(counts.getOrDefault(item, 0), floor + held)));
        if (config.allowBuilding && !stations.isEmpty()) {
            Set<StationId> localStations = new HashSet<>();
            knownStations.forEach((id, pos) -> {
                if (stations.contains(id) && pos.getSquaredDistance(client.player.getBlockPos()) <= 256) localStations.add(id);
            });
            InventorySnapshot localStock = new InventorySnapshot(counts, localStations, durability,
                    protectedCounts, durabilityLots, toolLots);
            for (StationId id : new HashSet<>(stations)) {
                if (localStations.contains(id)) continue;
                ItemId placementItem = GameCatalog.id(Registries.BLOCK.get(GameApi.identifier(id.toString())).asItem());
                if (StoredCrafting.canSupplyOne(catalog.snapshot(), localStock, placementItem)) {
                    stations.remove(id);
                    if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                            "[Lodekeeper] STATION_POLICY decision=replace_distant station={} position={}", id, knownStations.get(id));
                }
            }
        }
        return new InventorySnapshot(counts, stations, durability, protectedCounts, durabilityLots, toolLots);
    }

    private boolean inventoryAdmissionSafe() {
        if (client.player == null || client.world == null) return false;
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) {
            status = "waiting for the open container to close"; return false;
        }
        if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            status = "waiting for the cursor stack to be returned"; return false;
        }
        return true;
    }

    private void startNextRequest() {
        if (stopAfterStep || cleanupRun != null || cleanupReturnBarrier() || !inventoryAdmissionSafe()) return;
        while (active == null) {
            boolean foreground = !queue.isEmpty();
            Request candidate = foreground ? queue.peekFirst() : maintenanceQueue.peekFirst();
            if (candidate == null) return;
            if (!newEffectCurrent(candidate)) {
                if (foreground) queue.removeFirst(); else maintenanceQueue.removeFirst();
                if (candidate instanceof AcquireRequest acquire && acquire.project() != null) abortProject(acquire.project());
                if (candidate instanceof AcquireRequest acquire && acquire.maintained())
                    scheduleMaintenanceRequests(maintained.fail(acquire.maintenanceTaskId(), observedInventory));
                message("Skipped expired/context-mismatched job " + candidate.jobToken());
                continue;
            }
            if (!foreground) {
                AcquireRequest maintainedRequest = (AcquireRequest) candidate;
                if (!hasActiveMaintenance(maintainedRequest.maintenanceTaskId())) { maintenanceQueue.removeFirst(); continue; }
                if (observedInventory.getOrDefault(maintainedRequest.item(), 0) >= maintainedRequest.count()) {
                    maintenanceQueue.removeFirst();
                    scheduleMaintenanceRequests(maintained.complete(maintainedRequest.maintenanceTaskId(), observedInventory));
                    continue;
                }
            }
            if (foreground) queue.removeFirst(); else maintenanceQueue.removeFirst();
            active = candidate;
            acquisitionScope = active instanceof AcquireRequest acquire ? createAcquisitionScope(active, acquire) : null;
            miningContinuation = null;
            unavailableSources.clear();
            frontier = null; rejectedResources.clear(); lastResourceFailure = null; resetLogDiscovery();
            planningRetries = 0; stationAccessFailures = 0; unreachableStations.clear();
            continueActiveRequest();
        }
    }

    private boolean hasActiveMaintenance(String taskId) {
        return taskId != null && maintained.activeRequests().stream().anyMatch(request -> request.taskId().equals(taskId));
    }

    private void yieldMaintenanceForForeground() {
        if (activeMaintained()) foregroundYieldPending = true;
    }

    private boolean transactionInProgress() {
        return stationPlacementHand != null && !stationPlacementHand.settled
                || nativeAcquisitionActive() || otherTransactionInProgress();
    }

    private boolean otherTransactionInProgress() {
        return step != null && (step.kind() == PlanKind.CRAFT || step.kind() == PlanKind.SMELT)
                && (crafting != null || stonecutting != null || smelting != null);
    }

    private boolean isActiveMaintenanceTransaction(ItemId item) {
        return active != null && activeMaintained() && acquisitionView().item().equals(item)
                && (transactionInProgress() || openingStation && step != null
                && (step.kind() == PlanKind.CRAFT || step.kind() == PlanKind.SMELT));
    }

    private boolean shouldDrainActiveTransaction() {
        return cancelledRequest == active && active != null || stopAfterStep || airRecovery.active() || healthRecovery != null || active != null && activeMaintained()
                && (foregroundYieldPending || unmaintainAfterStep.contains(acquisitionView().item())
                || maintainAfterStep.containsKey(acquisitionView().item()));
    }

    private void requestActiveTransactionDrain() {
        if (stationPlacementHand != null) cancelStationPlacement();
        requestNativeDrain(NativeRun.DrainReason.PREEMPT);
        if (crafting != null) crafting.requestDrain();
        if (stonecutting != null) stonecutting.requestDrain();
        if (smelting != null) smelting.requestDrain();
    }

    private boolean canYieldMaintenanceNow() {
        if (cleanupReturnBarrier()) return false;
        if (active == null || !activeMaintained() || client.player == null
                || stationPlacementWait != null || cleanupRun != null || transactionInProgress()
                || crafting != null || stonecutting != null || smelting != null || openingStation
                || client.player.currentScreenHandler != client.player.playerScreenHandler) return false;
        return client.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    private void yieldActiveMaintenance() {
        if (!foregroundYieldPending || !canYieldMaintenanceNow()) return;
        AcquireRequest yielded = acquisitionView();
        if (pendingPlan != null) pendingPlan.cancel(false);
        pendingPlan = null;
        resetAction();
        if (cleanupReturnBarrier()) return;
        active = null; acquisitionScope = null;
        maintenanceQueue.addFirst(yielded);
        foregroundYieldPending = false;
        status = "maintenance waiting for foreground goals";
    }

    private void cancelMaintenanceTasks(List<String> taskIds) {
        if (taskIds.isEmpty()) return;
        Set<String> cancelled = Set.copyOf(taskIds);
        maintenanceQueue.removeIf(request -> cancelled.contains(request.maintenanceTaskId()));
        if (active != null && cancelled.contains(activeMaintenanceId())) {
            if (cleanupRun != null || cleanupReturnBarrier()) { cancelledRequest = active; return; }
            if (nativeAcquisitionActive()) {
                unmaintainAfterStep.add(acquisitionView().item()); requestActiveTransactionDrain(); return;
            }
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = null;
            resetAction();
            if (cleanupReturnBarrier()) { cancelledRequest = active; return; }
            active = null; acquisitionScope = null;
            foregroundYieldPending = false;
            status = "idle";
        }
    }

    private void scheduleMaintenanceRequests(List<MaintainedDemandModel.MaintenanceRequest> requests) {
        for (MaintainedDemandModel.MaintenanceRequest request : requests) {
            if (maintenanceQueue.stream().anyMatch(queued -> request.taskId().equals(queued.maintenanceTaskId()))
                    || active != null && request.taskId().equals(activeMaintenanceId())) continue;
            if (maintenanceQueue.size() + (active != null && activeMaintained() ? 1 : 0) >= MAX_MAINTENANCE_QUEUE) {
                scheduleMaintenanceRequests(maintained.fail(request.taskId(), observedInventory));
                continue;
            }
            maintenanceQueue.addLast(request("maintain " + request.item(), request.item(), request.targetCount(), false,
                    request.taskId(), null));
        }
    }

    private MovementProgressScope projectLogGatherScope() {
        if (active == null || activeProject() == null || activeProject().aborted || activeMaintained()
                || step == null || step.kind() != PlanKind.GATHER || stepAuxiliaryInvestment
                || isFoodPreparation(step) || stepShieldIdentity != null || catalog == null
                || !catalog.tags.getOrDefault(LOGS_TAG, List.of()).contains(step.output())
                || parentMovementScope == null || !parentMovementScope.matches(acquisitionScope, client.world, client.player,
                        placementProvenance.session().orElse(null))) return null;
        return parentMovementScope;
    }

    private void sampleStationStock() {
        MovementProgressScope scope = projectLogGatherScope();
        if (scope == null) return;
        int count = actions.count(GameCatalog.item(CRAFTING_TABLE_ITEM));
        if (!scope.stationStockReconsidered && scope.sampledTableCount >= 0 && count > scope.sampledTableCount)
            scope.tableGainFrom = scope.tableGainFrom < 0 ? scope.sampledTableCount
                    : Math.min(scope.tableGainFrom, scope.sampledTableCount);
        scope.sampledTableCount = count;
    }

    private boolean canReconsiderStationStock() {
        return projectLogGatherScope() != null && !paused && !stopAfterStep && !editingSettings()
                && client.world == world && config.allowBreaking && catalog.ready()
                && stepCatalogGeneration == catalog.generation() && !exploring && !foregroundYieldPending
                && !airRecovery.active() && healthRecovery == null && !threats.active() && !food.active()
                && !nativeAcquisitionActive() && !animalAcquisitionPending && !foodReplanPending && !equipment.active()
                && !stationRecovery.active() && !stationRoom.active() && stationPlacementWait == null && cleanupRun == null
                && !openingStation && !transactionInProgress() && crafting == null && stonecutting == null && smelting == null
                && !hasOwnedStationHandlerOpen() && client.currentScreen == null && !manualStationInput()
                && client.player.currentScreenHandler == client.player.playerScreenHandler
                && client.player.currentScreenHandler.getCursorStack().isEmpty()
                && (!moving || movement.canReconsiderMiningSource());
    }

    private void invalidateStationStockHint() {
        StationStockHint hint = stationStockHint;
        if (hint != null && (pendingPlan != hint.future() || step != hint.step() || parentMovementScope != hint.scope()
                || !nativePlanningCurrent() || !canReconsiderStationStock() || hint.generation() != catalog.generation()
                || actions.count(GameCatalog.item(CRAFTING_TABLE_ITEM)) <= hint.scope().tableGainFrom))
            dropStationStockHint();
    }

    private void dropStationStockHint() {
        StationStockHint hint = stationStockHint;
        if (hint == null) return;
        stationStockHint = null;
        if (pendingPlan == hint.future()) {
            hint.future().cancel(false);
            pendingPlan = null;
            pendingPreferencePlan = false;
        }
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] STATION_STOCK_HINT job={} scope={} source={} decision=invalidated",
                hint.scope().request.jobToken(), System.identityHashCode(hint.scope()), hint.step().sourceId());
    }

    private void observeInventory() {
        if (client.player == null) return;
        long fingerprint = inventoryFingerprint();
        if (inventoryFingerprintInitialized && fingerprint == lastInventoryFingerprint
                && observedInventory.equals(captureInventoryCounts())) return;
        inventoryFingerprintInitialized = true;
        lastInventoryFingerprint = fingerprint;
        observedInventory = captureInventoryCounts();
        scheduleMaintenanceRequests(maintained.onInventoryChanged(observedInventory));
    }

    private long inventoryFingerprint() {
        long hash = 0xcbf29ce484222325L;
        var main = ClientAccess.main(client.player.getInventory());
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = main.get(slot);
            long value = stack.isEmpty() ? 0 : ((long) System.identityHashCode(stack.getItem()) << 32) ^ stack.getCount();
            hash ^= value;
            hash *= 0x100000001b3L;
            hash ^= slot;
            hash *= 0x100000001b3L;
        }
        for (int index = 0; index < 5; index++) {
            int slot = index < 4 ? index + 5 : 45;
            ItemStack stack = client.player.playerScreenHandler.getSlot(slot).getStack();
            long value = stack.isEmpty() ? 0 : ((long) System.identityHashCode(stack.getItem()) << 32) ^ stack.getCount();
            hash ^= value;
            hash *= 0x100000001b3L;
            hash ^= slot + 36;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private Map<ItemId, Integer> captureInventoryCounts() {
        Map<ItemId, Integer> counts = new TreeMap<>();
        actions.heldInventory().forEach((name, count) -> counts.put(ItemId.parse(name), count));
        counts.replaceAll((item, count) -> ordinaryNativeCommodity(item) ? ordinaryCount(item) : count);
        return Map.copyOf(counts);
    }

    private InventorySnapshot withKnownStations(InventorySnapshot preferred) {
        Set<StationId> stations = new HashSet<>(preferred.availableStations());
        knownStations.forEach((id, pos) -> { if (client.world.getBlockState(pos).getBlock() == Registries.BLOCK.get(GameApi.identifier(id.toString()))) stations.add(id); });
        if (stations.equals(preferred.availableStations())) return preferred;
        return new InventorySnapshot(preferred.counts(), stations, preferred.remainingDurability(),
                preferred.protectedCounts(), preferred.durabilityLots(), preferred.toolLots());
    }

    private PlanResult planWithStationFallback(CatalogSnapshot snapshot, InventorySnapshot preferred,
                                               InventorySnapshot existing, ItemId target, int count,
                                               PlanningPreferences preferences) {
        PlanResult result = planner.planFast(snapshot, preferred, target, count, PlannerLimits.DEFAULT, preferences);
        if (!result.success() && !preferred.availableStations().equals(existing.availableStations())) {
            return planner.planFast(snapshot, existing, target, count, PlannerLimits.DEFAULT, preferences);
        }
        return result;
    }

    private Map<ItemId, Integer> protectedCounts(Map<ItemId, Integer> counts, ItemId activeTarget) {
        Map<ItemId, Integer> protectedCounts = new TreeMap<>();
        (activeTarget == null ? maintained.reservedCounts() : maintained.reservedCountsFor(activeTarget)).forEach((item, count) -> {
            int outsideStorage = Math.max(0, actions.heldCount(GameCatalog.item(item)) - actions.count(GameCatalog.item(item)));
            int held = Math.min(counts.getOrDefault(item, 0), Math.max(0, count - outsideStorage));
            if (held > 0) protectedCounts.merge(item, held, Math::max);
        });
        for (ProjectRun run : projects) {
            if (run.aborted) continue;
            run.spec.goals().forEach((item, targetCount) -> {
                if (item.equals(activeTarget)) return;
                int outsideStorage = Math.max(0, actions.heldCount(GameCatalog.item(item)) - actions.count(GameCatalog.item(item)));
                int held = Math.min(counts.getOrDefault(item, 0), Math.max(0, targetCount - outsideStorage));
                if (held > 0) protectedCounts.merge(item, held, Math::max);
            });
        }
        return Map.copyOf(protectedCounts);
    }

    private boolean recoverAirIfNeeded() {
        if (!airRecovery.active() && !airRecovery.ready()) return false;
        if (nativeRun == travelAction && travelAction != null) {
            travelAction.requestDrain(NativeRun.DrainReason.PREEMPT);
            if (tickTravelObserver()) return true;
        }
        boolean observingAnimal = animalAcquisition.active() && animalAcquisition.airObserver()
                || cropAcquisition.active() && cropAcquisition.airObserver();
        boolean observingStationHand = stationPlacementHand != null;
        useMovementProgress(null);
        try {
            if (stationPlacementWait != null || cleanupRun != null) {
                cancelStationPlacement();
                stopStationCleanup();
            }
            if (client.currentScreen != null && !otherTransactionInProgress() && !openingStation && !hasOwnedStationHandlerOpen()) {
                airRecovery.stop();
                pause("air recovery is blocked by the open player screen");
                return true;
            }
            if (!airRecovery.active()) {
                movement.checkAirRecoveryOwnership();
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                healthRecovery = null;
                stationRoom.stop(); stopStationCleanup();
                threats.stop(); equipment.stop();
                if (!observingAnimal) animalAcquisition.stop();
                food.stop();
                animalAcquisitionPending = foodReplanPending = false;
                airRecovery.begin();
            }
            if (otherTransactionInProgress() || !observingAnimal && nativeAcquisitionActive() || openingStation) {
                requestActiveTransactionDrain();
                status = "draining the owned transaction before air escape";
                return false;
            }
            if (!observingAnimal && !observingStationHand && (step != null || exploring || explorationMoving)) resetAction();
            input.release();
            if (airRecovery.tick()) {
                healthRecovery = null;
                observeInventory();
                if (!observingAnimal && !observingStationHand && active != null) continueActiveRequest();
            } else status = airRecovery.status();
            return true;
        } catch (RuntimeException failure) {
            if (failure instanceof MovementController.NavigationFailure navigation
                    && navigation.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw navigation;
            pause("air recovery needs attention: " + failure.getMessage());
            return true;
        }
    }

    private boolean recoverHealthIfNeeded() {
        if (airRecovery.active()) return false;
        if (stopAfterStep) { healthRecovery = null; return false; }
        float health = client.player.getHealth();
        if (healthRecovery == null && health > config.pauseBelowHealth) return false;
        if (cleanupReturnBarrier()) { pause("health safeguard while owned station RETURN debt is retained"); return true; }
        if (!config.autoEat) { pause("health safeguard"); return true; }
        if (config.pauseBelowHealth >= client.player.getMaxHealth()) {
            pause("Health safeguard threshold must be below the player's maximum health");
            return true;
        }
        if (healthRecovery == null) {
            useMovementProgress(null);
            healthRecovery = new HealthRecovery(System.nanoTime());
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = null;
            if (!transactionInProgress() && !openingStation) resetAction();
            if (cleanupReturnBarrier()) { status = "health recovery awaits owned station RETURN settlement"; return true; }
        }
        float recovered = Math.min(client.player.getMaxHealth(), Math.max(10, config.pauseBelowHealth + 2));
        if (health >= recovered) { healthRecovery = null; return false; }
        if (System.nanoTime() - healthRecovery.startedNanos() >= 40_000_000_000L) {
            pause("Health did not recover within 40 seconds");
            return true;
        }
        if (!movement.finishCancellation()) { status = "finishing movement before health recovery"; return true; }
        if (transactionInProgress() || openingStation) {
            requestActiveTransactionDrain();
            status = "draining owned inventory action before health recovery";
            return false;
        }
        if (food.active() || food.ready()) { foodCooldown = 0; return false; }
        if (client.player.getHungerManager().getFoodLevel() >= 18) {
            movement.stop(); input.release();
            status = "resting for natural health recovery · " + Math.round(health) + "/" + Math.round(recovered);
            return true;
        }
        if (nativeAcquisitionActive() || !transactionInProgress() && !openingStation
                && !hasOwnedStationHandlerOpen() && client.currentScreen == null && animalAcquisition.ready(true)) {
            animalAcquisitionCooldown = 0;
            return false;
        }
        pause("health safeguard; no available food for healing");
        return true;
    }

    private boolean needsMiningFoodStock() {
        if (step == null || step.kind() != PlanKind.GATHER || food.availableCookedNutrition() >= 36) return false;
        return step.candidateBlocks().stream().anyMatch(block -> {
            String id = block.toString();
            return id.equals("minecraft:iron_ore") || id.equals("minecraft:deepslate_iron_ore")
                    || id.equals("minecraft:diamond_ore") || id.equals("minecraft:deepslate_diamond_ore")
                    || id.equals("minecraft:coal_ore") || id.equals("minecraft:deepslate_coal_ore");
        });
    }

    private FoodController.Preparation foodPreparationOffer() {
        FoodController.Preparation preparation = config.autoEat ? food.preparationGoal() : null;
        if (preparation == null) {
            loggedFoodPreparationDeferred = null;
            return null;
        }
        int cookedPotential = food.availableCookedNutrition();
        boolean urgent = healthRecovery != null || client.player.getHungerManager().getFoodLevel() <= 14;
        boolean deferred = !urgent && cookedPotential < 36 && animalAcquisition.ready(false);
        if (config.debugLogging && !Boolean.valueOf(deferred).equals(loggedFoodPreparationDeferred)) {
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] FOOD_PREPARATION decision={} nutrition={} cooked_potential={} target=36",
                    deferred ? "batch_raw_food" : "cook_stored_food", food.availableNutrition(), cookedPotential);
            loggedFoodPreparationDeferred = deferred;
        }
        return deferred ? null : preparation;
    }

    private Map<ItemId, Integer> foodReservations() {
        Map<ItemId, Integer> result = new HashMap<>(maintained.reservedCounts());
        for (ProjectRun run : projects) if (!run.aborted)
            run.spec.goals().forEach((item, count) -> result.merge(item, count, Math::max));
        if (acquisitionView() != null) result.merge(acquisitionView().item(), acquisitionView().count(), Math::max);
        for (Request request : queue) if (request instanceof AcquireRequest acquire) result.merge(acquire.item(), acquire.count(), Math::max);
        result.replaceAll((item, count) -> Math.max(0, count
                - Math.max(0, actions.heldCount(GameCatalog.item(item)) - actions.count(GameCatalog.item(item)))));
        result.values().removeIf(count -> count == 0);
        if (step != null) {
            Map<ItemId, Integer> inputs = new HashMap<>();
            for (SelectedRequirement requirement : step.requirements())
                if (requirement instanceof SelectedItemRequirement item)
                    inputs.merge(item.item(), item.count(), Math::addExact);
            inputs.forEach((item, count) -> result.merge(item, count, Math::addExact));
        }
        return Map.copyOf(result);
    }

    private InventorySnapshot backfillStock() {
        InventorySnapshot inventory = protectedFoodInventory(inventorySnapshot(null));
        Map<ItemId, Integer> floors = new HashMap<>(inventory.protectedCounts());
        requestedBackfillStock.forEach((item, count) -> floors.merge(item,
                Math.min(count, inventory.count(item)), Math::max));
        return new InventorySnapshot(inventory.counts(), inventory.availableStations(),
                inventory.remainingDurability(), floors, inventory.durabilityLots(), inventory.toolLots());
    }

    private InventorySnapshot protectedFoodInventory(InventorySnapshot inventory) {
        Map<ItemId, Integer> reserved = new HashMap<>(inventory.protectedCounts());
        foodReservations().forEach((item, count) -> {
            int held = Math.min(count, inventory.count(item));
            if (held > 0) reserved.merge(item, held, Math::max);
        });
        return new InventorySnapshot(inventory.counts(), inventory.availableStations(),
                inventory.remainingDurability(), reserved, inventory.durabilityLots(), inventory.toolLots());
    }

    private boolean tickActiveThreat() {
        if (!threats.active()) return false;
        try {
            if (active == null || paused) { threats.stop(); return true; }
            if (config.pauseOnScreen && client.currentScreen != null) {
                healthRecovery = null;
                stationRoom.stop(); threats.stop(); equipment.stop(); animalAcquisition.stop(); food.stop();
                movement.suspend(); input.release();
                return true;
            }
            if (!config.autoDefend || stopAfterStep) {
                threats.stop();
                continueActiveRequest();
                return true;
            }
            threats.useShield(config.autoUseShield);
            threats.updateProtection(foodReservations());
            if (threats.tick(config.pauseBelowHealth)) { observeInventory(); continueActiveRequest(); }
            else status = threats.status();
        } catch (RuntimeException failure) {
            threats.stop();
            pause("Threat response needs attention: " + failure.getMessage());
        }
        return true;
    }

    private CatalogSnapshot planningCatalog(boolean allowGathering) {
        CatalogSnapshot full = catalog.snapshot();
        if (allowGathering) return full;
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        full.itemDefinitions().values().forEach(builder::item);
        catalog.tags.forEach(builder::tag);
        full.knownItems().stream().flatMap(item -> full.sourcesFor(item).stream())
                .filter(source -> !(source instanceof GatherSource)).forEach(builder::source);
        return builder.build();
    }

    private void requestPlan(AcquisitionScope scope) {
        if (!scopeCurrent(scope)) return;
        if (scope == travelFoodScope) { requestTravelFoodPlan(scope); return; }
        if (scope == travelShieldScope) { requestTravelShieldPlan(scope); return; }
        AcquisitionDemand demand = scope.demand();
        if (nativeAcquisitionActive()) { requestNativeDrain(NativeRun.DrainReason.REPLAN); return; }
        dropStationStockHint();
        if (airRecovery.active()) return;
        if (paused || active == null) return;
        pendingPreferencePlan = false;
        foodReplanPending = false;
        ensureCatalog(); status = "planning";
        if (!catalog.ready()) { status = "waiting for recipe catalog"; return; }
        if (!nearbyResources.ready()) { status = "indexing local resource options"; return; }
        if (!nearbyStations.ready()) { status = "checking nearby crafting stations"; return; }
        observeInventory();
        if (!nativeAcquisitionActive() && goalCount() >= demand.totalStockTarget()) { finishGoal(); return; }
        if (demand.anyLogs() && !config.allowBreaking) {
            pause("Wood gathering requires allowBreaking=true");
            return;
        }
        ItemId item = demand.item();
        if (demand.anyLogs()) {
            item = chooseLogs();
            if (item == null) {
                LocalReachScan logReachScan = localReachScan(LocalReachPurpose.LOG_CHOICE);
                boolean localPending = !localLogSources.isEmpty() && (logReachScan == null || !logReachScan.complete());
                if (logScan == null && !localPending && (logSources.isEmpty() || logScanComplete)) beginExploration();
                return;
            }
        }
        boolean gatheringEnabled = config.allowBreaking;
        CatalogSnapshot full = planningCatalog(gatheringEnabled);
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        if (!unavailableSources.isEmpty()) {
            full.itemDefinitions().values().forEach(builder::item); catalog.tags.forEach(builder::tag);
            full.knownItems().stream().flatMap(output -> full.sourcesFor(output).stream()).filter(source -> !unavailableSources.contains(source.sourceId())
                    && (gatheringEnabled || !(source instanceof GatherSource))).forEach(builder::source);
        }
        CatalogSnapshot snapshot = unavailableSources.isEmpty() ? full : builder.build();
        Set<String> excludedGatherSourceIds = full.gatherSources().stream()
                .filter(GatherSource.class::isInstance).map(GatherSource.class::cast)
                .filter(source -> unavailableSources.contains(source.sourceId()))
                .map(GatherSource::sourceId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean explorationEnabled = gatheringEnabled && config.allowExploration;
        InventorySnapshot inventory = inventorySnapshot(demand.item());
        InventorySnapshot knownInventory = withKnownStations(inventory);
        int requested = demand.anyLogs() ? demand.totalStockTarget() - goalCount() + inventory.count(item)
                : Math.max(1, demand.totalStockTarget() - Math.max(0, goalCount() - inventory.count(item)));
        final ItemId targetItem = item; final int targetCount = requested;
        HarvestOffer harvestOffer = gatheringEnabled && demand.anyLogs() && config.optimizeWoodTools
                ? captureHarvestOffer(demand.totalStockTarget() - goalCount()) : null;
        PlanningPreferences preferences = nearbyResources.snapshot();
        pendingPlanPreferencesVersion = nearbyResources.version();
        pendingPlanGeneration = catalog.generation();
        CatalogSnapshot filteredSnapshot = snapshot;
        CombatPreparationOffer combatOffer = captureCombatPreparationOffer(filteredSnapshot, inventory, knownInventory);
        ShieldPreparationOffer shieldOffer = captureShieldPreparationOffer(filteredSnapshot, inventory, knownInventory);
        ProjectSpec project = activeProject() != null && !activeProject().aborted
                ? remainingProject(activeProject().spec, inventory) : null;
        Map<ItemId, Integer> gatherCapacity = project == null ? Map.of() : gatherCapacity(filteredSnapshot);
        food.updateProtection(foodReservations());
        FoodController.Preparation offeredPreparation = foodPreparationOffer();
        FoodController.Preparation preparation = offeredPreparation != null
                && offeredPreparation.targetCount() - actions.count(GameCatalog.item(offeredPreparation.item()))
                    <= gatherCapacity(offeredPreparation.item()) ? offeredPreparation : null;
        List<AcquisitionSource> cookingSources = preparation == null ? List.of()
                : preparationSources(filteredSnapshot, preparation);
        InventorySnapshot cookingInventory = cookingSources.isEmpty() ? inventory
                : protectedFoodInventory(inventory);
        InventorySnapshot knownCookingInventory = cookingSources.isEmpty() ? knownInventory
                : protectedFoodInventory(knownInventory);
        boolean debugPlanning = config.debugLogging;
        Set<ItemId> logMaterials = Set.copyOf(catalog.tags.getOrDefault(LOGS_TAG, List.of()));
        if (planSerial == Long.MAX_VALUE) throw new IllegalStateException("Planning serial capacity reached");
        pendingPlanIdentity = new PlanIdentity(scope, ++planSerial, active.context(), catalog,
                catalog.generation(), nearbyResources.version(), active.context().owner().policyGeneration());
        pendingNativeIdentity = new NativePlanningIdentity(active, client.world, client.player,
                placementProvenance.session().orElse(null), catalog.generation());
        pendingPlan = CompletableFuture.supplyAsync(() -> {
            ProjectPlanResult joint = project == null ? null : planner.planProjectFast(filteredSnapshot, inventory, project,
                    PlannerLimits.DEFAULT, preferences);
            if (joint != null && !joint.success() && !inventory.availableStations().equals(knownInventory.availableStations())) {
                joint = planner.planProjectFast(filteredSnapshot, knownInventory, project,
                        PlannerLimits.DEFAULT, preferences);
            }
            if (preparation != null && !cookingSources.isEmpty() && (joint == null || joint.success())) {
                InventorySnapshot protectedCooking = joint == null ? cookingInventory
                        : ProjectMaterialReservations.protectOptionalWork(cookingInventory, joint);
                InventorySnapshot protectedKnownCooking = joint == null ? knownCookingInventory
                        : ProjectMaterialReservations.protectOptionalWork(knownCookingInventory, joint);
                CatalogSnapshot cookingCatalog = filteredSnapshot.withOutputSources(preparation.item(), cookingSources);
                PlanResult cooking = planWithStationFallback(cookingCatalog, protectedCooking, protectedKnownCooking,
                        preparation.item(), preparation.targetCount(), preferences);
                if (cooking.success() && !cooking.steps().isEmpty()
                        && cooking.steps().stream().noneMatch(candidate -> (candidate.kind() == PlanKind.GATHER || candidate.kind() == PlanKind.NATIVE))) {
                    List<PlanStep> safeCooking = cooking.steps().stream().map(candidate -> {
                        Map<String, String> attributes = new HashMap<>(candidate.attributes());
                        attributes.put("foodPreparation", "true");
                        if (candidate.kind() == PlanKind.SMELT && preparation.item().equals(candidate.output()))
                            attributes.put("ordinaryInputOnly", "true");
                        return new PlanStep(candidate.kind(), candidate.sourceId(), candidate.output(),
                                candidate.outputCount(), candidate.operationCount(), candidate.requirements(),
                                candidate.candidateBlocks(), candidate.recipeType(), candidate.recipeWidth(),
                                candidate.recipeHeight(), candidate.station(), candidate.customType(), attributes, candidate.nativeWork());
                    }).toList();
                    return new PlanningOutcome(new PlanResult(cooking.target(), cooking.requestedCount(), safeCooking,
                            cooking.blockedReasons(), cooking.optimal(), cooking.expandedNodes(), cooking.elapsedNanos()), false, true);
                }
            }
            if (joint != null) {
                List<String> originalGathers = debugPlanning ? joint.steps().stream()
                        .filter(candidate -> candidate.kind() == PlanKind.GATHER)
                        .map(candidate -> candidate.sourceId() + ":" + candidate.outputCount()).toList() : List.of();
                joint = ProjectGatherBatch.consolidateInitialMaterial(planner, filteredSnapshot, inventory, joint,
                        PlannerLimits.DEFAULT, preferences, logMaterials);
                if (debugPlanning && joint.success()) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] PROJECT_MATERIALS project={} before={} after={}", project.name(), originalGathers,
                        joint.steps().stream().filter(candidate -> candidate.kind() == PlanKind.GATHER)
                                .map(candidate -> candidate.sourceId() + ":" + candidate.outputCount()).toList());
                if (debugPlanning) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] PLAN project={} success={} steps={} branches={} elapsedMs={}",
                        project.name(), joint.success(), joint.steps().size(), joint.expandedNodes(),
                        joint.elapsedNanos() / 1_000_000.0);
                if (joint.success() && !joint.steps().isEmpty()) {
                    List<PlanStep> steps = new ArrayList<>(joint.steps());
                    PlanStep first = steps.get(0);
                    if (steps.get(0).kind() == PlanKind.GATHER)
                        steps.set(0, ProjectGatherBatch.firstStep(planner, filteredSnapshot, inventory, steps,
                                PlannerLimits.DEFAULT, preferences, gatherCapacity.getOrDefault(steps.get(0).output(), 0)));
                    PlanResult basePlan = new PlanResult(targetItem, targetCount, steps, List.of(),
                            joint.optimal(), joint.expandedNodes(), joint.elapsedNanos());
                    PlanningOutcome shieldPreparation = shieldPreparationOutcome(basePlan, shieldOffer, preferences);
                    if (shieldPreparation != null) return shieldPreparation;
                    PlanningOutcome combatPreparation = combatPreparationOutcome(basePlan, joint, combatOffer, preferences);
                    if (combatPreparation != null) return combatPreparation;
                    return new PlanningOutcome(basePlan, false, false, steps.get(0) == first ? null : first, null);
                }
            }
            PlanResult filteredPlan = planWithStationFallback(filteredSnapshot, inventory, knownInventory,
                    targetItem, targetCount, preferences);
            if (filteredPlan.success() && (joint == null || joint.success())) {
                PlanningOutcome shieldPreparation = shieldPreparationOutcome(filteredPlan, shieldOffer, preferences);
                if (shieldPreparation != null) return shieldPreparation;
                PlanningOutcome combatPreparation = combatPreparationOutcome(filteredPlan, joint, combatOffer, preferences);
                if (combatPreparation != null) return combatPreparation;
            }
            if (filteredPlan.success() && harvestOffer != null) {
                try {
                    PlanResult investmentPlan = planner.planFast(harvestOffer.catalog(), harvestOffer.inventory(),
                            WOODEN_AXE, harvestOffer.demand().targetCount());
                    if (investmentPlan.success() && usesOnlyCapturedInvestmentSources(investmentPlan, harvestOffer)) {
                        long adjustedBenefit = HarvestInvestment.adjustedBenefitForGoalStock(investmentPlan,
                                harvestOffer.goalLogItems(), harvestOffer.estimates().expectedBenefitTicks(),
                                harvestOffer.estimates().miningAndTravelTicksPerLog());
                        if (adjustedBenefit < 1) return new PlanningOutcome(filteredPlan, false, false);
                        HarvestInvestment.TickEstimates adjustedEstimates = new HarvestInvestment.TickEstimates(
                                adjustedBenefit, harvestOffer.estimates().miningAndTravelTicksPerLog(),
                                harvestOffer.estimates().craftTicksPerOperation(), harvestOffer.estimates().stationPlacementTicks(),
                                harvestOffer.estimates().minimumNetSavingTicks());
                        HarvestInvestment.Decision decision = HarvestInvestment.approve(filteredPlan, investmentPlan,
                                harvestOffer.demand(), harvestOffer.localOutputs(), Set.of(CRAFTING_TABLE), adjustedEstimates);
                        if (debugPlanning) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                                "[Lodekeeper] HARVEST_INVESTMENT approved={} tools={} remaining={} benefitTicks={} costTicks={} reason={}",
                                decision.approved(), harvestOffer.demand().targetCount(), harvestOffer.demand().remainingBlocks(),
                                decision.estimatedBenefitTicks(), decision.estimatedCostTicks(), decision.reason());
                        if (decision.approved()) return new PlanningOutcome(investmentPlan, false, true);
                    }
                } catch (RuntimeException ignored) {
                    // Optional planning must never replace the already-complete wood plan on failure.
                }
                return new PlanningOutcome(filteredPlan, false, false);
            }
            if (!explorationEnabled || excludedGatherSourceIds.isEmpty()
                    || !ExplorationRecovery.isLogicalFailure(filteredPlan)) {
                return new PlanningOutcome(filteredPlan, false, false);
            }
            // Recovery has its own default fast-planner budget (20 ms); each planner call remains independently capped.
            PlanResult fullPlan = planWithStationFallback(full, inventory, knownInventory, targetItem, targetCount, preferences);
            return new PlanningOutcome(filteredPlan,
                    ExplorationRecovery.provesExploration(filteredPlan, fullPlan, full, excludedGatherSourceIds), false);
        }, plannerWorker);
    }

    private ShieldPreparationOffer captureShieldPreparationOffer(CatalogSnapshot snapshot,
                                                                InventorySnapshot inventory,
                                                                InventorySnapshot knownInventory) {
        return captureShieldOffer(snapshot, inventory, knownInventory, false);
    }

    private ShieldPreparationOffer captureShieldOffer(CatalogSnapshot snapshot, InventorySnapshot inventory,
                                                       InventorySnapshot knownInventory, boolean travel) {
        if (deferShieldPreparation) {
            deferShieldPreparation = false;
            return null;
        }
        if (!config.autoDefend || !config.autoUseShield || !config.autoCraftShield || client.player == null
                || !client.player.isAlive() || client.player.getAbilities().creativeMode || client.player.isSpectator()
                || !client.player.getOffHandStack().isEmpty() || gatherCapacity(ItemId.parse("minecraft:shield")) < 1)
            return null;
        threats.updateProtection(foodReservations());
        if (threats.hasAvailableShield()) return null;
        Set<ItemId> planks = Set.copyOf(catalog.tags.getOrDefault(PLANKS_TAG, List.of()));
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        snapshot.itemDefinitions().values().forEach(builder::item);
        catalog.tags.forEach(builder::tag);
        for (AcquisitionSource source : catalog.sources) {
            if (!(source instanceof CraftingSource crafting) || unavailableSources.contains(source.sourceId())
                    || !Set.of(ItemId.parse("minecraft:shield"), CRAFTING_TABLE_ITEM).contains(source.output())) continue;
            var recipe = catalog.recipes.get(source.sourceId());
            if (recipe == null || crafting.outputCount() != 1) continue;
            ItemStack output = recipe.outputPerOperation();
            if (!GameApi.canCombine(output, new ItemStack(GameCatalog.item(crafting.output())))
                    || output.getCount() != 1) continue;
            builder.source(source);
        }
        Map<ItemId, Integer> ordinary = new HashMap<>();
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getStack(index);
            if (!stack.isEmpty() && !stack.hasEnchantments() && !GameApi.hasCustomName(stack)
                    && GameApi.canCombine(stack, new ItemStack(stack.getItem())))
                ordinary.merge(GameCatalog.id(stack.getItem()), stack.getCount(), Math::addExact);
        }
        Map<ItemId, Integer> reserved = new HashMap<>(inventory.protectedCounts());
        foodReservations().forEach((item, count) -> reserved.merge(item, count, Math::max));
        reserved.replaceAll((item, count) -> Math.min(count, ordinary.getOrDefault(item, 0)));
        reserved.values().removeIf(count -> count == 0);
        InventorySnapshot known = new InventorySnapshot(ordinary, knownInventory.availableStations(), Map.of(), reserved);
        ShieldPlanningIdentity identity = shieldPlanningIdentity();
        PendingGoals goals = travel ? pendingGoals(false) : identity.goals();
        if (goals.unresolvedLogs() || goals.targets().size() > ProjectSpec.MAX_GOALS) return null;
        ProjectSpec pendingProject = null;
        if (!goals.targets().isEmpty()) {
            pendingProject = remainingProject(new ProjectSpec("pending_goals", "Pending inventory targets",
                    goals.targets(), ProjectSpec.Purpose.INVENTORY_GOALS), inventory);
            if (!travel && pendingProject == null) return null;
        } else if (!travel) return null;
        return new ShieldPreparationOffer(builder.build(), known, planks,
                config.shieldIronReserve, config.shieldPlankReserve, config.allowBuilding,
                snapshot, knownInventory, pendingProject, identity);
    }

    private PlanningOutcome shieldPreparationOutcome(PlanResult basePlan, ShieldPreparationOffer offer,
                                                     PlanningPreferences preferences) {
        if (offer == null || !basePlan.success() || basePlan.steps().isEmpty()) return null;
        return onHandShieldOutcome(offer, preferences);
    }

    private PlanningOutcome onHandShieldOutcome(ShieldPreparationOffer offer, PlanningPreferences preferences) {
        if (offer == null) return null;
        try {
            InventorySnapshot reserved = offer.knownInventory();
            if (offer.pendingProject() != null) {
                ProjectPlanResult pending = planner.planProjectFast(offer.workCatalog(), offer.workInventory(),
                        offer.pendingProject(), PlannerLimits.DEFAULT, preferences);
                if (!pending.success()) return null;
                reserved = ProjectMaterialReservations.protectOptionalWork(reserved, pending);
            }
            InventorySnapshot stock = shieldFloorInventory(reserved, offer);
            if (stock == null) return null;
            ItemId shieldItem = ItemId.parse("minecraft:shield"), iron = ItemId.parse("minecraft:iron_ingot");
            PlanResult plan = planner.planFast(offer.catalog(), stock, shieldItem, 1, PlannerLimits.DEFAULT, preferences);
            if (!plan.success() || plan.steps().isEmpty() || plan.steps().size() > 3) return null;
            int shields = 0, tables = 0, ironCost = 0, plankCost = 0;
            for (PlanStep candidate : plan.steps()) {
                if (candidate.kind() == PlanKind.PLACE_STATION) {
                    if (!offer.canPlaceStations() || !CRAFTING_TABLE.equals(candidate.station())) return null;
                    continue;
                }
                if (candidate.kind() != PlanKind.CRAFT || candidate.operationCount() != 1 || candidate.outputCount() != 1)
                    return null;
                boolean shieldCraft = shieldItem.equals(candidate.output());
                if (shieldCraft) shields++;
                else if (CRAFTING_TABLE_ITEM.equals(candidate.output())) tables++;
                else return null;
                int ironForStep = 0, planksForStep = 0;
                for (SelectedRequirement requirement : candidate.requirements()) {
                    if (requirement instanceof SelectedItemRequirement input && input.consumed()) {
                        if (iron.equals(input.item())) ironForStep += input.count();
                        else if (offer.planks().contains(input.item())) planksForStep += input.count();
                        else return null;
                    } else if (requirement instanceof SelectedToolRequirement) return null;
                }
                if (ironForStep != (shieldCraft ? 1 : 0) || planksForStep != (shieldCraft ? 6 : 4)) return null;
                ironCost += ironForStep;
                plankCost += planksForStep;
            }
            if (shields != 1 || tables > 1 || ironCost != 1 || plankCost != 6 + tables * 4) return null;
            InventorySnapshot reservedStock = reserved;
            List<PlanStep> marked = new ArrayList<>();
            for (int index = 0; index < plan.steps().size(); index++) {
                PlanStep candidate = plan.steps().get(index);
                Map<String, String> attributes = new HashMap<>(candidate.attributes());
                attributes.put("shieldPreparation", "true");
                attributes.put("ordinaryInputOnly", "true");
                attributes.put("shieldIronFloor", Integer.toString(offer.ironFloor()));
                attributes.put("shieldPlankFloor", Integer.toString(offer.plankFloor()));
                reservedStock.protectedCounts().forEach((item, count) -> attributes.put("shieldReserved:" + item, Integer.toString(count)));
                Map<ItemId, Integer> future = new HashMap<>();
                for (PlanStep later : plan.steps().subList(index + 1, plan.steps().size()))
                    for (SelectedRequirement requirement : later.requirements())
                        if (requirement instanceof SelectedItemRequirement input && input.consumed())
                            future.merge(input.item(), input.count(), Math::addExact);
                future.forEach((item, count) -> attributes.put("shieldFuture:" + item, Integer.toString(count)));
                marked.add(new PlanStep(candidate.kind(), candidate.sourceId(), candidate.output(), candidate.outputCount(),
                        candidate.operationCount(), candidate.requirements(), candidate.candidateBlocks(), candidate.recipeType(),
                        candidate.recipeWidth(), candidate.recipeHeight(), candidate.station(), candidate.customType(), attributes, candidate.nativeWork()));
            }
            return new PlanningOutcome(new PlanResult(plan.target(), plan.requestedCount(), marked, plan.blockedReasons(),
                    plan.optimal(), plan.expandedNodes(), plan.elapsedNanos()), false, true, null, offer.identity());
        } catch (RuntimeException ignored) { return null; }
    }

    private static InventorySnapshot shieldFloorInventory(InventorySnapshot stock, ShieldPreparationOffer offer) {
        Map<ItemId, Integer> floors = new HashMap<>(stock.protectedCounts());
        ItemId iron = ItemId.parse("minecraft:iron_ingot");
        int ironReserved = floors.getOrDefault(iron, 0);
        if ((long) stock.count(iron) - ironReserved < (long) offer.ironFloor() + 1) return null;
        floors.put(iron, ironReserved + offer.ironFloor());
        int toReserve = offer.plankFloor();
        for (ItemId item : offer.planks().stream().sorted().toList()) {
            int current = floors.getOrDefault(item, 0);
            int allocated = Math.min(toReserve, Math.max(0, stock.count(item) - current));
            if (allocated > 0) floors.put(item, current + allocated);
            toReserve -= allocated;
        }
        if (toReserve != 0) return null;
        return new InventorySnapshot(stock.counts(), stock.availableStations(), Map.of(), floors);
    }

    private PendingGoals pendingGoals() { return pendingGoals(true); }
    private PendingGoals pendingGoals(boolean includeActive) {
        Map<ItemId, Integer> targets = new TreeMap<>();
        boolean unresolvedLogs = false;
        if (includeActive && acquisitionView() != null) {
            targets.merge(acquisitionView().item(), acquisitionView().count(), Math::max);
            unresolvedLogs = acquisitionView().anyLogs();
        }
        for (Request request : queue) if (request instanceof AcquireRequest acquire) {
            targets.merge(acquire.item(), acquire.count(), Math::max);
            unresolvedLogs |= acquire.anyLogs();
        }
        for (ProjectRun run : projects) if (!run.aborted)
            run.spec.goals().forEach((item, count) -> targets.merge(item, count, Math::max));
        for (MaintainedDemandModel.Status target : maintained.statuses())
            targets.merge(target.item(), target.targetCount(), Math::max);
        return new PendingGoals(targets, unresolvedLogs);
    }

    private ShieldPlanningIdentity shieldPlanningIdentity() {
        return new ShieldPlanningIdentity(acquisitionScope, active.jobToken(), pendingGoals(), client.world, client.world.getRegistryKey(),
                placementProvenance.session(), catalog.generation(), nearbyResources.version(),
                new ShieldOptions(config.autoDefend, config.autoUseShield, config.autoCraftShield,
                        config.allowBreaking, config.allowBuilding, config.shieldIronReserve, config.shieldPlankReserve),
                unavailableSources);
    }

    private boolean shieldIdentityCurrent(ShieldPlanningIdentity identity) {
        return identity != null && active != null && client.world != null && catalog != null && catalog.ready()
                && identity.equals(shieldPlanningIdentity()) && catalog.usesCurrentProvider();
    }

    private boolean shieldStepCurrent() {
        return stepShieldIdentity == null || shieldIdentityCurrent(stepShieldIdentity);
    }

    private Map<ItemId, Integer> shieldExecutionReservations() {
        Map<ItemId, Integer> reserved = new HashMap<>(foodReservations());
        if (step != null) for (SelectedRequirement requirement : step.requirements())
            if (requirement instanceof SelectedItemRequirement input)
                reserved.computeIfPresent(input.item(), (item, count) -> Math.max(0, count - input.count()));
        return Map.copyOf(reserved);
    }

    private CombatPreparationOffer captureCombatPreparationOffer(CatalogSnapshot snapshot,
                                                                  InventorySnapshot inventory,
                                                                  InventorySnapshot knownInventory) {
        if (!config.autoDefend || client.player == null || client.world == null || !client.player.isAlive()
                || client.player.getAbilities().creativeMode || client.player.isSpectator()
                || client.world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL
                || COMBAT_PREPARATION_PICKAXES.stream().noneMatch(actions::hasTool)
                || gatherCapacity(STONE_SWORD) < 1 || threats.hasPreparedMeleeWeapon()) return null;

        Set<ItemId> planks = Set.copyOf(catalog.tags.getOrDefault(PLANKS_TAG, List.of()));
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        snapshot.itemDefinitions().values().forEach(builder::item);
        catalog.tags.forEach(builder::tag);
        for (AcquisitionSource source : catalog.sources) {
            if (unavailableSources.contains(source.sourceId())) continue;
            if (source instanceof CraftingSource crafting && isCombatPreparationCraftSource(crafting, planks)) {
                builder.source(source);
            } else if (config.allowBreaking && source instanceof GatherSource gather
                    && isCombatPreparationGatherSource(gather)) {
                builder.source(source);
            }
        }
        return new CombatPreparationOffer(builder.build(), inventory, knownInventory, config.allowBuilding);
    }

    private boolean isCombatPreparationCraftSource(CraftingSource source, Set<ItemId> planks) {
        if (!source.output().equals(STONE_SWORD) && !source.output().equals(STICK)
                && !source.output().equals(CRAFTING_TABLE_ITEM) && !planks.contains(source.output())) return false;
        if (source.requirements().stream().anyMatch(requirement ->
                !(requirement instanceof StationRequirement station)
                        || !station.station().equals(CRAFTING_TABLE)
                        || !station.placementItem().equals(CRAFTING_TABLE_ITEM))) return false;

        RecipeWork recipe = catalog.recipes.get(source.sourceId());
        if (recipe == null || recipe.kind() != RecipeWork.Kind.SHAPED_CRAFTING
                && recipe.kind() != RecipeWork.Kind.SHAPELESS_CRAFTING) return false;
        boolean matchingLayout = source.recipeType() == RecipeType.SHAPED
                ? recipe.kind() == RecipeWork.Kind.SHAPED_CRAFTING
                : recipe.kind() == RecipeWork.Kind.SHAPELESS_CRAFTING;
        if (!matchingLayout || source.width() != recipe.width() || source.height() != recipe.height()) return false;
        ItemStack output = recipe.outputPerOperation();
        return output.getCount() == source.outputCount()
                && GameApi.canCombine(output, new ItemStack(GameCatalog.item(source.output())));
    }

    private static boolean isCombatPreparationGatherSource(GatherSource source) {
        if (source.outputCount() != 1 || source.blocks().size() != 1
                || source.requirements().stream().anyMatch(requirement -> !(requirement instanceof ToolRequirement))) return false;
        String block = source.blocks().get(0).toString();
        return source.sourceId().equals("gather:" + block)
                && source.output().equals(COMBAT_GATHER_OUTPUTS.get(block));
    }

    private PlanningOutcome combatPreparationOutcome(PlanResult basePlan, ProjectPlanResult project,
                                                     CombatPreparationOffer offer,
                                                     PlanningPreferences preferences) {
        if (offer == null || !basePlan.success() || basePlan.steps().stream().noneMatch(step ->
                step.kind() == PlanKind.GATHER && step.candidateBlocks().stream()
                        .anyMatch(block -> COMBAT_PREPARATION_ORES.contains(block.toString())))) return null;
        try {
            InventorySnapshot protectedInventory = project == null ? offer.inventory()
                    : ProjectMaterialReservations.protectOptionalWork(offer.inventory(), project);
            InventorySnapshot protectedKnownInventory = project == null ? offer.knownInventory()
                    : ProjectMaterialReservations.protectOptionalWork(offer.knownInventory(), project);
            PlanResult preparation = planWithStationFallback(offer.catalog(), protectedInventory,
                    protectedKnownInventory, STONE_SWORD, 1, preferences);
            if (!isBoundedCombatPreparationPlan(preparation, offer.catalog(), offer.canPlaceStations())) return null;
            List<PlanStep> markedSteps = preparation.steps().stream().map(candidate -> {
                Map<String, String> attributes = new HashMap<>(candidate.attributes());
                attributes.put("combatPreparation", "true");
                return new PlanStep(candidate.kind(), candidate.sourceId(), candidate.output(),
                        candidate.outputCount(), candidate.operationCount(), candidate.requirements(),
                        candidate.candidateBlocks(), candidate.recipeType(), candidate.recipeWidth(),
                        candidate.recipeHeight(), candidate.station(), candidate.customType(), attributes, candidate.nativeWork());
            }).toList();
            PlanResult markedPlan = new PlanResult(preparation.target(), preparation.requestedCount(), markedSteps,
                    preparation.blockedReasons(), preparation.optimal(), preparation.expandedNodes(), preparation.elapsedNanos());
            return new PlanningOutcome(markedPlan, false, true);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isBoundedCombatPreparationPlan(PlanResult plan, CatalogSnapshot catalogSnapshot, boolean canPlaceStations) {
        if (!plan.success() || plan.steps().isEmpty() || plan.steps().size() > 5) return false;
        int gatherOperations = 0, gatheredBlocks = 0, craftingOperations = 0;
        for (PlanStep step : plan.steps()) {
            if (step.station() != null && !step.station().equals(CRAFTING_TABLE)) return false;
            switch (step.kind()) {
                case GATHER -> {
                    AcquisitionSource selected = catalogSnapshot.sourcesFor(step.output()).stream()
                            .filter(source -> source.sourceId().equals(step.sourceId())).findFirst().orElse(null);
                    if (!(selected instanceof GatherSource gather) || !isCombatPreparationGatherSource(gather)
                            || !gather.output().equals(step.output()) || !gather.blocks().containsAll(step.candidateBlocks())
                            || step.operationCount() > 2 - gatherOperations || step.outputCount() > 2 - gatheredBlocks) return false;
                    gatherOperations += step.operationCount();
                    gatheredBlocks += step.outputCount();
                }
                case CRAFT -> {
                    AcquisitionSource selected = catalogSnapshot.sourcesFor(step.output()).stream()
                            .filter(source -> source.sourceId().equals(step.sourceId())).findFirst().orElse(null);
                    if (!(selected instanceof CraftingSource crafting) || !crafting.output().equals(step.output())
                            || crafting.recipeType() != step.recipeType() || crafting.width() != step.recipeWidth()
                            || crafting.height() != step.recipeHeight()
                            || crafting.requirements().stream().anyMatch(requirement ->
                                    !(requirement instanceof StationRequirement station)
                                            || !station.station().equals(CRAFTING_TABLE)
                                            || !station.placementItem().equals(CRAFTING_TABLE_ITEM))
                            || step.operationCount() > 4 - craftingOperations) return false;
                    craftingOperations += step.operationCount();
                }
                case PLACE_STATION -> {
                    if (!canPlaceStations || !CRAFTING_TABLE.equals(step.station())) return false;
                }
                case SMELT, CUSTOM, NATIVE -> { return false; }
            }
        }
        return true;
    }

    private List<AcquisitionSource> preparationSources(CatalogSnapshot snapshot, FoodController.Preparation preparation) {
        return preparationSources(snapshot, preparation, false);
    }
    private List<AcquisitionSource> preparationSources(CatalogSnapshot snapshot, FoodController.Preparation preparation,
                                                      boolean requireComplete) {
        List<AcquisitionSource> selected = new ArrayList<>();
        long deadline = System.nanoTime() + 1_000_000L;
        int inspected = 0;
        for (AcquisitionSource candidate : snapshot.sourcesFor(preparation.item())) {
            if (++inspected > 128 || System.nanoTime() >= deadline)
                return requireComplete ? List.of() : List.copyOf(selected);
            if (!(candidate instanceof SmeltingSource source) || source.outputCount() != 1) continue;
            RecipeWork nativeRecipe = catalog.recipes.get(source.sourceId());
            if (nativeRecipe == null || nativeRecipe.kind() != RecipeWork.Kind.SMELTING) continue;
            ItemStack output = nativeRecipe.outputPerOperation();
            if (output.getCount() != 1 || !GameApi.canCombine(output, GameCatalog.item(preparation.item()).getDefaultStack())
                    || !nativeRecipe.inputs().get(0).predicate().test(GameCatalog.item(preparation.raw()).getDefaultStack())) continue;
            selected.add(new SmeltingSource(source.sourceId(), source.output(), 1,
                    Ingredient.of(preparation.raw()), source.fuels(), source.cookTicks(),
                    source.requirements(), source.fuelProgressTicks()));
        }
        return requireComplete && System.nanoTime() >= deadline ? List.of() : List.copyOf(selected);
    }

    private static boolean isFoodPreparation(PlanStep candidate) {
        return candidate != null && Boolean.parseBoolean(candidate.attributes().getOrDefault("foodPreparation", "false"));
    }

    private ProjectSpec remainingProject(ProjectSpec project, InventorySnapshot inventory) {
        Map<ItemId, Integer> missing = new TreeMap<>();
        project.goals().forEach((item, target) -> {
            int equipped = Math.max(0, observedInventory.getOrDefault(item, 0) - inventory.count(item));
            int storageTarget = Math.max(0, target - equipped);
            if (inventory.count(item) < storageTarget) missing.put(item, storageTarget);
        });
        return missing.isEmpty() ? null : new ProjectSpec(project.name(), project.description(), missing, project.purpose());
    }

    private Map<ItemId, Integer> gatherCapacity(CatalogSnapshot snapshot) {
        Map<ItemId, Integer> capacity = new HashMap<>();
        snapshot.gatherSources().stream()
                .map(AcquisitionSource::output).distinct().limit(512)
                .forEach(item -> capacity.put(item, gatherCapacity(item)));
        return Map.copyOf(capacity);
    }

    private int gatherCapacity(ItemId item) {
        ItemStack expected = new ItemStack(Registries.ITEM.get(GameApi.identifier(item.toString())));
        int free = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) {
            if (stack.isEmpty()) free += expected.getMaxCount();
            else if (GameApi.canCombine(stack, expected))
                free += Math.max(0, Math.min(stack.getMaxCount(), expected.getMaxCount()) - stack.getCount());
        }
        return free;
    }

    private void rememberNativeLogTargets() {
        if (step == null || step.kind() != PlanKind.GATHER || client.world == null) return;
        Set<Block> blocks = new HashSet<>();
        for (BlockId id : step.candidateBlocks()) {
            Block block = Registries.BLOCK.get(GameApi.identifier(id.toString()));
            if (block != Blocks.AIR) blocks.add(block);
        }
        for (BlockPos position : movement.knownMiningTargets()) {
            if (!client.world.isChunkLoaded(position)) continue;
            Block block = client.world.getBlockState(position).getBlock();
            if (blocks.contains(block)) rememberDiscoveredSource(step.sourceId(), position, block);
        }
    }

    private HarvestOffer captureHarvestOffer(int remainingBlocks) {
        if (remainingBlocks < 1 || client.player == null
                || !catalog.ready() || emptyMainInventorySlots() < MIN_FREE_SLOTS_FOR_WOOD_TOOL_OFFER) return null;

        ItemStack freshAxe = new ItemStack(Items.WOODEN_AXE);
        int freshDurability = freshAxe.getMaxDamage();
        int wear = GameApi.blockBreakWear(freshAxe);
        if (freshDurability < 1 || wear < 1 || wear > 1_000_000) return null;
        int minimumBeforeBreak = Math.max(2, wear + 1);
        if (freshDurability < minimumBeforeBreak) return null;

        LocalLogEvidence logs = captureLocalLogEvidence(freshAxe);
        if (logs == null || logs.sources().isEmpty() || logs.leastSavingTicks() < 1) return null;
        int comparableHeldCapacity = comparableHeldToolCapacity(freshAxe, logs.states(), remainingBlocks);
        if (comparableHeldCapacity < 0) return null;
        int axeBlocksRemaining = remainingBlocks - comparableHeldCapacity;
        if (axeBlocksRemaining < 1) return null;

        InventorySnapshot capturedAxes = inventorySnapshot(WOODEN_AXE);
        if (capturedAxes.protectedCounts().getOrDefault(WOODEN_AXE, 0) > 0) return null;
        InventorySnapshot investmentInventory = inventoryWithKnownWoodenAxes(capturedAxes, freshAxe, freshDurability, wear);
        if (investmentInventory == null) return null;
        HarvestInvestment.ToolDemand demand = HarvestInvestment.additionalDemand(investmentInventory, WOODEN_AXE,
                axeBlocksRemaining, freshDurability, wear, minimumBeforeBreak, 2).orElse(null);
        if (demand == null) return null;

        Set<String> allowedAxeCraftSources = validatedAxeCraftSources(freshAxe, freshDurability, wear, logs.states());
        if (allowedAxeCraftSources.isEmpty()) return null;
        Set<String> nativeCraftSources = eligibleNativeCraftSources(allowedAxeCraftSources);
        CatalogSnapshot auxiliaryCatalog = auxiliaryCatalog(logs, nativeCraftSources);
        if (!config.allowBuilding && !investmentInventory.availableStations().contains(CRAFTING_TABLE)) return null;

        long uncoveredBlocks = Math.max(0L, demand.remainingBlocks() - demand.usableHeldCapacity());
        long profitableAddedCapacity = Math.min(demand.addedSafeCapacity(), uncoveredBlocks);
        long expectedBenefit = profitableAddedCapacity * logs.leastSavingTicks();
        long miningAndTravel = (long) logs.highestHandTicks() + 20;
        if (expectedBenefit > 1_000_000_000L || miningAndTravel > 1_000_000_000L) return null;
        HarvestInvestment.TickEstimates estimates = new HarvestInvestment.TickEstimates(expectedBenefit,
                miningAndTravel, WOOD_TOOL_CRAFT_TICKS, WOOD_TOOL_STATION_TICKS, WOOD_TOOL_MINIMUM_SAVING_TICKS);
        Set<ItemId> goalLogItems = Set.copyOf(catalog.tags.getOrDefault(LOGS_TAG, List.of()));
        return new HarvestOffer(auxiliaryCatalog, investmentInventory, demand, logs.sources(), logs.outputs(), goalLogItems,
                nativeCraftSources, allowedAxeCraftSources, estimates);
    }

    private LocalLogEvidence captureLocalLogEvidence(ItemStack freshAxe) {
        Set<ItemId> logItems = new HashSet<>(catalog.tags.getOrDefault(LOGS_TAG, List.of()));
        if (logItems.isEmpty()) return null;
        Map<String, GatherSource> nativeLogSources = new HashMap<>();
        for (AcquisitionSource candidate : catalog.sources) {
            if (!(candidate instanceof GatherSource gather) || !gather.sourceId().startsWith("gather:")
                    || unavailableSources.contains(gather.sourceId()) || !logItems.contains(gather.output())
                    || gather.requirements().stream().anyMatch(ToolRequirement.class::isInstance)) continue;
            nativeLogSources.put(gather.sourceId(), gather);
        }

        Map<String, GatherLimit> sourceLimits = new LinkedHashMap<>();
        Set<ItemId> outputs = new LinkedHashSet<>();
        List<BlockState> states = new ArrayList<>();
        int inspected = 0, highestHandTicks = 0, leastSavingTicks = Integer.MAX_VALUE;
        long radiusSquared = (long) config.searchRadius * config.searchRadius;
        for (Map.Entry<String, List<BlockPos>> entry : discoveredSources.entrySet()) {
            GatherSource source = nativeLogSources.get(entry.getKey());
            if (source == null) continue;
            Set<Block> candidateBlocks = new HashSet<>();
            for (BlockId id : source.blocks()) {
                Block block = Registries.BLOCK.get(GameApi.identifier(id.toString()));
                if (block != Blocks.AIR) candidateBlocks.add(block);
            }
            if (candidateBlocks.isEmpty()) continue;

            List<BlockState> sourceStates = new ArrayList<>();
            int sourceHandMax = 0, sourceSavingMin = Integer.MAX_VALUE;
            boolean unsafeSource = false;
            for (BlockPos position : entry.getValue()) {
                if (inspected >= MAX_LOCAL_LOG_POSITIONS) break;
                inspected++;
                if (rejectedResources.contains(position)) continue;
                long dx = (long) position.getX() - client.player.getBlockX();
                long dz = (long) position.getZ() - client.player.getBlockZ();
                if (dx * dx + dz * dz > radiusSquared || !client.world.isChunkLoaded(position)) continue;
                BlockState state = client.world.getBlockState(position);
                if (!candidateBlocks.contains(state.getBlock())) continue;
                if (state.isToolRequired()) { unsafeSource = true; continue; }
                float hardness = state.getHardness(client.world, position);
                float axeSpeed = freshAxe.getMiningSpeedMultiplier(state);
                int handTicks = boundedBreakTicks(hardness, 1.0f);
                int axeTicks = boundedBreakTicks(hardness, axeSpeed);
                if (handTicks < 1 || axeTicks < 1) { unsafeSource = true; continue; }
                sourceStates.add(state);
                sourceHandMax = Math.max(sourceHandMax, handTicks);
                sourceSavingMin = Math.min(sourceSavingMin, handTicks - axeTicks);
            }
            if (unsafeSource || sourceStates.isEmpty()) continue;
            sourceLimits.put(entry.getKey(), new GatherLimit(source.output(), sourceStates.size()));
            outputs.add(source.output());
            states.addAll(sourceStates);
            highestHandTicks = Math.max(highestHandTicks, sourceHandMax);
            leastSavingTicks = Math.min(leastSavingTicks, sourceSavingMin);
            if (inspected >= MAX_LOCAL_LOG_POSITIONS) break;
        }
        if (states.isEmpty()) return null;
        return new LocalLogEvidence(Map.copyOf(sourceLimits), Set.copyOf(outputs), List.copyOf(states),
                highestHandTicks, leastSavingTicks);
    }

    private static int boundedBreakTicks(float hardness, float speed) {
        if (!Float.isFinite(hardness) || hardness <= 0 || !Float.isFinite(speed) || speed <= 0) return -1;
        double ticks = Math.ceil(30.0 * hardness / speed);
        return !Double.isFinite(ticks) || ticks < 1 || ticks > 1_000_000_000L ? -1 : (int) ticks;
    }

    private int comparableHeldToolCapacity(ItemStack freshAxe, List<BlockState> localStates, int remainingBlocks) {
        long capacity = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) {
            if (stack.isEmpty() || isStandardWoodenAxe(stack, freshAxe, freshAxe.getMaxDamage(), GameApi.blockBreakWear(freshAxe))) continue;
            boolean atLeastAsFastEverywhere = true;
            for (BlockState state : localStates) {
                float heldSpeed = stack.getMiningSpeedMultiplier(state);
                float axeSpeed = freshAxe.getMiningSpeedMultiplier(state);
                if (!Float.isFinite(heldSpeed) || heldSpeed <= 0) return -1;
                if (heldSpeed < axeSpeed) { atLeastAsFastEverywhere = false; break; }
            }
            if (!atLeastAsFastEverywhere) continue;
            int wear = GameApi.blockBreakWear(stack);
            if (wear < 0 || wear > 1_000_000) return -1;
            long perStack;
            if (!stack.isDamageable()) {
                perStack = remainingBlocks;
            } else {
                int remainingDurability = stack.getMaxDamage() - stack.getDamage();
                if (wear == 0) perStack = remainingDurability >= 1 ? remainingBlocks : 0;
                else {
                    int minimumBeforeBreak = Math.max(2, wear + 1);
                    perStack = remainingDurability < minimumBeforeBreak ? 0
                            : (remainingDurability - (long) minimumBeforeBreak) / wear + 1;
                }
            }
            capacity = Math.min(remainingBlocks, capacity + perStack * stack.getCount());
            if (capacity >= remainingBlocks) return remainingBlocks;
        }
        return (int) capacity;
    }

    private static boolean isStandardWoodenAxe(ItemStack stack, ItemStack freshAxe, int freshDurability, int freshWear) {
        if (!stack.isOf(Items.WOODEN_AXE) || stack.getMaxDamage() != freshDurability
                || GameApi.blockBreakWear(stack) != freshWear) return false;
        ItemStack normalized = stack.copy();
        normalized.setDamage(0);
        return ItemStack.areEqual(normalized, freshAxe);
    }

    private InventorySnapshot inventoryWithKnownWoodenAxes(InventorySnapshot captured, ItemStack freshAxe,
                                                              int freshDurability, int freshWear) {
        Map<ItemId, Integer> counts = new HashMap<>(captured.counts());
        Map<ItemId, Integer> durability = new HashMap<>(captured.remainingDurability());
        Map<ItemId, List<Integer>> lots = new HashMap<>(captured.durabilityLots());
        Map<ItemId, List<InventoryToolLot>> toolLots = new HashMap<>(captured.toolLots());
        Map<ItemId, Integer> protectedCounts = new HashMap<>(captured.protectedCounts());
        counts.remove(WOODEN_AXE);
        durability.remove(WOODEN_AXE);
        lots.remove(WOODEN_AXE);
        toolLots.remove(WOODEN_AXE);
        protectedCounts.remove(WOODEN_AXE);

        List<Integer> knownLots = new ArrayList<>();
        int knownCount = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) {
            if (!stack.isOf(Items.WOODEN_AXE)) continue;
            int stackWear = GameApi.blockBreakWear(stack);
            if (stackWear < 0) return null;
            if (!isStandardWoodenAxe(stack, freshAxe, freshDurability, freshWear)) continue;
            int remaining = freshDurability - stack.getDamage();
            if (remaining < 0 || knownCount > 1_000_000_000 - stack.getCount()) return null;
            knownCount += stack.getCount();
            for (int index = 0; index < stack.getCount(); index++) knownLots.add(remaining);
        }
        if (knownCount > 0) {
            counts.put(WOODEN_AXE, knownCount);
            knownLots.sort(Integer::compareTo);
            lots.put(WOODEN_AXE, List.copyOf(knownLots));
            toolLots.put(WOODEN_AXE, knownLots.stream().map(remaining -> new InventoryToolLot(remaining, false)).toList());
            durability.put(WOODEN_AXE, knownLots.get(knownLots.size() - 1));
        }
        return new InventorySnapshot(counts, captured.availableStations(), durability, protectedCounts, lots, toolLots);
    }

    private Set<String> validatedAxeCraftSources(ItemStack freshAxe, int freshDurability, int freshWear,
                                                   List<BlockState> localStates) {
        Set<String> candidates = new HashSet<>();
        for (AcquisitionSource source : catalog.sources) {
            if (source instanceof CraftingSource crafting && crafting.output().equals(WOODEN_AXE)) candidates.add(source.sourceId());
        }
        Set<String> valid = new HashSet<>();
        for (String sourceId : candidates) {
            RecipeWork recipe = catalog.recipes.get(sourceId);
            if (recipe == null || recipe.kind() == RecipeWork.Kind.SMELTING) continue;
            ItemStack output = recipe.outputPerOperation();
            if (!output.isOf(Items.WOODEN_AXE) || output.getCount() != 1 || output.getDamage() != 0
                    || output.getMaxDamage() != freshDurability || GameApi.blockBreakWear(output) != freshWear
                    || !ItemStack.areEqual(output, freshAxe)) continue;
            boolean sameSpeed = true;
            for (BlockState state : localStates) {
                float outputSpeed = output.getMiningSpeedMultiplier(state);
                float freshSpeed = freshAxe.getMiningSpeedMultiplier(state);
                if (!Float.isFinite(outputSpeed) || outputSpeed <= 0 || Float.compare(outputSpeed, freshSpeed) != 0) {
                    sameSpeed = false;
                    break;
                }
            }
            if (sameSpeed) valid.add(sourceId);
        }
        return Set.copyOf(valid);
    }

    private Set<String> eligibleNativeCraftSources(Set<String> allowedAxeCraftSources) {
        Set<String> result = new HashSet<>();
        for (AcquisitionSource source : catalog.sources) {
            if (!(source instanceof CraftingSource crafting) || !catalog.recipes.containsKey(source.sourceId())) continue;
            if (crafting.output().equals(WOODEN_AXE) && !allowedAxeCraftSources.contains(source.sourceId())) continue;
            if (crafting.requirements().stream().anyMatch(requirement -> !(requirement instanceof StationRequirement station)
                    || !station.station().equals(CRAFTING_TABLE))) continue;
            result.add(source.sourceId());
        }
        return Set.copyOf(result);
    }

    private CatalogSnapshot auxiliaryCatalog(LocalLogEvidence logs, Set<String> nativeCraftSources) {
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        catalog.snapshot().itemDefinitions().values().forEach(builder::item);
        catalog.tags.forEach(builder::tag);
        for (AcquisitionSource source : catalog.sources) {
            if ((source instanceof GatherSource && logs.sources().containsKey(source.sourceId()))
                    || (source instanceof CraftingSource && nativeCraftSources.contains(source.sourceId()))) builder.source(source);
        }
        return builder.build();
    }

    private static boolean usesOnlyCapturedInvestmentSources(PlanResult plan, HarvestOffer offer) {
        Map<String, Long> gathered = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            switch (step.kind()) {
                case GATHER -> {
                    GatherLimit limit = offer.localSources().get(step.sourceId());
                    if (limit == null || !limit.output().equals(step.output())) return false;
                    long used = gathered.merge(step.sourceId(), (long) step.operationCount(), Long::sum);
                    if (used > limit.localPositions()) return false;
                }
                case CRAFT -> {
                    if (!offer.nativeCraftSources().contains(step.sourceId())) return false;
                    if (WOODEN_AXE.equals(step.output()) && !offer.allowedAxeCraftSources().contains(step.sourceId())) return false;
                }
                case PLACE_STATION -> { if (!CRAFTING_TABLE.equals(step.station())) return false; }
                case SMELT, CUSTOM, NATIVE -> { return false; }
            }
        }
        return true;
    }

    private int emptyMainInventorySlots() {
        int empty = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (stack.isEmpty()) empty++;
        return empty;
    }

    private boolean prepareIngredientWood() {
        if (active == null || acquisitionView().anyLogs || step == null || step.output() == null
                || acquisitionView().item.equals(step.output())
                || !catalog.tags.getOrDefault(TagId.parse("minecraft:logs"), List.of()).contains(step.output())) return false;
        List<BlockPos> known = discoveredSources.get(step.sourceId());
        boolean validSelectedHint = known != null && selectedGatherToolAvailable()
                && known.stream().anyMatch(this::liveSelectedGatherHint);
        if (validSelectedHint && known.stream().anyMatch(pos -> liveSelectedGatherHint(pos)
                && actions.hit(pos) != null)) return false;
        BlockPos publishedHint = ingredientWoodPublished.get(step.sourceId());
        boolean missingPublishedHint = publishedHint != null && !liveSelectedGatherHint(publishedHint);
        if (missingPublishedHint) {
            ingredientWoodPublished.remove(step.sourceId());
            ingredientWoodExamined.clear();
        }
        BlockPos feet = client.player.getBlockPos();
        if (ingredientWoodRequest != acquisitionScope || ingredientWoodGeneration != catalog.generation()
                || ingredientWoodOrigin == null || feet.getSquaredDistance(ingredientWoodOrigin) > 256
                || ingredientWoodRejectedCount != rejectedResources.size()) {
            ingredientWoodRequest = acquisitionScope;
            ingredientWoodGeneration = catalog.generation();
            ingredientWoodOrigin = feet.toImmutable();
            ingredientWoodRejectedCount = rejectedResources.size();
            ingredientWoodScan = null;
            localIngredientWoodReachScan = null;
            ingredientWoodSources.clear(); localIngredientWoodSources.clear();
            ingredientWoodSourceIds.clear(); localIngredientWoodSourceIds.clear();
            ingredientWoodExamined.clear(); ingredientWoodPublished.clear();
            Set<ItemId> logs = new HashSet<>(catalog.tags.getOrDefault(TagId.parse("minecraft:logs"), List.of()));
            int captured = 0;
            for (AcquisitionSource source : catalog.sources) {
                if (!(source instanceof GatherSource gather) || !logs.contains(source.output())
                        || unavailableSources.contains(source.sourceId())) continue;
                if (++captured > 512) break;
                for (BlockId id : gather.blocks()) {
                    Block block = Registries.BLOCK.get(GameApi.identifier(id.toString()));
                    if (block == Blocks.AIR) continue;
                    if (!ingredientWoodSources.containsKey(block) && ingredientWoodSources.size() >= 512) continue;
                    List<GatherSource> sources = ingredientWoodSources.computeIfAbsent(block, ignored -> new ArrayList<>());
                    if (sources.size() < 32) {
                        sources.add(gather);
                        ingredientWoodSourceIds.add(gather.sourceId());
                        if (gather.sourceId().startsWith("gather:") && hasSatisfiedToolRequirements(gather)) {
                            List<GatherSource> localSources = localIngredientWoodSources.computeIfAbsent(block, ignored -> new ArrayList<>());
                            if (localSources.size() < 32) {
                                localSources.add(gather);
                                localIngredientWoodSourceIds.add(gather.sourceId());
                            }
                        }
                    }
                }
            }
            if (!ingredientWoodSources.isEmpty()) ingredientWoodScan = new BlockSearch(client,
                    ingredientWoodSources.keySet(), config.searchRadius, rejectedResources, true);
        }
        if (ingredientWoodScan == null || !ingredientWoodSourceIds.contains(step.sourceId())) return false;
        while (!localIngredientWoodSources.isEmpty()) {
            BlockPos local = findLocalReachable(localIngredientWoodSources.keySet(), localIngredientWoodSourceIds,
                    LocalReachPurpose.RECIPE_WOOD);
            LocalReachScan recipeWoodScan = localReachScan(LocalReachPurpose.RECIPE_WOOD);
            if (local == null) break;
            Block block = client.world.getBlockState(local).getBlock();
            List<GatherSource> sources = localIngredientWoodSources.get(block);
            if (sources == null) {
                recipeWoodScan.skipResult();
                continue;
            }
            boolean foundNewSource = false;
            for (GatherSource source : sources) {
                BlockPos previous = ingredientWoodPublished.get(source.sourceId());
                if (previous != null && previous.equals(local)) continue;
                rememberDiscoveredSource(source.sourceId(), local, block);
                ingredientWoodPublished.put(source.sourceId(), local.toImmutable());
                if (source.sourceId().equals(step.sourceId())) {
                    localReachHint = new LocalReachHint(client.world, client.world.getRegistryKey(), acquisitionScope,
                            catalog.generation(), Set.copyOf(rejectedResources), client.player.getBlockPos(),
                            source.sourceId(), local.toImmutable(), block);
                }
                foundNewSource = true;
            }
            if (foundNewSource) {
                resetAction(); continueActiveRequest(); return true;
            }
            recipeWoodScan.skipResult();
        }
        LocalReachScan recipeWoodScan = localReachScan(LocalReachPurpose.RECIPE_WOOD);
        if (!localIngredientWoodSources.isEmpty() && recipeWoodScan != null && !recipeWoodScan.complete()) {
            status = "finding nearby recipe wood · local " + recipeWoodScan.progressDescription();
            return true;
        }
        if (validSelectedHint) return false;
        if (ingredientWoodScan.complete() && missingPublishedHint) {
            ingredientWoodScan = new BlockSearch(client, ingredientWoodSources.keySet(), config.searchRadius, rejectedResources, true);
            ingredientWoodExamined.clear(); ingredientWoodPublished.clear();
        }
        startDiscoveryBudget();
        long before = ingredientWoodScan.progressToken();
        boolean complete = ingredientWoodScan.advance(config.scanBlocksPerTick,
                Math.max(0, discoveryDeadlineNanos - System.nanoTime()));
        if (before != ingredientWoodScan.progressToken()) actionTicks = 0;
        status = "finding nearby recipe wood · " + ingredientWoodScan.progressDescription();
        Set<BlockPos> positions = new LinkedHashSet<>(ingredientWoodScan.results());
        positions.addAll(ingredientWoodScan.representativeResults());
        ingredientWoodExamined.retainAll(positions);
        boolean foundNewSource = false;
        int candidatesLeft = 8;
        for (BlockPos pos : positions) {
            if (candidatesLeft == 0) break;
            if (!ingredientWoodExamined.add(pos)) continue;
            candidatesLeft--;
            if (!hasLoadedChunk(pos)) continue;
            Block block = rejectedResources.contains(pos) ? null : client.world.getBlockState(pos).getBlock();
            List<GatherSource> sources = block == null ? null : ingredientWoodSources.get(block);
            Set<String> liveSourceIds = new HashSet<>();
            if (sources != null) for (GatherSource source : sources) liveSourceIds.add(source.sourceId());
            var published = ingredientWoodPublished.entrySet().iterator();
            while (published.hasNext()) {
                var entry = published.next();
                if (!pos.equals(entry.getValue()) || liveSourceIds.contains(entry.getKey())) continue;
                published.remove();
                List<BlockPos> cached = discoveredSources.get(entry.getKey());
                if (cached != null) {
                    cached.remove(pos);
                    if (cached.isEmpty()) discoveredSources.remove(entry.getKey());
                }
            }
            if (sources == null) continue;
            Map<BlockPos, Block> checkedHints = new HashMap<>();
            for (GatherSource source : sources) {
                BlockPos previous = ingredientWoodPublished.get(source.sourceId());
                if (previous != null) {
                    if (previous.equals(pos)) continue;
                    Block previousBlock = null;
                    if (hasLoadedChunk(previous) && !rejectedResources.contains(previous)) {
                        previousBlock = checkedHints.computeIfAbsent(previous,
                                position -> client.world.getBlockState(position).getBlock());
                    }
                    List<GatherSource> previousSources = ingredientWoodSources.get(previousBlock);
                    if (previousSources != null && previousSources.stream()
                            .anyMatch(candidate -> candidate.sourceId().equals(source.sourceId()))) continue;
                    ingredientWoodPublished.remove(source.sourceId());
                    List<BlockPos> cached = discoveredSources.get(source.sourceId());
                    if (cached != null) {
                        cached.remove(previous);
                        if (cached.isEmpty()) discoveredSources.remove(source.sourceId());
                    }
                }
                if (!discoveredSources.containsKey(source.sourceId()) && discoveredSources.size() >= 64)
                    discoveredSources.remove(discoveredSources.keySet().iterator().next());
                List<BlockPos> cached = discoveredSources.computeIfAbsent(source.sourceId(), ignored -> new ArrayList<>());
                if (cached.size() < 512 && !cached.contains(pos)) cached.add(pos.toImmutable());
                nearbyResources.observeDiscoveredSource(source.sourceId(), pos, block);
                ingredientWoodPublished.put(source.sourceId(), pos.toImmutable());
                foundNewSource = true;
            }
            if (foundNewSource) break;
        }
        if (complete) {
            Set<GatherSource> sources = new HashSet<>();
            ingredientWoodSources.values().forEach(sources::addAll);
            for (GatherSource source : sources) {
                boolean fullyCovered = source.blocks().stream().allMatch(id ->
                        ingredientWoodSources.containsKey(Registries.BLOCK.get(GameApi.identifier(id.toString()))));
                boolean present = source.blocks().stream().anyMatch(id ->
                        ingredientWoodScan.found(Registries.BLOCK.get(GameApi.identifier(id.toString()))));
                if (fullyCovered && !present) unavailableSources.add(source.sourceId());
            }
        }
        if (foundNewSource || unavailableSources.contains(step.sourceId())) {
            resetAction(); continueActiveRequest(); return true;
        }
        return !complete;
    }

    private boolean liveSelectedGatherHint(BlockPos pos) {
        return !rejectedResources.contains(pos) && hasLoadedChunk(pos)
                && step.candidateBlocks().stream().anyMatch(id ->
                    Registries.BLOCK.get(GameApi.identifier(id.toString())) == client.world.getBlockState(pos).getBlock());
    }

    private void resetLogDiscovery() {
        logScan = null; localLogReachScan = null; localReachHint = null;
        logSources.clear(); localLogSources.clear(); logCandidates.clear(); attemptedLogCandidates.clear();
        droppedLogCandidate = null; localLogCandidateOutput = null; localLogCandidateSourceId = null;
        logScanComplete = false; logScanRequest = null; logScanGeneration = -1;
    }

    private ItemId chooseLogs() {
        if (logScanRequest != acquisitionScope || logScanGeneration != catalog.generation()) {
            resetLogDiscovery();
            logScanRequest = acquisitionScope;
            logScanGeneration = catalog.generation();
        }
        if (!logCandidates.isEmpty()) return logCandidates.peekFirst();
        NearbyResources.LogObservation nearby = nearbyResources.bestLogObservation();
        if (nearby != null && !attemptedLogCandidates.contains(nearby.source().output())
                && !unavailableSources.contains(nearby.source().sourceId())
                && !rejectedResources.contains(nearby.position()) && hasSatisfiedToolRequirements(nearby.source())) {
            rememberDiscoveredSource(nearby.source().sourceId(), nearby.position(),
                    client.world.getBlockState(nearby.position()).getBlock());
            logCandidates.addLast(nearby.source().output());
            return logCandidates.peekFirst();
        }
        if (logSources.isEmpty()) {
            localLogSources.clear();
            Set<ItemId> logs = new HashSet<>(catalog.tags.getOrDefault(TagId.parse("minecraft:logs"),List.of()));
            for (AcquisitionSource source : catalog.sources) {
                if (!(source instanceof GatherSource gather) || !logs.contains(source.output())
                        || unavailableSources.contains(source.sourceId())) continue;
                for (BlockId id : gather.blocks()) {
                    Block block = Registries.BLOCK.get(GameApi.identifier(id.toString()));
                    if (block == Blocks.AIR) continue;
                    logSources.putIfAbsent(block,gather);
                    if (gather.sourceId().startsWith("gather:") && hasSatisfiedToolRequirements(gather))
                        localLogSources.putIfAbsent(block, gather);
                }
            }
        }
        if (logSources.isEmpty()) return null;
        if (localLogSources.isEmpty()) localReachScan(LocalReachPurpose.LOG_CHOICE, null);
        Set<String> sourceIds = localLogSources.values().stream().map(GatherSource::sourceId)
                .collect(java.util.stream.Collectors.toSet());
        while (!localLogSources.isEmpty() && (!discoveryBudgetStarted || discoveryBudgetAvailable())) {
            BlockPos local = findLocalReachable(localLogSources.keySet(), sourceIds, LocalReachPurpose.LOG_CHOICE);
            LocalReachScan logReachScan = localReachScan(LocalReachPurpose.LOG_CHOICE);
            if (local == null) break;
            Block block = client.world.getBlockState(local).getBlock();
            GatherSource source = localLogSources.get(block);
            if (source == null) {
                logReachScan.skipResult();
                continue;
            }
            rememberDiscoveredSource(source.sourceId(), local, block);
            localReachHint = new LocalReachHint(client.world, client.world.getRegistryKey(), acquisitionScope,
                    catalog.generation(), Set.copyOf(rejectedResources), client.player.getBlockPos(),
                    source.sourceId(), local.toImmutable(), block);
            if (!attemptedLogCandidates.contains(source.output())) {
                localLogCandidateOutput = source.output();
                localLogCandidateSourceId = source.sourceId();
                if (!logCandidates.contains(source.output())) logCandidates.addLast(source.output());
                return logCandidates.peekFirst();
            }
            localReachHint = null;
            logReachScan.skipResult();
        }
        LocalReachScan logReachScan = localReachScan(LocalReachPurpose.LOG_CHOICE);
        if (!localLogSources.isEmpty() && logReachScan != null && !logReachScan.complete()) {
            status = "discovering locally reachable logs · " + logReachScan.progressDescription();
            return null;
        }
        if (logScanComplete) return null;
        if (logScan == null) {
            var eligibleLogs = logSources.values().stream().map(source -> GameCatalog.item(source.output()))
                .collect(java.util.stream.Collectors.toSet());
            ItemEntity dropped = client.world.getEntitiesByClass(ItemEntity.class,
                client.player.getBoundingBox().expand(12), entity -> entity.isAlive()
                    && (entity.isOnGround() || entity.isTouchingWater()) && entity.getStack().isIn(ItemTags.LOGS)
                    && eligibleLogs.contains(entity.getStack().getItem()))
                .stream().min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
            droppedLogCandidate = dropped == null ? null : GameCatalog.id(dropped.getStack().getItem());
            logScan = new BlockSearch(client,logSources.keySet(),config.searchRadius,rejectedResources);
        }
        startDiscoveryBudget();
        if (!discoveryBudgetAvailable()) {
            status = "discovering nearby logs · local reach checked";
            return null;
        }
        long priorProgress = logScan.progressToken();
        boolean discoveryComplete = logScan.advance(config.scanBlocksPerTick,
                Math.max(0, discoveryDeadlineNanos - System.nanoTime()));
        if (logScan.progressToken() != priorProgress) actionTicks = 0;
        status = "discovering nearby logs · " + logScan.progressDescription();
        Map<String,List<BlockPos>> grouped = new LinkedHashMap<>();
        Set<ItemId> outputs = new LinkedHashSet<>();
        if (droppedLogCandidate != null) outputs.add(droppedLogCandidate);
        for (BlockPos position : logScan.results()) {
            if (rejectedResources.contains(position)) continue;
            GatherSource source = logSources.get(client.world.getBlockState(position).getBlock());
            if (source == null) continue;
            if (outputs.size() < 64) outputs.add(source.output());
            grouped.computeIfAbsent(source.sourceId(),ignored -> new ArrayList<>()).add(position);
        }
        for (var entry : grouped.entrySet()) {
            if (!discoveredSources.containsKey(entry.getKey()) && discoveredSources.size() >= 64)
                discoveredSources.remove(discoveredSources.keySet().iterator().next());
            List<BlockPos> known = discoveredSources.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>());
            for (BlockPos position : entry.getValue()) if (!known.contains(position) && known.size() < 512) known.add(position);
        }
        for (ItemId output : outputs) {
            if (!attemptedLogCandidates.contains(output) && !logCandidates.contains(output)) logCandidates.addLast(output);
        }
        // Presence is independent of the nearest-512 position cache; dense forests must not hide rare variants.
        for (GatherSource source : new HashSet<>(logSources.values())) {
            if (source.output().equals(droppedLogCandidate)) continue;
            boolean present = source.blocks().stream().anyMatch(id -> logScan.found(Registries.BLOCK.get(GameApi.identifier(id.toString()))));
            if (logScan.complete() && !present) unavailableSources.add(source.sourceId());
        }
        if (discoveryComplete) {
            droppedLogCandidate = null;
            logScan = null;
            logScanComplete = true;
        }
        return logCandidates.peekFirst();
    }

    private boolean tryNextLogPlan(PlanResult result) {
        if (!acquisitionView().anyLogs || logCandidates.isEmpty() || result.blockedReasons().isEmpty()
                || !result.blockedReasons().stream().allMatch(reason -> switch (reason.code()) {
                    case NO_SOURCE, CYCLE, EMPTY_TAG, UNREACHABLE_REQUIREMENT, UNSUPPORTED_SOURCE -> true;
                    default -> false;
                })) return false;
        ItemId rejectedCandidate = logCandidates.removeFirst();
        attemptedLogCandidates.add(rejectedCandidate);
        if (rejectedCandidate.equals(localLogCandidateOutput)) {
            LocalReachScan logReachScan = localReachScan(LocalReachPurpose.LOG_CHOICE);
            if (logReachScan != null && Objects.equals(localReachHint == null ? null : localReachHint.sourceId(), localLogCandidateSourceId))
                logReachScan.skipResult();
            localReachHint = null;
            localLogCandidateOutput = null;
            localLogCandidateSourceId = null;
        }
        planningRetries = 0;
        continueActiveRequest();
        return true;
    }

    private int goalCount() {
        if (!acquisitionView().anyLogs) return ordinaryNativeCommodity(acquisitionView().item)
                ? ordinaryCount(acquisitionView().item) : actions.heldCount(GameCatalog.item(acquisitionView().item));
        int count = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (stack.isIn(ItemTags.LOGS)) count += stack.getCount();
        for (var slot : GOAL_EQUIPMENT_SLOTS) {
            ItemStack stack = client.player.getEquippedStack(slot);
            if (stack.isIn(ItemTags.LOGS)) count = Math.addExact(count, stack.getCount());
        }
        return count;
    }
    private void useMovementProgress(MovementProgressScope scope) {
        if (movement.attachMovementProgress(scope == null ? null : scope.progress))
            lastMovementProgressToken = movement.progressToken();
    }

    private void retireMovementProgressScopes() {
        dropStationStockHint();
        useMovementProgress(null);
        parentMovementScope = auxiliaryMovementScope = null;
    }

    private OwnedStationLedger.Session validateMovementProgressScopes() {
        OwnedStationLedger.Session session = placementProvenance.session().orElse(null);
        boolean retired = false;
        if (parentMovementScope != null
                && !parentMovementScope.matches(acquisitionScope, client.world, client.player, session)) {
            parentMovementScope = null;
            retired = true;
        }
        if (auxiliaryMovementScope != null
                && !auxiliaryMovementScope.matches(acquisitionScope, client.world, client.player, session)) {
            auxiliaryMovementScope = null;
            retired = true;
        }
        if (retired) useMovementProgress(null);
        return session;
    }

    private void syncMovementProgress() {
        OwnedStationLedger.Session session = validateMovementProgressScopes();
        invalidateStationStockHint();
        if (active == null || session == null || client.world != world || paused || editingSettings()
                || config.pauseOnScreen && client.currentScreen != null
                || airRecovery.active() || healthRecovery != null || threats.active() || food.active()
                || nativeAcquisitionActive() || cleanupRun != null || step == null && !exploring) {
            useMovementProgress(null);
            return;
        }
        MovementProgressScope scope = stepAuxiliaryInvestment ? auxiliaryMovementScope : parentMovementScope;
        if (scope != null) useMovementProgress(scope);
        else if (step != null) admitMovementProgress(step, stepAuxiliaryInvestment);
        else admitExplorationMovementProgress();
    }

    private void admitMovementProgress(PlanStep next, boolean auxiliary) {
        OwnedStationLedger.Session session = validateMovementProgressScopes();
        if (active == null || session == null) { useMovementProgress(null); return; }
        ItemId output = next.output();
        List<ItemId> logs = catalog.tags.getOrDefault(LOGS_TAG, List.of());
        // Prerequisite logs retain their unfinished ingredient scope across species choices.
        if (next.kind() == PlanKind.GATHER && logs.contains(output)
                && (acquisitionView().anyLogs || !logs.contains(acquisitionView().item))) output = null;
        MovementDemand demand = new MovementDemand(next.kind(), output,
                next.kind() == PlanKind.PLACE_STATION ? next.station() : null, next.customType());
        MovementProgressScope scope = auxiliary ? auxiliaryMovementScope : parentMovementScope;
        if (scope == null || scope.demand != null && !scope.demand.equals(demand))
            scope = new MovementProgressScope(acquisitionScope, client.world, client.player, session, demand);
        else scope.demand = demand;
        if (auxiliary) auxiliaryMovementScope = scope;
        else {
            parentMovementScope = scope;
            auxiliaryMovementScope = null;
        }
        useMovementProgress(scope);
    }

    private void admitExplorationMovementProgress() {
        OwnedStationLedger.Session session = validateMovementProgressScopes();
        if (active == null || session == null) { useMovementProgress(null); return; }
        if (parentMovementScope == null)
            parentMovementScope = new MovementProgressScope(acquisitionScope, client.world, client.player, session, null);
        auxiliaryMovementScope = null;
        useMovementProgress(parentMovementScope);
    }

    private void begin(PlanStep next, long plannedGeneration, boolean auxiliaryInvestment, long plannedPreferencesVersion,
                       ShieldPlanningIdentity shieldIdentity) {
        if (!planIdentityCurrent(pendingPlanIdentity)) { continueActiveRequest(); return; }
        if (shieldIdentity != null && !shieldIdentityCurrent(shieldIdentity)) {
            deferShieldPreparation = true;
            continueActiveRequest();
            return;
        }
        if (!catalog.ready() || catalog.generation() != plannedGeneration) {
            if (catalog.ready()) continueActiveRequest();
            else status = "waiting for recipe catalog";
            return;
        }
        if (next.kind() == PlanKind.SMELT && ordinaryNativeCommodity(next.output())) {
            Map<String, String> attributes = new HashMap<>(next.attributes());
            attributes.put("ordinaryInputOnly", "true");
            next = new PlanStep(next.kind(), next.sourceId(), next.output(), next.outputCount(), next.operationCount(),
                    next.requirements(), next.candidateBlocks(), next.recipeType(), next.recipeWidth(), next.recipeHeight(),
                    next.station(), next.customType(), attributes, next.nativeWork());
        }
        MiningContinuation continuation = miningContinuation;
        resetAction();
        if (cleanupReturnBarrier()) return;
        stepPlanIdentity = pendingPlanIdentity;
        if (next.kind() == PlanKind.GATHER && continuation != null
                && continuation.matches(client.world, client.world.getRegistryKey(), acquisitionScope, next.sourceId()))
            miningContinuation = continuation;
        prepareStationAttempts(next);
        stationDiscoveryDone = false;
        admitMovementProgress(next, auxiliaryInvestment);
        step = next; stepCatalogGeneration = plannedGeneration; actionTicks = 0;
        stepAuxiliaryInvestment = auxiliaryInvestment;
        stepShieldIdentity = shieldIdentity;
        lastMovementProgressToken = movement.progressToken();
        stepPreferencesVersion = plannedPreferencesVersion;
        status = (auxiliaryInvestment ? "preparing for task · " : "") + next.kind() + " " + next.sourceId();
        if (config.debugLogging && next.kind() == PlanKind.SMELT)
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] SMELT_REQUIREMENTS source={} selected={}", next.sourceId(), next.requirements());
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] STEP kind={} source={} output={} count={} operations={} auxiliary={}",
                next.kind(), next.sourceId(), next.output(), next.outputCount(), next.operationCount(), auxiliaryInvestment);
        MovementProgressScope stockScope = projectLogGatherScope();
        if (stockScope != null && stockScope.sampledTableCount < 0)
            stockScope.sampledTableCount = actions.count(GameCatalog.item(CRAFTING_TABLE_ITEM));
        baseline = next.output() == null ? 0 : actions.count(GameCatalog.item(next.output()));
        lastObservedCount = baseline; lastSmeltProgress = 0;
        refreshNavigationProtection();
    }
    int explorationAttemptsMade() { return frontier == null ? 0 : frontier.attempts(); }
    boolean resourceRejected(BlockPos position) { return rejectedResources.contains(position); }

    private boolean canExplore(PlanningOutcome outcome) {
        return config.allowExploration && !unavailableSources.isEmpty() && outcome.explorationProven();
    }
    private void beginExploration() {
        if (!config.allowExploration) throw new IllegalStateException("Resource not found nearby; exploration is disabled");
        if (unavailableSources.isEmpty()) throw new IllegalStateException("No known gathering source is available for this goal");
        if (beginCarryTable()) return;
        resetAction();
        admitExplorationMovementProgress();
        BlockPos feet = client.player.getBlockPos();
        if (frontier == null) frontier = new ExplorationFrontier(feet.getX(), feet.getZ(),
            config.explorationAttempts, config.explorationDistance);
        beginFrontierAtPlayer(feet);
        exploring = true; status = "finding a safe exploration route";
    }
    private void beginFrontierAtPlayer(BlockPos feet) {
        int feetY16 = GameTerrain.quantizedFeetY16(client.player.getY());
        if (feetY16 == GameTerrain.INVALID_FEET_Y16) {
            throw new MovementController.NavigationFailure("Exploration requires a modeled sixteenth-block feet height");
        }
        terrain.beginSearch(); frontier.beginAt16(feet.getX(), feetY16, feet.getZ());
    }
    private void explore() {
        if (!config.allowExploration) throw new IllegalStateException("Exploration was disabled");
        if (!nativeAcquisitionActive() && goalCount() >= acquisitionView().count) { finishGoal(); return; }
        movement.observeConfirmedProgress();
        long movementProgress = movement.progressToken();
        if (movementProgress != lastMovementProgressToken) {
            lastMovementProgressToken = movementProgress;
            explorationTicks = 0;
        }
        if (++explorationTicks > config.actionTimeoutTicks) {
            retryExploration(); return;
        }
        if (explorationMoving) {
            status = "exploring " + frontier.attempts() + "/" + config.explorationAttempts + " · " + movement.status();
            try {
                if (movement.tick()) {
                    var waypoint = frontier.waypoint();
                    BlockPos feet = client.player.getBlockPos();
                    int feetY16 = GameTerrain.quantizedFeetY16(client.player.getY());
                    if (feet.getX() != waypoint.x() || feet.getZ() != waypoint.z()
                            || feetY16 == GameTerrain.INVALID_FEET_Y16 || feetY16 != waypoint.feetY16()) {
                        throw new MovementController.NavigationFailure("Exploration segment stopped before its waypoint");
                    }
                    movement.stop(); unavailableSources.clear(); resetLogDiscovery(); resetAction(); planningRetries = 0; continueActiveRequest();
                }
            } catch (MovementController.NavigationFailure blocked) { retryExploration(); }
            return;
        }
        var state = frontier.advance(terrain,32,1_000_000);
        if (state == ExplorationFrontier.Status.EXHAUSTED) {
            throw new IllegalStateException("No safe unexplored waypoint remains within the exploration bounds after "
                + frontier.attempts() + " attempts; move to another area or adjust exploration limits"
                + (lastResourceFailure == null ? "" : " · Last resource attempt: " + lastResourceFailure));
        }
        if (state == ExplorationFrontier.Status.READY) {
            var point = frontier.waypoint();
            refreshNavigationProtection(); movement.startExploration(point);
            lastMovementProgressToken = movement.progressToken();
            explorationMoving = true;
        }
    }
    private void retryExploration() {
        movement.stop(); explorationMoving = false; explorationTicks = 0;
        lastMovementProgressToken = movement.progressToken();
        BlockPos feet = client.player.getBlockPos();
        beginFrontierAtPlayer(feet);
        status = "trying another safe exploration waypoint";
    }
    private void rejectResource(String reason) {
        lastResourceFailure = target.getX() + "," + target.getY() + "," + target.getZ() + ": " + reason;
        if (config.debugLogging)
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info("[Lodekeeper] RESOURCE_REJECT reason={}", lastResourceFailure);
        movement.stop(); actions.cancel(); moving = false;
        if (rejectedResources.size() >= 128) throw new IllegalStateException("Resource approach retry limit reached");
        rejectedResources.add(target.toImmutable()); target = null; scan = null; clearGatherAttempt(); actionTicks = 0;
        status = "trying another reachable resource";
    }
    private boolean shouldRejectTimedOutGather() {
        if (step == null || step.kind() != PlanKind.GATHER || target == null || movingPickup
                || !config.allowBreaking || transactionInProgress() || crafting != null || stonecutting != null || smelting != null
                || openingStation || hasOwnedStationHandlerOpen()) return false;
        if (!hasLoadedChunk(target)) return false;
        Block current = client.world.getBlockState(target).getBlock();
        return step.candidateBlocks().stream().map(id -> Registries.BLOCK.get(GameApi.identifier(id.toString())))
                .anyMatch(block -> block == current);
    }
    private void observeGatherRemoval() {
        if (gatherMineTarget == null) return;
        if (target == null || !gatherMineTarget.equals(target)) { clearGatherAttempt(); return; }
        if (!hasLoadedChunk(gatherMineTarget)) return;
        BlockState current = client.world.getBlockState(gatherMineTarget);
        if (current.isAir()) {
            movement.recordConfirmedWorldAction();
            target = null;
            actions.cancel();
            clearGatherAttempt();
        } else if (current.getBlock() != gatherMineBlock) {
            clearGatherAttempt();
        }
    }
    private void clearGatherAttempt() {
        gatherMineTarget = null;
        gatherMineBlock = null;
    }
    private boolean hasLoadedChunk(BlockPos position) {
        return client.world != null && client.world.getChunkManager().getChunk(
                position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }
    private void startDiscoveryBudget() {
        if (discoveryBudgetStarted) return;
        discoveryBudgetStarted = true;
        discoveryDeadlineNanos = System.nanoTime() + DISCOVERY_BUDGET_NANOS;
    }
    private boolean discoveryBudgetAvailable() {
        return discoveryBudgetStarted && System.nanoTime() < discoveryDeadlineNanos;
    }
    private LocalReachScan localReachScan(LocalReachPurpose purpose) {
        return switch (purpose) {
            case LOG_CHOICE -> localLogReachScan;
            case RECIPE_WOOD -> localIngredientWoodReachScan;
            case GATHER -> localGatherReachScan;
        };
    }
    private void localReachScan(LocalReachPurpose purpose, LocalReachScan scan) {
        switch (purpose) {
            case LOG_CHOICE -> localLogReachScan = scan;
            case RECIPE_WOOD -> localIngredientWoodReachScan = scan;
            case GATHER -> localGatherReachScan = scan;
        }
    }
    private BlockPos findLocalReachable(Set<Block> blocks, Set<String> sourceIds, LocalReachPurpose purpose) {
        if (client.world == null || client.player == null || active == null || catalog == null) return null;
        BlockPos origin = client.player.getBlockPos();
        var eye = client.player.getEyePos();
        Set<BlockPos> rejected = Set.copyOf(rejectedResources);
        Object dimension = client.world.getRegistryKey();
        LocalReachScan localReachScan = localReachScan(purpose);
        if (localReachScan == null || !localReachScan.matches(client.world, dimension, acquisitionScope, catalog.generation(),
                blocks, sourceIds, rejected, origin)) {
            localReachScan = new LocalReachScan(client.world, dimension, acquisitionScope, catalog.generation(), blocks,
                    sourceIds, rejected, origin, eye.x, eye.y, eye.z);
            localReachScan(purpose, localReachScan);
        }
        startDiscoveryBudget();
        if (localReachScan.result() != null) {
            if (isCurrentLocalCandidate(localReachScan.result(), blocks)) return localReachScan.result();
            localReachScan.restart();
        }
        if (localReachScan.negativeExpired(System.nanoTime())) localReachScan.restart();
        long before = localReachScan.progressToken();
        BlockPos result = localReachScan.advance(this, discoveryDeadlineNanos);
        if (before != localReachScan.progressToken()) actionTicks = 0;
        return result;
    }
    private boolean selectedGatherToolAvailable() {
        SelectedToolRequirement tool = step.requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
        return tool == null || actions.hasTool(tool);
    }
    private boolean isCurrentLocalCandidate(BlockPos position, Set<Block> blocks) {
        return !rejectedResources.contains(position) && hasLoadedChunk(position)
                && blocks.contains(client.world.getBlockState(position).getBlock()) && actions.hit(position) != null;
    }
    private boolean hasSatisfiedToolRequirements(GatherSource source) {
        for (Requirement requirement : source.requirements()) {
            if (!(requirement instanceof ToolRequirement tool)) continue;
            boolean satisfied = false;
            for (ItemSelector selector : tool.tools().alternatives()) {
                List<ItemId> choices = selector instanceof ItemSelector.Exact exact
                        ? List.of(exact.item())
                        : catalog.tags.getOrDefault(((ItemSelector.Tag) selector).tag(), List.of());
                for (ItemId item : choices) {
                    if (actions.hasTool(new SelectedToolRequirement(item, tool.minimumDurability(), tool.purpose()))) {
                        satisfied = true;
                        break;
                    }
                }
                if (satisfied) break;
            }
            if (!satisfied) return false;
        }
        return true;
    }
    private BlockPos localReachHint(Set<Block> blocks, String sourceId) {
        LocalReachHint hint = localReachHint;
        if (hint == null) return null;
        Set<BlockPos> rejected = Set.copyOf(rejectedResources);
        if (client.world == null || client.player == null || catalog == null || hint.world() != client.world
                || !Objects.equals(hint.dimension(), client.world.getRegistryKey()) || hint.acquisition() != acquisitionScope
                || hint.generation() != catalog.generation() || !hint.rejected().equals(rejected)
                || !hint.origin().equals(client.player.getBlockPos())) {
            localReachHint = null;
            return null;
        }
        if (!hint.sourceId().equals(sourceId)) return null;
        if (!blocks.contains(hint.block()) || !isCurrentLocalCandidate(hint.position(), blocks)) {
            localReachHint = null;
            return null;
        }
        return hint.position();
    }
    private void rememberDiscoveredSource(String sourceId, BlockPos position, Block block) {
        if (!discoveredSources.containsKey(sourceId) && discoveredSources.size() >= 64)
            discoveredSources.remove(discoveredSources.keySet().iterator().next());
        List<BlockPos> known = discoveredSources.computeIfAbsent(sourceId, ignored -> new ArrayList<>());
        known.remove(position);
        known.add(0, position.toImmutable());
        while (known.size() > 512) known.remove(known.size() - 1);
        nearbyResources.observeDiscoveredSource(sourceId, position, block);
    }
    private void gather() {
        if (!inventoryAdmissionSafe()) { input.release(); return; }
        if (beginPreviousCommandTable(step)) return;
        if (!config.allowBreaking) throw new IllegalStateException("Gathering requires allowBreaking=true");
        Set<Block> blocks = new LinkedHashSet<>();
        GatherCandidates.forStep(catalog.snapshot(), step, unavailableSources).forEach(id -> {
            Block block = Registries.BLOCK.get(GameApi.identifier(id.toString()));
            if (block != Blocks.AIR) blocks.add(block);
        });
        SelectedToolRequirement tool = step.requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
        if (tool != null && !actions.hasTool(tool)) { resetAction(); continueActiveRequest(); return; }
        refreshNavigationProtection();
        Set<BlockPos> priorRejectedPositions = Set.of();
        if (miningContinuation != null) {
            if (miningContinuation.matches(client.world, client.world.getRegistryKey(), acquisitionScope, step.sourceId()))
                priorRejectedPositions = miningContinuation.rejectedPositions();
            else miningContinuation = null;
        }
        if (config.debugLogging && !priorRejectedPositions.isEmpty())
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] MINING_CONTINUE source={} retainedRejects={}", step.sourceId(), priorRejectedPositions.size());
        movement.startMining(blocks.toArray(Block[]::new), GameCatalog.item(step.output()),
                Math.addExact(baseline, step.outputCount()), tool, priorRejectedPositions);
        moving = true; movingPickup = false; status = movement.status();
    }

    private void refreshNavigationProtection() {
        Set<net.minecraft.item.Item> reserved = new HashSet<>();
        protectedCounts(captureInventoryCounts(), acquisitionView() == null ? null : acquisitionView().item).keySet()
                .forEach(item -> reserved.add(GameCatalog.item(item)));
        if (acquisitionView() != null) reserved.add(GameCatalog.item(acquisitionView().item));
        if (step != null) {
            if (step.output() != null) reserved.add(GameCatalog.item(step.output()));
            for (SelectedRequirement requirement : step.requirements()) {
                if (requirement instanceof SelectedItemRequirement item) reserved.add(GameCatalog.item(item.item()));
                if (requirement instanceof SelectedToolRequirement tool) reserved.add(GameCatalog.item(tool.item()));
                if (requirement instanceof SelectedStationRequirement station)
                    reserved.add(Registries.BLOCK.get(GameApi.identifier(station.station().toString())).asItem());
            }
        }
        Set<Block> stations = new HashSet<>(List.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.SMOKER,
                Blocks.BLAST_FURNACE, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.HOPPER,
                Blocks.DISPENSER, Blocks.DROPPER, Blocks.ENDER_CHEST));
        for (Block block : Registries.BLOCK) if (block.getDefaultState().hasBlockEntity()) stations.add(block);
        for (BlockPos position : knownStations.values()) if (client.world != null)
            stations.add(client.world.getBlockState(position).getBlock());
        movement.updateProtection(reserved, stations);
    }
    private void placeStation() {
        if (step != null && Boolean.parseBoolean(step.attributes().getOrDefault("shieldPreparation", "false"))
                && (!shieldStepCurrent() || !config.autoDefend || !config.autoUseShield || !config.autoCraftShield)
                && (stationPlacementWait == null || !stationPlacementWait.sent())) {
            resetAction(); continueActiveRequest(); return;
        }
        if (client.player == null || client.world == null || active == null || step == null
                || step.kind() != PlanKind.PLACE_STATION || step.station() == null) {
            cancelStationPlacement();
            pause("Station placement lost its active plan; no placement was sent");
            return;
        }
        Block block = Registries.BLOCK.get(GameApi.identifier(step.station().toString()));
        if (stationPlacementWait != null) {
            StationPlacementWait waiting = stationPlacementWait;
            if (active != waiting.request() || waiting.acquisition() != acquisitionScope || block != waiting.block() || step == null || step.kind() != PlanKind.PLACE_STATION
                    || !placementProvenance.session().filter(waiting.session()::equals).isPresent()) {
                pause("Station placement context changed; ownership was not assumed");
                return;
            }
            var confirmed = placementProvenance.records().stream()
                    .filter(record -> record.ticket() == waiting.hand().ticket
                            && waiting.hand().settled && record.jobToken() == active.jobToken()
                            && record.session().equals(waiting.session())
                            && stationPosition(record).equals(waiting.position())
                            && record.expectedBlockId().toString().equals(step.station().toString())).findFirst();
            if (waiting.sent()) {
                if (confirmed.isPresent() && hasLoadedChunk(waiting.position())
                        && client.world.getBlockState(waiting.position()).isOf(block)) {
                    knownStations.put(step.station(), waiting.position());
                    terrain.changed();
                    completeStep();
                } else if (!placementProvenance.serializesBotActions()) {
                    pause("Station placement has no confirmed server ownership; further placement is quarantined");
                } else status = "waiting for the server station placement and inventory receipts";
                return;
            }
            target = waiting.position();
            sendStationPlacement(block);
            return;
        }
        BlockPos readinessPosition = target == null ? client.player.getBlockPos() : target;
        if (placementProvenance.readinessStatus(active.jobToken(), readinessPosition, block).orElse(null)
                == PlacementProvenance.ReservationStatus.QUARANTINED) {
            pause("Station placement is quarantined after uncertain server evidence; no further placement will be sent in this session");
            return;
        }
        BlockPos existing = findExistingStation(block);
        if (existing != null) {
            knownStations.put(step.station(), existing);
            terrain.changed();
            completeStep();
            return;
        }
        if (!config.allowBuilding) {
            stationDiscoveryDone = false;
            throw new IllegalStateException("Station " + step.station() + " is not available nearby, and allowBuilding=false; enable it with config allowBuilding true");
        }
        if (stationRoom.active()) {
            BlockPos prepared = stationRoom.site();
            if (!config.allowBreaking) {
                stationRoom.stop();
                throw stationPlacementFailure("room preparation requires allowBreaking=true", prepared);
            }
            try {
                if (!stationRoom.tick()) { status = stationRoom.status(); return; }
                stationRoom.stop();
                terrain.changed();
                target = prepared;
            } catch (RuntimeException failure) {
                stationRoom.stop();
                rejectStationSite(prepared);
                message("Station room preparation is retrying: " + failure.getMessage());
                return;
            }
        }
        if (target != null && client.world.getBlockState(target).isOf(block)) { knownStations.put(step.station(), target); terrain.changed(); completeStep(); return; }
        if (target != null && !safeStationStructure(target)) rejectStationSite(target);
        if (stationPlacementFailures >= MAX_STATION_PLACEMENT_ATTEMPTS)
            throw stationPlacementFailure("placement attempt limit reached", client.player.getBlockPos());
        if (target == null) target = findStationCandidate(true);
        if (target == null) target = findStationCandidate(false);
        if (target == null) {
            BlockPos preparation = config.allowBreaking && !isTravelFoodPreparation(step) ? findStationPreparation() : null;
            if (preparation != null) {
                if (stationRoom.begin(preparation)) status = stationRoom.status();
                else rejectStationSite(preparation);
                return;
            }
            stationDiscoveryDone = false;
            throw stationPlacementFailure("no visible, reachable full-floor placement site nearby", client.player.getBlockPos());
        }
        if (!actions.canPlaceAt(target)) {
            if (target.equals(stationApproachTarget)) {
                rejectStationSite(target);
                if (stationPlacementFailures >= MAX_STATION_PLACEMENT_ATTEMPTS)
                    throw stationPlacementFailure("no usable interaction stance at explored placement sites", client.player.getBlockPos());
                return;
            }
            stationApproachTarget = target;
            refreshNavigationProtection();
            movement.startPlacement(target);
            moving = true;
            return;
        }
        sendStationPlacement(block);
    }

    private StationHandAdmission captureStationHandAdmission() {
        if (!client.isOnThread() || client.player == null || client.world == null
                || client.interactionManager == null || client.getNetworkHandler() == null || active == null
                || client.getNetworkHandler().getConnection() == null || !client.getNetworkHandler().getConnection().isOpen()
                || step == null || catalog == null) return null;
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        if (owner == null) return null;
        return new StationHandAdmission(acquisitionScope, stepPlanIdentity, active, step, client.player, client.world, client.getNetworkHandler(),
                client.getNetworkHandler().getConnection(), client.player.currentScreenHandler, client.player.input,
                placementProvenance.session().orElse(null), catalog, catalog.generation(), owner, owner.captureSession(),
                owner.policyGeneration());
    }

    private boolean admitStationHandEffect(StationHandAdmission captured, BlockPos position, Block block, boolean allowOwnedInput) {
        if (captured == null || !client.isOnThread() || client.player == null || !client.player.isAlive()
                || client.world == null || client.world != world || client.interactionManager == null
                || active != captured.request() || !scopeCurrent(captured.acquisition())
                || captured.planning() != stepPlanIdentity || !planIdentityCurrent(stepPlanIdentity)
                || cancelledRequest == active || step != captured.plannedStep() || step.kind() != PlanKind.PLACE_STATION
                || step.station() == null || !step.station().toString().equals(Registries.BLOCK.getId(block).toString())
                || target == null || !target.equals(position) || paused || stopAfterStep || editingSettings()
                || !config.allowBuilding || client.player.getHealth() <= config.pauseBelowHealth
                || healthRecovery != null || airRecovery.active() || airRecovery.ready()
                || threats.active() || food.active() || nativeAcquisitionActive() || animalAcquisitionPending
                || equipment.active() || stationRecovery.active() || stationRecovery.pickupRetained() || stationRoom.active()
                || otherTransactionInProgress() || openingStation || cleanupRun != null || moving || explorationMoving
                || catalog == null || catalog != captured.catalog() || !catalog.ready() || !catalog.usesCurrentProvider()
                || catalog.generation() != captured.catalogGeneration() || stepCatalogGeneration != catalog.generation()
                || client.player != captured.player() || client.world != captured.world()
                || client.getNetworkHandler() != captured.network() || client.getNetworkHandler() == null
                || client.getNetworkHandler().getConnection() != captured.connection()
                || client.getNetworkHandler().getConnection() == null || !client.getNetworkHandler().getConnection().isOpen()
                || client.currentScreen != null || client.player.currentScreenHandler != captured.menu()
                || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.currentScreenHandler.getCursorStack().isEmpty() || client.player.isUsingItem()
                || manualStationInput() || client.player.input == null || client.player.input != captured.expectedInput()
                || !(client.player.input.getClass() == net.minecraft.client.input.KeyboardInput.class
                    || allowOwnedInput && client.player.input == input)
                || ClientAccess.selectedSlot(client.player.getInventory()) < 0
                || ClientAccess.selectedSlot(client.player.getInventory()) > 8
                || captured.session() == null || !placementProvenance.session().filter(captured.session()::equals).isPresent()
                || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != captured.nativeOwner()
                || !captured.nativeOwner().isCurrent(captured.nativeSession())
                || captured.nativeSession().world() != client.world
                || captured.nativeOwner().policyGeneration() != captured.policyGeneration()
                || isFoodPreparation(step) && !config.autoEat
                || Boolean.parseBoolean(step.attributes().getOrDefault("shieldPreparation", "false"))
                    && (!shieldStepCurrent() || !config.autoDefend || !config.autoUseShield || !config.autoCraftShield))
            return false;
        if (isTravelFoodPreparation(captured.plannedStep()) && (stationPlacementHand == null || !stationPlacementHand.sent)
                && !travelFoodPlacementCurrent(captured.acquisition(), captured.plannedStep(), block)) return false;
        movement.checkAirRecoveryOwnership();
        if (!movement.finishCancellation() || !stationHandNativeQuiescent(captured.nativeOwner())) return false;
        return hasLoadedChunk(position) && safeStationStructure(position) && actions.canPlaceAt(position)
                && protection.mayPlace(position);
    }

    private boolean stationHandNativeQuiescent(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner) {
        if (owner == null || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != owner
                || owner.getPrimaryBaritone() == null) return false;
        var bot = owner.getPrimaryBaritone();
        var pathing = bot.getPathingBehavior();
        if (pathing.hasPath() || pathing.isPathing() || pathing.getInProgress().isPresent()) return false;
        dev.lodekeeper.navigation.kernel.api.process.IBaritoneProcess[] processes = {
                bot.getCustomGoalProcess(), bot.getMineProcess(), bot.getFollowProcess(), bot.getBuilderProcess(),
                bot.getExploreProcess(), bot.getFarmProcess(), bot.getGetToBlockProcess(), bot.getElytraProcess()
        };
        for (var process : processes) if (process.isActive()) return false;
        for (var key : dev.lodekeeper.navigation.kernel.api.utils.input.Input.values())
            if (bot.getInputOverrideHandler().isInputForcedDown(key)) return false;
        return true;
    }

    private boolean travelFoodPlacementCurrent(AcquisitionScope scope, PlanStep planned, Block block) {
        if (!travelFoodDemandCurrent(scope) || !scopeCurrent(scope) || step != planned
                || !planIdentityCurrent(stepPlanIdentity) || paused || stopAfterStep) return false;
        Map<ItemId, Integer> reserved = new HashMap<>(shieldExecutionReservations());
        Map<ItemId, Integer> needed = new HashMap<>();
        planned.attributes().forEach((key, value) -> {
            if (key.startsWith("travelFoodReserved:")) reserved.merge(ItemId.parse(key.substring(19)), Integer.parseInt(value), Math::max);
            if (key.startsWith("travelFoodFuture:")) needed.merge(ItemId.parse(key.substring(17)), Integer.parseInt(value), Math::addExact);
        });
        needed.merge(GameCatalog.id(block.asItem()), 1, Math::addExact);
        Map<ItemId, Integer> ordinary = ordinaryTravelFoodCounts();
        for (var need : needed.entrySet())
            if (ordinary.getOrDefault(need.getKey(), 0) - (long) reserved.getOrDefault(need.getKey(), 0) < need.getValue()) return false;
        return true;
    }

    private void sendStationPlacement(Block block) {
        if (isTravelFoodPreparation(step) && !travelFoodPlacementCurrent(acquisitionScope, step, block)) {
            travelFoodInvalidated = true;
            cancelStationPlacement();
            if (stationPlacementHand == null) finishAcquisitionPhase(travelFoodScope);
            return;
        }
        if (!admitStationHandEffect(captureStationHandAdmission(), target, block, stationPlacementHand == null)) {
            if (stationPlacementHand != null) cancelStationPlacement();
            status = "station placement admission yielded without a new effect"; return;
        }
        if (Boolean.parseBoolean(step.attributes().getOrDefault("shieldPreparation", "false"))) {
            if (!shieldStepCurrent()) {
                deferShieldPreparation = true;
                cancelStationPlacement();
                resetAction();
                continueActiveRequest();
                return;
            }
            ItemId table = GameCatalog.id(block.asItem());
            Map<ItemId, Integer> reserved = new HashMap<>(shieldExecutionReservations());
            step.attributes().forEach((key, value) -> {
                if (key.startsWith("shieldReserved:"))
                    reserved.merge(ItemId.parse(key.substring(15)), Integer.parseInt(value), Math::max);
            });
            ItemStack ordinaryTable = new ItemStack(GameCatalog.item(table));
            int ordinaryTables = 0;
            for (int index = 0; index < 36; index++) {
                ItemStack stack = client.player.getInventory().getStack(index);
                if (!stack.isEmpty() && GameApi.canCombine(stack, ordinaryTable))
                    ordinaryTables += stack.getCount();
            }
            if (ordinaryTables <= reserved.getOrDefault(table, 0)) {
                cancelStationPlacement();
                resetAction();
                continueActiveRequest();
                return;
            }
        }
        if (stationPlacementHand == null) {
            StationHandAdmission beforeRelease = captureStationHandAdmission();
            if (!admitStationHandEffect(beforeRelease, target, block, true)) {
                status = "waiting for safe native station hand ownership"; return;
            }
            input.release();
            StationHandAdmission captured = captureStationHandAdmission();
            if (!admitStationHandEffect(captured, target, block, false)) {
                status = "waiting for ordinary native keyboard input before station placement"; return;
            }
            BlockPos position = target.toImmutable();
            stationPlacementHand = new PlayerActions.StationPlacementHand(active.jobToken(), position, block,
                    captured.expectedInput(), captured.nativeOwner(), captured.nativeSession(),
                    () -> admitStationHandEffect(captured, position, block, false));
        }
        PlayerActions.PlacementAttempt attempt = actions.placeStation(target, block, active.jobToken(),
                Boolean.parseBoolean(step.attributes().getOrDefault("shieldPreparation", "false")), stationPlacementHand);
        switch (attempt) {
            case YIELDED -> {
                cancelStationPlacement();
                status = "station hand admission yielded; draining without a placement attempt";
            }
            case SENT, WAITING_FOR_PROVENANCE -> {
                var session = placementProvenance.session().orElseThrow();
                stationPlacementWait = new StationPlacementWait(acquisitionScope, active, target, block, session,
                        attempt == PlayerActions.PlacementAttempt.SENT, stationPlacementHand);
                status = attempt == PlayerActions.PlacementAttempt.SENT
                        ? "waiting for confirmed station ownership"
                        : "waiting for the server inventory baseline before station placement";
            }
            case INVENTORY_TIMEOUT -> pause("Station inventory confirmation timed out (" + placementProvenance.inventoryReadiness() + "); the goal is preserved");
            case QUARANTINED -> pause("Station placement is quarantined after uncertain server evidence; no further placement will be sent in this session");
            case REJECTED -> {
                cancelStationPlacement();
                BlockPos rejected = target;
                rejectStationSite(rejected);
                if (stationPlacementFailures >= MAX_STATION_PLACEMENT_ATTEMPTS)
                    throw stationPlacementFailure("placement was rejected at " + rejected, rejected);
            }
        }
    }

    private BlockPos findExistingStation(Block block) {
        BlockPos preferred = nearbyStations.closestPreferred(step.station(), unreachableStations, rejectedStationSites);
        if (preferred != null) return preferred;
        if (stationDiscoveryDone) return null;
        stationDiscoveryDone = true;
        BlockPos known = knownStations.get(step.station());
        if (known != null && known.getSquaredDistance(client.player.getBlockPos()) <= 256 && !unreachableStations.contains(known) && !rejectedStationSites.contains(known) && hasLoadedChunk(known) && client.world.getBlockState(known).isOf(block)) return known;
        BlockPos player = client.player.getBlockPos();
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            BlockPos candidate = player.add(dx, dy, dz);
            if (unreachableStations.contains(candidate) || rejectedStationSites.contains(candidate) || !hasLoadedChunk(candidate) || !client.world.getBlockState(candidate).isOf(block)) continue;
            double distance = candidate.getSquaredDistance(player);
            if (distance < closestDistance) { closest = candidate; closestDistance = distance; }
        }
        return closest;
    }

    private BlockPos findStationCandidate(boolean requireReach) {
        BlockPos player = client.player.getBlockPos();
        BlockPos closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = -6; dy <= 4; dy++) {
            BlockPos candidate = player.add(dx, dy, dz);
            double distance = candidate.getSquaredDistance(player);
            if (distance >= closestDistance || rejectedStationSites.contains(candidate)
                    || client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(candidate))) continue;
            if (!safeStationStructure(candidate) || requireReach && !actions.canPlaceAt(candidate)) continue;
            closest = candidate;
            closestDistance = distance;
        }
        return closest;
    }

    private BlockPos findStationPreparation() {
        BlockPos player = client.player.getBlockPos();
        BlockPos closest = null;
        double distance = Double.POSITIVE_INFINITY;
        Map<String, Integer> refusals = config.debugLogging ? new TreeMap<>() : null;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos candidate = player.add(dx, dy, dz);
            double nextDistance = candidate.getSquaredDistance(player);
            if (nextDistance >= distance || rejectedStationSites.contains(candidate)) continue;
            String problem = stationRoom.preparationProblemAt(candidate);
            if (problem != null) {
                if (refusals != null) refusals.merge(problem, 1, Integer::sum);
                continue;
            }
            closest = candidate;
            distance = nextDistance;
        }
        if (closest == null && refusals != null)
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] STATION_ROOM exhausted position={} allowBreaking={} refusals={}",
                    new net.minecraft.util.math.Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()), config.allowBreaking, refusals);
        return closest;
    }

    private void prepareStationAttempts(PlanStep next) {
        if (next.kind() != PlanKind.PLACE_STATION) return;
        if (stationPlacementOwner != acquisitionScope || !Objects.equals(stationPlacementStation, next.station())) {
            rejectedStationSites.clear();
            stationPlacementFailures = 0;
            stationPlacementOwner = acquisitionScope;
            stationPlacementStation = next.station();
        }
    }

    private boolean safeStationStructure(BlockPos candidate) {
        if (client.world.getChunkManager().getChunk(candidate.getX() >> 4, candidate.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, false) == null) return false;
        var state = client.world.getBlockState(candidate);
        return state.getFluidState().isEmpty() && !state.hasBlockEntity()
                && !state.isOf(Blocks.FIRE) && !state.isOf(Blocks.SOUL_FIRE) && !state.isOf(Blocks.POWDER_SNOW)
                && state.isReplaceable() && actions.safePlacementSupport(candidate.down());
    }

    private void rejectStationSite(BlockPos rejected) {
        if (config.debugLogging)
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] STATION_SITE rejected site={} player={} box={} overlap={} placeable={} roomProblem={}",
                    rejected, new net.minecraft.util.math.Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()), client.player.getBoundingBox(),
                    client.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(rejected)), actions.canPlaceAt(rejected),
                    stationRoom.preparationProblemAt(rejected));
        rejectedStationSites.add(rejected.toImmutable());
        stationPlacementFailures++;
        stationApproachTarget = null;
        target = null;
    }

    private IllegalStateException stationPlacementFailure(String reason, BlockPos position) {
        Block block = Registries.BLOCK.get(GameApi.identifier(step.station().toString()));
        String item = Registries.ITEM.getId(block.asItem()).toString();
        return new IllegalStateException("Cannot place required station " + step.station() + " at " + position
                + ": " + reason + "; required " + item + " (inventory " + actions.count(block.asItem())
                + "), tried " + stationPlacementFailures + " site(s)");
    }
    private boolean stationReady() {
        var handler = client.player.currentScreenHandler;
        if (step.kind() == PlanKind.SMELT && step.station() == null) {
            throw new IllegalStateException("Smelting plan omitted its cooking station");
        }
        if (step.kind() == PlanKind.SMELT && !isSupportedCookingStation(step.station())) {
            throw new IllegalStateException("Unsupported planned cooking station " + step.station());
        }
        if (openingStation) {
            boolean correct = step.kind() == PlanKind.CRAFT
                    ? (isStonecuttingStep() ? handler.getClass() == net.minecraft.screen.StonecutterScreenHandler.class
                        : handler instanceof net.minecraft.screen.CraftingScreenHandler)
                    : step.kind() == PlanKind.SMELT && isExactCookingStationHandler(handler, step.station());
            if (correct) {
                if (handler != stationOpeningFrom) ownedStationHandler = handler;
                openingStation = false;
                stationOpenTicks = 0;
                stationOpeningFrom = null;
                return true;
            }
            if (!(handler instanceof PlayerScreenHandler)) {
                String container = step.kind() == PlanKind.SMELT ? "cooking station" : "container";
                throw new IllegalStateException("An unexpected " + container + " opened");
            }
            return false;
        }
        if (step.station() == null) {
            if (handler != client.player.playerScreenHandler) throw new IllegalStateException("Close your current container first");
            return true;
        }
        if (!(handler instanceof PlayerScreenHandler)) throw new IllegalStateException("Close your current container first");
        BlockPos station = knownStations.get(step.station());
        if (station == null) throw new IllegalStateException("Required station disappeared");
        if (actions.hit(station) == null) { refreshNavigationProtection(); movement.startInteraction(station); moving = true; return false; }
        stationOpeningFrom = handler;
        openingStation = actions.use(station);
        stationOpenTicks = 0;
        if (!openingStation) stationOpeningFrom = null;
        return false;
    }
    private boolean isStonecuttingStep() {
        return step != null && STONECUTTER.equals(step.station());
    }
    private void stonecut() {
        if (stonecutting == null) {
            StonecuttingWork work = catalog.stonecuts.get(step.sourceId());
            if (work == null || !catalog.ready() || stepCatalogGeneration != catalog.generation())
                throw new IllegalStateException("Stonecutting recipe disappeared or changed before it could start");
            if (!stationReady()) return;
            stonecutting = new StonecuttingAction(client, actions, work, step,
                    () -> catalog != null && catalog.ready() && stepCatalogGeneration == catalog.generation()
                            && catalog.usesCurrentProvider());
        }
        if (shouldDrainActiveTransaction()) stonecutting.requestDrain();
        if (stonecutting.tick()) completeStep();
    }
    private void craft() {
        if (isStonecuttingStep()) { stonecut(); return; }
        if (crafting == null) {
            if (!stationReady()) return;
            var recipe = catalog.recipes.get(step.sourceId());
            if (recipe == null || stepCatalogGeneration != catalog.generation()) throw new IllegalStateException("Recipe disappeared or changed before crafting could start");
            crafting = new CraftingAction(client, actions, recipe, step,
                    () -> catalog != null && catalog.ready() && catalog.generation() == stepCatalogGeneration
                            && catalog.usesCurrentProvider(), optionalPreparationAuthority(), this::shieldExecutionReservations,
                    () -> config.autoDefend && config.autoUseShield && config.autoCraftShield,
                    Set.copyOf(catalog.tags.getOrDefault(PLANKS_TAG, List.of())),
                    () -> config.shieldIronReserve, () -> config.shieldPlankReserve);
        }
        if (shouldDrainActiveTransaction()) crafting.requestDrain();
        if (crafting.tick()) completeStep();
    }
    private void smelt() {
        if (smelting == null) {
            var recipe = catalog.recipes.get(step.sourceId());
            if (recipe == null || recipe.kind() != RecipeWork.Kind.SMELTING
                    || stepCatalogGeneration != catalog.generation()) throw new IllegalStateException("Cooking recipe disappeared or changed before it could start");
            if (!isSupportedCookingStation(recipe.cookingStation())) {
                throw new IllegalStateException("Unsupported recipe cooking station " + recipe.cookingStation());
            }
            if (!Objects.equals(recipe.cookingStation(), step.station())) {
                throw new IllegalStateException("Planned cooking station " + step.station()
                        + " does not match the recipe cooking station " + recipe.cookingStation());
            }
            if (!stationReady()) return;
            if (isFoodPreparation(step) && (!config.autoEat
                    || step.outputCount() > gatherCapacity(step.output()))) {
                resetAction();
                continueActiveRequest();
                return;
            }
            smelting = new SmeltingAction(client, actions, recipe, step, optionalPreparationAuthority(), this::shieldExecutionReservations);
        }
        if (shouldDrainActiveTransaction()) smelting.requestDrain();
        if (smelting.tick()) completeStep();
    }

    private static boolean isSupportedCookingStation(StationId station) {
        return FURNACE.equals(station) || SMOKER.equals(station) || BLAST_FURNACE.equals(station);
    }

    private static boolean isExactCookingStationHandler(ScreenHandler handler, StationId station) {
        if (handler == null || station == null) return false;
        if (FURNACE.equals(station)) return handler.getClass() == net.minecraft.screen.FurnaceScreenHandler.class;
        if (SMOKER.equals(station)) return handler.getClass() == net.minecraft.screen.SmokerScreenHandler.class;
        if (BLAST_FURNACE.equals(station)) return handler.getClass() == net.minecraft.screen.BlastFurnaceScreenHandler.class;
        return false;
    }
    private void completeStep() {
        boolean auxiliaryCompleted = stepAuxiliaryInvestment;
        if (stopAfterStep) { stopNow(true); return; }
        if (cancelledRequest == active && active != null) { resetAction(); tickCancelledRequest(); return; }
        boolean prepareCombatWeapon = step != null && step.kind() == PlanKind.CRAFT
                && step.output().equals(STONE_SWORD)
                && Boolean.parseBoolean(step.attributes().getOrDefault("combatPreparation", "false"));
        resetAction();
        if (cleanupReturnBarrier()) return;
        if (auxiliaryCompleted) auxiliaryMovementScope = null;
        else parentMovementScope = null;
        useMovementProgress(null);
        planningRetries = 0;
        if (prepareCombatWeapon && config.autoDefend && !paused && movement.finishCancellation())
            actions.select(GameCatalog.item(STONE_SWORD));
        if (applyDeferredUnmaintain()) return;
        if (foregroundYieldPending && active != null && activeMaintained()) {
            if (canYieldMaintenanceNow()) yieldActiveMaintenance();
            else { status = "foreground queued; waiting for inventory screen and cursor to be safe"; return; }
            return;
        }
        continueActiveRequest();
    }

    private boolean applyDeferredUnmaintain() {
        if (active == null || !activeMaintained() || !unmaintainAfterStep.contains(acquisitionView().item())) return false;
        if (cleanupRun != null || cleanupReturnBarrier()) { cancelledRequest = active; return true; }
        ItemId item = acquisitionView().item();
        unmaintainAfterStep.remove(item);
        Integer replacementTarget = maintainAfterStep.remove(item);
        active = null; acquisitionScope = null;
        retireMovementProgressScopes();
        pendingPlan = null;
        cancelMaintenanceTasks(maintained.unmaintain(item));
        if (replacementTarget != null) {
            maintained.maintain(item, replacementTarget);
            observeInventory();
            scheduleMaintenanceRequests(maintained.onInventoryChanged(observedInventory));
            message("Updated maintained target: " + item + " " + replacementTarget);
        } else {
            message("No longer maintaining " + item);
        }
        foregroundYieldPending = false;
        status = "idle";
        return true;
    }
    private void cancelStationPlacement() {
        if (stationPlacementHand != null && !stationPlacementHand.settled) {
            stationPlacementHand.requestDrain();
            if (!stationPlacementHand.sent) placementProvenance.cancelPending();
            stationPlacementWait = null;
            return;
        }
        placementProvenance.cancelPending();
        stationPlacementWait = null; stationPlacementHand = null;
    }

    private void abandonStationPlacementHand() {
        if (stationPlacementHand != null) {
            stationPlacementHand.loseRights(); stationPlacementHand.abandoned = true;
            actions.captureStationHandRightsLoss(stationPlacementHand, "AutomationEngine.abandonStationPlacementHand", "engine_abandonment");
            logStationHandRightsLoss(stationPlacementHand);
        }
        stationPlacementHand = null; stationPlacementWait = null;
    }

    private void logStationHandRightsLoss(PlayerActions.StationPlacementHand hand) {
        if (hand == null || !hand.diagnosticCaptured || hand.diagnosticLogged) return;
        hand.diagnosticLogged = true;
        try {
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] STATION_HAND_RIGHTS_LOST first_failed_predicate={} callsite={} evaluated_mask={} value_mask={} manual_evaluated_mask={} manual_down_mask={} {}",
                    hand.diagnosticFailedPredicate, hand.diagnosticCallsite,
                    hand.diagnosticGuardEvaluated, hand.diagnosticGuardValues,
                    hand.diagnosticManualEvaluated, hand.diagnosticManualDown, hand.diagnosticFirstLoss);
        } catch (RuntimeException | Error ignored) { }
    }

    /** Observes the captured hand before pause/settings and every other hand writer. */
    private boolean tickStationPlacementHand() {
        PlayerActions.StationPlacementHand hand = stationPlacementHand;
        if (hand == null) return false;
        actions.observeStationPlacementHand(hand, airRecovery.active() && client.player != null && client.player.input == input,
                "AutomationEngine.tickStationPlacementHand.observe");
        if (hand.abandoned) { abandonStationPlacementHand(); return false; }
        if (airRecovery.active() || !paused && !stopAfterStep && airRecovery.ready()) {
            cancelStationPlacement();
            logStationHandRightsLoss(hand);
            if (stopAfterStep || paused || editingSettings()) airRecovery.stop();
            else { recoverAirIfNeeded(); return true; }
        }
        try { movement.checkAirRecoveryOwnership(); }
        catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
            hand.loseRights();
            actions.captureStationHandRightsLoss(hand, "AutomationEngine.tickStationPlacementHand.checkAirRecoveryOwnership", "navigation_ownership_lost");
            logStationHandRightsLoss(hand);
            pauseAfterOwnershipLoss(failure); return true;
        }
        logStationHandRightsLoss(hand);
        if (hand.rightsLost && !paused && !stopAfterStep)
            pause("Station hand restoration yielded to changed player ownership; pending placement evidence is retained");
        if (active == null || active.jobToken() != hand.jobToken || step == null || step.kind() != PlanKind.PLACE_STATION)
            cancelStationPlacement();
        if (!hand.sent && !hand.draining) return false;
        OwnedStationLedger.StationRecord confirmed = hand.ticket == null ? null : placementProvenance.records().stream()
                .filter(record -> record.ticket() == hand.ticket && record.session().equals(hand.session)
                        && record.jobToken() == hand.jobToken && stationPosition(record).equals(hand.position)
                        && record.expectedBlockId().equals(hand.ticket.intent().expectedBlockId()))
                .findFirst().orElse(null);
        boolean loadedRecord = confirmed != null && hasLoadedChunk(hand.position)
                && client.world.getBlockState(hand.position).isOf(hand.block);
        if ((!hand.sent || loadedRecord) && !moving && !explorationMoving && movement.finishCancellation()
                && stationHandNativeQuiescent(hand.nativeOwner) && actions.finishStationPlacementHand(hand, confirmed)) {
            logStationHandRightsLoss(hand);
            boolean draining = hand.draining;
            stationPlacementHand = null;
            if (stopAfterStep) { stopNow(true); return true; }
            if (draining) {
                stationPlacementWait = null;
                if (!paused && !editingSettings() && active != null) { resetAction(); continueActiveRequest(); }
                return true;
            }
            return false;
        }
        logStationHandRightsLoss(hand);
        input.release();
        if (!paused) {
            status = "waiting for exact station placement and hand inventory evidence";
            if (hand.sent && !placementProvenance.serializesBotActions())
                pause("Station placement remains unproved; its hand and ticket are retained without another interaction");
        }
        return true;
    }


    private boolean manualStationInput() {
        var options = client.options;
        return options.attackKey.isPressed() || options.useKey.isPressed()
                || options.forwardKey.isPressed() || options.backKey.isPressed()
                || options.leftKey.isPressed() || options.rightKey.isPressed()
                || options.jumpKey.isPressed() || options.sneakKey.isPressed() || options.sprintKey.isPressed();
    }

    void chunkUnloaded(ClientWorld sourceWorld, int chunkX, int chunkZ) {
        placementProvenance.forgetChunk(sourceWorld, chunkX, chunkZ);
        terrain.changedChunk(chunkX, chunkZ);
        if (sourceWorld == client.world && cleanupRun != null && cleanupRun.current != null) {
            var position = cleanupRun.current.position();
            if ((position.x() >> 4) == chunkX && (position.z() >> 4) == chunkZ)
                pause("Owned station cleanup stopped because its chunk unloaded; the station was left for inspection");
        }
    }

    private static BlockPos stationPosition(OwnedStationLedger.StationRecord record) {
        var position = record.position();
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private boolean wholeJobSatisfied(AcquireRequest request) {
        if (request.maintained()) return false;
        ProjectRun project = request.project();
        if (project == null) return true;
        return !project.aborted && projects.contains(project)
                && project.pending.stream().allMatch(item -> item.equals(request.item()))
                && project.spec.goals().entrySet().stream().allMatch(goal ->
                        observedInventory.getOrDefault(goal.getKey(), 0) >= goal.getValue());
    }

    private void pruneCompletedStandaloneTables() {
        if (completedStandaloneTables.isEmpty()) return;
        var session = placementProvenance.session();
        var records = placementProvenance.records();
        completedStandaloneTables.removeIf(record -> session.isEmpty() || !record.session().equals(session.get())
                || !records.contains(record) && !(record == pendingStationPickup && stationRecovery.pickupRetained()
                        && placementProvenance.confirmedStationRemoved(record))
                && !(cleanupRun != null && cleanupRun.purpose == CleanupPurpose.PREVIOUS_COMMAND_TABLE
                        && cleanupRun.current == record && (stationRecovery.active() || stationRecovery.pickupRetained())
                        && placementProvenance.confirmedStationRemoved(record)));
    }

    private boolean stationProducerLive(long jobToken) {
        return active != null && active.jobToken() == jobToken
                || queue.stream().anyMatch(request -> request.jobToken() == jobToken)
                || maintenanceQueue.stream().anyMatch(request -> request.jobToken() == jobToken)
                || projects.stream().anyMatch(project -> project.jobToken == jobToken);
    }

    private boolean selectedPreviousCommandTable(AcquireRequest request, OwnedStationLedger.StationRecord record) {
        return previousCommandTableCheck != null && previousCommandTableCheck.acquisition() == acquisitionScope && previousCommandTableCheck.jobToken() == request.jobToken()
                && previousCommandTableCheck.selected() == record;
    }

    private boolean beginPreviousCommandTable(PlanStep gatherStep) {
        if (active == null || activeMaintained() || activeProject() != null
                || previousCommandTableCheck != null && previousCommandTableCheck.acquisition() == acquisitionScope && previousCommandTableCheck.jobToken() == active.jobToken()) return false;
        previousCommandTableCheck = new PreviousCommandTableCheck(acquisitionScope, active.jobToken(), null);
        if (paused || stopAfterStep || healthRecovery != null || airRecovery.active()
                || threats.active() || threats.ready() || food.active() || nativeAcquisitionActive() || equipment.active()
                || client.player.getHealth() <= config.pauseBelowHealth || client.player.getHungerManager().getFoodLevel() <= 14
                || !config.recoverPlacedStations || !config.allowBreaking || !config.allowBuilding
                || config.stationRecoveryRange < 1 || manualStationInput() || client.currentScreen != null
                || client.player.currentScreenHandler != client.player.playerScreenHandler
                || !client.player.currentScreenHandler.getCursorStack().isEmpty() || transactionInProgress() || openingStation
                || stationPlacementWait != null || placementProvenance.serializesBotActions()
                || actions.heldCount(GameCatalog.item(CRAFTING_TABLE_ITEM)) >= 1
                || gatherCapacity(CRAFTING_TABLE_ITEM) < 1 || stationRecovery.pickupRetained()) return false;
        int reservedCapacity = CRAFTING_TABLE_ITEM.equals(gatherStep.output()) ? 1
                : new ItemStack(GameCatalog.item(gatherStep.output())).getMaxCount();
        if (gatherCapacity(gatherStep.output()) - reservedCapacity < gatherStep.outputCount()) return false;
        pruneCompletedStandaloneTables();
        var session = placementProvenance.session();
        if (session.isEmpty()) return false;
        var records = placementProvenance.records();
        OwnedStationLedger.StationRecord nearest = null;
        double nearestDistance = (double) config.stationRecoveryRange * config.stationRecoveryRange;
        for (var record : completedStandaloneTables) {
            if (record.jobToken() >= active.jobToken() || stationProducerLive(record.jobToken())
                    || !record.session().equals(session.get()) || !records.contains(record)
                    || !CRAFTING_TABLE_ITEM.equals(record.expectedBlockId())
                    || !CRAFTING_TABLE_ITEM.equals(record.stationItemId())) continue;
            BlockPos position = stationPosition(record);
            if (!hasLoadedChunk(position) || client.world.getBlockState(position).getBlock() != Blocks.CRAFTING_TABLE
                    || !protection.mayBreak(position)) continue;
            double dx = position.getX() + 0.5 - client.player.getX();
            double dy = position.getY() + 0.5 - client.player.getY();
            double dz = position.getZ() + 0.5 - client.player.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= nearestDistance) {
                nearest = record;
                nearestDistance = distance;
            }
        }
        if (nearest == null) return false;
        previousCommandTableCheck = new PreviousCommandTableCheck(acquisitionScope, active.jobToken(), nearest);
        if (pendingPlan != null) pendingPlan.cancel(false);
        resetAction();
        if (cleanupReturnBarrier()) return true;
        pendingPlan = null;
        cleanupRun = new CleanupRun(CleanupPurpose.PREVIOUS_COMMAND_TABLE, acquisitionScope, acquisitionView(), session.get(), List.of(nearest), System.nanoTime());
        status = "recovering the owned crafting table before leaving";
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] PREVIOUS_COMMAND_TABLE producerJob={} consumerJob={} position={}",
                nearest.jobToken(), active.jobToken(), stationPosition(nearest));
        return true;
    }

    private boolean beginCarryTable() {
        if (active == null || activeProject() == null || activeProject().aborted
                || !projects.contains(activeProject()) || healthRecovery != null
                || !config.recoverPlacedStations || !config.allowBreaking || !config.allowBuilding
                || config.stationRecoveryRange < 1
                || actions.heldCount(GameCatalog.item(CRAFTING_TABLE_ITEM)) >= 1
                || gatherCapacity(CRAFTING_TABLE_ITEM) < 1
                || pendingStationPickup != null && stationRecovery.pickupRetained()) return false;
        var session = placementProvenance.session();
        if (session.isEmpty()) return false;
        OwnedStationLedger.StationRecord nearest = null;
        double nearestDistance = (double) config.stationRecoveryRange * config.stationRecoveryRange;
        for (var record : placementProvenance.records()) {
            if (record.jobToken() != active.jobToken() || !record.session().equals(session.get())
                    || !CRAFTING_TABLE_ITEM.equals(record.expectedBlockId())
                    || !CRAFTING_TABLE_ITEM.equals(record.stationItemId())
                    || deferredStationCleanup.contains(record)) continue;
            BlockPos position = stationPosition(record);
            if (!hasLoadedChunk(position) || client.world.getBlockState(position).getBlock() != Blocks.CRAFTING_TABLE
                    || !protection.mayBreak(position)) continue;
            double dx = position.getX() + 0.5 - client.player.getX();
            double dy = position.getY() + 0.5 - client.player.getY();
            double dz = position.getZ() + 0.5 - client.player.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= nearestDistance) {
                nearest = record;
                nearestDistance = distance;
            }
        }
        if (nearest == null) return false;
        if (pendingPlan != null) pendingPlan.cancel(false);
        resetAction();
        if (cleanupReturnBarrier()) return true;
        pendingPlan = null;
        cleanupRun = new CleanupRun(CleanupPurpose.CARRY_TABLE, acquisitionScope, acquisitionView(), session.get(), List.of(nearest), System.nanoTime());
        status = "recovering the owned crafting table before leaving";
        return true;
    }

    private boolean beginStationCleanup(AcquireRequest request) {
        if (cleanedJobToken == request.jobToken()
                && (!(active instanceof TravelRequest) || cleanedAcquisitionScope == acquisitionScope)) return false;
        if (!config.recoverPlacedStations) { cleanedJobToken = request.jobToken(); cleanedAcquisitionScope = acquisitionScope; return false; }
        var session = placementProvenance.session();
        if (session.isEmpty()) return false;
        List<OwnedStationLedger.StationRecord> records = new ArrayList<>(placementProvenance.records().stream()
                .filter(record -> record.jobToken() == request.jobToken()
                        && record.session().equals(session.get())).toList());
        if (pendingStationPickup != null && pendingStationPickup.session().equals(session.get())
                && stationRecovery.pickupRetained()
                && placementProvenance.confirmedStationRemoved(pendingStationPickup)
                && !selectedPreviousCommandTable(request, pendingStationPickup)
                && !records.contains(pendingStationPickup)) records.add(0, pendingStationPickup);
        deferredStationCleanup.removeIf(record -> !record.session().equals(session.get())
                || !placementProvenance.records().contains(record));
        for (var record : deferredStationCleanup)
            if (!selectedPreviousCommandTable(request, record) && !records.contains(record)) records.add(record);
        if (pendingStationPickup != null && selectedPreviousCommandTable(request, pendingStationPickup)
                && stationRecovery.pickupRetained() && !records.isEmpty()) {
            for (var record : records)
                if (placementProvenance.records().contains(record) && !deferredStationCleanup.contains(record))
                    deferredStationCleanup.addLast(record);
            cleanedJobToken = request.jobToken(); cleanedAcquisitionScope = acquisitionScope;
            stationCleanupIncompleteJobToken = request.jobToken();
            stationCleanupIncompleteReason = "an earlier command's exact table drop is retained; remaining stations were deferred";
            return false;
        }
        deferredStationCleanup.removeIf(record -> !selectedPreviousCommandTable(request, record));
        if (records.isEmpty()) { cleanedJobToken = request.jobToken(); cleanedAcquisitionScope = acquisitionScope; return false; }
        if (stationCleanupIncompleteJobToken != request.jobToken()) {
            stationCleanupIncompleteJobToken = -1;
            stationCleanupIncompleteReason = null;
        }
        resetAction();
        if (cleanupReturnBarrier()) return true;
        pendingPlan = null;
        if (cleanupBudget == null || cleanupBudget.jobToken() != request.jobToken()
                || !cleanupBudget.session().equals(session.get()))
            cleanupBudget = new CleanupBudget(request.jobToken(), session.get(), System.nanoTime());
        cleanupRun = new CleanupRun(CleanupPurpose.FINISH_JOB, acquisitionScope, request, session.get(), records, cleanupBudget.startedNanos());
        status = "returning confirmed owned stations after the whole job";
        message("Inventory goals reached; checking " + records.size() + " confirmed owned stations for recovery");
        return true;
    }

    boolean retainsOwnedStationReceipt(PlacementProvenance source, OwnedStationLedger.Session session,
                                      OwnedStationLedger.BlockPosition cell) {
        if (source != placementProvenance) return false;
        if (cleanupRun != null && cleanupRun.session.equals(session) && cleanupRun.current != null
                && cleanupRun.current.session().equals(session) && cleanupRun.current.position().equals(cell)) return true;
        return pendingStationPickup != null && stationRecovery.pickupRetained()
                && pendingStationPickup.session().equals(session) && pendingStationPickup.position().equals(cell);
    }

    private boolean mayRecoverOwnedStation(BlockPos position) {
        CleanupRun cleanup = cleanupRun;
        if (cleanup == null || cleanup.current == null || acquisitionScope != cleanup.acquisition || active != cleanup.acquisition.parent()
                || !position.equals(stationPosition(cleanup.current))
                || !placementProvenance.session().filter(cleanup.session::equals).isPresent()
                || !(placementProvenance.records().contains(cleanup.current)
                        || cleanup.current.equals(pendingStationPickup) && stationRecovery.pickupRetained()
                        && placementProvenance.confirmedStationRemoved(cleanup.current))) return false;
        if (cleanup.purpose == CleanupPurpose.PREVIOUS_COMMAND_TABLE
                && (cleanup.request.maintained() || cleanup.request.project() != null
                        || !selectedPreviousCommandTable(cleanup.request, cleanup.current)
                        || !completedStandaloneTables.contains(cleanup.current)
                        || cleanup.current.jobToken() >= cleanup.request.jobToken()
                        || stationProducerLive(cleanup.current.jobToken())
                        || !CRAFTING_TABLE_ITEM.equals(cleanup.current.expectedBlockId())
                        || !CRAFTING_TABLE_ITEM.equals(cleanup.current.stationItemId())
                        || !config.recoverPlacedStations || !config.allowBreaking || !config.allowBuilding
                        || healthRecovery != null || airRecovery.active() || threats.active() || threats.ready()
                        || client.player.getHealth() <= config.pauseBelowHealth
                        || client.player.getHungerManager().getFoodLevel() <= 14
                        || pendingStationPickup != null && pendingStationPickup != cleanup.current && stationRecovery.pickupRetained())) return false;
        return protection.mayBreak(position);
    }

    private boolean cleanupReturnBarrier() {
        return stationRecovery.returnPending();
    }
    private boolean cleanupRestorationCurrent(CleanupRun cleanup) {
        return cleanup != null && !cleanup.restorationRevoked && active == cleanup.acquisition.parent()
                && acquisitionScope == cleanup.acquisition && contextBound(active.context())
                && !airRecovery.active() && !airRecovery.ready() && !manualStationInput()
                && stationPlacementHand == null;
    }
    private void revokeCleanupRestoration() {
        if (cleanupRun != null) cleanupRun.restorationRevoked = true;
        stationRecovery.revokeRestoration();
    }
    private boolean tickCleanupReturnObserver() {
        if (cleanupRun == null || !stationRecovery.observingReturn() || !cleanupReturnBarrier()) return false;
        if (airRecovery.active() || airRecovery.ready()) {
            revokeCleanupRestoration();
            if (!paused && recoverAirIfNeeded()) return true;
        }
        tickStationCleanup();
        return true;
    }
    private void settleRecoveredStation(CleanupRun cleanup) {
        var recovered = cleanup.current;
        placementProvenance.recovered(recovered);
        completedStandaloneTables.remove(recovered);
        if (recovered.equals(pendingStationPickup)) pendingStationPickup = null;
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] OWNED_STATION_RECOVERED job={} producerJob={} consumerJob={} position={}",
                cleanup.request.jobToken(), recovered.jobToken(), cleanup.request.jobToken(), stationPosition(recovered));
        knownStations.values().removeIf(stationPosition(recovered)::equals);
        stationRecovery.stop();
        if (cleanupReturnBarrier()) { status = stationRecovery.status(); return; }
        cleanup.current = null;
        terrain.changed();
        observeInventory();
    }

    private void stopStationCleanup() {
        stationRecovery.stop();
        if (cleanupReturnBarrier()) {
            if (cleanupRun != null && cleanupRun.current != null) pendingStationPickup = cleanupRun.current;
            status = "retaining the original owned station RETURN actor and cleanup budget";
            return;
        }
        if (stationRecovery.pickupRetained() && cleanupRun != null && cleanupRun.current != null)
            pendingStationPickup = cleanupRun.current;
        else if (cleanupRun != null && cleanupRun.current != null
                && cleanupRun.current.equals(pendingStationPickup)) pendingStationPickup = null;
        if (cleanupRun != null && cleanupRun.purpose != CleanupPurpose.PREVIOUS_COMMAND_TABLE
                && (cleanupRun.purpose == CleanupPurpose.FINISH_JOB || cleanupRun.incomplete)) {
            if (cleanupRun.current != null && placementProvenance.records().contains(cleanupRun.current)
                    && !deferredStationCleanup.contains(cleanupRun.current))
                deferredStationCleanup.addLast(cleanupRun.current);
            for (var record : cleanupRun.remaining)
                if (placementProvenance.records().contains(record) && !deferredStationCleanup.contains(record))
                    deferredStationCleanup.addLast(record);
        }
        cleanupRun = null;
    }

    private String stationCleanupStatus(CleanupRun cleanup) {
        String detail = stationRecovery.status();
        return cleanup.purpose == CleanupPurpose.PREVIOUS_COMMAND_TABLE && !detail.contains("owned crafting table")
                ? detail + " · recovering the owned crafting table" : detail;
    }

    private void tickStationCleanup() {
        CleanupRun cleanup = cleanupRun;
        if (cleanup == null) return;
        if (acquisitionScope != cleanup.acquisition || active != cleanup.acquisition.parent() || !placementProvenance.session().filter(cleanup.session::equals).isPresent()) {
            pause("Owned station cleanup lost its job or native session; remaining stations were left in place");
            return;
        }
        if (cleanup.current != null && stationRecovery.observingReturn() && cleanupReturnBarrier()) {
            boolean rights = cleanupRestorationCurrent(cleanup);
            boolean pickup = rights && !paused && newEffectCurrent(active) && config.recoverPlacedStations;
            if (stationRecovery.tickRetainedReturn(rights, pickup)) {
                settleRecoveredStation(cleanup);
            } else if (!cleanupReturnBarrier()) {
                skipCleanupStation(cleanup, stationRecovery.status());
            } else status = stationRecovery.status();
            if (!cleanupReturnBarrier() && stopAfterStep) stopNow(true);
            return;
        }
        if (cleanup.restorationRevoked || !newEffectCurrent(active)) {
            stationRecovery.stop();
            if (cleanupReturnBarrier()) { status = stationRecovery.status(); return; }
            cleanup.incomplete = true;
            cleanup.incompleteReason = cleanup.restorationRevoked ? "restoration rights revoked; remaining stations were left in place"
                    : "job authority ended; no fresh station recovery admitted";
            completeStationCleanup(cleanup); return;
        }
        if (!config.recoverPlacedStations) {
            message("Owned station recovery was disabled; remaining stations were left in place");
            cleanup.incomplete = true;
            cleanup.incompleteReason = "station recovery was disabled";
            completeStationCleanup(cleanup);
            return;
        }
        int limitSeconds = cleanup.purpose == CleanupPurpose.PREVIOUS_COMMAND_TABLE ? 20 : 60;
        if (System.nanoTime() - cleanup.startedNanos >= limitSeconds * 1_000_000_000L) {
            message("Owned station cleanup reached its " + limitSeconds + " second limit; remaining stations were left in place");
            cleanup.incomplete = true;
            cleanup.incompleteReason = "the " + limitSeconds + " second cleanup limit was reached";
            completeStationCleanup(cleanup);
            return;
        }
        if (stationRecovery.pickupIncomplete() && cleanup.current != null) {
            skipCleanupStation(cleanup, stationRecovery.status());
            return;
        }
        if (stationRecovery.active()) {
            status = stationCleanupStatus(cleanup);
            try {
                if (stationRecovery.tick()) {
                    settleRecoveredStation(cleanup);
                }
            } catch (MovementController.NavigationFailure failure) {
                if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) {
                    pauseAfterOwnershipLoss(failure);
                    return;
                }
                skipCleanupStation(cleanup, failure.getMessage());
            } catch (RuntimeException failure) {
                Throwable cause = failure;
                while (cause != null) {
                    if (cause instanceof MovementController.NavigationFailure navigationFailure
                            && navigationFailure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) {
                        pauseAfterOwnershipLoss(navigationFailure);
                        return;
                    }
                    cause = cause.getCause();
                }
                skipCleanupStation(cleanup, describe(failure));
            }
            return;
        }
        if (!movement.finishCancellation()) { status = "finishing movement before owned station cleanup"; return; }
        if (manualStationInput() || client.currentScreen != null || client.player.currentScreenHandler != client.player.playerScreenHandler) {
            pause("Owned station cleanup yielded to player input or an inventory screen");
            return;
        }
        OwnedStationLedger.StationRecord record = cleanup.remaining.pollFirst();
        if (record == null) { completeStationCleanup(cleanup); return; }
        cleanup.current = record;
        BlockPos position = stationPosition(record);
        Block block = Registries.BLOCK.get(GameApi.identifier(record.expectedBlockId().toString()));
        if (!mayRecoverOwnedStation(position) || !hasLoadedChunk(position)) {
            skipCleanupStation(cleanup, "station ownership, loaded chunk, or claim permission is no longer safe");
            return;
        }
        movement.checkAirRecoveryOwnership();
        refreshNavigationProtection();
        if (!stationRecovery.begin(position, block)) {
            skipCleanupStation(cleanup, stationRecovery.status());
            return;
        }
        status = stationCleanupStatus(cleanup);
    }

    private void skipCleanupStation(CleanupRun cleanup, String reason) {
        if (cleanupReturnBarrier()) {
            pendingStationPickup = cleanup.current;
            status = stationRecovery.status();
            return;
        }
        cleanup.incomplete = true;
        cleanup.incompleteReason = reason.length() > 180 ? reason.substring(0, 180) : reason;
        if (stationRecovery.pickupRetained()) pendingStationPickup = cleanup.current;
        else {
            if (cleanup.current.equals(pendingStationPickup)) pendingStationPickup = null;
            if (cleanup.purpose != CleanupPurpose.PREVIOUS_COMMAND_TABLE
                    && placementProvenance.records().contains(cleanup.current)
                    && !deferredStationCleanup.contains(cleanup.current)) deferredStationCleanup.addLast(cleanup.current);
        }
        message("Owned station left at " + stationPosition(cleanup.current) + ": " + reason);
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] OWNED_STATION_LEFT job={} producerJob={} consumerJob={} position={} reason={}",
                cleanup.request.jobToken(), cleanup.current.jobToken(), cleanup.request.jobToken(), stationPosition(cleanup.current), reason);
        if (cleanup.purpose == CleanupPurpose.PREVIOUS_COMMAND_TABLE || stationRecovery.pickupRetained()) {
            completeStationCleanup(cleanup);
            return;
        }
        stationRecovery.stop();
        if (cleanupReturnBarrier()) { status = stationRecovery.status(); return; }
        cleanup.current = null;
    }

    private void completeStationCleanup(CleanupRun cleanup) {
        stationRecovery.stop();
        if (cleanupReturnBarrier()) { status = stationRecovery.status(); return; }
        if (stopAfterStep) { stopNow(true); return; }
        if (cleanup.purpose == CleanupPurpose.CARRY_TABLE || cleanup.purpose == CleanupPurpose.PREVIOUS_COMMAND_TABLE) {
            stopStationCleanup();
            if (cleanupReturnBarrier()) return;
            observeInventory();
            continueActiveRequest();
            return;
        }
        cleanedJobToken = cleanup.request.jobToken(); cleanedAcquisitionScope = cleanup.acquisition;
        if (cleanup.incomplete) {
            stationCleanupIncompleteJobToken = cleanup.request.jobToken();
            stationCleanupIncompleteReason = cleanup.incompleteReason;
        }
        stopStationCleanup();
        if (cleanupReturnBarrier()) return;
        observeInventory();
        finishGoal();
    }

    private void finishGoal() {
        if (cleanupRun != null || cleanupReturnBarrier()) return;
        if (stopAfterStep) { stopNow(true); return; }
        if (nativeAcquisitionActive()) { requestNativeDrain(NativeRun.DrainReason.REPLAN); return; }
        AcquireRequest finished = acquisitionView();
        if (finished == null) return;
        if (cancelledRequest == active) { tickCancelledRequest(); return; }
        if (acquisitionScope.parent() != finished) { finishAcquisitionPhase(acquisitionScope); return; }
        if (config.autoEquipArmor && !paused && !transactionInProgress() && !openingStation) {
            if (moving || exploring || hasOwnedStationHandlerOpen()) resetAction();
            if (!movement.finishCancellation()) { status = "finishing movement before equipping completed gear"; return; }
            if (equipment.tick()) { status = equipment.status(); return; }
        }
        observeInventory();
        if (wholeJobSatisfied(finished) && beginStationCleanup(finished)) return;
        if (!finished.maintained() && finished.project() == null && goalCount() >= finished.count()) {
            pruneCompletedStandaloneTables();
            for (var record : placementProvenance.records())
                if (record.jobToken() == finished.jobToken()
                        && placementProvenance.session().filter(record.session()::equals).isPresent()
                        && CRAFTING_TABLE_ITEM.equals(record.expectedBlockId())
                        && CRAFTING_TABLE_ITEM.equals(record.stationItemId())) completedStandaloneTables.add(record);
        }
        resetAction();
        if (cleanupReturnBarrier()) return;
        active = null; acquisitionScope = null; pendingPlan = null; status = "idle";
        String cleanupWarning = stationCleanupWarning(finished);
        if (finished.maintained()) {
            scheduleMaintenanceRequests(maintained.complete(finished.maintenanceTaskId(), observedInventory));
            message("Maintained target reached: " + finished.item() + " "
                    + observedInventory.getOrDefault(finished.item(), 0) + "/" + finished.count()
                    + cleanupWarning);
        } else if (finished.project() != null && !finished.project().aborted) {
            completeProjectGoal(finished.project(), finished.item(), cleanupWarning);
        } else if (finished.project() != null) {
            message("Completed project item target: " + finished.item() + " " + finished.count()
                    + cleanupWarning);
        } else {
            message((cleanupWarning.isEmpty() ? "Complete: " : "Inventory target reached: ")
                    + finished.count() + " × " + finished.name() + cleanupWarning);
        }
    }

    private String stationCleanupWarning(AcquireRequest request) {
        if (stationCleanupIncompleteJobToken != request.jobToken()) return "";
        return "; owned station cleanup incomplete: " + stationCleanupIncompleteReason
                + ". Retry owned station cleanup in a later job within this server session";
    }

    private void completeProjectGoal(ProjectRun run, ItemId completedItem, String cleanupWarning) {
        if (run.aborted || !projects.contains(run)) return;
        run.pending.remove(completedItem);
        if (!run.pending.isEmpty()) return;

        List<Map.Entry<ItemId, Integer>> missing = run.spec.goals().entrySet().stream()
                .filter(goal -> observedInventory.getOrDefault(goal.getKey(), 0) < goal.getValue()).toList();
        if (missing.isEmpty()) {
            projects.remove(run);
            message((cleanupWarning.isEmpty() ? "Project complete: " : "Project inventory targets reached: ") + run.spec.name() + " (all " + run.spec.goals().size()
                    + " inventory targets are present)" + cleanupWarning);
            return;
        }
        if (run.reconciliationPasses >= MAX_PROJECT_RECONCILIATIONS
                || queue.size() + missing.size() > MAX_FOREGROUND_QUEUE) {
            abortProject(run);
            message("Project incomplete: " + run.spec.name() + " still needs " + missing.stream()
                    .limit(5).map(goal -> goal.getKey() + " " + observedInventory.getOrDefault(goal.getKey(), 0) + "/" + goal.getValue())
                    .toList());
            return;
        }

        run.reconciliationPasses++;
        run.pending.clear();
        for (Map.Entry<ItemId, Integer> goal : missing) {
            run.pending.add(goal.getKey());
            appendRequest(request("project " + run.spec.name() + " · " + goal.getKey(), goal.getKey(),
                    goal.getValue(), false, null, run));
        }
        message("Rechecking project " + run.spec.name() + " after inventory changed (pass "
                + run.reconciliationPasses + "/" + MAX_PROJECT_RECONCILIATIONS + ")" + cleanupWarning);
    }

    private void abortProject(ProjectRun run) {
        if (run == null || run.aborted) return;
        run.aborted = true;
        run.pending.clear();
        projects.remove(run);
        queue.removeIf(request -> request instanceof AcquireRequest acquire && acquire.project() == run);
    }

    private void failActive(String reason) {
        if (cleanupRun != null || cleanupReturnBarrier()) { pause(reason); return; }
        if (active instanceof TravelRequest && acquisitionScope == null) {
            if (travelAction != null) travelAction.requestDrain(NativeRun.DrainReason.FAILURE);
            pause(reason); return;
        }
        AcquireRequest failed = acquisitionView();
        if (reason.startsWith("Cannot place required station ")) {
            pause(reason + ". The current goal and remaining project are preserved");
            return;
        }
        if (reason.startsWith("Cannot safely return ")) {
            pause(reason + ". Free inventory space, then resume; the owned container stays open");
            return;
        }
        if (transactionInProgress()) {
            pause(reason + ". The current crafting/cooking-station transaction is preserved in its open handler");
            return;
        }
        if (failed != null && failed.maintained()) {
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = null;
            boolean keepOwnedHandler = hasOwnedStationHandlerOpen();
            resetAction(!keepOwnedHandler);
            if (cleanupReturnBarrier()) { pause(reason); return; }
            active = null; acquisitionScope = null;
            retireMovementProgressScopes();
            observeInventory();
            scheduleMaintenanceRequests(maintained.fail(failed.maintenanceTaskId(), observedInventory));
            status = "maintenance blocked";
            message("Maintenance blocked for " + failed.item() + ": " + reason + ". It will retry after inventory changes or a new maintain command.");
            if (keepOwnedHandler) pause("Maintenance blocked while its owned container remains open; inspect it, close it, then resume");
            return;
        }
        if (failed != null && failed.project() != null) {
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = null;
            resetAction(!hasOwnedStationHandlerOpen());
            if (cleanupReturnBarrier()) { pause(reason); return; }
            abortProject(failed.project());
            active = null; acquisitionScope = null;
            pause("Project " + failed.project().spec.name() + " blocked: " + reason);
            return;
        }
        pause(reason);
    }
    private String recoverTransactions() {
        animalAcquisition.pause(); cropAcquisition.pause();
        String warning = "";
        try { if (crafting != null) crafting.pause(); }
        catch (RuntimeException ex) { warning = describe(ex); }
        try { if (stonecutting != null) stonecutting.pause(); }
        catch (RuntimeException exception) { warning = warning.isBlank() ? exception.getMessage() : warning + "; " + exception.getMessage(); }
        try { if (smelting != null) smelting.pause(); }
        catch (RuntimeException ex) { warning = warning.isBlank() ? describe(ex) : warning + "; " + describe(ex); }
        return warning;
    }
    private static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
    private boolean hasOwnedStationHandlerOpen() {
        return client.player != null && ownedStationHandler != null
                && client.player.currentScreenHandler == ownedStationHandler;
    }

    private void resetAction() { resetAction(true); }

    private void resetAction(boolean closeOwnedHandler) {
        if (cleanupReturnBarrier()) { stopStationCleanup(); return; }
        if (nativeRun instanceof TravelAction travel && !travel.safeToRelease()) {
            travel.requestDrain(NativeRun.DrainReason.PREEMPT); return;
        }
        if (stationPlacementHand != null && !stationPlacementHand.settled) { cancelStationPlacement(); return; }
        if (nativeAcquisitionActive()) { requestNativeDrain(NativeRun.DrainReason.REPLAN); return; }
        dropStationStockHint();
        useMovementProgress(null);
        validateMovementProgressScopes();
        cancelStationPlacement();
        miningContinuation = null;
        stationRoom.stop();
        stopStationCleanup();
        if (cleanupReturnBarrier()) return;
        try { animalAcquisition.stop(); }
        catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
            pauseAfterOwnershipLoss(failure);
        }
        animalAcquisitionPending = false;
        threats.stop(); equipment.stop(); food.stop(); foodReplanPending = false;
        ScreenHandler stationHandler = ownedStationHandler;
        boolean closeThisHandler = closeOwnedHandler && hasOwnedStationHandlerOpen();
        String warning;
        try { warning = recoverTransactions(); }
        finally { input.release(); }
        try { movement.stop(); }
        catch (RuntimeException ex) { message("Movement cancellation: " + ex.getMessage()); }
        finally {
            crafting = null; stonecutting = null; smelting = null; openingStation = false; stationOpenTicks = 0; moving = false; movingPickup = false;
            step = null; stepAuxiliaryInvestment = false; stepShieldIdentity = null; scan = null; localGatherReachScan = null;
            target = null; stationApproachTarget = null; verifyTicks = 0;
            clearGatherAttempt();
            exploring = false; explorationMoving = false; explorationTicks = 0;
        }
        if (warning.isBlank() && closeThisHandler && client.player != null
                && client.player.currentScreenHandler == stationHandler) client.player.closeHandledScreen();
        ownedStationHandler = null;
        stationOpeningFrom = null;
        if (!warning.isBlank()) message("Inventory recovery needs your attention: " + warning);
    }
    private void pauseAfterOwnershipLoss(MovementController.NavigationFailure failure) {
        revokeCleanupRestoration();
        if (travelAction != null && nativeRun == travelAction) travelAction.pause();
        if (stationPlacementHand != null) {
            stationPlacementHand.loseRights();
            actions.captureStationHandRightsLoss(stationPlacementHand, "AutomationEngine.pauseAfterOwnershipLoss", "navigation_ownership_lost");
            logStationHandRightsLoss(stationPlacementHand);
        }
        dropStationStockHint();
        useMovementProgress(null);
        cancelStationPlacement();
        stopStationCleanup();
        airRecovery.abandon();
        animalAcquisition.abandonNavigationOwnership(); cropAcquisition.abandonNavigationOwnership();
        if (pendingPlan != null) pendingPlan.cancel(false);
        pendingPlan = null;
        pendingPreferencePlan = false;
        animalAcquisitionPending = false;
        paused = true;
        input.release();
        status = failure.getMessage() + "; resume to reclaim navigation";
        message("Paused: " + status);
    }

    void pause(String reason) {
        logStationHandRightsLoss(stationPlacementHand);
        if (travelAction != null && nativeRun == travelAction) travelAction.pause();
        dropStationStockHint();
        useMovementProgress(null);
        cancelStationPlacement();
        stopStationCleanup();
        airRecovery.stop();
        healthRecovery = null;
        stationRoom.stop();
        try { animalAcquisition.pause(); cropAcquisition.pause(); }
        catch (MovementController.NavigationFailure failure) {
            if (failure.kind != MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
            pauseAfterOwnershipLoss(failure);
        }
        animalAcquisitionPending = false;
        threats.stop(); equipment.stop(); food.stop();
        if (!nativeAcquisitionActive()) movement.suspend();
        if (stopAfterStep) reason += ". The safe stop is paused; resume to finish draining the current transaction";
        paused = true;
        String warning;
        try { warning = recoverTransactions(); }
        finally { input.release(); }
        try { actions.cancel(); } catch (RuntimeException ex) { warning += " " + ex.getMessage(); }
        if (!warning.isBlank()) reason += ". " + warning;
        message("Paused: " + reason + ". Use resume or stop."); status = reason;
    }
    void resume() {
        if (travelAction != null) travelAction.resume();
        animalQuotaTickNanos = System.nanoTime();
        paused = false; actionTicks = 0; lastMovementProgressToken = movement.progressToken();
        if (active != null && !exploring && pendingPlan == null && step == null) continueActiveRequest();
        message(stopAfterStep ? "Resuming the safe drain before stopping" : "Resumed");
    }
    void stop() {
        if (travelAction != null && nativeRun == travelAction) {
            stopAfterStep = true; paused = false; travelAction.requestDrain(NativeRun.DrainReason.STOP);
            status = "stopping after travel cancellation and settings release"; return;
        }
        cancelStationPlacement();
        if (stationPlacementHand != null) {
            stopAfterStep = true; paused = false; airRecovery.stop();
            status = "stopping after station placement evidence and hand restoration"; return;
        }
        if (nativeAcquisitionActive()) {
            stopAfterStep = true; paused = false; airRecovery.stop();
            requestNativeDrain(NativeRun.DrainReason.STOP);
            status = "stopping after native acquisition evidence and cancellation drain"; return;
        }
        if (stopAfterStep) return;
        if (transactionInProgress() || openingStation) {
            stopAfterStep = true;
            paused = false;
            requestActiveTransactionDrain();
            status = "stopping after current safe station transaction";
            message("Stopping after the current crafting/cooking-station transaction is safely recovered");
            return;
        }
        stopNow(true);
    }

    private void stopNow(boolean announce) {
        if (cleanupReturnBarrier()) {
            stopAfterStep = true; paused = false;
            stopStationCleanup();
            status = "stopping after retained owned station RETURN settlement";
            return;
        }
        if (travelAction != null && nativeRun == travelAction && !travelAction.safeToRelease()) {
            stopAfterStep = true; paused = false; travelAction.requestDrain(NativeRun.DrainReason.STOP); return;
        }
        if (stationPlacementHand != null && !stationPlacementHand.settled) {
            cancelStationPlacement(); stopAfterStep = true; paused = false; airRecovery.stop(); return;
        }
        if (nativeAcquisitionActive()) {
            stopAfterStep = true; paused = false; airRecovery.stop();
            requestNativeDrain(NativeRun.DrainReason.STOP); return;
        }
        stopAfterStep = true; paused = false;
        stopStationCleanup();
        if (cleanupReturnBarrier()) {
            status = "stopping after retained owned station RETURN settlement";
            return;
        }
        resetAction();
        if (cleanupReturnBarrier() || stationPlacementHand != null || nativeAcquisitionActive()) return;
        retireMovementProgressScopes();
        cleanupBudget = null;
        cancelStationPlacement();
        airRecovery.stop();
        stationPlacementOwner = null; stationPlacementStation = null;
        rejectedStationSites.clear(); stationPlacementFailures = 0;
        healthRecovery = null;
        ingredientWoodScan = null; ingredientWoodRequest = null; ingredientWoodOrigin = null;
        ingredientWoodSources.clear(); localIngredientWoodSources.clear();
        ingredientWoodSourceIds.clear(); localIngredientWoodSourceIds.clear();
        ingredientWoodExamined.clear(); ingredientWoodPublished.clear();
        localLogReachScan = localIngredientWoodReachScan = localGatherReachScan = null;
        localReachHint = null;
        resetLogDiscovery();
        if (pendingPlan != null) pendingPlan.cancel(false); pendingPlan = null;
        maintained.unmaintainAll();
        maintenanceQueue.clear();
        for (ProjectRun run : new ArrayList<>(projects)) abortProject(run);
        active = null; acquisitionScope = null; nativeRun = null; nativeRunParent = null; nativeRunScope = null;
        travelAction = null; travelActionParent = null; travelShieldPreflightParent = null; travelFoodPreflightParent = null; travelShieldScope = null; travelFoodScope = null; travelFoodOffer = null; travelFoodPreparation = null; travelFoodDemand = null; travelFoodInvalidated = false; cancelledRequest = null; queue.clear(); paused = false; foregroundYieldPending = false; stopAfterStep = false;
        unmaintainAfterStep.clear(); maintainAfterStep.clear(); status = "idle";
        if (announce) message("Stopped");
    }
    void clearQueue() {
        queue.clear();
        for (ProjectRun run : new ArrayList<>(projects)) abortProject(run);
        foregroundYieldPending = false;
        message("Foreground queue cleared; maintained targets remain active");
    }
    boolean visualizationActive() {
        return client.player != null && client.world != null && client.world == world && active != null;
    }
    private boolean editingSettings() {
        var screen = client.currentScreen;
        return screen instanceof AutomationSettingsScreen || screen instanceof NavigationPreferencesScreen
                || screen instanceof ClaimsScreen;
    }
    boolean visualizationPaused() { return paused || editingSettings() || config.pauseOnScreen && client.currentScreen != null
                && crafting == null && stonecutting == null && smelting == null && !openingStation && cleanupRun == null; }
    String visualizationGoal() {
        if (active == null) return "Idle";
        if (acquisitionView() == null) return active.name() + " · job " + active.jobToken();
        return (activeProject() == null ? active.name() : activeProject().spec.name() + " · " + acquisitionView().item().path())
                + " · " + goalCount() + "/" + acquisitionView().count();
    }
    String visualizationDetail() { return visualizationPaused() && !paused ? "Waiting for the screen to close" : status; }
    BlockPos visualizationTarget() { return visualizationActive() && !visualizationPaused() ? diagnosticTarget() : null; }
    dev.lodekeeper.nav.NavigationSnapshot visualizationNavigation(boolean includeNodes) {
        if (visualizationPaused()) return dev.lodekeeper.nav.NavigationSnapshot.EMPTY;
        var snapshot = visualizationActive() && (moving || explorationMoving || nativeAcquisitionActive()
                || stationRecovery.active() || threats.active())
                ? movement.visualization(includeNodes) : dev.lodekeeper.nav.NavigationSnapshot.EMPTY;
        return snapshot.withScene(snapshot.scene().withAdditionalMarkers(visualizationMarkers()));
    }

    private dev.lodekeeper.nav.NavigationSceneSnapshot.Marker[] visualizationMarkers() {
        if (client.player == null) return new dev.lodekeeper.nav.NavigationSceneSnapshot.Marker[0];
        var markers = new ArrayList<dev.lodekeeper.nav.NavigationSceneSnapshot.Marker>();
        if (config.showStations) {
            for (var record : placementProvenance.records()) {
                var p = record.position();
                markers.add(dev.lodekeeper.nav.NavigationSceneSnapshot.Marker.confirmedStation(p.x(), p.y(), p.z()));
            }
            if (cleanupRun != null && cleanupRun.current != null
                    && placementProvenance.session().filter(cleanupRun.session::equals).isPresent()) {
                var p = cleanupRun.current.position();
                markers.add(dev.lodekeeper.nav.NavigationSceneSnapshot.Marker.stationRecoveryPending(p.x(), p.y(), p.z()));
            }
        }
        if (config.showBackfill) markers.addAll(Arrays.asList(backfill.visualizationMarkers()));
        var player = client.player;
        return markers.stream().sorted(Comparator.comparingDouble(marker ->
                        marker.distanceSquared(player.getX(), player.getY(), player.getZ())))
                .limit(dev.lodekeeper.nav.NavigationSceneSnapshot.MAX_MARKERS)
                .toArray(dev.lodekeeper.nav.NavigationSceneSnapshot.Marker[]::new);
    }
    boolean placementStockReady() { return placementProvenance.confirmedInventoryReady(); }
    String placementInventoryReadiness() { return placementProvenance.inventoryReadiness(); }
    AnimalHarvestAction.Observation nativeAnimalObservation() { return animalAcquisition.observation(); }
    CropHarvestAction.Observation nativeCropObservation() { return cropAcquisition.observation(); }

    record TravelCompletionObservation(Object requestIdentity, NativeRun.TravelReceipt receipt,
                                       long acceptedNanos, long deadlineNanos, long completedNanos) { }
    private TravelCompletionObservation lastTravelCompletion;
    TravelCompletionObservation diagnosticTravelCompletion() { return lastTravelCompletion; }

    Object diagnosticTaskIdentity() {
        if (active != null) return activeProject() == null ? active : activeProject();
        Request next = queue.peekFirst();
        return next instanceof AcquireRequest acquire && acquire.project() != null ? acquire.project() : next;
    }
    dev.lodekeeper.nav.NavigationSnapshot diagnosticNavigation() {
        return (moving || explorationMoving || nativeAcquisitionActive() || stationRecovery.active() || threats.active()) ? movement.visualization(false) : dev.lodekeeper.nav.NavigationSnapshot.EMPTY;
    }
    BlockPos diagnosticTarget() { return stationRecovery.active() ? stationRecovery.position() : stationRoom.active() ? stationRoom.site() : target != null ? target : movement.miningTarget(); }
    dev.lodekeeper.nav.Goal diagnosticRouteGoal() { return movement.diagnosticGoal(); }
    int diagnosticRouteGoalCandidateCount() { return movement.diagnosticGoalCandidateCount(); }

    String diagnosticExecution() {
        var player = client.player;
        var handler = player == null ? null : player.currentScreenHandler;
        var station = step == null || step.station() == null ? null : knownStations.get(step.station());
        return " execution[opening=" + openingStation + ",moving=" + moving
                + ",crafting=" + (crafting != null) + ",smelting=" + (smelting != null)
                + ",actionTicks=" + actionTicks + ",station=" + station
                + ",handler=" + (handler == null ? "none" : handler.getClass().getSimpleName())
                + ",sneaking=" + (player != null && player.isSneaking()) + "]";
    }

    String status() {
        return (paused ? "paused · " : "") + status + (active == null ? "" : " · job " + active.jobToken()) + " · " + queue.size() + " foreground queued · "
                + maintenanceQueue.size() + " maintenance queued";
    }
    private static String requestSummary(Request request) {
        if (request == null) return "idle";
        return "job " + request.jobToken() + " " + request.name()
                + (request instanceof AcquireRequest acquire ? " · target " + acquire.count() + " " + acquire.item() : "");
    }
    void showQueue() {
        message("Active: " + requestSummary(active) + "; foreground: " + queue.stream().map(AutomationEngine::requestSummary).toList()
                + "; maintenance: " + maintenanceQueue.stream().map(AutomationEngine::requestSummary).toList());
    }
    void message(String message) { if (client.player != null) client.player.sendMessage(net.minecraft.text.Text.literal("[Lodekeeper] " + message), false); }
}
