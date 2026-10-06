package dev.lodekeeper.fabric.modern;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BlockOptionalMetaLookup;
import dev.lodekeeper.core.SelectedToolRequirement;
import dev.lodekeeper.nav.ExplorationFrontier;
import dev.lodekeeper.nav.NavigationSnapshot;
import dev.lodekeeper.nav.Path;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.core.BlockPos;

import java.util.*;

/** Owns one upstream process; inventory transactions wait for safe cancellation. */
final class MovementController {
    private enum Mode { IDLE, MOVE, MINE, PICKUP, SUSPENDED }
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    private IBaritone bot;
    private Mode mode = Mode.IDLE, resumeMode = Mode.IDLE;
    private boolean cancelling;
    private baritone.api.pathing.goals.Goal routeGoal;
    private dev.lodekeeper.nav.Goal diagnosticGoal;
    private Block[] mineBlocks = new Block[0];
    private Item output;
    private int targetCount;
    private SelectedToolRequirement tool;
    private Set<Item> reserved = Set.of();
    private Set<Block> protectedBlocks = Set.of();
    private SettingsLease lease;
    private long progressToken, startedNanos;
    private double observedX, observedY, observedZ, requestX, requestZ;
    private boolean positionObserved;
    private int requestTicks, failedCalculations, nextRescanTick, rescanAttempts, lastBreakTick;
    private int waitingTicks, rescanFailedCalculations, rescanInventoryCount, rescanChunkX, rescanChunkZ;
    private NavigationFailure pendingBreakFailure;
    private BlockPos lastLoggedBreakPosition;
    private Item lastLoggedBreakTool;
    private List<Item> scaffoldItems = List.of();
    private NavigationSnapshot observation = NavigationSnapshot.EMPTY;

    static final class NavigationFailure extends IllegalStateException {
        enum Kind { TOOL, PROCESS_ENDED, PROTECTED_BLOCK, OTHER }
        final Kind kind;
        NavigationFailure(String reason) { this(Kind.OTHER, reason); }
        NavigationFailure(Kind kind, String reason) { super(reason); this.kind = kind; }
    }

    MovementController(Minecraft client, LodekeeperConfig config, PlayerActions actions,
                       BotInput input, GameTerrain terrain) {
        this.client = client; this.config = config; this.actions = actions; this.input = input;
    }

    void updateProtection(Set<Item> reserved, Set<Block> protectedBlocks) {
        this.reserved = Set.copyOf(reserved);
        this.protectedBlocks = Set.copyOf(protectedBlocks);
        if (lease != null) applyProtection();
    }

    void startExploration(ExplorationFrontier.Waypoint waypoint) {
        BlockPos target = new BlockPos(waypoint.x(), Math.toIntExact(Math.floorDiv(waypoint.feetY16(), 16)), waypoint.z());
        startMove(new GoalBlock(target), dev.lodekeeper.nav.Goal.exact16(target.getX(), target.getY() * 16, target.getZ()));
    }

    void start(BlockPos target, int radius) {
        startMove(new GoalNear(target, radius), dev.lodekeeper.nav.Goal.near16(target.getX(), target.getY() * 16, target.getZ(), radius * 16));
    }

    void startInteraction(BlockPos target) {
        startMove(new GoalGetToBlock(target), dev.lodekeeper.nav.Goal.near16(target.getX(), target.getY() * 16, target.getZ(), 32));
    }

    private void startMove(baritone.api.pathing.goals.Goal goal, dev.lodekeeper.nav.Goal diagnostic) {
        prepare(); routeGoal = goal; diagnosticGoal = diagnostic; mode = Mode.MOVE;
        launch();
    }

    void startPickup(ItemEntity item) {
        prepare(); output = item.getItem().getItem(); targetCount = actions.count(output) + 1;
        BlockPos position = item.blockPosition();
        diagnosticGoal = dev.lodekeeper.nav.Goal.near16(position.getX(), position.getY() * 16, position.getZ(), 32);
        mode = Mode.PICKUP; launch();
    }

