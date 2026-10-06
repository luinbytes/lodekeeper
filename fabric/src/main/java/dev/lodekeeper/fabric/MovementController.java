package dev.lodekeeper.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.utils.BlockOptionalMetaLookup;
import dev.lodekeeper.core.SelectedToolRequirement;
import dev.lodekeeper.nav.ExplorationFrontier;
import dev.lodekeeper.nav.NavigationSnapshot;
import dev.lodekeeper.nav.Path;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;

/** Owns one upstream process; inventory transactions wait for safe cancellation. */
final class MovementController {
    private enum Mode { IDLE, MOVE, MINE, DESCEND, PICKUP, SUSPENDED }
    private final MinecraftClient client;
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
    private int requestTicks, failedCalculations, lastBreakTick, phaseStartedTick;
    private int miningY = Integer.MIN_VALUE, lastDiscoveryMergeTick = -100, lastScanLogTick = -20;
    private BlockSearch miningDiscovery;
    private final Set<Long> scannedMiningChunks = new HashSet<>();
    private final Set<BlockPos> rejectedMiningTargets = new LinkedHashSet<>();
    private final Set<BlockPos> discoveredMiningTargets = new LinkedHashSet<>();
    private final Set<BlockPos> pendingMiningTargets = new LinkedHashSet<>();
    private NavigationFailure pendingBreakFailure;
    private BlockPos lastLoggedBreakPosition, miningTarget;
    private Item lastLoggedBreakTool;
    private List<Item> scaffoldItems = List.of();
    private NavigationSnapshot observation = NavigationSnapshot.EMPTY;

    static final class NavigationFailure extends IllegalStateException {
        enum Kind { TOOL, PROCESS_ENDED, PROTECTED_BLOCK, OTHER }
        final Kind kind;
        NavigationFailure(String reason) { this(Kind.OTHER, reason); }
        NavigationFailure(Kind kind, String reason) { super(reason); this.kind = kind; }
    }

