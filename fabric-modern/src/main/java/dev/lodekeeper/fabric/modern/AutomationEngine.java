package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.AcquisitionPlanner;
import dev.lodekeeper.core.BlockId;
import dev.lodekeeper.core.BlockedReason;
import dev.lodekeeper.core.CatalogSnapshot;
import dev.lodekeeper.core.GatherSource;
import dev.lodekeeper.core.InventorySnapshot;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.MaintainedDemandModel;
import dev.lodekeeper.core.PlanKind;
import dev.lodekeeper.core.PlanResult;
import dev.lodekeeper.core.PlanStep;
import dev.lodekeeper.core.ProjectCatalog;
import dev.lodekeeper.core.ProjectSpec;
import dev.lodekeeper.core.SelectedToolRequirement;
import dev.lodekeeper.core.StationId;
import dev.lodekeeper.core.TagId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        final Set<ItemId> pending = new java.util.TreeSet<>();
        int reconciliationPasses;
        boolean aborted;
        ProjectRun(ProjectSpec spec) { this.spec = spec; }
        @Override public String toString() { return spec.name(); }
    }
    private final Minecraft client;
    final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    private final FoodController food;
    final GameTerrain terrain;
    private final MovementController movement;
    private final ExecutorService plannerWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lodekeeper-planner");
        thread.setDaemon(true);
        return thread;
    });
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
    private ClientLevel world;
    private GameCatalog catalog;
    private Request active;
    private CompletableFuture<PlanResult> pendingPlan;
    private PlanStep step;
    private BlockSearch scan;
    private BlockPos target;
    private CraftingAction crafting;
    private SmeltingAction smelting;
    private boolean moving, paused, openingStation, foregroundYieldPending, stopAfterStep;
    private AbstractContainerMenu ownedStationMenu, stationOpeningFrom;
    private final Set<ItemId> unmaintainAfterStep = new java.util.TreeSet<>();
    private final Map<ItemId, Integer> maintainAfterStep = new TreeMap<>();
    private int planningRetries, stationPlacementFailures, actionTicks, baseline, verifyTicks, lastObservedCount, catalogRefreshTicks;
    private boolean stationDiscoveryDone;
    private int inventorySampleTicks;
    private int foodCooldown;
    private boolean foodReplanPending;
    private boolean inventoryFingerprintInitialized;
    private long lastInventoryFingerprint;
    private Map<ItemId, Integer> observedInventory = Map.of();
    private long lastSmeltProgress;
    private String status = "idle";

    AutomationEngine(Minecraft client, LodekeeperConfig config) {
        this.client = client;
        this.config = config;
        actions = new PlayerActions(client);
        input = new BotInput();
        food = new FoodController(client, actions);
        terrain = new GameTerrain(client, config);
        movement = new MovementController(client, config, actions, input, terrain);
    }

    void tick() {
        if (client.level != world) {
            stopNow(false);
            world = client.level;
            ownedStations.clear();
            unavailableSources.clear();
            discoveredSources.clear();
            catalog = null;
            inventorySampleTicks = 0;
            inventoryFingerprintInitialized = false;
            observedInventory = Map.of();
        }
        if (client.player == null || client.level == null) { food.stop(); input.release(); return; }
        if (foodCooldown > 0) foodCooldown--;
        if (++inventorySampleTicks >= INVENTORY_SAMPLE_INTERVAL_TICKS) {
            inventorySampleTicks = 0;
            observeInventory();
        }
        if (catalog != null && ++catalogRefreshTicks % 40 == 0) catalog.refreshLearnedRecipes();
        try {
            if (foregroundYieldPending && canYieldMaintenanceNow()) yieldActiveMaintenance();
            if (active == null && !paused) startNextRequest();
            if (active == null || paused) { food.stop(); input.release(); return; }
            if (!client.player.isAlive()) {
                if (stopAfterStep) stopNow(true); else pause("player is no longer alive");
                return;
            }
            if (!stopAfterStep && client.player.getHealth() <= config.pauseBelowHealth) { pause("health safeguard"); return; }
            if (!stopAfterStep && config.pauseOnScreen && client.gui.screen() != null && crafting == null && smelting == null && !openingStation) {
                food.stop();
                input.release();
                return;
            }
            if (foregroundYieldPending && !transactionInProgress() && !openingStation && !canYieldMaintenanceNow()) {
                input.release();
                status = "foreground queued; waiting for inventory screen and cursor to be safe";
                return;
            }
            if (food.active()) {
                input.release();
                status = "eating before continuing " + active.name();
                if (!config.autoEat || client.gui.screen() != null) {
                    food.stop();
                    requestPlan();
                } else if (food.tick()) {
                    requestPlan();
                }
                return;
            }
            if (foodReplanPending) { requestPlan(); return; }
            if (config.autoEat && foodCooldown == 0 && !stopAfterStep && !transactionInProgress()
                    && !openingStation && !hasOwnedStationMenuOpen() && food.ready()) {
                if (pendingPlan != null) pendingPlan.cancel(false);
                pendingPlan = null;
                resetAction();
                foodCooldown = 100;
                if (food.begin()) {
                    foodReplanPending = true;
                    status = "eating before continuing " + active.name();
                } else {
                    requestPlan();
                }
                return;
            }
            if (pendingPlan == null && step == null && catalog != null && catalog.ready()) requestPlan();
            if (pendingPlan != null) {
                if (!pendingPlan.isDone()) return;
                PlanResult result = pendingPlan.join();
                pendingPlan = null;
                if (!result.success() && planningRetries++ < 1 && result.blockedReasons().stream().anyMatch(reason -> reason.code() == BlockedReason.Code.TIME_LIMIT)) {
                    requestPlan();
                    return;
                }
                if (!result.success()) {
                    failActive("No plan: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(3).toList());
                    return;
                }
                if (result.steps().isEmpty()) { finishGoal(); return; }
                begin(result.steps().get(0));
            }
            if (step == null) return;
            int observed = step.output() == null ? 0 : actions.count(GameCatalog.item(step.output()));
            if (observed != lastObservedCount) { lastObservedCount = observed; actionTicks = 0; }
            if (smelting != null && smelting.progressToken() != lastSmeltProgress) {
                lastSmeltProgress = smelting.progressToken();
                actionTicks = 0;
            }
            if (++actionTicks > config.actionTimeoutTicks) throw new IllegalStateException("Action timeout: " + step.sourceId());
            if (step.output() != null && crafting == null && smelting == null
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
        } catch (RuntimeException exception) {
            failActive(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
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
            if (isActiveMaintenanceTransaction(item)) {
                unmaintainAfterStep.add(item);
                deferredItem = item;
            } else {
                unmaintainAfterStep.remove(item);
                cancelled.addAll(maintained.unmaintain(item));
            }
        }
        if (active != null && active.maintained() && unmaintainAfterStep.contains(active.item())) {
            requestActiveTransactionDrain();
        }
        cancelMaintenanceTasks(cancelled);
        if (itemOrAll.equals("all")) message(unmaintainAfterStep.isEmpty()
                ? "Cleared all maintained targets" : "Cleared maintained targets after the active safe step");
        else message(deferredItem != null ? "Will stop maintaining after the current safe step"
                : "No longer maintaining " + itemOrAll);
    }

    void showMaintained() {
        ensureCatalog();
        observeInventory();
        List<MaintainedDemandModel.Status> targets = maintained.statuses();
        if (targets.isEmpty()) { message("No maintained inventory targets"); return; }
        for (MaintainedDemandModel.Status target : targets) {
            message(target.item() + " " + target.actualCount() + "/" + target.targetCount()
                    + " · " + target.state().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + (target.activeReservedCount() > 0 ? " · queued " + target.activeReservedCount() : "")
                    + (unmaintainAfterStep.contains(target.item()) ? " · stopping after safe step" : "")
                    + (maintainAfterStep.containsKey(target.item()) ? " · updating to " + maintainAfterStep.get(target.item()) + " after safe step" : ""));
        }
    }

    private ItemId resolve(String name) {
        var resolution = catalog.snapshot().resolveItem(name);
        if (!resolution.found()) throw new IllegalArgumentException(resolution.ambiguous()
                ? "Ambiguous item: " + resolution.candidates() : "Unknown item: " + name);
        return resolution.item();
    }

    void preview(String name, int count) {
        ensureCatalog();
        if (!catalog.ready()) { message("Loading the integrated world's recipe catalog; try plan again shortly"); return; }
        ItemId item = name == null ? active != null ? active.item
                : queue.isEmpty() ? maintenanceQueue.isEmpty() ? null : maintenanceQueue.peekFirst().item
                : queue.peekFirst().item : resolve(name);
        if (item == null) { message("No active or queued goal"); return; }
        CatalogSnapshot snapshot = catalog.snapshot();
        InventorySnapshot inventory = inventorySnapshot(item);
        CompletableFuture.supplyAsync(() -> planner.plan(snapshot, inventory, item, count), plannerWorker).thenAccept(result -> client.execute(() -> {
            if (!result.success()) message("Blocked: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(4).toList());
            else {
                message("Plan: " + result.steps().size() + " steps, " + result.expandedNodes() + " branches, "
                        + String.format(Locale.ROOT, "%.2f", result.elapsedNanos() / 1_000_000d) + " ms");
                result.steps().stream().limit(12).forEach(next -> message(next.kind() + " "
                        + (next.output() == null ? next.station() : next.outputCount() + " × " + next.output())));
            }
        }));
    }

    private void ensureCatalog() {
        if (client.level == null || client.player == null) throw new IllegalStateException("Join a world first");
        if (catalog == null) { catalog = new GameCatalog(client); catalog.load(); }
    }

    private void ensureNotStopping() {
        if (stopAfterStep) throw new IllegalStateException("Finishing the current safe transaction before stopping; retry after it completes");
    }

    private void startNextRequest() {
        if (queue.isEmpty() && maintenanceQueue.isEmpty()) { status = "idle"; return; }
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            status = "waiting for the open container to close";
            return;
        }
        if (client.player != null && !client.player.containerMenu.getCarried().isEmpty()) {
            status = "waiting for the cursor stack to be returned";
            return;
        }
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
        if (!canYieldMaintenanceNow()) {
            status = "foreground queued; finishing or safely closing maintenance work";
            requestActiveTransactionDrain();
            return;
        }
        yieldActiveMaintenance();
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
                || client.player.containerMenu != client.player.inventoryMenu) return false;
        return client.player.containerMenu.getCarried().isEmpty();
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
            if (transactionInProgress() || openingStation) {
                unmaintainAfterStep.add(active.item());
                requestActiveTransactionDrain();
                return;
            }
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
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
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

    private InventorySnapshot inventorySnapshot(ItemId activeTarget) {
        Map<ItemId, Integer> counts = new HashMap<>(), durability = new HashMap<>();
        actions.inventory().forEach((name, count) -> counts.put(ItemId.parse(name), count));
        for (int index = 0; index < 36; index++) {
            ItemStack stack = client.player.getInventory().getItem(index);
            if (!stack.isEmpty() && stack.isDamageableItem()) {
                durability.merge(GameCatalog.id(stack.getItem()), stack.getMaxDamage() - stack.getDamageValue(), Math::max);
            }
        }
        Set<StationId> stations = new HashSet<>();
        ownedStations.forEach((id, position) -> {
            if (client.level.getBlockState(position).getBlock() == BuiltInRegistries.BLOCK.getValue(Identifier.parse(id.toString()))) stations.add(id);
        });
        return new InventorySnapshot(counts, stations, durability, protectedCounts(counts, activeTarget));
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
        ensureCatalog();
        if (!catalog.ready()) { status = "loading recipes"; return; }
        status = "planning";
        observeInventory();
        if (goalCount() >= active.count) { finishGoal(); return; }
        CatalogSnapshot full = catalog.snapshot();
        CatalogSnapshot snapshot = full;
        if (!unavailableSources.isEmpty()) {
            CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
            full.itemDefinitions().values().forEach(definition -> builder.item(definition.id(), definition.maximumDurability(), definition.fuelBurnTicks(), definition.aliases().toArray(String[]::new)));
            catalog.tags.forEach(builder::tag);
            catalog.sources.stream().filter(source -> !unavailableSources.contains(source.sourceId())).forEach(builder::source);
            snapshot = builder.build();
        }
        InventorySnapshot inventory = inventorySnapshot(active.item);
        ItemId item = active.item;
        int requested = active.count;
        if (active.anyLogs) {
            item = chooseLogs();
            requested = active.count - goalCount() + inventory.count(item);
        }
        ItemId targetItem = item;
        int targetCount = requested;
        CatalogSnapshot planningSnapshot = snapshot;
        pendingPlan = CompletableFuture.supplyAsync(() -> planner.plan(planningSnapshot, inventory, targetItem, targetCount), plannerWorker);
    }

    private ItemId chooseLogs() {
        return catalog.tags.getOrDefault(TagId.parse("minecraft:logs"), List.of()).stream()
                .filter(item -> catalog.sources.stream().anyMatch(source -> source.output().equals(item)
                        && source instanceof GatherSource && !unavailableSources.contains(source.sourceId())))
                .sorted(Comparator.comparingInt((ItemId item) -> item.path().equals("oak_log") ? 0 : item.path().endsWith("_log") ? 1 : 2)
                        .thenComparing(ItemId::toString))
                .findFirst().orElseThrow(() -> new IllegalStateException("No discoverable log source remains in the search area"));
    }

    private int goalCount() {
        if (!active.anyLogs) return actions.count(GameCatalog.item(active.item));
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.is(ItemTags.LOGS)) count += stack.getCount();
        }
        return count;
    }

    private void finishGoal() {
        Request finished = active;
        if (finished == null) return;
        observeInventory();
        active = null;
        pendingPlan = null;
        resetAction();
        status = "idle";
        if (finished.maintained()) {
            scheduleMaintenanceRequests(maintained.complete(finished.maintenanceTaskId(), observedInventory));
            message("Maintained target reached: " + finished.item() + " "
                    + observedInventory.getOrDefault(finished.item(), 0) + "/" + finished.count());
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

    private String sourceDiagnostic() {
        if (catalog == null || catalog.unsupported.isEmpty()) return "";
        return ". Some registered recipes/sources were skipped: "
                + catalog.unsupported.stream().limit(3).toList();
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
            boolean keepOwnedMenu = hasOwnedStationMenuOpen();
            resetAction(!keepOwnedMenu);
            active = null;
            observeInventory();
            scheduleMaintenanceRequests(maintained.fail(failed.maintenanceTaskId(), observedInventory));
            status = "maintenance blocked";
            message("Maintenance blocked for " + failed.item() + ": " + reason
                    + ". It will retry after inventory changes or a new maintain command." + sourceDiagnostic());
            if (keepOwnedMenu) pause("Maintenance blocked while its owned container remains open; inspect it, close it, then resume");
            return;
        }
        if (failed != null && failed.project() != null) {
            abortProject(failed.project());
            active = null;
            if (pendingPlan != null) pendingPlan.cancel(false);
            pendingPlan = null;
            resetAction(!hasOwnedStationMenuOpen());
            pause("Project " + failed.project().spec.name() + " blocked: " + reason + sourceDiagnostic());
            return;
        }
        pause(reason + sourceDiagnostic());
    }

    private void begin(PlanStep next) {
        resetAction();
        rejectedStationSites.clear();
        stationPlacementFailures = 0;
        stationDiscoveryDone = false;
        step = next;
        actionTicks = 0;
        status = next.kind() + " " + next.sourceId();
        baseline = next.output() == null ? 0 : actions.count(GameCatalog.item(next.output()));
        lastObservedCount = baseline;
        lastSmeltProgress = 0;
    }

    private void gather() {
        Set<Block> blocks = new HashSet<>();
        step.candidateBlocks().forEach(id -> blocks.add(GameCatalog.block(id)));
        var searchBox = client.player.getBoundingBox().inflate(12);
        Item wanted = GameCatalog.item(step.output());
        ItemEntity dropped = client.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), searchBox,
                        entity -> entity.isAlive() && (entity.onGround() || entity.isInWater()) && entity.getItem().is(wanted))
                .stream().min(Comparator.comparingDouble(client.player::distanceToSqr)).orElse(null);
        if (dropped != null) {
            if (client.player.distanceToSqr(dropped) > 1) {
                movement.start(dropped.blockPosition(), 0);
                moving = true;
                return;
            }
            if (!config.allowBreaking) {
                status = "waiting for nearby dropped items";
                return;
            }
        }
        if (!config.allowBreaking) {
            throw new IllegalStateException("Gathering requires breaking blocks, but allowBreaking=false; enable it with config allowBreaking true");
        }
        if (target == null) {
            List<BlockPos> known = discoveredSources.get(step.sourceId());
            if (known != null) {
                known.removeIf(position -> !sourceStillAvailable(position, blocks));
                target = known.stream()
                        .filter(position -> Math.pow(position.getX() - client.player.getX(), 2)
                                + Math.pow(position.getZ() - client.player.getZ(), 2) <= config.searchRadius * config.searchRadius)
                        .min(Comparator.comparingDouble(position -> client.player.distanceToSqr(
                                position.getX() + .5, position.getY() + .5, position.getZ() + .5)))
                        .orElse(null);
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
        if (!sourceStillAvailable(target, blocks)) { target = null; actions.cancel(); return; }
        SelectedToolRequirement tool = step.requirements().stream()
                .filter(SelectedToolRequirement.class::isInstance)
                .map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
        if (tool != null && !actions.hasTool(tool)) { resetAction(); requestPlan(); return; }
        if (!actions.mine(target, tool)) { movement.startInteraction(target); moving = true; }
    }

    private boolean sourceStillAvailable(BlockPos position, Set<Block> blocks) {
        if (client.level.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) == null) return false;
        return blocks.contains(client.level.getBlockState(position).getBlock());
    }

    private void placeStation() {
        Block block = GameCatalog.block(BlockId.parse(step.station().toString()));
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
        if (target != null && client.level.getBlockState(target).is(block)) {
            ownedStations.put(step.station(), target);
            terrain.changed();
            completeStep();
            return;
        }
        if (target != null && !safeStationCandidate(target)) rejectStationSite(target);
        if (target == null) target = findStationCandidate();
        if (target == null) {
            stationDiscoveryDone = false;
            throw stationPlacementFailure("no visible, reachable full-floor placement site nearby", client.player.blockPosition());
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
        if (known != null && client.level.getBlockState(known).is(block)) return known;
        BlockPos player = client.player.blockPosition();
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            BlockPos candidate = player.offset(dx, dy, dz);
            if (!client.level.getBlockState(candidate).is(block)) continue;
            double distance = candidate.distSqr(player);
            if (distance < closestDistance) { closest = candidate; closestDistance = distance; }
        }
        return closest;
    }

    private BlockPos findStationCandidate() {
        BlockPos player = client.player.blockPosition();
        for (int radius = 1; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos candidate = player.offset(dx, 0, dz);
                if (candidate.distSqr(player) < 2 || rejectedStationSites.contains(candidate)) continue;
                if (safeStationCandidate(candidate)) return candidate;
            }
        }
        return null;
    }

    private boolean safeStationCandidate(BlockPos candidate) {
        var destination = client.level.getBlockState(candidate);
        BlockPos floor = candidate.below();
        var support = client.level.getBlockState(floor);
        boolean fullFloor = Block.isShapeFullBlock(support.getCollisionShape(client.level, floor));
        Block supportBlock = support.getBlock();
        boolean safeSupport = !support.hasBlockEntity()
                && supportBlock != Blocks.CRAFTING_TABLE && supportBlock != Blocks.CARTOGRAPHY_TABLE
                && supportBlock != Blocks.FLETCHING_TABLE && supportBlock != Blocks.SMITHING_TABLE
                && supportBlock != Blocks.STONECUTTER && supportBlock != Blocks.LOOM
                && supportBlock != Blocks.ENCHANTING_TABLE;
        return destination.canBeReplaced() && fullFloor && safeSupport && actions.canPlaceAt(candidate);
    }

    private void rejectStationSite(BlockPos rejected) {
        rejectedStationSites.add(rejected.immutable());
        stationPlacementFailures++;
        target = null;
    }

    private IllegalStateException stationPlacementFailure(String reason, BlockPos position) {
        Block block = GameCatalog.block(BlockId.parse(step.station().toString()));
        Item item = block.asItem();
        return new IllegalStateException("Cannot place required station " + step.station() + " at " + position
                + ": " + reason + "; required " + GameCatalog.id(item) + " (inventory " + actions.count(item)
                + "), tried " + stationPlacementFailures + " site(s)");
    }

    private boolean stationReady() {
        var menu = client.player.containerMenu;
        if (openingStation) {
            boolean correct = step.kind() == PlanKind.CRAFT ? menu instanceof net.minecraft.world.inventory.CraftingMenu
                    : menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu;
            if (correct) {
                if (menu != stationOpeningFrom) ownedStationMenu = menu;
                openingStation = false;
                stationOpeningFrom = null;
                return true;
            }
            if (menu != client.player.inventoryMenu) throw new IllegalStateException("An unexpected container opened");
            return false;
        }
        if (step.station() == null) {
            if (menu != client.player.inventoryMenu) throw new IllegalStateException("Close your current container first");
            return true;
        }
        if (menu != client.player.inventoryMenu) throw new IllegalStateException("Close your current container first");
        BlockPos station = ownedStations.get(step.station());
        if (station == null) throw new IllegalStateException("Required station disappeared");
        if (actions.hit(station) == null) { movement.start(station, 2); moving = true; return false; }
        stationOpeningFrom = menu;
        openingStation = actions.use(station);
        if (!openingStation) stationOpeningFrom = null;
        return false;
    }

    private void craft() {
        if (crafting == null) {
            if (!stationReady()) return;
            GameCatalog.RecipeWork recipe = catalog.recipes.get(step.sourceId());
            if (recipe == null) throw new IllegalStateException("Recipe is no longer visible to this client");
            crafting = new CraftingAction(client, actions, recipe, step);
        }
        if (shouldDrainActiveTransaction()) crafting.requestDrain();
        if (crafting.tick()) completeStep();
    }

    private void smelt() {
        if (smelting == null) {
            if (!stationReady()) return;
            GameCatalog.RecipeWork recipe = catalog.recipes.get(step.sourceId());
            if (recipe == null) throw new IllegalStateException("Cooking recipe is no longer visible to this client");
            smelting = new SmeltingAction(client, actions, recipe, step);
        }
        if (shouldDrainActiveTransaction()) smelting.requestDrain();
        if (smelting.tick()) completeStep();
    }

    private void completeStep() {
        if (stopAfterStep) { stopNow(true); return; }
        resetAction();
        planningRetries = 0;
        if (applyDeferredUnmaintain()) return;
        if (foregroundYieldPending && active != null && active.maintained()) {
            if (canYieldMaintenanceNow()) yieldActiveMaintenance();
            else {
                status = "foreground queued; waiting for inventory screen and cursor to be safe";
                return;
            }
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

    private String recoverTransactions() {
        String warning = "";
        try { if (crafting != null) crafting.pause(); }
        catch (RuntimeException exception) { warning = exception.getMessage(); }
        try { if (smelting != null) smelting.pause(); }
        catch (RuntimeException exception) { warning = exception.getMessage(); }
        return warning;
    }

    private boolean hasOwnedStationMenuOpen() {
        return client.player != null && ownedStationMenu != null
                && client.player.containerMenu == ownedStationMenu;
    }

    private void resetAction() { resetAction(true); }

    private void resetAction(boolean closeOwnedMenu) {
        food.stop();
        foodReplanPending = false;
        AbstractContainerMenu stationMenu = ownedStationMenu;
        boolean closeThisMenu = closeOwnedMenu && hasOwnedStationMenuOpen();
        String warning;
        try { warning = recoverTransactions(); }
        finally { input.release(); }
        try { movement.stop(); }
        catch (RuntimeException exception) { message("Movement cancellation: " + exception.getMessage()); }
        finally {
            crafting = null; smelting = null; openingStation = false; moving = false;
            step = null; scan = null; target = null; verifyTicks = 0;
        }
        if (warning.isBlank() && closeThisMenu && client.player != null
                && client.player.containerMenu == stationMenu) client.player.closeContainer();
        ownedStationMenu = null;
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
        try { actions.cancel(); }
        catch (RuntimeException exception) { warning += " " + exception.getMessage(); }
        if (!warning.isBlank()) reason += ". " + warning;
        message("Paused: " + reason + ". Use resume or stop.");
        status = reason;
    }

    void resume() {
        paused = false;
        actionTicks = 0;
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
        if (pendingPlan != null) pendingPlan.cancel(false);
        pendingPlan = null;
        maintained.unmaintainAll();
        maintenanceQueue.clear();
        for (ProjectRun run : new ArrayList<>(projects)) abortProject(run);
        resetAction();
        active = null;
        queue.clear();
        paused = false;
        foregroundYieldPending = false;
        stopAfterStep = false;
        unmaintainAfterStep.clear();
        maintainAfterStep.clear();
        status = "idle";
        if (announce) message("Stopped");
    }

    void clearQueue() {
        queue.clear();
        for (ProjectRun run : new ArrayList<>(projects)) abortProject(run);
        foregroundYieldPending = false;
        message("Foreground queue cleared; maintained targets remain active");
    }
    String status() {
        String waiting = foregroundYieldPending && active != null && active.maintained()
                ? " · foreground waiting for a safe station boundary" : "";
        return (paused ? "paused · " : "") + status + waiting + " · " + queue.size() + " foreground queued · "
                + maintenanceQueue.size() + " maintenance queued";
    }
    void showQueue() { message("Active: " + active + "; foreground: " + queue + "; maintenance: " + maintenanceQueue); }
    void message(String message) {
        if (client.player != null) client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Lodekeeper] " + message));
    }
}
