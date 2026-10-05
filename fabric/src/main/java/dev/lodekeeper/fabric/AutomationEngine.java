package dev.lodekeeper.fabric;

import dev.lodekeeper.core.*;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates one goal/action at a time. Pure planning runs on a single bounded worker. */
final class AutomationEngine {
    private static final int MAX_FOREGROUND_QUEUE = 32;
    private static final int MAX_MAINTENANCE_QUEUE = 32;
    private static final int MAX_PROJECT_RECONCILIATIONS = 3;
    private static final int INVENTORY_SAMPLE_INTERVAL_TICKS = 10;
    private static final int MAX_STATION_PLACEMENT_ATTEMPTS = 24;

    private record Request(String name, ItemId item, int count, boolean anyLogs,
                           String maintenanceTaskId, ProjectRun project) {
        boolean maintained() { return maintenanceTaskId != null; }
    }

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
    private final Set<String> unavailableSources = new HashSet<>();
    private final Map<String, List<BlockPos>> discoveredSources = new LinkedHashMap<>();
    private final AcquisitionPlanner planner = new AcquisitionPlanner();
    private ClientWorld world;
    private GameCatalog catalog;
    private Request active;
    private CompletableFuture<PlanResult> pendingPlan;
    private long pendingPlanGeneration;
    private long stepCatalogGeneration;
    private PlanStep step;
    private BlockSearch scan;
    private BlockPos target;
    private CraftingAction crafting;
    private SmeltingAction smelting;
    private boolean moving, paused, openingStation, foregroundYieldPending, stopAfterStep;
    private ScreenHandler ownedStationHandler, stationOpeningFrom;
    private final Set<ItemId> unmaintainAfterStep = new TreeSet<>();
    private final Map<ItemId, Integer> maintainAfterStep = new TreeMap<>();
    private int planningRetries;
    private int stationPlacementFailures;
    private boolean stationDiscoveryDone;
    private int actionTicks, baseline, verifyTicks, lastObservedCount;
    private int inventorySampleTicks, foodCooldown;
    private boolean foodReplanPending;
    private boolean inventoryFingerprintInitialized;
    private volatile boolean recipeRefreshPending;
    private long lastInventoryFingerprint;
    private Map<ItemId, Integer> observedInventory = Map.of();
    private long lastSmeltProgress;
    private String status = "idle";
    AutomationEngine(MinecraftClient client, LodekeeperConfig config) {
        this.client = client; this.config = config;
        actions = new PlayerActions(client); input = new BotInput(client); terrain = new GameTerrain(client, config);
        movement = new MovementController(client, config, actions, input, terrain);
        food = new FoodController(client, actions);
    }
    void tick() {
        if (client.world != world) {
            stopNow(false); world = client.world; ownedStations.clear(); unavailableSources.clear(); discoveredSources.clear(); catalog = null;
            recipeRefreshPending = false;
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
        if (client.player == null || client.world == null) { food.stop(); input.release(); return; }
        if (foodCooldown > 0) foodCooldown--;
        try {
        if (++inventorySampleTicks >= INVENTORY_SAMPLE_INTERVAL_TICKS) {
            inventorySampleTicks = 0;
            observeInventory();
        }
            if (foregroundYieldPending && canYieldMaintenanceNow()) yieldActiveMaintenance();
            if (active == null && !paused) startNextRequest();
            if (active == null || paused) { food.stop(); input.release(); return; }
            if (!client.player.isAlive() || client.player.getHealth() <= config.pauseBelowHealth) { pause("health safeguard"); return; }
            if (config.pauseOnScreen && client.currentScreen != null && crafting == null && smelting == null && !openingStation) { food.stop(); input.release(); return; }
            if (foregroundYieldPending && !transactionInProgress() && !openingStation && !canYieldMaintenanceNow()) {
                input.release(); status = "foreground queued; waiting for inventory screen and cursor to be safe"; return;
            }
            if (food.active()) {
                input.release(); status = "eating before continuing " + active.name();
                if (!config.autoEat || client.currentScreen != null) { food.stop(); requestPlan(); }
                else if (food.tick()) requestPlan();
                return;
            }
            if (foodReplanPending) { requestPlan(); return; }
            if (active != null && pendingPlan == null && step == null) {
                if (!catalog.ready()) { status = "waiting for recipe catalog"; return; }
                requestPlan();
            }
            if (config.autoEat && foodCooldown == 0 && !stopAfterStep && !transactionInProgress()
                    && !openingStation && !hasOwnedStationHandlerOpen() && food.ready()) {
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                resetAction();
                foodCooldown = 100;
                if (food.begin()) { foodReplanPending = true; status = "eating before continuing " + active.name(); }
                else requestPlan();
                return;
            }
            if (pendingPlan != null) {
                if (!pendingPlan.isDone()) return;
                PlanResult result = pendingPlan.join();
                long resultGeneration = pendingPlanGeneration;
                pendingPlan = null;
                if (!catalog.ready() || resultGeneration != catalog.generation()) {
                    if (catalog.ready()) requestPlan();
                    else status = "waiting for recipe catalog";
                    return;
                }
                if (!result.success() && planningRetries++ < 1 && result.blockedReasons().stream().anyMatch(r -> r.code() == BlockedReason.Code.TIME_LIMIT)) { requestPlan(); return; }
                if (!result.success()) { failActive("No plan: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(3).toList()); return; }
                if (result.steps().isEmpty()) { finishGoal(); return; }
                begin(result.steps().get(0), resultGeneration);
            }
            if (step == null) return;
            if (stepCatalogGeneration != catalog.generation() || !catalog.ready()) {
                if (crafting != null || smelting != null) {
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
            int observed = step.output() == null ? 0 : actions.count(GameCatalog.item(step.output()));
            if (observed != lastObservedCount) { lastObservedCount = observed; actionTicks = 0; }
            if (smelting != null && smelting.progressToken() != lastSmeltProgress) { lastSmeltProgress = smelting.progressToken(); actionTicks = 0; }
            if (++actionTicks > config.actionTimeoutTicks) throw new IllegalStateException("Action timeout: " + step.sourceId());
            if (step.output() != null && crafting == null && smelting == null
                    && !openingStation
                    && actions.count(GameCatalog.item(step.output())) >= baseline + step.outputCount()) {
                input.idle();
                if (++verifyTicks >= 8) completeStep();
                return;
            }
            verifyTicks = 0;
            if (moving) { status = movement.status(); if (movement.tick()) { moving = false; movement.stop(); } return; }
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
        spec.goals().forEach((item, count) -> {
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
        ensureCatalog();
        observeInventory();
        ItemId item = name == null ? active != null ? active.item : queue.isEmpty() ? maintenanceQueue.isEmpty() ? null : maintenanceQueue.peekFirst().item : queue.peekFirst().item : resolve(name);
        if (item == null) { message("No active or queued goal"); return; }
        CatalogSnapshot snapshot = catalog.snapshot(); InventorySnapshot inventory = inventorySnapshot(item);
        CompletableFuture.supplyAsync(() -> planner.plan(snapshot, inventory, item, count), plannerWorker).thenAccept(result -> client.execute(() -> {
            if (!result.success()) message("Blocked: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(4).toList());
            else {
                message("Plan: " + result.steps().size() + " steps, " + result.expandedNodes() + " branches, " + String.format(Locale.ROOT, "%.2f", result.elapsedNanos() / 1_000_000d) + " ms");
                result.steps().stream().limit(12).forEach(s -> message(s.kind() + " " + (s.output() == null ? s.station() : s.outputCount() + " × " + s.output())));
            }
        }));
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
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (!stack.isEmpty() && stack.isDamageable()) {
            ItemId item = GameCatalog.id(stack.getItem());
            int remaining = stack.getMaxDamage() - stack.getDamage();
            durability.merge(item, remaining, Math::max);
            durabilityLots.computeIfAbsent(item, ignored -> new ArrayList<>()).add(remaining);
        }
        Set<StationId> stations = new HashSet<>();
        ownedStations.forEach((id, pos) -> { if (client.world.getBlockState(pos).getBlock() == Registries.BLOCK.get(GameApi.identifier(id.toString()))) stations.add(id); });
        Map<ItemId, Integer> protectedCounts = protectedCounts(counts, activeTarget);
        return new InventorySnapshot(counts, stations, durability, protectedCounts, durabilityLots);
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
            planningRetries = 0;
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
                && (crafting != null || smelting != null);
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
        if (smelting != null) smelting.requestDrain();
    }

    private boolean canYieldMaintenanceNow() {
        if (active == null || !active.maintained() || client.player == null || transactionInProgress()
                || crafting != null || smelting != null || openingStation
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

    private void requestPlan() {
        foodReplanPending = false;
        ensureCatalog(); status = "planning";
        if (!catalog.ready()) { status = "waiting for recipe catalog"; return; }
        observeInventory();
        if (goalCount() >= active.count) { finishGoal(); return; }
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        CatalogSnapshot full = catalog.snapshot();
        if (!unavailableSources.isEmpty()) {
            full.itemDefinitions().values().forEach(builder::item); catalog.tags.forEach(builder::tag);
            catalog.sources.stream().filter(s -> !unavailableSources.contains(s.sourceId())).forEach(builder::source);
        }
        CatalogSnapshot snapshot = unavailableSources.isEmpty() ? full : builder.build();
        InventorySnapshot inventory = inventorySnapshot(active.item);
        ItemId item = active.item;
        int requested = active.count;
        if (active.anyLogs) {
            item = chooseLogs();
            requested = active.count - goalCount() + inventory.count(item);
        }
        final ItemId targetItem = item; final int targetCount = requested;
        pendingPlanGeneration = catalog.generation();
        pendingPlan = CompletableFuture.supplyAsync(() -> planner.plan(snapshot, inventory, targetItem, targetCount), plannerWorker);
    }
    private ItemId chooseLogs() {
        List<ItemId> logs = catalog.tags.getOrDefault(TagId.parse("minecraft:logs"), List.of());
        return logs.stream().filter(id -> catalog.sources.stream().anyMatch(s -> s.output().equals(id) && s instanceof GatherSource && !unavailableSources.contains(s.sourceId())))
            .sorted(Comparator.comparingInt((ItemId id) -> id.path().equals("oak_log") ? 0 : id.path().endsWith("_log") ? 1 : 2).thenComparing(ItemId::toString))
            .findFirst().orElseThrow(() -> new IllegalStateException("No discoverable log source remains in the search area"));
    }
    private int goalCount() {
        if (!active.anyLogs) return actions.count(GameCatalog.item(active.item));
        int count = 0;
        for (ItemStack stack : ClientAccess.main(client.player.getInventory())) if (stack.isIn(ItemTags.LOGS)) count += stack.getCount();
        return count;
    }
    private void begin(PlanStep next, long plannedGeneration) {
        if (!catalog.ready() || catalog.generation() != plannedGeneration) {
            if (catalog.ready()) requestPlan();
            else status = "waiting for recipe catalog";
            return;
        }
        resetAction(); rejectedStationSites.clear(); stationPlacementFailures = 0; stationDiscoveryDone = false;
        step = next; stepCatalogGeneration = plannedGeneration; actionTicks = 0; status = next.kind() + " " + next.sourceId();
        baseline = next.output() == null ? 0 : actions.count(GameCatalog.item(next.output()));
        lastObservedCount = baseline; lastSmeltProgress = 0;
    }
    private void gather() {
        Set<Block> blocks = new HashSet<>();
        step.candidateBlocks().forEach(id -> blocks.add(Registries.BLOCK.get(GameApi.identifier(id.toString()))));
        ItemEntity dropped = client.world.getEntitiesByClass(ItemEntity.class, client.player.getBoundingBox().expand(12), e -> e.isAlive() && (e.isOnGround() || e.isTouchingWater()) && e.getStack().isOf(GameCatalog.item(step.output()))).stream().min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
        if (dropped != null) {
            if (client.player.squaredDistanceTo(dropped) > 1) { movement.start(dropped.getBlockPos(), 0); moving = true; return; }
            if (!config.allowBreaking) { status = "waiting for nearby dropped items"; return; }
        }
        if (!config.allowBreaking) {
            throw new IllegalStateException("Gathering requires breaking blocks, but allowBreaking=false; enable it with config allowBreaking true");
        }
        if (target == null) {
            List<BlockPos> known = discoveredSources.get(step.sourceId());
            if (known != null) {
                known.removeIf(pos -> !blocks.contains(client.world.getBlockState(pos).getBlock()));
                target = known.stream().filter(pos -> Math.pow(pos.getX() - client.player.getX(), 2) + Math.pow(pos.getZ() - client.player.getZ(), 2) <= config.searchRadius * config.searchRadius)
                    .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(ClientAccess.position(client.player)))).orElse(null);
            }
        }
        if (target == null) {
            if (scan == null) scan = new BlockSearch(client, blocks, config.searchRadius);
            status = "discovering " + step.output();
            if (!scan.advance(config.scanBlocksPerTick, 1_000_000)) return;
            target = scan.result();
            if (discoveredSources.size() >= 64) discoveredSources.remove(discoveredSources.keySet().iterator().next());
            discoveredSources.put(step.sourceId(), new ArrayList<>(scan.results()));
            scan = null;
            if (target == null) { unavailableSources.add(step.sourceId()); resetAction(); requestPlan(); return; }
        }
        if (!blocks.contains(client.world.getBlockState(target).getBlock())) { target = null; actions.cancel(); return; }
        SelectedToolRequirement tool = step.requirements().stream().filter(SelectedToolRequirement.class::isInstance).map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
        if (tool != null && !actions.hasTool(tool)) { resetAction(); requestPlan(); return; }
        if (!actions.mine(target, tool)) { movement.startInteraction(target); moving = true; }
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
        if (target != null && !safeStationCandidate(target)) rejectStationSite(target);
        if (target == null) target = findStationCandidate();
        if (target == null) {
            stationDiscoveryDone = false;
            throw stationPlacementFailure("no visible, reachable full-floor placement site nearby", client.player.getBlockPos());
        }
        if (!actions.place(target, block)) {
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
        if (known != null && client.world.getBlockState(known).isOf(block)) return known;
        BlockPos player = client.player.getBlockPos();
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            BlockPos candidate = player.add(dx, dy, dz);
            if (!client.world.getBlockState(candidate).isOf(block)) continue;
            double distance = candidate.getSquaredDistance(player);
            if (distance < closestDistance) { closest = candidate; closestDistance = distance; }
        }
        return closest;
    }

    private BlockPos findStationCandidate() {
        BlockPos player = client.player.getBlockPos();
        for (int radius = 1; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos candidate = player.add(dx, 0, dz);
                if (candidate.getSquaredDistance(player) < 2 || rejectedStationSites.contains(candidate)) continue;
                if (safeStationCandidate(candidate)) return candidate;
            }
        }
        return null;
    }

    private boolean safeStationCandidate(BlockPos candidate) {
        var destination = client.world.getBlockState(candidate);
        BlockPos floor = candidate.down();
        var support = client.world.getBlockState(floor);
        boolean fullFloor = Block.isShapeFullCube(support.getCollisionShape(client.world, floor));
        boolean safeSupport = !support.hasBlockEntity() && !support.isOf(Blocks.CRAFTING_TABLE)
                && !support.isOf(Blocks.CARTOGRAPHY_TABLE) && !support.isOf(Blocks.FLETCHING_TABLE)
                && !support.isOf(Blocks.SMITHING_TABLE) && !support.isOf(Blocks.STONECUTTER)
                && !support.isOf(Blocks.LOOM) && !support.isOf(Blocks.ENCHANTING_TABLE);
        return destination.isReplaceable() && fullFloor && safeSupport && actions.canPlaceAt(candidate);
    }

    private void rejectStationSite(BlockPos rejected) {
        rejectedStationSites.add(rejected.toImmutable());
        stationPlacementFailures++;
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
        if (openingStation) {
            boolean correct = step.kind() == PlanKind.CRAFT ? handler instanceof net.minecraft.screen.CraftingScreenHandler : handler instanceof net.minecraft.screen.FurnaceScreenHandler;
            if (correct) {
                if (handler != stationOpeningFrom) ownedStationHandler = handler;
                openingStation = false;
                stationOpeningFrom = null;
                return true;
            }
            if (!(handler instanceof PlayerScreenHandler)) throw new IllegalStateException("An unexpected container opened");
            return false;
        }
        if (step.station() == null) {
            if (handler != client.player.playerScreenHandler) throw new IllegalStateException("Close your current container first");
            return true;
        }
        if (!(handler instanceof PlayerScreenHandler)) throw new IllegalStateException("Close your current container first");
        BlockPos station = ownedStations.get(step.station());
        if (station == null) throw new IllegalStateException("Required station disappeared");
        if (actions.hit(station) == null) { movement.start(station, 2); moving = true; return false; }
        stationOpeningFrom = handler;
        openingStation = actions.use(station);
        if (!openingStation) stationOpeningFrom = null;
        return false;
    }
    private void craft() {
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
            if (!stationReady()) return;
            var recipe = catalog.recipes.get(step.sourceId());
            if (recipe == null || recipe.kind() != RecipeWork.Kind.SMELTING
                    || stepCatalogGeneration != catalog.generation()) throw new IllegalStateException("Smelting recipe disappeared or changed before smelting could start");
            smelting = new SmeltingAction(client, actions, recipe, step);
        }
        if (shouldDrainActiveTransaction()) smelting.requestDrain();
        if (smelting.tick()) completeStep();
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
            pause(reason + ". The current crafting/furnace transaction is preserved in its open handler");
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
        food.stop(); foodReplanPending = false;
        ScreenHandler stationHandler = ownedStationHandler;
        boolean closeThisHandler = closeOwnedHandler && hasOwnedStationHandlerOpen();
        String warning;
        try { warning = recoverTransactions(); }
        finally { input.release(); }
        try { movement.stop(); }
        catch (RuntimeException ex) { message("Movement cancellation: " + ex.getMessage()); }
        finally {
            crafting = null; smelting = null; openingStation = false; moving = false;
            step = null; scan = null; target = null; verifyTicks = 0;
        }
        if (warning.isBlank() && closeThisHandler && client.player != null
                && client.player.currentScreenHandler == stationHandler) client.player.closeHandledScreen();
        ownedStationHandler = null;
        stationOpeningFrom = null;
        if (!warning.isBlank()) message("Inventory recovery needs your attention: " + warning);
    }
    void pause(String reason) {
        food.stop();
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
        paused = false; actionTicks = 0;
        if (active != null && pendingPlan == null && step == null) requestPlan();
        message(stopAfterStep ? "Resuming the safe drain before stopping" : "Resumed");
    }
    void stop() {
        if (stopAfterStep) return;
        if (transactionInProgress() || openingStation) {
            stopAfterStep = true;
            paused = false;
            requestActiveTransactionDrain();
            status = "stopping after current safe station transaction";
            message("Stopping after the current crafting/furnace transaction is safely recovered");
            return;
        }
        stopNow(true);
    }

    private void stopNow(boolean announce) {
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
    String status() {
        return (paused ? "paused · " : "") + status + " · " + queue.size() + " foreground queued · "
                + maintenanceQueue.size() + " maintenance queued";
    }
    void showQueue() { message("Active: " + active + "; foreground: " + queue + "; maintenance: " + maintenanceQueue); }
    void message(String message) { if (client.player != null) client.player.sendMessage(net.minecraft.text.Text.literal("[Lodekeeper] " + message), false); }
}