    void startMining(Block[] blocks, Item output, int totalCount, SelectedToolRequirement tool) {
        if (blocks.length == 0) throw new NavigationFailure("No supported mining blocks for " + output);
        prepare(); mineBlocks = blocks.clone(); this.output = output; targetCount = totalCount; this.tool = tool;
        if (tool != null && !actions.prepareMiningTool(tool, mineBlocks[0].defaultBlockState(), output)) {
            throw new NavigationFailure(NavigationFailure.Kind.TOOL, "Required mining tool is unavailable or worn: " + tool.item());
        }
        mode = Mode.MINE;
        rescanInventoryCount = actions.count(output);
        rescanChunkX = (int) Math.floor(client.player.getX()) >> 4;
        rescanChunkZ = (int) Math.floor(client.player.getZ()) >> 4;
        launch();
    }

    private void prepare() {
        if (client.player == null || client.level == null) throw new NavigationFailure("World unavailable");
        stop();
        if (!finishCancellation()) throw new NavigationFailure("Finishing previous movement before starting a new route");
        if (bot == null) {
            bot = BaritoneAPI.getProvider().getPrimaryBaritone();
            bot.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override public void onPathEvent(PathEvent event) {
                    if (mode == Mode.IDLE && !cancelling) return;
                    if (event == PathEvent.CALC_FAILED) failedCalculations++;
                    if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                            "[Lodekeeper] NAV backend=baritone mode={} event={} elapsedMs={}",
                            mode, event, (System.nanoTime() - startedNanos) / 1_000_000);
                    if (config.debugLogging && event == PathEvent.CALC_STARTED && client.player != null)
                        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                                "[Lodekeeper] NAV calcTool={} slot={}",
                                GameCatalog.id(client.player.getMainHandItem().getItem()), client.player.getInventory().getSelectedSlot());
                }
            });
        }
        input.release(); actions.cancel();
        startedNanos = System.nanoTime(); requestTicks = failedCalculations = rescanAttempts = 0;
        nextRescanTick = 100; lastBreakTick = -100; waitingTicks = rescanFailedCalculations = 0;
        requestX = client.player.getX(); requestZ = client.player.getZ();
        routeGoal = null; diagnosticGoal = null; mineBlocks = new Block[0]; output = null; tool = null;
        observation = NavigationSnapshot.EMPTY; positionObserved = false; pendingBreakFailure = null;
        lastLoggedBreakPosition = null; lastLoggedBreakTool = null;
    }

    private void launch() {
        input.release();
        lease = new SettingsLease();
        Settings settings = BaritoneAPI.getSettings();
        lease.set(settings.allowBreak, config.allowBreaking);
        lease.set(settings.allowPlace, config.allowBuilding);
        lease.set(settings.allowInventory, false);
        lease.set(settings.allowParkour, config.allowParkour);
        lease.set(settings.allowParkourPlace, config.allowParkour && config.allowBuilding);
        lease.set(settings.allowSprint, true);
        lease.set(settings.allowWaterBucketFall, false);
        lease.set(settings.maxFallHeightNoWater, 3);
        lease.set(settings.autoTool, true);
        lease.set(settings.assumeExternalAutoTool, true);
        lease.set(settings.itemSaver, true);
        lease.set(settings.itemSaverThreshold, tool == null ? 1 : Math.max(1, tool.minimumDurability() - 1));
        lease.set(settings.renderPath, false); lease.set(settings.renderGoal, false);
        lease.set(settings.renderSelection, false); lease.set(settings.renderSelectionBoxes, false);
        lease.set(settings.mineScanDroppedItems, true);
        lease.set(settings.exploreForBlocks, config.allowExploration);
        lease.set(settings.legitMine, false);
        lease.set(settings.mineGoalUpdateInterval, 0);
        lease.set(settings.primaryTimeoutMS, 250L); lease.set(settings.failureTimeoutMS, 1500L);
        lease.set(settings.planAheadPrimaryTimeoutMS, 750L); lease.set(settings.planAheadFailureTimeoutMS, 2000L);
        applyProtection();
        actions.prepareScaffoldHotbar(scaffoldItems);
        switch (mode) {
            case MOVE -> bot.getCustomGoalProcess().setGoalAndPath(routeGoal);
            case PICKUP -> bot.getFollowProcess().pickup(stack -> stack.is(output));
            case MINE -> {
                boolean diamond = Arrays.asList(mineBlocks).contains(Blocks.DIAMOND_ORE)
                        || Arrays.asList(mineBlocks).contains(Blocks.DEEPSLATE_DIAMOND_ORE);
                int exploreY = (int) Math.floor(client.player.getY());
                if (diamond) exploreY = -55;
                else if (Arrays.asList(mineBlocks).contains(Blocks.IRON_ORE)
                        || Arrays.asList(mineBlocks).contains(Blocks.DEEPSLATE_IRON_ORE)) exploreY = 16;
                else if (Arrays.asList(mineBlocks).contains(Blocks.COAL_ORE)
                        || Arrays.asList(mineBlocks).contains(Blocks.DEEPSLATE_COAL_ORE)) exploreY = 48;
                lease.set(settings.legitMineYLevel, exploreY);
                // Upstream quantity includes several possible drops. Lodekeeper checks the exact output itself.
                bot.getMineProcess().mine(0, new BlockOptionalMetaLookup(mineBlocks));
            }
            default -> throw new IllegalStateException("No navigation request to launch");
        }
    }

    private void applyProtection() {
        Settings settings = BaritoneAPI.getSettings();
        List<Item> scaffold = new ArrayList<>();
        for (Item item : List.of(Blocks.DIRT.asItem(), Blocks.COBBLESTONE.asItem(),
                Blocks.NETHERRACK.asItem(), Blocks.STONE.asItem())) if (!reserved.contains(item)) scaffold.add(item);
        scaffoldItems = List.copyOf(scaffold);
        lease.set(settings.acceptableThrowawayItems, scaffold);
        Set<Block> forbidden = new LinkedHashSet<>(lease.original(settings.blocksToDisallowBreaking));
        forbidden.addAll(protectedBlocks);
        lease.set(settings.blocksToDisallowBreaking, new ArrayList<>(forbidden));
    }

    boolean tick() {
        if (cancelling) return finishCancellation();
        if (mode == Mode.SUSPENDED) { mode = resumeMode; resumeMode = Mode.IDLE; launch(); }
        if (mode == Mode.IDLE) return true;
        if (client.player == null || client.level == null) { stop(); return false; }
        input.release(); requestTicks++;
        if (pendingBreakFailure != null) {
            NavigationFailure failure = pendingBreakFailure; pendingBreakFailure = null;
            stop(); throw failure;
        }
        if (mode == Mode.MINE) {
            double limit = config.allowExploration ? config.explorationDistance : config.searchRadius;
            double dx = client.player.getX() - requestX, dz = client.player.getZ() - requestZ;
            if (dx * dx + dz * dz > limit * limit
                    || requestTicks > Math.max(config.actionTimeoutTicks, config.explorationAttempts * 200)) {
                stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                        "Mining reached its configured exploration distance or request-time limit");
            }
        }
        observeConfirmedProgress();
        if (requestTicks % 4 == 0) samplePath();
        actions.prepareScaffoldHotbar(scaffoldItems);
        if (mode == Mode.MINE && tool != null && !actions.hasTool(tool)) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.TOOL, "Mining tool reached its safe wear reserve: " + tool.item());
        }
        boolean satisfied = mode == Mode.MOVE
                ? routeGoal.isInGoal(client.player.blockPosition())
                : actions.count(output) >= targetCount;
        if (satisfied) { stop(); return finishCancellation(); }
        boolean active = switch (mode) {
            case MOVE -> bot.getCustomGoalProcess().isActive();
            case MINE -> bot.getMineProcess().isActive();
            case PICKUP -> bot.getFollowProcess().isActive();
            default -> false;
        };
        var pathing = bot.getPathingBehavior();
        boolean waiting = !pathing.hasPath() && pathing.getInProgress().isEmpty();
        if (!active && requestTicks > 10 && waiting) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Navigation process ended before its target was reached");
        }
        waitingTicks = waiting ? waitingTicks + 1 : 0;
        if (mode == Mode.MINE && requestTicks >= nextRescanTick && requestTicks - lastBreakTick > 20) {
            boolean exploring = pathing.getGoal() instanceof baritone.api.pathing.goals.GoalRunAway;
            if (exploring || waitingTicks >= 20) {
                int chunkX = (int) Math.floor(client.player.getX()) >> 4;
                int chunkZ = (int) Math.floor(client.player.getZ()) >> 4;
                int held = actions.count(output);
                if (failedCalculations > rescanFailedCalculations && held <= rescanInventoryCount
                        && chunkX == rescanChunkX && chunkZ == rescanChunkZ) {
                    stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                            "Mining failed to reach targets in this scan region");
                }
                if (++rescanAttempts > Math.max(1, config.explorationAttempts)) {
                    stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                            "Mining exhausted its configured discovery attempts");
                }
                nextRescanTick = requestTicks + 100;
                rescanFailedCalculations = failedCalculations;
                rescanInventoryCount = held; rescanChunkX = chunkX; rescanChunkZ = chunkZ;
                waitingTicks = 0;
                // No asynchronous scans survive into another request. Retain this request's limits.
                if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] NAV backend=baritone rescan={} requestTicks={} exploring={}",
                        rescanAttempts, requestTicks, exploring);
                suspend();
                return false;
            }
        }
        return false;
    }

    /** Native destroy HEAD gate: runs before the selected-slot sync and destroy packet. */
    boolean prepareAutomatedBreak(BlockPos position) {
        if (lease == null || bot == null
                || !bot.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)) return true;
        if (cancelling || mode == Mode.IDLE || mode == Mode.SUSPENDED || !config.allowBreaking) return false;
        if (client.level == null || client.player == null) return false;
        if (config.pauseOnScreen && GameApi.screen(client) != null) return false;
        var state = client.level.getBlockState(position);
        if (state.hasBlockEntity() || protectedBlocks.contains(state.getBlock())) {
            pendingBreakFailure = new NavigationFailure(NavigationFailure.Kind.PROTECTED_BLOCK,
                    "Navigation refused a protected block at " + position);
            return false;
        }
        SelectedToolRequirement required = mode == Mode.MINE && Arrays.asList(mineBlocks).contains(state.getBlock()) ? tool : null;
        if (!actions.prepareMiningTool(required, state, mode == Mode.MINE && Arrays.asList(mineBlocks).contains(state.getBlock()) ? output : null)) {
            pendingBreakFailure = new NavigationFailure(required == null
                    ? NavigationFailure.Kind.PROCESS_ENDED : NavigationFailure.Kind.TOOL,
                    "No safe harvest tool for " + state.getBlock() + " at " + position);
            return false;
        }
        var stack = client.player.getMainHandItem();
        if (config.debugLogging && (!position.equals(lastLoggedBreakPosition) || stack.getItem() != lastLoggedBreakTool)) {
            int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : -1;
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] BREAK target={} block={} tool={} slot={} remaining={} required={}",
                    position, state.getBlock(), GameCatalog.id(stack.getItem()), client.player.getInventory().getSelectedSlot(), remaining,
                    required == null ? "route" : required.item());
            lastLoggedBreakPosition = position.immutable();
            lastLoggedBreakTool = stack.getItem();
        }
        lastBreakTick = requestTicks;
        return true;
    }

    void suspend() {
        if (mode == Mode.IDLE || mode == Mode.SUSPENDED) return;
        resumeMode = mode; mode = Mode.SUSPENDED;
        cancelling = true; bot.getPathingBehavior().cancelEverything(); input.release();
    }

    void stop() {
        resumeMode = Mode.IDLE;
        if (bot != null && (mode != Mode.IDLE || cancelling || lease != null)) {
            mode = Mode.IDLE; cancelling = true; bot.getPathingBehavior().cancelEverything();
        }
        input.release(); observation = NavigationSnapshot.EMPTY;
    }

    boolean finishCancellation() {
        if (!cancelling) return true;
        boolean cancelled = bot.getPathingBehavior().cancelEverything();
        if (client.player != null && client.level != null) {
            if (!cancelled || bot.getPathingBehavior().hasPath() || bot.getPathingBehavior().isPathing()
                    || bot.getPathingBehavior().getInProgress().isPresent()) return false;
            var velocity = client.player.getDeltaMovement();
            if (!(client.player.onGround() || client.player.isInWater() || client.player.onClimbable())
                    || velocity.x * velocity.x + velocity.z * velocity.z > .0004) return false;
        }
        if (lease != null) { lease.restore(); lease = null; }
        cancelling = false; observation = NavigationSnapshot.EMPTY;
        return true;
    }

    void shutdownUpstream() {
        IBaritone primary = BaritoneAPI.getProvider().getPrimaryBaritone();
        primary.getPathingBehavior().cancelEverything();
        primary.getInputOverrideHandler().clearAllKeys();
        Class<?> implementation = primary.getClass();
        if (!implementation.getName().startsWith("baritone.")) return;
        java.util.concurrent.ThreadPoolExecutor executor = null;
        try {
            for (var field : implementation.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || field.getType() != java.util.concurrent.ThreadPoolExecutor.class) continue;
                if (executor != null) throw new IllegalStateException("Ambiguous upstream executor ownership");
                field.setAccessible(true);
                executor = (java.util.concurrent.ThreadPoolExecutor) field.get(null);
            }
            // The pinned API jars expose no lifecycle hook. Close their pool only when Minecraft quits.
            if (executor != null) executor.shutdownNow();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("lodekeeper").warn("[Lodekeeper] Upstream shutdown failed", failure);
        }
    }

    boolean canReconsiderMiningSource() {
        return mode == Mode.MINE && requestTicks - lastBreakTick > 20;
    }

    long progressToken() { return progressToken; }
    void recordConfirmedWorldAction() { progressToken++; }
    void observeConfirmedProgress() {
        if (client.player == null) return;
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        if (!positionObserved || Math.abs(x - observedX) + Math.abs(y - observedY) + Math.abs(z - observedZ) >= .05) {
            if (positionObserved) progressToken++;
            observedX = x; observedY = y; observedZ = z; positionObserved = true;
        }
    }

    private void samplePath() {
        var pathing = bot.getPathingBehavior();
        IPath upstream = pathing.getCurrent() == null ? null : pathing.getCurrent().getPath();
        int index = pathing.getCurrent() == null ? 0 : pathing.getCurrent().getPosition();
        var calculation = pathing.getInProgress();
        boolean searching = calculation.isPresent();
        Path path = null;
        if (upstream != null) {
            var positions = upstream.positions();
            int first = Math.max(0, Math.min(index, positions.size() - 1));
            int size = Math.min(256, positions.size() - first);
            int[] coordinates = new int[size * 3];
            for (int i = 0; i < size; i++) {
                var position = positions.get(first + i);
                coordinates[i * 3] = position.getX(); coordinates[i * 3 + 1] = position.getY(); coordinates[i * 3 + 2] = position.getZ();
            }
            path = Path.observation(coordinates);
        }
        observation = new NavigationSnapshot(path, 0, 0, 0, 0, System.nanoTime() - startedNanos,
                requestTicks, failedCalculations, searching, new long[0], new byte[0], new boolean[0]);
    }

    NavigationSnapshot visualization(boolean includeNodes) { return observation; }
    dev.lodekeeper.nav.Goal diagnosticGoal() { return diagnosticGoal; }
    int diagnosticGoalCandidateCount() { return diagnosticGoal == null ? 0 : 1; }
    String status() {
        if (cancelling) return "finishing movement safely";
        if (mode == Mode.SUSPENDED) return "movement suspended";
        if (mode == Mode.MINE) return "mining and collecting " + output + " · " + actions.count(output) + "/" + targetCount
                + (observation.searching() ? " · planning next route" : "");
        if (mode == Mode.PICKUP) return "collecting dropped " + output;
        return observation.searching() ? "planning route" : "following route";
    }

    /** Restore only values this task still owns; leave external setting edits intact. */
    private static final class SettingsLease {
        private final Map<Settings.Setting<?>, Object> originals = new IdentityHashMap<>();
        private final Map<Settings.Setting<?>, Object> assigned = new IdentityHashMap<>();
        <T> void set(Settings.Setting<T> setting, T value) {
            originals.putIfAbsent(setting, setting.value); assigned.put(setting, value); setting.value = value;
        }
        @SuppressWarnings("unchecked") <T> T original(Settings.Setting<T> setting) {
            return (T) originals.getOrDefault(setting, setting.value);
        }
        void restore() { originals.forEach((setting, value) -> restoreOne(setting, value)); }
        @SuppressWarnings("unchecked") private <T> void restoreOne(Settings.Setting<T> setting, Object value) {
            if (Objects.equals(setting.value, assigned.get(setting))) setting.value = (T) value;
        }
    }
}
