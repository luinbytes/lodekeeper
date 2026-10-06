package dev.lodekeeper.fabric;

import dev.lodekeeper.core.*;
import dev.lodekeeper.nav.ExplorationFrontier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
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
    private static final StationId CRAFTING_TABLE = StationId.parse("minecraft:crafting_table");
    private static final StationId FURNACE = StationId.parse("minecraft:furnace");
    private static final StationId SMOKER = StationId.parse("minecraft:smoker");
    private static final StationId BLAST_FURNACE = StationId.parse("minecraft:blast_furnace");
    private static final TagId LOGS_TAG = TagId.parse("minecraft:logs");

    private record Request(String name, ItemId item, int count, boolean anyLogs,
                           String maintenanceTaskId, ProjectRun project) {
        boolean maintained() { return maintenanceTaskId != null; }
    }

    private enum LocalReachPurpose { LOG_CHOICE, RECIPE_WOOD, GATHER }

    private record PlanningOutcome(PlanResult result, boolean explorationProven, boolean auxiliaryInvestment) { }
    private record GatherLimit(ItemId output, int localPositions) { }
    private record LocalLogEvidence(Map<String, GatherLimit> sources, Set<ItemId> outputs,
                                    List<BlockState> states, int highestHandTicks, int leastSavingTicks) { }
    private record HarvestOffer(CatalogSnapshot catalog, InventorySnapshot inventory,
                                HarvestInvestment.ToolDemand demand, Map<String, GatherLimit> localSources,
                                Set<ItemId> localOutputs, Set<ItemId> goalLogItems, Set<String> nativeCraftSources,
                                Set<String> allowedAxeCraftSources, HarvestInvestment.TickEstimates estimates) { }

    private static final class LocalReachScan {
        private final Object world;
        private final Object dimension;
        private final Request request;
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

        LocalReachScan(Object world, Object dimension, Request request, long generation,
                       Set<Block> blocks, Set<String> sources, Set<BlockPos> rejected,
                       BlockPos origin, double eyeX, double eyeY, double eyeZ) {
            this.world = world;
            this.dimension = dimension;
            this.request = request;
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

        boolean matches(Object world, Object dimension, Request request, long generation,
                        Set<Block> blocks, Set<String> sources, Set<BlockPos> rejected, BlockPos origin) {
            return this.world == world && Objects.equals(this.dimension, dimension) && this.request == request
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

    private record LocalReachHint(Object world, Object dimension, Request request, long generation,
                                  Set<BlockPos> rejected, BlockPos origin, String sourceId,
                                  BlockPos position, Block block) { }

    private static final class ProjectRun {
        final ProjectSpec spec;
        final Set<ItemId> pending = new TreeSet<>();
        int reconciliationPasses;
        boolean aborted;

        ProjectRun(ProjectSpec spec) { this.spec = spec; }
        @Override public String toString() { return spec.name(); }
    }

    private final MinecraftClient client;
    final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    private final FoodController food;
    private final PassiveFoodAction foodAcquisition;
    private final PortableWorkbenchAction workbenchRecovery;
    final GameTerrain terrain;
    private final MovementController movement;
    private final ExecutorService plannerWorker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lodekeeper-planner"); t.setDaemon(true); return t; });
    private final Deque<Request> queue = new ArrayDeque<>();
    private final Deque<Request> maintenanceQueue = new ArrayDeque<>();
    private final Set<ProjectRun> projects = new LinkedHashSet<>();
    private final ProjectCatalog projectCatalog = ProjectCatalog.standard();
    private final MaintainedDemandModel maintained = new MaintainedDemandModel();
    private final Map<StationId, BlockPos> ownedStations = new HashMap<>();
    private final Set<BlockPos> rejectedStationSites = new HashSet<>();
    private final Set<BlockPos> createdWorkbenches = new HashSet<>(), unreachableStations = new HashSet<>();
    private BlockPos recoveringWorkbench;
    private boolean workbenchRecoveryChecked;
    private final Set<String> unavailableSources = new HashSet<>();
    private final Map<String, List<BlockPos>> discoveredSources = new LinkedHashMap<>();
    private final AcquisitionPlanner planner = new AcquisitionPlanner();
    private final NearbyResources nearbyResources;
    private long pendingPlanPreferencesVersion, stepPreferencesVersion;
    private int preferenceRefreshCooldown;
    private ClientWorld world;
    private GameCatalog catalog;
    private Request active;
    private CompletableFuture<PlanningOutcome> pendingPlan;
    private boolean previewPending;
    private long pendingPlanGeneration;
    private long stepCatalogGeneration;
    private PlanStep step;
    private BlockSearch scan;
    private BlockSearch logScan;
    private LocalReachScan localLogReachScan, localIngredientWoodReachScan, localGatherReachScan;
    private LocalReachHint localReachHint;
    private BlockSearch ingredientWoodScan;
    private Request ingredientWoodRequest;
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
    private Request logScanRequest;
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
    private int inventorySampleTicks, foodCooldown;
    private boolean foodReplanPending, foodAcquisitionPending;
    private int foodAcquisitionCooldown, stationAccessFailures;
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
        nearbyResources = new NearbyResources(client);
        actions = new PlayerActions(client); input = new BotInput(client); terrain = new GameTerrain(client, config);
        movement = new MovementController(client, config, actions, input, terrain);
        food = new FoodController(client, actions);
        foodAcquisition = new PassiveFoodAction(client, config, actions, movement);
        workbenchRecovery = new PortableWorkbenchAction(client, config, actions, movement);
    }
    boolean prepareAutomatedBreak(BlockPos position) { return movement.prepareAutomatedBreak(position); }
    void dispose() {
        try { stopNow(false); }
        finally { plannerWorker.shutdownNow(); movement.shutdownUpstream(); }
    }

    void tick() {
        discoveryBudgetStarted = false;
        discoveryDeadlineNanos = 0;
        if (client.world != world) {
            stopNow(false); world = client.world; ownedStations.clear(); createdWorkbenches.clear(); unreachableStations.clear(); unavailableSources.clear(); discoveredSources.clear(); catalog = null;
            localLogReachScan = localIngredientWoodReachScan = localGatherReachScan = null; localReachHint = null;
            recipeRefreshPending = false;
            nearbyResources.reset();
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
        if (client.player == null || client.world == null) { foodAcquisition.stop(); food.stop(); movement.suspend(); input.release(); return; }
        if (!movement.finishCancellation()) { status = "finishing movement before inventory actions"; return; }
        nearbyResources.tick(catalog, config.scanBlocksPerTick);
        if (preferenceRefreshCooldown > 0) preferenceRefreshCooldown--;
        if (foodCooldown > 0) foodCooldown--;
        if (foodAcquisitionCooldown > 0) foodAcquisitionCooldown--;
        try {
        if (++inventorySampleTicks >= INVENTORY_SAMPLE_INTERVAL_TICKS) {
            inventorySampleTicks = 0;
            observeInventory();
        }
            if (foregroundYieldPending && canYieldMaintenanceNow()) yieldActiveMaintenance();
            if (active == null && !paused) startNextRequest();
            if (active == null || paused) { foodAcquisition.stop(); food.stop(); movement.suspend(); input.release(); return; }
            if (!client.player.isAlive() || client.player.getHealth() <= config.pauseBelowHealth) { pause("health safeguard"); return; }
            if (config.pauseOnScreen && client.currentScreen != null && crafting == null && stonecutting == null && smelting == null && !openingStation) { foodAcquisition.stop(); food.stop(); movement.suspend(); input.release(); return; }
            if (foregroundYieldPending && !transactionInProgress() && !openingStation && !canYieldMaintenanceNow()) {
                input.release(); status = "foreground queued; waiting for inventory screen and cursor to be safe"; return;
            }
            if (workbenchRecovery.active()) {
                status = workbenchRecovery.status();
                try {
                    if (workbenchRecovery.tick()) {
                        createdWorkbenches.remove(recoveringWorkbench);
                        ownedStations.remove(CRAFTING_TABLE, recoveringWorkbench);
                        recoveringWorkbench = null;
                        observeInventory();
                        requestPlan();
                    }
                } catch (RuntimeException failure) {
                    workbenchRecovery.stop();
                    message("Workbench recovery is replanning: " + failure.getMessage());
                    requestPlan();
                }
                return;
            }
            food.updateProtection(foodReservations());
            if (food.active()) {
                input.release(); status = "eating before continuing " + active.name();
                if (!config.autoEat || client.currentScreen != null) { food.stop(); requestPlan(); }
                else if (food.tick()) requestPlan();
                return;
            }
            if (foodAcquisition.active()) {
                status = foodAcquisition.status();
                try {
                    if (foodAcquisition.tick()) {
                        foodAcquisitionCooldown = 20;
                        observeInventory();
                        requestPlan();
                    }
                } catch (RuntimeException failure) {
                    foodAcquisition.stop();
                    foodAcquisitionCooldown = 200;
                    message("Food acquisition is replanning: " + failure.getMessage());
                    requestPlan();
                }
                return;
            }
            if (foodAcquisitionPending) {
                foodAcquisitionPending = false;
                if (config.autoEat && foodAcquisition.begin()) status = foodAcquisition.status();
                else { foodAcquisitionCooldown = 200; requestPlan(); }
                return;
            }
            if (foodReplanPending) { requestPlan(); return; }
            if (active != null && !exploring && pendingPlan == null && step == null) {
                if (!catalog.ready()) { status = "waiting for recipe catalog"; return; }
                requestPlan();
            }
            if (config.autoEat && foodCooldown == 0 && !stopAfterStep && !transactionInProgress()
                    && !openingStation && !hasOwnedStationHandlerOpen() && food.ready()) {
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                resetAction();
                if (!movement.finishCancellation()) { foodReplanPending = true; return; }
                foodCooldown = 100;
                if (food.begin()) { foodReplanPending = true; status = "eating before continuing " + active.name(); }
                else requestPlan();
                return;
            }
            if (config.autoEat && foodAcquisitionCooldown == 0 && !stopAfterStep
                    && !transactionInProgress() && !openingStation && !hasOwnedStationHandlerOpen()
                    && client.currentScreen == null && !food.ready()
                    && (client.player.getHungerManager().getFoodLevel() <= 14
                        || needsMiningFoodStock())
                    && foodAcquisition.ready()) {
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                resetAction();
                foodAcquisitionPending = true;
                status = "finishing movement before acquiring food";
                return;
            }
            if (exploring) { explore(); return; }
            if (pendingPlan != null && (pendingPlan.isDone() || !pendingPreferencePlan || step == null)) {
                if (!pendingPlan.isDone()) return;
                PlanningOutcome outcome = pendingPlan.join();
                if (pendingPreferencePlan && step != null) {
                    pendingPlan = null;
                    pendingPreferencePlan = false;
                    stepPreferencesVersion = pendingPlanPreferencesVersion;
                    PlanResult preferred = outcome.result();
                    if (preferred.success() && !preferred.steps().isEmpty()
                            && !preferred.steps().get(0).sourceId().equals(step.sourceId())) {
                        resetAction();
                        requestPlan();
                        return;
                    }
                } else {
                    PlanResult result = outcome.result();
                    long resultGeneration = pendingPlanGeneration;
                    pendingPlan = null;
                    if (!catalog.ready() || resultGeneration != catalog.generation()) {
                        if (catalog.ready()) requestPlan();
                        else status = "waiting for recipe catalog";
                        return;
                    }
                    if (!result.success() && pendingPlanPreferencesVersion != nearbyResources.version()) {
                        requestPlan();
                        return;
                    }
                    if (outcome.auxiliaryInvestment() && result.steps().isEmpty()) { requestPlan(); return; }
                    if (goalCount() >= active.count) { finishGoal(); return; }
                    if (!result.success() && planningRetries++ < 4 && result.blockedReasons().stream().anyMatch(r -> r.code() == BlockedReason.Code.TIME_LIMIT)) { requestPlan(); return; }
                    if (!result.success() && tryNextLogPlan(result)) return;
                    if (!result.success() && canExplore(outcome)) { beginExploration(); return; }
                    if (!result.success()) { failActive("No plan: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(3).toList()); return; }
                    if (result.steps().isEmpty()) { finishGoal(); return; }
                    begin(result.steps().get(0), resultGeneration, outcome.auxiliaryInvestment(), pendingPlanPreferencesVersion);
                }
            }
            if (step == null) return;
            if (stepCatalogGeneration != catalog.generation() || !catalog.ready()) {
                if (crafting != null || stonecutting != null || smelting != null) {
                    // The action owns an immutable RecipeWork snapshot. Let it finish the
                    // in-flight transfer and safely drain before replanning against new data.
                    requestActiveTransactionDrain();
                } else {
                    if (openingStation && !stationReady()) return;
                    resetAction();
                    if (catalog.ready()) requestPlan();
                    else status = "waiting for recipe catalog";
                    return;
                }
            }
            if (step.kind() == PlanKind.GATHER && target == null && scan != null
                    && stepPreferencesVersion != nearbyResources.version() && preferenceRefreshCooldown == 0) {
                preferenceRefreshCooldown = 20;
                resetAction();
                requestPlan();
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
                    if (active.anyLogs) resetLogDiscovery();
                    requestPlan();
                    pendingPreferencePlan = pendingPlan != null;
                    if (step == null) return;
                }
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
            if (step.output() != null && crafting == null && stonecutting == null && smelting == null
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
                        message("Mining is replanning: " + blocked.getMessage());
                        if (blocked.kind != MovementController.NavigationFailure.Kind.TOOL)
                            unavailableSources.add(step.sourceId());
                        resetAction(); requestPlan();
                    } else if (step.kind() == PlanKind.PLACE_STATION && target != null
                            && stationPlacementFailures < MAX_STATION_PLACEMENT_ATTEMPTS) {
                        movement.stop();
                        moving = false;
                        rejectStationSite(target);
                    } else if ((step.kind() == PlanKind.CRAFT || step.kind() == PlanKind.SMELT)
                            && step.station() != null && stationAccessFailures++ < 3) {
                        BlockPos unreachable = ownedStations.remove(step.station());
                        if (unreachable != null) unreachableStations.add(unreachable);
                        message("Station access is replanning: " + blocked.getMessage());
                        resetAction(); requestPlan();
                    } else throw blocked;
                }
                return;
            }
            switch (step.kind()) {
                case GATHER -> gather();
                case PLACE_STATION -> placeStation();
                case CRAFT -> craft();
                case SMELT -> smelt();
                case CUSTOM -> throw new IllegalStateException("No executor registered for " + step.customType());
            }
        } catch (RuntimeException ex) { failActive(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
    }
    void enqueue(String name, int count) {
        ensureNotStopping();
        ensureCatalog();
        boolean logs = name.equalsIgnoreCase("wood") || name.equalsIgnoreCase("logs");
        ItemId item = logs ? ItemId.parse("minecraft:oak_log") : resolve(name);
        if (queue.size() >= MAX_FOREGROUND_QUEUE) throw new IllegalStateException("Foreground queue limit is " + MAX_FOREGROUND_QUEUE + " goals");
        yieldMaintenanceForForeground();
        queue.addLast(new Request(name, item, count, logs, null, null));
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
        yieldMaintenanceForForeground();
        ProjectRun run = new ProjectRun(spec);
        projects.add(run);
        Set<ItemId> gatheringTools = new HashSet<>();
        for (AcquisitionSource source : catalog.sources) if (source instanceof GatherSource)
            for (Requirement requirement : source.requirements()) if (requirement instanceof ToolRequirement tool)
                tool.tools().alternatives().forEach(selector -> gatheringTools.addAll(snapshot.expand(selector)));
        spec.goals().entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<ItemId, Integer> entry) -> gatheringTools.contains(entry.getKey()) ? 0 : 1)
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> {
            ItemId item = entry.getKey(); int count = entry.getValue();
            run.pending.add(item);
            queue.addLast(new Request("project " + spec.name() + " · " + item, item, count, false, null, run));
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
        if (active != null && active.maintained() && unmaintainAfterStep.contains(active.item())) requestActiveTransactionDrain();
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
        ItemId item = name == null ? active != null ? active.item : queue.isEmpty() ? maintenanceQueue.isEmpty() ? null : maintenanceQueue.peekFirst().item : queue.peekFirst().item : resolve(name);
        if (item == null) { message("No active or queued goal"); return; }
        CatalogSnapshot snapshot = catalog.snapshot(); InventorySnapshot inventory = inventorySnapshot(item);
        var previewWorld = client.world;
        PlanningPreferences preferences = nearbyResources.snapshot();
        long previewGeneration = catalog.generation();
        previewPending = true;
        CompletableFuture.supplyAsync(() -> previewPlan(snapshot, inventory, item, count, preferences), plannerWorker).whenComplete((result, failure) -> client.execute(() -> {
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
    private PlanResult previewPlan(CatalogSnapshot snapshot, InventorySnapshot inventory, ItemId item, int count, PlanningPreferences preferences) {
        PlanResult result = planner.planFast(snapshot, inventory, item, count, PlannerLimits.DEFAULT, preferences);
        for (int retry = 0; retry < 4 && !result.success()
                && result.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.TIME_LIMIT); retry++) {
            result = planner.planFast(snapshot, inventory, item, count, PlannerLimits.DEFAULT, preferences);
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
    private InventorySnapshot inventorySnapshot(ItemId activeTarget) {
        Map<ItemId, Integer> counts = new HashMap<>(observedInventory), durability = new HashMap<>();
        Map<ItemId, List<Integer>> durabilityLots = new HashMap<>();
        Map<ItemId, List<InventoryToolLot>> toolLots = new HashMap<>();
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (!stack.isEmpty() && stack.isDamageable()) {
            ItemId item = GameCatalog.id(stack.getItem());
            int remaining = stack.getMaxDamage() - stack.getDamage();
            durability.merge(item, remaining, Math::max);
            durabilityLots.computeIfAbsent(item, ignored -> new ArrayList<>()).add(remaining);
            toolLots.computeIfAbsent(item, ignored -> new ArrayList<>())
                    .add(new InventoryToolLot(remaining, GameApi.hasSilkTouch(stack)));
        }
        Set<StationId> stations = new HashSet<>();
        ownedStations.forEach((id, pos) -> { if (client.world.getBlockState(pos).getBlock() == Registries.BLOCK.get(GameApi.identifier(id.toString()))) stations.add(id); });
        Map<ItemId, Integer> protectedCounts = protectedCounts(counts, activeTarget);
        return new InventorySnapshot(counts, stations, durability, protectedCounts, durabilityLots, toolLots);
    }

    private void startNextRequest() {
        while (active == null) {
            if (!queue.isEmpty()) {
                active = queue.removeFirst();
            } else if (!maintenanceQueue.isEmpty()) {
                Request request = maintenanceQueue.removeFirst();
                if (!hasActiveMaintenance(request.maintenanceTaskId())) continue;
                if (observedInventory.getOrDefault(request.item(), 0) >= request.count()) {
                    scheduleMaintenanceRequests(maintained.complete(request.maintenanceTaskId(), observedInventory));
                    continue;
                }
                active = request;
            } else {
                return;
            }
            unavailableSources.clear();
            frontier = null; rejectedResources.clear(); lastResourceFailure = null; resetLogDiscovery();
            planningRetries = 0; stationAccessFailures = 0; unreachableStations.clear();
            requestPlan();
        }
    }

    private boolean hasActiveMaintenance(String taskId) {
        return taskId != null && maintained.activeRequests().stream().anyMatch(request -> request.taskId().equals(taskId));
    }

    private void yieldMaintenanceForForeground() {
        if (active == null || !active.maintained()) return;
        foregroundYieldPending = true;
        if (canYieldMaintenanceNow()) {
            yieldActiveMaintenance();
        } else {
            status = "foreground queued; finishing or safely closing maintenance work";
            requestActiveTransactionDrain();
        }
    }

    private boolean transactionInProgress() {
        return step != null && (step.kind() == PlanKind.CRAFT || step.kind() == PlanKind.SMELT)
                && (crafting != null || stonecutting != null || smelting != null);
    }

    private boolean isActiveMaintenanceTransaction(ItemId item) {
        return active != null && active.maintained() && active.item().equals(item)
                && (transactionInProgress() || openingStation && step != null
                && (step.kind() == PlanKind.CRAFT || step.kind() == PlanKind.SMELT));
    }

    private boolean shouldDrainActiveTransaction() {
        return stopAfterStep || active != null && active.maintained()
                && (foregroundYieldPending || unmaintainAfterStep.contains(active.item())
                || maintainAfterStep.containsKey(active.item()));
    }

    private void requestActiveTransactionDrain() {
        if (crafting != null) crafting.requestDrain();
        if (stonecutting != null) stonecutting.requestDrain();
        if (smelting != null) smelting.requestDrain();
    }

    private boolean canYieldMaintenanceNow() {
        if (active == null || !active.maintained() || client.player == null || transactionInProgress()
                || crafting != null || stonecutting != null || smelting != null || openingStation
                || client.player.currentScreenHandler != client.player.playerScreenHandler) return false;
        return client.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    private void yieldActiveMaintenance() {
        if (!foregroundYieldPending || !canYieldMaintenanceNow()) return;
        Request yielded = active;
        if (pendingPlan != null) pendingPlan.cancel(false);
        pendingPlan = null;
        active = null;
        resetAction();
        maintenanceQueue.addFirst(yielded);
        foregroundYieldPending = false;
        status = "maintenance waiting for foreground goals";
    }

    private void cancelMaintenanceTasks(List<String> taskIds) {
        if (taskIds.isEmpty()) return;
        Set<String> cancelled = Set.copyOf(taskIds);
        maintenanceQueue.removeIf(request -> cancelled.contains(request.maintenanceTaskId()));
        if (active != null && cancelled.contains(active.maintenanceTaskId())) {
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = null;
            active = null;
            resetAction();
            foregroundYieldPending = false;
            status = "idle";
        }
    }

    private void scheduleMaintenanceRequests(List<MaintainedDemandModel.MaintenanceRequest> requests) {
        for (MaintainedDemandModel.MaintenanceRequest request : requests) {
            if (maintenanceQueue.stream().anyMatch(queued -> request.taskId().equals(queued.maintenanceTaskId()))
                    || active != null && request.taskId().equals(active.maintenanceTaskId())) continue;
            if (maintenanceQueue.size() + (active != null && active.maintained() ? 1 : 0) >= MAX_MAINTENANCE_QUEUE) {
                scheduleMaintenanceRequests(maintained.fail(request.taskId(), observedInventory));
                continue;
            }
            maintenanceQueue.addLast(new Request("maintain " + request.item(), request.item(), request.targetCount(), false,
                    request.taskId(), null));
        }
    }

    private void observeInventory() {
        if (client.player == null) return;
        long fingerprint = inventoryFingerprint();
        if (inventoryFingerprintInitialized && fingerprint == lastInventoryFingerprint) return;
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
        return hash;
    }

    private Map<ItemId, Integer> captureInventoryCounts() {
        Map<ItemId, Integer> counts = new TreeMap<>();
        actions.inventory().forEach((name, count) -> counts.put(ItemId.parse(name), count));
        return Map.copyOf(counts);
    }

    private Map<ItemId, Integer> protectedCounts(Map<ItemId, Integer> counts, ItemId activeTarget) {
        Map<ItemId, Integer> protectedCounts = new TreeMap<>();
        maintained.reservedCountsFor(activeTarget).forEach((item, count) -> protectedCounts.merge(item, count, Math::max));
        for (ProjectRun run : projects) {
            if (run.aborted) continue;
            run.spec.goals().forEach((item, targetCount) -> {
                if (item.equals(activeTarget)) return;
                int held = Math.min(counts.getOrDefault(item, 0), targetCount);
                if (held > 0) protectedCounts.merge(item, held, Math::max);
            });
        }
        return Map.copyOf(protectedCounts);
    }

    private boolean needsMiningFoodStock() {
        if (step == null || step.kind() != PlanKind.GATHER || food.availableNutrition() >= 36) return false;
        return step.candidateBlocks().stream().anyMatch(block -> {
            String id = block.toString();
            return id.equals("minecraft:iron_ore") || id.equals("minecraft:deepslate_iron_ore")
                    || id.equals("minecraft:diamond_ore") || id.equals("minecraft:deepslate_diamond_ore")
                    || id.equals("minecraft:coal_ore") || id.equals("minecraft:deepslate_coal_ore");
        });
    }

    private Map<ItemId, Integer> foodReservations() {
        Map<ItemId, Integer> result = new HashMap<>(maintained.reservedCounts());
        for (ProjectRun run : projects) if (!run.aborted)
            run.spec.goals().forEach((item, count) -> result.merge(item, count, Math::max));
        if (active != null) result.merge(active.item(), active.count(), Math::max);
        for (Request request : queue) result.merge(request.item(), request.count(), Math::max);
        if (step != null) {
            Map<ItemId, Integer> inputs = new HashMap<>();
            for (SelectedRequirement requirement : step.requirements())
                if (requirement instanceof SelectedItemRequirement item)
                    inputs.merge(item.item(), item.count(), Math::addExact);
            inputs.forEach((item, count) -> result.merge(item, count, Math::addExact));
        }
        return Map.copyOf(result);
    }

    private void requestPlan() {
        pendingPreferencePlan = false;
        foodReplanPending = false;
        ensureCatalog(); status = "planning";
        if (!catalog.ready()) { status = "waiting for recipe catalog"; return; }
        if (!nearbyResources.ready()) { status = "indexing local resource options"; return; }
        observeInventory();
        if (goalCount() >= active.count) { finishGoal(); return; }
        ItemId item = active.item;
        if (active.anyLogs) {
            item = chooseLogs();
            if (item == null) {
                LocalReachScan logReachScan = localReachScan(LocalReachPurpose.LOG_CHOICE);
                boolean localPending = !localLogSources.isEmpty() && (logReachScan == null || !logReachScan.complete());
                if (logScan == null && !localPending && (logSources.isEmpty() || logScanComplete)) beginExploration();
                return;
            }
        }
        CatalogSnapshot full = catalog.snapshot();
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        if (!unavailableSources.isEmpty()) {
            full.itemDefinitions().values().forEach(builder::item); catalog.tags.forEach(builder::tag);
            catalog.sources.stream().filter(s -> !unavailableSources.contains(s.sourceId())).forEach(builder::source);
        }
        CatalogSnapshot snapshot = unavailableSources.isEmpty() ? full : builder.build();
        Set<String> excludedGatherSourceIds = catalog.sources.stream()
                .filter(GatherSource.class::isInstance).map(GatherSource.class::cast)
                .filter(source -> unavailableSources.contains(source.sourceId()))
                .map(GatherSource::sourceId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean explorationEnabled = config.allowExploration;
        InventorySnapshot inventory = inventorySnapshot(active.item);
        int requested = active.anyLogs ? active.count - goalCount() + inventory.count(item) : active.count;
        final ItemId targetItem = item; final int targetCount = requested;
        HarvestOffer harvestOffer = active.anyLogs && config.optimizeWoodTools
                ? captureHarvestOffer(active.count - goalCount()) : null;
        PlanningPreferences preferences = nearbyResources.snapshot();
        pendingPlanPreferencesVersion = nearbyResources.version();
        pendingPlanGeneration = catalog.generation();
        CatalogSnapshot filteredSnapshot = snapshot;
        pendingPlan = CompletableFuture.supplyAsync(() -> {
            PlanResult filteredPlan = planner.planFast(filteredSnapshot, inventory, targetItem, targetCount, PlannerLimits.DEFAULT, preferences);
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
            PlanResult fullPlan = planner.planFast(full, inventory, targetItem, targetCount, PlannerLimits.DEFAULT, preferences);
            return new PlanningOutcome(filteredPlan,
                    ExplorationRecovery.provesExploration(filteredPlan, fullPlan, full, excludedGatherSourceIds), false);
        }, plannerWorker);
    }

    private HarvestOffer captureHarvestOffer(int remainingBlocks) {
        if (remainingBlocks < 1 || logScan != null || logCandidates.isEmpty() || client.player == null
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
                case SMELT, CUSTOM -> { return false; }
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
        if (active == null || active.anyLogs || step == null || step.output() == null
                || active.item.equals(step.output())
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
        if (ingredientWoodRequest != active || ingredientWoodGeneration != catalog.generation()
                || ingredientWoodOrigin == null || feet.getSquaredDistance(ingredientWoodOrigin) > 256
                || ingredientWoodRejectedCount != rejectedResources.size()) {
            ingredientWoodRequest = active;
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
                    localReachHint = new LocalReachHint(client.world, client.world.getRegistryKey(), active,
                            catalog.generation(), Set.copyOf(rejectedResources), client.player.getBlockPos(),
                            source.sourceId(), local.toImmutable(), block);
                }
                foundNewSource = true;
            }
            if (foundNewSource) {
                resetAction(); requestPlan(); return true;
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
            resetAction(); requestPlan(); return true;
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
        if (logScanRequest != active || logScanGeneration != catalog.generation()) {
            resetLogDiscovery();
            logScanRequest = active;
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
            localReachHint = new LocalReachHint(client.world, client.world.getRegistryKey(), active,
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
        if (!active.anyLogs || logCandidates.isEmpty() || result.blockedReasons().isEmpty()
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
        requestPlan();
        return true;
    }

    private int goalCount() {
        if (!active.anyLogs) return actions.count(GameCatalog.item(active.item));
        int count = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (stack.isIn(ItemTags.LOGS)) count += stack.getCount();
        return count;
    }
    private void begin(PlanStep next, long plannedGeneration, boolean auxiliaryInvestment, long plannedPreferencesVersion) {
        if (!catalog.ready() || catalog.generation() != plannedGeneration) {
            if (catalog.ready()) requestPlan();
            else status = "waiting for recipe catalog";
            return;
        }
        resetAction(); rejectedStationSites.clear(); stationPlacementFailures = 0; stationDiscoveryDone = false;
        workbenchRecoveryChecked = false;
        step = next; stepCatalogGeneration = plannedGeneration; actionTicks = 0;
        lastMovementProgressToken = movement.progressToken();
        stepPreferencesVersion = plannedPreferencesVersion;
        status = (auxiliaryInvestment ? "tool investment · " : "") + next.kind() + " " + next.sourceId();
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
        resetAction();
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
        if (goalCount() >= active.count) { finishGoal(); return; }
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
                    movement.stop(); unavailableSources.clear(); resetLogDiscovery(); resetAction(); planningRetries = 0; requestPlan();
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
        if (localReachScan == null || !localReachScan.matches(client.world, dimension, active, catalog.generation(),
                blocks, sourceIds, rejected, origin)) {
            localReachScan = new LocalReachScan(client.world, dimension, active, catalog.generation(), blocks,
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
                || !Objects.equals(hint.dimension(), client.world.getRegistryKey()) || hint.request() != active
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
        if (!config.allowBreaking) throw new IllegalStateException("Gathering requires allowBreaking=true");
        if (!workbenchRecoveryChecked) {
            workbenchRecoveryChecked = true;
            BlockPos workbench = ownedStations.get(CRAFTING_TABLE);
            if (createdWorkbenches.contains(workbench) && workbenchRecovery.begin(workbench)) {
                recoveringWorkbench = workbench;
                status = workbenchRecovery.status();
                return;
            }
        }
        Set<Block> blocks = new LinkedHashSet<>();
        step.candidateBlocks().forEach(id -> {
            Block block = Registries.BLOCK.get(GameApi.identifier(id.toString()));
            if (block != Blocks.AIR) blocks.add(block);
        });
        SelectedToolRequirement tool = step.requirements().stream().filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
        if (tool != null && !actions.hasTool(tool)) { resetAction(); requestPlan(); return; }
        refreshNavigationProtection();
        movement.startMining(blocks.toArray(Block[]::new), GameCatalog.item(step.output()),
                Math.addExact(baseline, step.outputCount()), tool);
        moving = true; movingPickup = false; status = movement.status();
    }

    private void refreshNavigationProtection() {
        Set<net.minecraft.item.Item> reserved = new HashSet<>();
        protectedCounts(captureInventoryCounts(), active == null ? null : active.item).keySet()
                .forEach(item -> reserved.add(GameCatalog.item(item)));
        if (active != null) reserved.add(GameCatalog.item(active.item));
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
        for (BlockPos position : ownedStations.values()) if (client.world != null)
            stations.add(client.world.getBlockState(position).getBlock());
        movement.updateProtection(reserved, stations);
    }
    private void placeStation() {
        Block block = Registries.BLOCK.get(GameApi.identifier(step.station().toString()));
        BlockPos existing = findExistingStation(block);
        if (existing != null) {
            ownedStations.put(step.station(), existing);
            terrain.changed();
            completeStep();
            return;
        }
        if (!config.allowBuilding) {
            stationDiscoveryDone = false;
            throw new IllegalStateException("Station " + step.station() + " is not available nearby, and allowBuilding=false; enable it with config allowBuilding true");
        }
        if (target != null && client.world.getBlockState(target).isOf(block)) { ownedStations.put(step.station(), target); terrain.changed(); completeStep(); return; }
        if (target != null && !safeStationStructure(target)) rejectStationSite(target);
        if (stationPlacementFailures >= MAX_STATION_PLACEMENT_ATTEMPTS)
            throw stationPlacementFailure("placement attempt limit reached", client.player.getBlockPos());
        if (target == null) target = findStationCandidate(true);
        if (target == null) target = findStationCandidate(false);
        if (target == null) {
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
            movement.startInteraction(target);
            moving = true;
            return;
        }
        boolean placed = actions.place(target, block);
        if (placed && block == Blocks.CRAFTING_TABLE) createdWorkbenches.add(target.toImmutable());
        if (!placed) {
            BlockPos rejected = target;
            rejectStationSite(rejected);
            if (stationPlacementFailures >= MAX_STATION_PLACEMENT_ATTEMPTS) {
                throw stationPlacementFailure("placement was rejected at " + rejected, rejected);
            }
        }
    }

    private BlockPos findExistingStation(Block block) {
        if (stationDiscoveryDone) return null;
        stationDiscoveryDone = true;
        BlockPos known = ownedStations.get(step.station());
        if (known != null && !unreachableStations.contains(known) && !rejectedStationSites.contains(known) && hasLoadedChunk(known) && client.world.getBlockState(known).isOf(block)) return known;
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
            if (distance < 2 || distance >= closestDistance || rejectedStationSites.contains(candidate)) continue;
            if (!safeStationStructure(candidate) || requireReach && !actions.canPlaceAt(candidate)) continue;
            closest = candidate;
            closestDistance = distance;
        }
        return closest;
    }

    private boolean safeStationStructure(BlockPos candidate) {
        if (client.world.getChunkManager().getChunk(candidate.getX() >> 4, candidate.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.FULL, false) == null) return false;
        return client.world.getBlockState(candidate).isReplaceable() && actions.safePlacementSupport(candidate.down());
    }

    private void rejectStationSite(BlockPos rejected) {
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
        BlockPos station = ownedStations.get(step.station());
        if (station == null) throw new IllegalStateException("Required station disappeared");
        if (actions.hit(station) == null) { refreshNavigationProtection(); movement.startInteraction(station); moving = true; return false; }
        stationOpeningFrom = handler;
        openingStation = actions.use(station);
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
                            && catalog.usesCurrentProvider());
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
            smelting = new SmeltingAction(client, actions, recipe, step);
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
        if (stopAfterStep) { stopNow(true); return; }
        resetAction(); planningRetries = 0;
        if (applyDeferredUnmaintain()) return;
        if (foregroundYieldPending && active != null && active.maintained()) {
            if (canYieldMaintenanceNow()) yieldActiveMaintenance();
            else { status = "foreground queued; waiting for inventory screen and cursor to be safe"; return; }
            return;
        }
        requestPlan();
    }

    private boolean applyDeferredUnmaintain() {
        if (active == null || !active.maintained() || !unmaintainAfterStep.remove(active.item())) return false;
        ItemId item = active.item();
        Integer replacementTarget = maintainAfterStep.remove(item);
        active = null;
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
    private void finishGoal() {
        Request finished = active;
        if (finished == null) return;
        observeInventory();
        active = null; pendingPlan = null; resetAction(); status = "idle";
        if (finished.maintained()) {
            scheduleMaintenanceRequests(maintained.complete(finished.maintenanceTaskId(), observedInventory));
            message("Maintained target reached: " + finished.item() + " " + observedInventory.getOrDefault(finished.item(), 0) + "/" + finished.count());
        } else if (finished.project() != null && !finished.project().aborted) {
            completeProjectGoal(finished.project(), finished.item());
        } else if (finished.project() != null) {
            message("Completed project item target: " + finished.item() + " " + finished.count());
        } else {
            message("Complete: " + finished.count() + " × " + finished.name());
        }
    }

    private void completeProjectGoal(ProjectRun run, ItemId completedItem) {
        if (run.aborted || !projects.contains(run)) return;
        run.pending.remove(completedItem);
        if (!run.pending.isEmpty()) return;

        List<Map.Entry<ItemId, Integer>> missing = run.spec.goals().entrySet().stream()
                .filter(goal -> observedInventory.getOrDefault(goal.getKey(), 0) < goal.getValue()).toList();
        if (missing.isEmpty()) {
            projects.remove(run);
            message("Project complete: " + run.spec.name() + " (all " + run.spec.goals().size() + " inventory targets are present)");
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
            queue.addLast(new Request("project " + run.spec.name() + " · " + goal.getKey(), goal.getKey(),
                    goal.getValue(), false, null, run));
        }
        message("Rechecking project " + run.spec.name() + " after inventory changed (pass "
                + run.reconciliationPasses + "/" + MAX_PROJECT_RECONCILIATIONS + ")");
    }

    private void abortProject(ProjectRun run) {
        if (run == null || run.aborted) return;
        run.aborted = true;
        run.pending.clear();
        projects.remove(run);
        queue.removeIf(request -> request.project() == run);
    }

    private void failActive(String reason) {
        Request failed = active;
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
            active = null;
            observeInventory();
            scheduleMaintenanceRequests(maintained.fail(failed.maintenanceTaskId(), observedInventory));
            status = "maintenance blocked";
            message("Maintenance blocked for " + failed.item() + ": " + reason + ". It will retry after inventory changes or a new maintain command.");
            if (keepOwnedHandler) pause("Maintenance blocked while its owned container remains open; inspect it, close it, then resume");
            return;
        }
        if (failed != null && failed.project() != null) {
            abortProject(failed.project());
            active = null;
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = null;
            resetAction(!hasOwnedStationHandlerOpen());
            pause("Project " + failed.project().spec.name() + " blocked: " + reason);
            return;
        }
        pause(reason);
    }
    private String recoverTransactions() {
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
        workbenchRecovery.stop(); recoveringWorkbench = null;
        foodAcquisition.stop(); foodAcquisitionPending = false;
        food.stop(); foodReplanPending = false;
        ScreenHandler stationHandler = ownedStationHandler;
        boolean closeThisHandler = closeOwnedHandler && hasOwnedStationHandlerOpen();
        String warning;
        try { warning = recoverTransactions(); }
        finally { input.release(); }
        try { movement.stop(); }
        catch (RuntimeException ex) { message("Movement cancellation: " + ex.getMessage()); }
        finally {
            crafting = null; stonecutting = null; smelting = null; openingStation = false; moving = false; movingPickup = false;
            step = null; scan = null; localGatherReachScan = null;
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
    void pause(String reason) {
        foodAcquisition.stop(); foodAcquisitionPending = false;
        food.stop(); movement.suspend();
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
        paused = false; actionTicks = 0; lastMovementProgressToken = movement.progressToken();
        if (active != null && !exploring && pendingPlan == null && step == null) requestPlan();
        message(stopAfterStep ? "Resuming the safe drain before stopping" : "Resumed");
    }
    void stop() {
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
        resetAction(); active = null; queue.clear(); paused = false; foregroundYieldPending = false; stopAfterStep = false;
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
    boolean visualizationPaused() { return paused || config.pauseOnScreen && client.currentScreen != null
                && crafting == null && stonecutting == null && smelting == null && !openingStation; }
    String visualizationGoal() { return active == null ? "Idle" : (active.project() == null ? active.name : active.project().spec.name() + " · " + active.item().path()) + " · " + goalCount() + "/" + active.count; }
    String visualizationDetail() { return visualizationPaused() && !paused ? "Waiting for the screen to close" : status; }
    BlockPos visualizationTarget() { return visualizationActive() && !visualizationPaused() ? diagnosticTarget() : null; }
    dev.lodekeeper.nav.NavigationSnapshot visualizationNavigation(boolean includeNodes) {
        return visualizationActive() && !visualizationPaused() && (moving || explorationMoving || foodAcquisition.active() || workbenchRecovery.active())
                ? movement.visualization(includeNodes) : dev.lodekeeper.nav.NavigationSnapshot.EMPTY;
    }
    Object diagnosticTaskIdentity() { return active == null ? null : active.project() == null ? active : active.project(); }
    dev.lodekeeper.nav.NavigationSnapshot diagnosticNavigation() {
        return (moving || explorationMoving || foodAcquisition.active() || workbenchRecovery.active()) ? movement.visualization(false) : dev.lodekeeper.nav.NavigationSnapshot.EMPTY;
    }
    BlockPos diagnosticTarget() { return target != null ? target : movement.miningTarget(); }
    dev.lodekeeper.nav.Goal diagnosticRouteGoal() { return movement.diagnosticGoal(); }
    int diagnosticRouteGoalCandidateCount() { return movement.diagnosticGoalCandidateCount(); }

    String status() {
        return (paused ? "paused · " : "") + status + " · " + queue.size() + " foreground queued · "
                + maintenanceQueue.size() + " maintenance queued";
    }
    void showQueue() { message("Active: " + active + "; foreground: " + queue + "; maintenance: " + maintenanceQueue); }
    void message(String message) { if (client.player != null) client.player.sendMessage(net.minecraft.text.Text.literal("[Lodekeeper] " + message), false); }
}