    MovementController(MinecraftClient client, LodekeeperConfig config, PlayerActions actions,
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

    void startRetreat(List<BlockPos> threats, int distance) {
        if (threats.isEmpty() || threats.size() > 16 || distance < 4 || distance > 32)
            throw new IllegalArgumentException("Retreat requires bounded threat positions and distance");
        startMove(new GoalRunAway(distance, threats.toArray(BlockPos[]::new)), null);
    }

    void startInteraction(BlockPos target) {
        startMove(new GoalGetToBlock(target), dev.lodekeeper.nav.Goal.near16(target.getX(), target.getY() * 16, target.getZ(), 32));
    }

    private void startMove(baritone.api.pathing.goals.Goal goal, dev.lodekeeper.nav.Goal diagnostic) {
        prepare(); routeGoal = goal; diagnosticGoal = diagnostic; mode = Mode.MOVE;
        launch();
    }

    void startPickup(ItemEntity item) {
        prepare(); output = item.getStack().getItem(); targetCount = actions.count(output) + 1;
        BlockPos position = item.getBlockPos();
        diagnosticGoal = dev.lodekeeper.nav.Goal.near16(position.getX(), position.getY() * 16, position.getZ(), 32);
        mode = Mode.PICKUP; launch();
    }

    void startMining(Block[] blocks, Item output, int totalCount, SelectedToolRequirement tool) {
        if (blocks.length == 0) throw new NavigationFailure("No supported mining blocks for " + output);
        prepare(); mineBlocks = blocks.clone(); this.output = output; targetCount = totalCount; this.tool = tool;
        if (tool != null && !actions.prepareMiningTool(tool, mineBlocks[0].getDefaultState(), output)) {
            throw new NavigationFailure(NavigationFailure.Kind.TOOL, "Required mining tool is unavailable or worn: " + tool.item());
        }
        mode = Mode.MINE;
        miningY = miningLevel();
        launch();
    }

    private void prepare() {
        if (client.player == null || client.world == null) throw new NavigationFailure("World unavailable");
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
                                GameCatalog.id(client.player.getMainHandStack().getItem()), ClientAccess.selectedSlot(client.player.getInventory()));
                }
            });
        }
        input.release(); actions.cancel();
        startedNanos = System.nanoTime(); requestTicks = failedCalculations = 0;
        lastBreakTick = lastDiscoveryMergeTick = -100; lastScanLogTick = -20;
        phaseStartedTick = 0; miningY = Integer.MIN_VALUE;
        scannedMiningChunks.clear(); rejectedMiningTargets.clear(); miningDiscovery = null;
        discoveredMiningTargets.clear(); pendingMiningTargets.clear();
        requestX = client.player.getX(); requestZ = client.player.getZ();
        routeGoal = null; diagnosticGoal = null; mineBlocks = new Block[0]; output = null; tool = null;
        observation = NavigationSnapshot.EMPTY; positionObserved = false; pendingBreakFailure = null;
        lastLoggedBreakPosition = miningTarget = null; lastLoggedBreakTool = null;
    }

    private void launch() {
        input.release();
        phaseStartedTick = requestTicks;
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
        lease.set(settings.primaryTimeoutMS, 500L); lease.set(settings.failureTimeoutMS, 2000L);
        lease.set(settings.planAheadPrimaryTimeoutMS, 4000L); lease.set(settings.planAheadFailureTimeoutMS, 5000L);
        applyProtection();
        actions.prepareScaffoldHotbar(scaffoldItems);
        switch (mode) {
            case MOVE -> bot.getCustomGoalProcess().setGoalAndPath(routeGoal);
            case PICKUP -> bot.getFollowProcess().pickup(stack -> stack.isOf(output));
            case DESCEND -> bot.getCustomGoalProcess().setGoalAndPath(new GoalYLevel(miningY));
            case MINE -> {
                lease.set(settings.legitMineYLevel, miningY == Integer.MIN_VALUE ? (int) Math.floor(client.player.getY()) : miningY);
                // Native quantity counts several drops. The request checks its exact output itself.
                long scanStarted = System.nanoTime();
                bot.getMineProcess().mine(0, new BlockOptionalMetaLookup(mineBlocks));
                var access = miningAccess();
                access.lodekeeper$blacklistedMiningTargets().addAll(rejectedMiningTargets);
                if (!rejectedMiningTargets.isEmpty())
                    access.lodekeeper$knownMiningTargets(new ArrayList<>(access.lodekeeper$knownMiningTargets().stream()
                            .filter(position -> !rejectedMiningTargets.contains(position)).toList()));
                logMiningScan("native-mine", System.nanoTime() - scanStarted, -1);
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
        if (mode == Mode.IDLE && !cancelling) return true;
        if (client.player == null || client.world == null) { stop(); return false; }
        if (mode != Mode.IDLE || resumeMode != Mode.IDLE) requestTicks++;
        boolean miningRequest = mode == Mode.MINE || mode == Mode.DESCEND
                || mode == Mode.SUSPENDED && (resumeMode == Mode.MINE || resumeMode == Mode.DESCEND);
        if (pendingBreakFailure != null) {
            NavigationFailure failure = pendingBreakFailure; pendingBreakFailure = null;
            stop(); throw failure;
        }
        if (miningRequest) {
            double limit = config.allowExploration ? config.explorationDistance : config.searchRadius;
            double dx = client.player.getX() - requestX, dz = client.player.getZ() - requestZ;
            int maxTicks = Math.max(config.actionTimeoutTicks, config.explorationAttempts * 200);
            if (dx * dx + dz * dz > limit * limit || requestTicks > maxTicks
                    || System.nanoTime() - startedNanos > maxTicks * 50_000_000L) {
                stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                        "Mining reached its configured exploration distance or request-time limit");
            }
            if (!config.allowBreaking || !config.allowExploration
                    && (mode == Mode.DESCEND || resumeMode == Mode.DESCEND)) {
                stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                        "Mining or depth exploration was disabled during the request");
            }
            if (tool != null && !actions.hasTool(tool)) {
                stop(); throw new NavigationFailure(NavigationFailure.Kind.TOOL,
                        "Mining tool reached its safe wear reserve: " + tool.item());
            }
        }
        if (cancelling) {
            boolean finished = finishCancellation();
            return finished && mode == Mode.IDLE && resumeMode == Mode.IDLE;
        }
        if (mode == Mode.SUSPENDED) { mode = resumeMode; resumeMode = Mode.IDLE; launch(); }
        if (mode == Mode.IDLE) return true;
        input.release();
        observeConfirmedProgress();
        if (requestTicks % 4 == 0) samplePath();
        actions.prepareScaffoldHotbar(scaffoldItems);
        boolean satisfied = mode == Mode.MOVE
                ? routeGoal.isInGoal(client.player.getBlockPos())
                : actions.count(output) >= targetCount;
        if (satisfied) { stop(); return finishCancellation(); }
        if (mode == Mode.DESCEND && new GoalYLevel(miningY).isInGoal(client.player.getBlockPos())) {
            changeMiningPhase(Mode.MINE);
            return false;
        }
        boolean active = switch (mode) {
            case MOVE, DESCEND -> bot.getCustomGoalProcess().isActive();
            case MINE -> bot.getMineProcess().isActive();
            case PICKUP -> bot.getFollowProcess().isActive();
            default -> false;
        };
        var pathing = bot.getPathingBehavior();
        boolean waiting = !pathing.hasPath() && pathing.getInProgress().isEmpty();
        boolean exploring = mode == Mode.MINE && active && pathing.getGoal() instanceof GoalRunAway
                && requestTicks - phaseStartedTick >= 5;
        if (miningY != Integer.MIN_VALUE && (mode == Mode.MINE || mode == Mode.DESCEND)) {
            if (mode == Mode.MINE && active) rememberRejectedMiningTargets();
            scanOneMiningChunk();
            pendingMiningTargets.removeIf(position -> !validMiningDiscovery(position)
                    || rejectedMiningTargets.contains(position));
            if (mode == Mode.MINE && active && requestTicks - lastDiscoveryMergeTick >= 4)
                mergeMiningDiscoveries();
            if ((mode == Mode.DESCEND || mode == Mode.MINE && !active) && !pendingMiningTargets.isEmpty()) {
                changeMiningPhase(Mode.MINE);
                return false;
            }
        }
        if (exploring && miningAccess().lodekeeper$knownMiningTargets().isEmpty()
                && config.allowExploration && miningY != Integer.MIN_VALUE
                && Math.abs((int) Math.floor(client.player.getY()) - miningY) > 8) {
            changeMiningPhase(Mode.DESCEND);
            return false;
        }
        if (!active && requestTicks - phaseStartedTick > 10 && waiting) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Navigation process ended before its target was reached");
        }
        return false;
    }

    private int miningLevel() {
        List<Block> blocks = Arrays.asList(mineBlocks);
        if (!List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COAL_ORE,
                Blocks.DEEPSLATE_COAL_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE).containsAll(blocks)) {
            return Integer.MIN_VALUE;
        }
        if (blocks.contains(Blocks.DIAMOND_ORE) || blocks.contains(Blocks.DEEPSLATE_DIAMOND_ORE)) return -55;
        if (blocks.contains(Blocks.IRON_ORE) || blocks.contains(Blocks.DEEPSLATE_IRON_ORE)) return 16;
        if (blocks.contains(Blocks.COAL_ORE) || blocks.contains(Blocks.DEEPSLATE_COAL_ORE)) return 48;
        return Integer.MIN_VALUE;
    }

    private void changeMiningPhase(Mode next) {
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] NAV phase={} -> {} targetY={} requestTicks={}", mode, next, miningY, requestTicks);
        if (mode == Mode.MINE && bot.getMineProcess().isActive()) rememberRejectedMiningTargets();
        resumeMode = next; mode = Mode.SUSPENDED;
        cancelling = true; bot.getPathingBehavior().cancelEverything(); input.release();
    }

    private static long miningChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private boolean validMiningDiscovery(BlockPos position) {
        if (client.world.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) == null) return false;
        double limit = config.allowExploration ? config.explorationDistance : config.searchRadius;
        double dx = position.getX() + .5 - requestX, dz = position.getZ() + .5 - requestZ;
        if (dx * dx + dz * dz > limit * limit) return false;
        var state = client.world.getBlockState(position);
        return Arrays.asList(mineBlocks).contains(state.getBlock()) && !state.hasBlockEntity()
                && !protectedBlocks.contains(state.getBlock()) && state.getHardness(client.world, position) >= 0;
    }

    private BaritoneMiningAccess miningAccess() {
        if (!(bot.getMineProcess() instanceof BaritoneMiningAccess access))
            throw new NavigationFailure("The verified mining bridge is unavailable");
        return access;
    }

    private void rememberRejectedMiningTargets() {
        for (BlockPos position : miningAccess().lodekeeper$blacklistedMiningTargets()) {
            if (rejectedMiningTargets.size() == 512) break;
            rejectedMiningTargets.add(position.toImmutable());
        }
    }

    private void mergeMiningDiscoveries() {
        if (pendingMiningTargets.isEmpty()) return;
        var access = miningAccess();
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        for (BlockPos existing : access.lodekeeper$knownMiningTargets()) {
            if (merged.size() == 64) break;
            if (!rejectedMiningTargets.contains(existing)) merged.add(existing);
        }
        int previousSize = merged.size();
        for (BlockPos fresh : pendingMiningTargets) {
            if (merged.size() == 64) break;
            if (!rejectedMiningTargets.contains(fresh) && validMiningDiscovery(fresh)) merged.add(fresh);
        }
        pendingMiningTargets.removeAll(merged);
        if (merged.size() == previousSize) return;
        access.lodekeeper$knownMiningTargets(new ArrayList<>(merged));
        lastDiscoveryMergeTick = requestTicks;
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] NAV discovered={} retained={} rejected={} requestTicks={} phase={}",
                merged.size() - previousSize, merged.size(), rejectedMiningTargets.size(), requestTicks, mode);
    }

    private void scanOneMiningChunk() {
        if (requestTicks % 20 == 0)
            discoveredMiningTargets.removeIf(position -> !validMiningDiscovery(position) || rejectedMiningTargets.contains(position));
        if (miningDiscovery == null) {
            if (scannedMiningChunks.size() >= Math.min(128, Math.max(1, config.explorationAttempts) * 4)
                    || discoveredMiningTargets.size() >= 512) return;
            int playerChunkX = (int) Math.floor(client.player.getX()) >> 4;
            int playerChunkZ = (int) Math.floor(client.player.getZ()) >> 4;
            double limit = config.allowExploration ? config.explorationDistance : config.searchRadius;
            int radius = Math.min(8, (int) Math.ceil(limit / 16) + 1);
            long nearest = Long.MIN_VALUE;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                int cx = playerChunkX + dx, cz = playerChunkZ + dz;
                long key = miningChunkKey(cx, cz);
                if (scannedMiningChunks.contains(key)) continue;
                double x = cx * 16.0 + 8, z = cz * 16.0 + 8;
                double originX = x - requestX, originZ = z - requestZ;
                if (originX * originX + originZ * originZ > (limit + 12) * (limit + 12)) continue;
                double px = x - client.player.getX(), pz = z - client.player.getZ();
                double distance = px * px + pz * pz;
                if (distance >= bestDistance || client.world.getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;
                nearest = key; bestDistance = distance;
            }
            if (nearest == Long.MIN_VALUE) return;
            int chunkX = (int) (nearest >> 32), chunkZ = (int) nearest;
            scannedMiningChunks.add(nearest);
            miningDiscovery = new BlockSearch(client, Set.copyOf(Arrays.asList(mineBlocks)),
                    (int) Math.ceil(limit), Set.of(), false, List.of(new ChunkPos(chunkX, chunkZ)));
        }
        long scanStarted = System.nanoTime();
        boolean complete = miningDiscovery.advance(4096, 1_000_000L);
        int added = 0;
        for (BlockPos position : miningDiscovery.results()) {
            if (added == 32 || discoveredMiningTargets.size() == 512) break;
            if (validMiningDiscovery(position) && !rejectedMiningTargets.contains(position)
                    && discoveredMiningTargets.add(position)) {
                pendingMiningTargets.add(position); added++;
            }
        }
        if (complete && miningDiscovery.results().stream().noneMatch(position ->
                !discoveredMiningTargets.contains(position) && !rejectedMiningTargets.contains(position)
                        && validMiningDiscovery(position))) miningDiscovery = null;
        logMiningScan("loaded-chunk-budgeted", System.nanoTime() - scanStarted, added);
    }

    private void logMiningScan(String source, long nanos, int hits) {
        if (config.debugLogging && nanos > 2_000_000L && requestTicks - lastScanLogTick >= 20) {
            lastScanLogTick = requestTicks;
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] NAV scan={} durationNanos={} hits={} requestTicks={}", source, nanos, hits, requestTicks);
        }
    }

    /** Native destroy HEAD gate: runs before the selected-slot sync and destroy packet. */
    boolean prepareAutomatedBreak(BlockPos position) {
        if (lease == null || bot == null
                || !bot.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT)) return true;
        if (cancelling || mode == Mode.IDLE || mode == Mode.SUSPENDED || !config.allowBreaking) return false;
        if (client.world == null || client.player == null) return false;
        if (config.pauseOnScreen && client.currentScreen != null) return false;
        var state = client.world.getBlockState(position);
        if (state.hasBlockEntity() || protectedBlocks.contains(state.getBlock())) {
            pendingBreakFailure = new NavigationFailure(NavigationFailure.Kind.PROTECTED_BLOCK,
                    "Navigation refused a protected block at " + position);
            return false;
        }
        boolean requestedOre = (mode == Mode.MINE || mode == Mode.DESCEND) && Arrays.asList(mineBlocks).contains(state.getBlock());
        SelectedToolRequirement required = requestedOre ? tool : null;
        if (!actions.prepareMiningTool(required, state, requestedOre ? output : null)) {
            pendingBreakFailure = new NavigationFailure(required == null
                    ? NavigationFailure.Kind.PROCESS_ENDED : NavigationFailure.Kind.TOOL,
                    "No safe harvest tool for " + state.getBlock() + " at " + position);
            return false;
        }
        var stack = client.player.getMainHandStack();
        if (config.debugLogging && (!position.equals(lastLoggedBreakPosition) || stack.getItem() != lastLoggedBreakTool)) {
            int remaining = stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : -1;
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] BREAK target={} block={} tool={} slot={} remaining={} required={}",
                    position, state.getBlock(), GameCatalog.id(stack.getItem()), ClientAccess.selectedSlot(client.player.getInventory()), remaining,
                    required == null ? "route" : required.item());
            lastLoggedBreakPosition = position.toImmutable();
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
            mode = Mode.IDLE;
            if (!cancelling) {
                cancelling = true;
                bot.getPathingBehavior().cancelEverything();
            }
        }
        input.release(); observation = NavigationSnapshot.EMPTY; miningTarget = null;
    }

    boolean finishCancellation() {
        if (!cancelling) return true;
        var pathing = bot.getPathingBehavior();
        boolean hasWork = pathing.hasPath() || pathing.isPathing() || pathing.getInProgress().isPresent();
        boolean cancelled = !hasWork || pathing.cancelEverything();
        if (client.player != null && client.world != null) {
            if (!cancelled || bot.getPathingBehavior().hasPath() || bot.getPathingBehavior().isPathing()
                    || bot.getPathingBehavior().getInProgress().isPresent()) return false;
            var velocity = client.player.getVelocity();
            boolean carriedByFluidOrClimb = client.player.isTouchingWater() || client.player.isClimbing();
            if (!carriedByFluidOrClimb && (!client.player.isOnGround()
                    || velocity.x * velocity.x + velocity.z * velocity.z > .0004)) return false;
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
        miningTarget = mode == Mode.MINE && upstream != null
                ? miningTarget(upstream.getGoal(), upstream.getDest(), 0) : null;
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

    private BlockPos miningTarget(baritone.api.pathing.goals.Goal goal, BlockPos destination, int depth) {
        if (depth > 2 || goal == null || !goal.isInGoal(destination)) return null;
        if (goal instanceof GoalComposite composite) {
            for (var child : composite.goals()) {
                BlockPos result = miningTarget(child, destination, depth + 1);
                if (result != null) return result;
            }
        } else if (goal instanceof IGoalRenderPos rendered) {
            BlockPos position = rendered.getGoalPos();
            if (client.world.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) == null) return null;
            for (int dy : new int[]{0, 1, -1, 2}) {
                BlockPos candidate = position.add(0, dy, 0);
                if (Arrays.asList(mineBlocks).contains(client.world.getBlockState(candidate).getBlock())) return candidate.toImmutable();
            }
        }
        return null;
    }

    BlockPos miningTarget() { return miningTarget; }

    NavigationSnapshot visualization(boolean includeNodes) { return observation; }
    dev.lodekeeper.nav.Goal diagnosticGoal() { return diagnosticGoal; }
    int diagnosticGoalCandidateCount() { return diagnosticGoal == null ? 0 : 1; }
    String status() {
        if (cancelling) return "finishing movement safely";
        if (mode == Mode.SUSPENDED) return "movement suspended";
        if (mode == Mode.MINE || mode == Mode.DESCEND) return (mode == Mode.DESCEND ? "seeking mining depth " + miningY + " for " : "mining and collecting ") + output + " · " + actions.count(output) + "/" + targetCount
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
