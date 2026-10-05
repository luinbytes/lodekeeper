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
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates one goal/action at a time. Pure planning runs on a single bounded worker. */
final class AutomationEngine {
    record Request(String name, ItemId item, int count, boolean anyLogs) {}
    private final MinecraftClient client;
    final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    final GameTerrain terrain;
    private final MovementController movement;
    private final ExecutorService plannerWorker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lodekeeper-planner"); t.setDaemon(true); return t; });
    private final Deque<Request> queue = new ArrayDeque<>();
    private final Map<StationId, BlockPos> ownedStations = new HashMap<>();
    private final Set<String> unavailableSources = new HashSet<>();
    private final AcquisitionPlanner planner = new AcquisitionPlanner();
    private ClientWorld world;
    private GameCatalog catalog;
    private Request active;
    private CompletableFuture<PlanResult> pendingPlan;
    private PlanStep step;
    private BlockSearch scan;
    private BlockPos target;
    private CraftingAction crafting;
    private SmeltingAction smelting;
    private boolean moving, paused, openingStation;
    private int planningRetries;
    private int actionTicks, baseline, verifyTicks, lastObservedCount;
    private long lastSmeltProgress;
    private String status = "idle";
    AutomationEngine(MinecraftClient client, LodekeeperConfig config) {
        this.client = client; this.config = config;
        actions = new PlayerActions(client); input = new BotInput(client); terrain = new GameTerrain(client, config);
        movement = new MovementController(client, config, actions, input, terrain);
    }
    void tick() {
        if (client.world != world) {
            stop(); world = client.world; ownedStations.clear(); unavailableSources.clear(); catalog = null;
        }
        if (client.player == null || client.world == null) { input.release(); return; }
        if (active == null && !paused && !queue.isEmpty()) { active = queue.removeFirst(); unavailableSources.clear(); planningRetries = 0; requestPlan(); }
        if (active == null || paused) { input.release(); return; }
        if (!client.player.isAlive() || client.player.getHealth() <= config.pauseBelowHealth) { pause("health safeguard"); return; }
        if (config.pauseOnScreen && client.currentScreen != null && crafting == null && smelting == null && !openingStation) { input.release(); return; }
        try {
            if (pendingPlan != null) {
                if (!pendingPlan.isDone()) return;
                PlanResult result = pendingPlan.join(); pendingPlan = null;
                if (!result.success() && planningRetries++ < 1 && result.blockedReasons().stream().anyMatch(r -> r.code() == BlockedReason.Code.TIME_LIMIT)) { requestPlan(); return; }
                if (!result.success()) { pause("No plan: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(3).toList()); return; }
                if (result.steps().isEmpty()) { finishGoal(); return; }
                begin(result.steps().get(0));
            }
            if (step == null) return;
            int observed = step.output() == null ? 0 : actions.count(GameCatalog.item(step.output()));
            if (observed != lastObservedCount) { lastObservedCount = observed; actionTicks = 0; }
            if (smelting != null && smelting.progressToken() != lastSmeltProgress) { lastSmeltProgress = smelting.progressToken(); actionTicks = 0; }
            if (++actionTicks > config.actionTimeoutTicks) throw new IllegalStateException("Action timeout: " + step.sourceId());
            if (step.output() != null && actions.count(GameCatalog.item(step.output())) >= baseline + step.outputCount()) {
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
        } catch (RuntimeException ex) { pause(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
    }
    void enqueue(String name, int count) {
        ensureCatalog();
        boolean logs = name.equalsIgnoreCase("wood") || name.equalsIgnoreCase("logs");
        ItemId item = logs ? ItemId.parse("minecraft:oak_log") : resolve(name);
        if (queue.size() >= 32) throw new IllegalStateException("Queue limit is 32 goals");
        queue.addLast(new Request(name, item, count, logs));
        message("Queued " + count + " × " + name);
    }
    private ItemId resolve(String name) {
        ItemResolution resolution = catalog.snapshot().resolveItem(name);
        if (!resolution.found()) throw new IllegalArgumentException(resolution.ambiguous() ? "Ambiguous item: " + resolution.candidates() : "Unknown item: " + name);
        return resolution.item();
    }
    void preview(String name, int count) {
        ensureCatalog();
        ItemId item = name == null ? active != null ? active.item : queue.isEmpty() ? null : queue.peekFirst().item : resolve(name);
        if (item == null) { message("No active or queued goal"); return; }
        CatalogSnapshot snapshot = catalog.snapshot(); InventorySnapshot inventory = inventorySnapshot();
        CompletableFuture.supplyAsync(() -> planner.plan(snapshot, inventory, item, count), plannerWorker).thenAccept(result -> client.execute(() -> {
            if (!result.success()) message("Blocked: " + result.blockedReasons().stream().map(BlockedReason::detail).limit(4).toList());
            else {
                message("Plan: " + result.steps().size() + " steps, " + result.expandedNodes() + " branches, " + String.format(Locale.ROOT, "%.2f", result.elapsedNanos() / 1_000_000d) + " ms");
                result.steps().stream().limit(12).forEach(s -> message(s.kind() + " " + (s.output() == null ? s.station() : s.outputCount() + " × " + s.output())));
            }
        }));
    }
    private void ensureCatalog() {
        if (client.world == null || client.player == null) throw new IllegalStateException("Join a world first");
        if (catalog == null) { catalog = new GameCatalog(client); catalog.load(); }
    }
    private InventorySnapshot inventorySnapshot() {
        Map<ItemId, Integer> counts = new HashMap<>(), durability = new HashMap<>();
        actions.inventory().forEach((name, count) -> counts.put(ItemId.parse(name), count));
        for (ItemStack stack : client.player.getInventory().main) if (!stack.isEmpty() && stack.isDamageable()) durability.merge(GameCatalog.id(stack.getItem()), stack.getMaxDamage() - stack.getDamage(), Math::max);
        Set<StationId> stations = new HashSet<>();
        ownedStations.forEach((id, pos) -> { if (client.world.getBlockState(pos).getBlock() == Registries.BLOCK.get(new Identifier(id.toString()))) stations.add(id); });
        return new InventorySnapshot(counts, stations, durability);
    }
    private void requestPlan() {
        ensureCatalog(); status = "planning";
        if (goalCount() >= active.count) { finishGoal(); return; }
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        CatalogSnapshot full = catalog.snapshot();
        if (!unavailableSources.isEmpty()) {
            full.itemDefinitions().values().forEach(builder::item); catalog.tags.forEach(builder::tag);
            catalog.sources.stream().filter(s -> !unavailableSources.contains(s.sourceId())).forEach(builder::source);
        }
        CatalogSnapshot snapshot = unavailableSources.isEmpty() ? full : builder.build();
        InventorySnapshot inventory = inventorySnapshot();
        ItemId item = active.item;
        int requested = active.count;
        if (active.anyLogs) {
            item = chooseLogs();
            requested = active.count - goalCount() + inventory.count(item);
        }
        final ItemId targetItem = item; final int targetCount = requested;
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
        for (ItemStack stack : client.player.getInventory().main) if (stack.isIn(ItemTags.LOGS)) count += stack.getCount();
        return count;
    }
    private void begin(PlanStep next) {
        resetAction(); step = next; actionTicks = 0; status = next.kind() + " " + next.sourceId();
        baseline = next.output() == null ? 0 : actions.count(GameCatalog.item(next.output()));
        lastObservedCount = baseline; lastSmeltProgress = 0;
    }
    private void gather() {
        Set<Block> blocks = new HashSet<>();
        step.candidateBlocks().forEach(id -> blocks.add(Registries.BLOCK.get(new Identifier(id.toString()))));
        ItemEntity dropped = client.world.getEntitiesByClass(ItemEntity.class, client.player.getBoundingBox().expand(12), e -> e.isAlive() && e.getStack().isOf(GameCatalog.item(step.output()))).stream().min(Comparator.comparingDouble(client.player::squaredDistanceTo)).orElse(null);
        if (dropped != null && client.player.squaredDistanceTo(dropped) > 1) { movement.start(dropped.getBlockPos(), 0); moving = true; return; }
        if (target == null) {
            if (scan == null) scan = new BlockSearch(client, blocks, config.searchRadius);
            status = "discovering " + step.output();
            if (!scan.advance(config.scanBlocksPerTick, 1_000_000)) return;
            target = scan.result(); scan = null;
            if (target == null) { unavailableSources.add(step.sourceId()); resetAction(); requestPlan(); return; }
        }
        if (!blocks.contains(client.world.getBlockState(target).getBlock())) { target = null; actions.cancel(); return; }
        SelectedToolRequirement tool = step.requirements().stream().filter(SelectedToolRequirement.class::isInstance).map(SelectedToolRequirement.class::cast).findFirst().orElse(null);
        if (!actions.mine(target, tool)) { movement.start(target, 1); moving = true; }
    }
    private void placeStation() {
        Block block = Registries.BLOCK.get(new Identifier(step.station().toString()));
        if (target == null) {
            BlockPos player = client.player.getBlockPos();
            for (int radius = 1; radius <= 3 && target == null; radius++) for (int dx = -radius; dx <= radius && target == null; dx++) for (int dz = -radius; dz <= radius; dz++) {
                BlockPos candidate = player.add(dx, 0, dz);
                if (candidate.getSquaredDistance(player) < 2 || !client.world.getBlockState(candidate).isReplaceable()) continue;
                if (Block.isShapeFullCube(client.world.getBlockState(candidate.down()).getCollisionShape(client.world, candidate.down()))) { target = candidate; break; }
            }
            if (target == null) throw new IllegalStateException("No safe station placement site nearby");
        }
        if (client.world.getBlockState(target).isOf(block)) { ownedStations.put(step.station(), target); terrain.changed(); completeStep(); return; }
        if (!actions.place(target, block)) throw new IllegalStateException("Cannot place required station");
    }
    private boolean stationReady() {
        var handler = client.player.currentScreenHandler;
        if (openingStation) {
            boolean correct = step.kind() == PlanKind.CRAFT ? handler instanceof net.minecraft.screen.CraftingScreenHandler : handler instanceof net.minecraft.screen.FurnaceScreenHandler;
            if (correct) return true;
            if (!(handler instanceof PlayerScreenHandler)) throw new IllegalStateException("An unexpected container opened");
            return false;
        }
        if (step.station() == null) {
            if (client.player.currentScreenHandler != client.player.playerScreenHandler) client.player.closeHandledScreen();
            return true;
        }
        if (!(handler instanceof PlayerScreenHandler)) throw new IllegalStateException("Close your current container first");
        BlockPos station = ownedStations.get(step.station());
        if (station == null) throw new IllegalStateException("Required station disappeared");
        if (actions.hit(station) == null) { movement.start(station, 2); moving = true; return false; }
        openingStation = actions.use(station); return false;
    }
    private void craft() {
        if (crafting == null) {
            if (!stationReady()) return;
            var recipe = catalog.recipes.get(step.sourceId());
            if (recipe == null) throw new IllegalStateException("Recipe disappeared");
            crafting = new CraftingAction(client, actions, recipe, step);
        }
        if (crafting.tick()) completeStep();
    }
    private void smelt() {
        if (smelting == null) {
            if (!stationReady()) return;
            var recipe = catalog.recipes.get(step.sourceId());
            if (!(recipe instanceof net.minecraft.recipe.AbstractCookingRecipe cooking)) throw new IllegalStateException("Cooking recipe disappeared");
            smelting = new SmeltingAction(client, actions, cooking, step);
        }
        if (smelting.tick()) completeStep();
    }
    private void completeStep() {
        resetAction(); planningRetries = 0;
        if (client.player.currentScreenHandler != client.player.playerScreenHandler) client.player.closeHandledScreen();
        requestPlan();
    }
    private void finishGoal() { message("Complete: " + active.count + " × " + active.name); active = null; pendingPlan = null; resetAction(); status = "idle"; }
    private String recoverTransactions() {
        String warning = "";
        try { if (crafting != null) crafting.pause(); }
        catch (RuntimeException ex) { warning = ex.getMessage(); }
        try { if (smelting != null) smelting.pause(); }
        catch (RuntimeException ex) { warning = ex.getMessage(); }
        return warning;
    }
    private void resetAction() {
        String warning;
        try { warning = recoverTransactions(); }
        finally { input.release(); }
        try { movement.stop(); }
        catch (RuntimeException ex) { message("Movement cancellation: " + ex.getMessage()); }
        finally {
            crafting = null; smelting = null; openingStation = false; moving = false;
            step = null; scan = null; target = null; verifyTicks = 0;
        }
        if (!warning.isBlank()) message("Inventory recovery needs your attention: " + warning);
    }
    void pause(String reason) {
        paused = true;
        String warning;
        try { warning = recoverTransactions(); }
        finally { input.release(); }
        try { actions.cancel(); } catch (RuntimeException ex) { warning += " " + ex.getMessage(); }
        if (!warning.isBlank()) reason += ". " + warning;
        message("Paused: " + reason + ". Use resume or stop."); status = reason;
    }
    void resume() { paused = false; actionTicks = 0; message("Resumed"); }
    void stop() {
        if (pendingPlan != null) pendingPlan.cancel(false); pendingPlan = null;
        resetAction(); active = null; queue.clear(); paused = false; status = "idle";
    }
    void clearQueue() { queue.clear(); message("Queue cleared"); }
    String status() { return (paused ? "paused · " : "") + status + " · " + queue.size() + " queued"; }
    void showQueue() { message("Active: " + active + "; queued: " + queue); }
    void message(String message) { if (client.player != null) client.player.sendMessage(net.minecraft.text.Text.literal("[Lodekeeper] " + message), false); }
}
