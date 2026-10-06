package dev.lodekeeper.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.ICustomGoalProcess;
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
import dev.lodekeeper.nav.StanceProbe;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;
import java.util.function.Predicate;

/** Owns one upstream process; inventory transactions wait for safe cancellation. */
final class MovementController {
    private static final int AIR_RECOVERY_MAX_PROBES = 12_000;
    private static final long AIR_RECOVERY_SEARCH_BUDGET_NANOS = 4_000_000L;
    private static final int AIR_RECOVERY_MAX_GOALS = 16;
    private static final int AIR_SWIM_MAX_VISITED = 4_096;
    private static final int AIR_SWIM_MAX_PATH = 64;
    private static final int[][] AIR_SWIM_STEPS = {
            {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
    };

    private record AirRecoveryCandidate(BlockPos position, int score, int distanceSquared, boolean supported) { }
    private record AirRecoveryOffset(int x, int z, int distanceSquared) { }
    private record AirSwimCell(boolean surface, boolean dryExit) { }
    private record AirSwimNode(BlockPos position, int depth) { }

    private static final Comparator<AirRecoveryCandidate> AIR_RECOVERY_CANDIDATE_ORDER =
            Comparator.comparingInt(AirRecoveryCandidate::score)
                    .thenComparingInt(AirRecoveryCandidate::distanceSquared)
                    .thenComparing(candidate -> !candidate.supported())
                    .thenComparingInt(candidate -> -candidate.position().getY())
                    .thenComparingInt(candidate -> candidate.position().getX())
                    .thenComparingInt(candidate -> candidate.position().getZ());

    record RetreatThreat(double x, double z) { }
    private enum Mode { IDLE, MOVE, AIR, MINE, DESCEND, PICKUP, FOLLOW, SUSPENDED }
    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    private final GameTerrain terrain;
    private IBaritone bot;
    private IBaritoneProcess cancellationProcess;
    private Mode mode = Mode.IDLE, resumeMode = Mode.IDLE;
    private boolean cancelling, followCancellationPending;
    private baritone.api.pathing.goals.Goal routeGoal;
    private baritone.api.pathing.goals.Goal airRecoveryGoal;
    private BlockPos lastAirRecoveryDestination;
    private BlockPos airSwimOrigin;
    private boolean airRecoveryCancellationPending;
    private dev.lodekeeper.nav.Goal diagnosticGoal;
    private Block[] mineBlocks = new Block[0];
    private Item output;
    private int targetCount;
    private SelectedToolRequirement tool;
    private Set<Item> reserved = Set.of();
    private Set<Block> protectedBlocks = Set.of();
    private SettingsLease lease;
    private UUID followTargetId;
    private Object followOwnerWorld, followOwnerPlayer;
    private Predicate<Entity> followFilter;
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
    private NavigationFailure pendingBreakFailure, pendingOwnershipFailure;
    private BlockPos lastLoggedBreakPosition, miningTarget;
    private Item lastLoggedBreakTool;
    private List<Item> scaffoldItems = List.of();
    private NavigationSnapshot observation = NavigationSnapshot.EMPTY;

    enum RequestLimitCause { DISTANCE, TICKS, WALL_TIME }
    record MiningRequestLimit(RequestLimitCause cause, int requestTicks, int maximumTicks,
                              long elapsedMillis, double distance, double maximumDistance,
                              Set<BlockPos> rejectedPositions) {
        MiningRequestLimit { rejectedPositions = Set.copyOf(rejectedPositions); }
    }

    static final class NavigationFailure extends IllegalStateException {
        enum Kind { TOOL, PROCESS_ENDED, OWNERSHIP_LOST, REQUEST_LIMIT, PROTECTED_BLOCK, OTHER }
        final Kind kind;
        final MiningRequestLimit requestLimit;
        NavigationFailure(String reason) { this(Kind.OTHER, reason); }
        NavigationFailure(Kind kind, String reason) { this(kind, reason, null); }
        NavigationFailure(Kind kind, String reason, MiningRequestLimit requestLimit) {
            super(reason); this.kind = kind; this.requestLimit = requestLimit;
        }
    }

    MovementController(MinecraftClient client, LodekeeperConfig config, PlayerActions actions,
                       BotInput input, GameTerrain terrain) {
        this.client = client; this.config = config; this.actions = actions; this.input = input; this.terrain = terrain;
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

    List<BlockPos> startRetreat(List<RetreatThreat> threats, int distance, Set<BlockPos> rejectedGoals, BlockPos origin) {
        if (threats.isEmpty() || threats.size() > 16 || distance < 4 || distance > 32
                || rejectedGoals.size() > 32 || origin == null
                || threats.stream().anyMatch(threat -> !Double.isFinite(threat.x()) || !Double.isFinite(threat.z())))
            throw new IllegalArgumentException("Retreat requires bounded threat positions and distance");
        prepare();
        terrain.beginSearch();
        BlockPos center = client.player.getBlockPos();
        List<BlockPos> goals = new ArrayList<>();
        Set<BlockPos> examined = new HashSet<>();
        StanceProbe stance = new StanceProbe();
        search: for (int radius = 4; radius <= 12; radius++) {
            for (int direction = 0; direction < 8; direction++) {
                double angle = direction * Math.PI / 4;
                int dx = (int) Math.round(Math.cos(angle)) * radius;
                int dz = (int) Math.round(Math.sin(angle)) * radius;
                if (dx * dx + dz * dz > 12 * 12) continue;
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                for (int dy : new int[]{0, 1, -1, 2, -2}) {
                    BlockPos candidate = new BlockPos(x, center.getY() + dy, z);
                    if (!examined.add(candidate) || rejectedGoals.contains(candidate)) continue;
                    double ox = x + .5 - origin.getX(), oy = candidate.getY() - origin.getY(), oz = z + .5 - origin.getZ();
                    if (ox * ox + oy * oy + oz * oz > 30.0 * 30.0) continue;
                    if (threats.stream().anyMatch(threat -> {
                        double tx = x + .5 - threat.x(), tz = z + .5 - threat.z();
                        return tx * tx + tz * tz < (distance + 3.0) * (distance + 3.0);
                    })) continue;
                    if (client.world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null
                            || !actions.safePlacementSupport(candidate.down())
                            || client.world.getBlockState(candidate.up(2)).getBlock() instanceof net.minecraft.block.FallingBlock) continue;
                    terrain.probeStance16(x, Math.multiplyExact(candidate.getY(), 16), z, stance);
                    if (!stance.loaded || !stance.bodyClear || !stance.fullSupport || stance.hazard
                            || stance.water || stance.climbable || stance.breakCount != 0) continue;
                    goals.add(candidate);
                    if (goals.size() == 16) break search;
                }
            }
        }
        if (goals.isEmpty()) throw new NavigationFailure("No safe dry retreat stance remains within the bounded search");
        routeGoal = new GoalComposite(goals.stream().map(GoalBlock::new).toArray(baritone.api.pathing.goals.Goal[]::new));
        diagnosticGoal = null;
        mode = Mode.MOVE;
        launch();
        return List.copyOf(goals);
    }

    enum AirExitPreference { DRY, SURFACE }

    List<BlockPos> startAirRecovery(Set<BlockPos> rejectedGoals, AirExitPreference preference) {
        Objects.requireNonNull(rejectedGoals);
        Objects.requireNonNull(preference);
        if (rejectedGoals.size() > 32) throw new IllegalArgumentException("Air recovery rejection seed exceeds 32 positions");
        if (client.player == null || client.world == null) throw new NavigationFailure("World unavailable");
        checkAirRecoveryOwnership();
        List<BlockPos> goals = new AirRecoverySearch(Set.copyOf(rejectedGoals), preference).findGoals();
        if (goals.isEmpty()) throw new NavigationFailure("No breathable air recovery stance remains within the bounded search");

        checkAirRecoveryOwnership();
        prepare();
        airRecoveryGoal = new GoalComposite(goals.stream().map(GoalBlock::new)
                .toArray(baritone.api.pathing.goals.Goal[]::new));
        lastAirRecoveryDestination = null;
        routeGoal = airRecoveryGoal;
        diagnosticGoal = null;
        mode = Mode.AIR;
        launch();
        return List.copyOf(goals);
    }

    private final class AirRecoverySearch {
        private final Set<BlockPos> rejectedGoals;
        private final AirExitPreference preference;
        private final BlockPos center = client.player.getBlockPos();
        private final long startedNanos = System.nanoTime();
        private final Map<Long, Boolean> loadedChunks = new HashMap<>();
        private final List<AirRecoveryCandidate> dryCandidates = new ArrayList<>(AIR_RECOVERY_MAX_GOALS);
        private final List<AirRecoveryCandidate> floatingCandidates = new ArrayList<>(AIR_RECOVERY_MAX_GOALS);
        private final BlockPos.Mutable feet = new BlockPos.Mutable();
        private final BlockPos.Mutable head = new BlockPos.Mutable();
        private final BlockPos.Mutable overhead = new BlockPos.Mutable();
        private final BlockPos.Mutable support = new BlockPos.Mutable();
        private int probes;
        private boolean exhausted;

        private AirRecoverySearch(Set<BlockPos> rejectedGoals, AirExitPreference preference) {
            this.rejectedGoals = rejectedGoals; this.preference = preference;
        }

        private List<BlockPos> findGoals() {
            scanOffset(0, 0);
            List<AirRecoveryOffset> offsets = new ArrayList<>();
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                int distanceSquared = dx * dx + dz * dz;
                if ((dx != 0 || dz != 0) && distanceSquared <= 12 * 12)
                    offsets.add(new AirRecoveryOffset(dx, dz, distanceSquared));
            }
            offsets.sort(Comparator.comparingInt(AirRecoveryOffset::distanceSquared)
                    .thenComparingInt(AirRecoveryOffset::x).thenComparingInt(AirRecoveryOffset::z));
            for (AirRecoveryOffset offset : offsets) {
                if (exhausted) break;
                scanOffset(offset.x(), offset.z());
                List<AirRecoveryCandidate> preferred = preference == AirExitPreference.DRY ? dryCandidates : floatingCandidates;
                if (preferred.size() == AIR_RECOVERY_MAX_GOALS
                        && offset.distanceSquared() > preferred.get(preferred.size() - 1).score()) break;
            }
            List<AirRecoveryCandidate> selected = preference == AirExitPreference.DRY ? dryCandidates : floatingCandidates;
            if (selected.isEmpty()) selected = preference == AirExitPreference.DRY ? floatingCandidates : dryCandidates;
            return selected.stream().map(AirRecoveryCandidate::position).toList();
        }

        private void scanOffset(int dx, int dz) {
            if (exhausted) return;
            probe(dx, dz, 0);
            for (int dy = 1; dy <= 16; dy++) {
                if (exhausted) return;
                probe(dx, dz, dy);
                if (dy == 1 && !exhausted) probe(dx, dz, -1);
            }
        }

        private void probe(int dx, int dz, int dy) {
            if (exhausted) return;
            if (probes >= AIR_RECOVERY_MAX_PROBES || System.nanoTime() - startedNanos > AIR_RECOVERY_SEARCH_BUDGET_NANOS) {
                exhausted = true;
                return;
            }
            probes++;

            int x = center.getX() + dx, y = center.getY() + dy, z = center.getZ() + dz;
            if (!inWorld(y) || !inWorld(y + 1) || !inWorld(y + 2)
                    || rejectedGoals.contains(new BlockPos(x, y, z)) || !loaded(x, z)) return;
            feet.set(x, y, z);
            head.set(x, y + 1, z);
            overhead.set(x, y + 2, z);
            BlockState feetState = client.world.getBlockState(feet);
            BlockState headState = client.world.getBlockState(head);
            if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                    || !clearAirRecoveryCell(feetState, feet) || !clearAirRecoveryCell(headState, head)
                    || !headState.getFluidState().isEmpty()
                    || !(feetState.getFluidState().isEmpty() || feetState.getFluidState().isIn(FluidTags.WATER))) return;
            if (client.world.getBlockState(overhead).getBlock() instanceof FallingBlock) return;

            boolean waterFeet = feetState.getFluidState().isIn(FluidTags.WATER);
            if (!inWorld(y - 1)) return;
            support.set(x, y - 1, z);
            BlockState supportState = client.world.getBlockState(support);
            boolean supported = supportState.getFluidState().isEmpty() && !hazardousAirRecoveryState(supportState)
                    && Block.isShapeFullCube(supportState.getCollisionShape(client.world, support));
            if (!waterFeet && !supported) return;
            int distanceSquared = dx * dx + dy * dy + dz * dz;
            int score = distanceSquared + (supported ? 0 : 3) + (waterFeet ? 2 : 0);
            AirRecoveryCandidate candidate = new AirRecoveryCandidate(feet.toImmutable(), score,
                    distanceSquared, supported);
            List<AirRecoveryCandidate> ranked = waterFeet ? floatingCandidates : dryCandidates;
            int index = Collections.binarySearch(ranked, candidate, AIR_RECOVERY_CANDIDATE_ORDER);
            if (index < 0) index = -index - 1;
            ranked.add(index, candidate);
            if (ranked.size() > AIR_RECOVERY_MAX_GOALS) ranked.remove(ranked.size() - 1);
        }

        private boolean loaded(int x, int z) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            long key = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
            Boolean present = loadedChunks.get(key);
            if (present == null) {
                present = client.world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
                loadedChunks.put(key, present);
            }
            return present;
        }

        private boolean inWorld(int y) { return !client.world.isOutOfHeightLimit(y); }

    }

    private boolean clearAirRecoveryCell(BlockState state, BlockPos position) {
        return state.getCollisionShape(client.world, position).isEmpty();
    }

    private static boolean hazardousAirRecoveryState(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA) || state.isOf(Blocks.FIRE)
                || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.MAGMA_BLOCK) || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.WITHER_ROSE);
    }

    List<BlockPos> airSwimRoute() {
        checkAirRecoveryOwnership();
        if (client.player == null || client.world == null) throw new NavigationFailure("World unavailable");
        IBaritone activeBot = bot == null ? BaritoneAPI.getProvider().getPrimaryBaritone() : bot;
        var pathing = activeBot.getPathingBehavior();
        if (mode != Mode.IDLE || resumeMode != Mode.IDLE || cancelling || cancellationProcess != null
                || airRecoveryCancellationPending || lease != null || pathing.hasPath() || pathing.isPathing()
                || pathing.getInProgress().isPresent())
            throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Native movement cancellation must finish before air-swim routing");

        BlockPos start = client.player.getBlockPos();
        AirSwimSearch search = new AirSwimSearch(start);
        List<BlockPos> route = search.findRoute();
        airSwimOrigin = route.isEmpty() ? null : start.toImmutable();
        return route;
    }

    /** Revalidates a planned swim step against loaded native collision and fluid state only. */
    boolean airSwimStepClear(BlockPos feetPosition) {
        if (feetPosition == null || airSwimOrigin == null || client.player == null || client.world == null
                || !withinAirSwimBounds(feetPosition, airSwimOrigin)) return false;
        int x = feetPosition.getX(), y = feetPosition.getY(), z = feetPosition.getZ();
        if (!airSwimChunkLoaded(x, z) || !airSwimInWorld(y - 1)
                || !airSwimInWorld(y) || !airSwimInWorld(y + 1) || !airSwimInWorld(y + 2)) return false;

        BlockPos headPosition = new BlockPos(x, y + 1, z);
        BlockPos supportPosition = new BlockPos(x, y - 1, z);
        BlockPos overheadPosition = new BlockPos(x, y + 2, z);
        BlockState feetState = client.world.getBlockState(feetPosition);
        BlockState headState = client.world.getBlockState(headPosition);
        BlockState supportState = client.world.getBlockState(supportPosition);
        if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                || hazardousAirRecoveryState(supportState)
                || !clearAirRecoveryCell(feetState, feetPosition)
                || !clearAirRecoveryCell(headState, headPosition)
                || client.world.getBlockState(overheadPosition).getBlock() instanceof FallingBlock
                || supportState.getBlock() instanceof FallingBlock) return false;

        boolean feetWater = feetState.getFluidState().isIn(FluidTags.WATER);
        boolean feetDry = feetState.getFluidState().isEmpty();
        boolean headWater = headState.getFluidState().isIn(FluidTags.WATER);
        boolean headDry = headState.getFluidState().isEmpty();
        if ((!feetWater && !feetDry) || (!headWater && !headDry)) return false;
        boolean supportedDry = supportState.getFluidState().isEmpty()
                && Block.isShapeFullCube(supportState.getCollisionShape(client.world, supportPosition));
        boolean aboveWater = supportState.getFluidState().isIn(FluidTags.WATER);
        return feetWater || supportedDry || feetDry && headDry && aboveWater;
    }

    private final class AirSwimSearch {
        private final BlockPos origin;
        private final long startedNanos = System.nanoTime();
        private final Map<Long, Boolean> loadedChunks = new HashMap<>();
        private final Map<BlockPos, BlockPos> parents = new HashMap<>();
        private final ArrayDeque<AirSwimNode> frontier = new ArrayDeque<>();
        private final BlockPos.Mutable feet = new BlockPos.Mutable();
        private final BlockPos.Mutable head = new BlockPos.Mutable();
        private final BlockPos.Mutable support = new BlockPos.Mutable();
        private final BlockPos.Mutable overhead = new BlockPos.Mutable();
        private int probes;
        private boolean exhausted;
        private BlockPos surfaceFallback;

        private AirSwimSearch(BlockPos origin) { this.origin = origin.toImmutable(); }

        private List<BlockPos> findRoute() {
            AirSwimCell startCell = inspect(origin);
            if (startCell == null) return List.of();
            parents.put(origin, null);
            frontier.addLast(new AirSwimNode(origin, 0));
            if (startCell.surface()) surfaceFallback = origin;
            if (startCell.dryExit()) return List.of();

            while (!frontier.isEmpty() && !exhausted) {
                AirSwimNode current = frontier.removeFirst();
                if (current.depth() >= AIR_SWIM_MAX_PATH) continue;
                for (int[] step : AIR_SWIM_STEPS) {
                    if (probes >= AIR_RECOVERY_MAX_PROBES
                            || System.nanoTime() - startedNanos > AIR_RECOVERY_SEARCH_BUDGET_NANOS) {
                        exhausted = true;
                        break;
                    }
                    probes++;
                    BlockPos next = new BlockPos(current.position().getX() + step[0],
                            current.position().getY() + step[1], current.position().getZ() + step[2]);
                    if (!withinAirSwimBounds(next, origin) || parents.containsKey(next) || !loaded(next)) continue;
                    if (parents.size() >= AIR_SWIM_MAX_VISITED) {
                        exhausted = true;
                        break;
                    }
                    AirSwimCell cell = inspect(next);
                    if (cell == null) continue;
                    parents.put(next, current.position());
                    int depth = current.depth() + 1;
                    if (cell.dryExit()) return reconstruct(next);
                    if (surfaceFallback == null && cell.surface()) surfaceFallback = next;
                    frontier.addLast(new AirSwimNode(next, depth));
                }
            }
            return surfaceFallback == null ? List.of() : reconstruct(surfaceFallback);
        }

        private AirSwimCell inspect(BlockPos position) {
            int x = position.getX(), y = position.getY(), z = position.getZ();
            if (!withinAirSwimBounds(position, origin) || !airSwimInWorld(y - 1)
                    || !airSwimInWorld(y) || !airSwimInWorld(y + 1) || !airSwimInWorld(y + 2)
                    || !loaded(x, z)) return null;
            feet.set(x, y, z); head.set(x, y + 1, z); support.set(x, y - 1, z); overhead.set(x, y + 2, z);
            BlockState feetState = client.world.getBlockState(feet);
            BlockState headState = client.world.getBlockState(head);
            BlockState supportState = client.world.getBlockState(support);
            if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                    || hazardousAirRecoveryState(supportState) || !clearAirRecoveryCell(feetState, feet)
                    || !clearAirRecoveryCell(headState, head)
                    || client.world.getBlockState(overhead).getBlock() instanceof FallingBlock
                    || supportState.getBlock() instanceof FallingBlock) return null;

            boolean feetWater = feetState.getFluidState().isIn(FluidTags.WATER);
            boolean feetDry = feetState.getFluidState().isEmpty();
            boolean headWater = headState.getFluidState().isIn(FluidTags.WATER);
            boolean headDry = headState.getFluidState().isEmpty();
            if ((!feetWater && !feetDry) || (!headWater && !headDry)) return null;

            boolean supportedDry = supportState.getFluidState().isEmpty()
                    && Block.isShapeFullCube(supportState.getCollisionShape(client.world, support));
            boolean aboveWater = supportState.getFluidState().isIn(FluidTags.WATER);
            boolean transition = feetDry && headDry && aboveWater;
            if (!feetWater && !supportedDry && !transition) return null;
            return new AirSwimCell(feetWater && headDry, feetDry && headDry && supportedDry);
        }

        private boolean loaded(BlockPos position) { return loaded(position.getX(), position.getZ()); }

        private boolean loaded(int x, int z) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            long key = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
            Boolean present = loadedChunks.get(key);
            if (present == null) {
                present = client.world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
                loadedChunks.put(key, present);
            }
            return present;
        }

        private List<BlockPos> reconstruct(BlockPos destination) {
            List<BlockPos> route = new ArrayList<>();
            BlockPos cursor = destination;
            while (!cursor.equals(origin)) {
                if (route.size() >= AIR_SWIM_MAX_PATH) return List.of();
                route.add(cursor.toImmutable());
                cursor = parents.get(cursor);
                if (cursor == null) return List.of();
            }
            Collections.reverse(route);
            return List.copyOf(route);
        }
    }

    private boolean withinAirSwimBounds(BlockPos position, BlockPos origin) {
        int dx = position.getX() - origin.getX(), dz = position.getZ() - origin.getZ();
        int dy = position.getY() - origin.getY();
        return Math.abs(dx) <= 12 && Math.abs(dz) <= 12 && dy >= -1 && dy <= 16;
    }

    private boolean airSwimChunkLoaded(int x, int z) {
        return client.world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    private boolean airSwimInWorld(int y) { return !client.world.isOutOfHeightLimit(y); }

    boolean debugLogging() { return config.debugLogging; }

    BlockPos retreatDestination() {
        if (mode != Mode.MOVE || bot == null || bot.getPathingBehavior().getCurrent() == null) return null;
        return bot.getPathingBehavior().getCurrent().getPath().getDest().toImmutable();
    }

    BlockPos airRecoveryDestination() {
        observeAirRecoveryDestination();
        return lastAirRecoveryDestination == null ? null : lastAirRecoveryDestination.toImmutable();
    }

    private void observeAirRecoveryDestination() {
        if (airRecoveryGoal == null || bot == null) return;
        var current = bot.getPathingBehavior().getCurrent();
        if (current == null) return;
        BlockPos destination = current.getPath().getDest();
        if (airRecoveryGoal.isInGoal(destination)) lastAirRecoveryDestination = destination.toImmutable();
    }

    void startInteraction(BlockPos target) {
        startMove(new GoalGetToBlock(target), dev.lodekeeper.nav.Goal.near16(target.getX(), target.getY() * 16, target.getZ(), 32));
    }

    private void startMove(baritone.api.pathing.goals.Goal goal, dev.lodekeeper.nav.Goal diagnostic) {
        prepare(); routeGoal = goal; diagnosticGoal = diagnostic; mode = Mode.MOVE;
        launch();
    }

    void startFollowing(AnimalEntity animal, Predicate<AnimalEntity> eligible) {
        Objects.requireNonNull(animal);
        Objects.requireNonNull(eligible);
        prepare();
        followTargetId = animal.getUuid();
        followOwnerWorld = client.world;
        followOwnerPlayer = client.player;
        followFilter = entity -> mode == Mode.FOLLOW && client.world == followOwnerWorld
                && client.player == followOwnerPlayer && entity instanceof AnimalEntity candidate
                && candidate.getUuid().equals(followTargetId) && eligible.test(candidate);
        mode = Mode.FOLLOW;
        launch();
    }

    void startPickup(ItemEntity item) {
        prepare(); output = item.getStack().getItem(); targetCount = actions.count(output) + 1;
        BlockPos position = item.getBlockPos();
        diagnosticGoal = dev.lodekeeper.nav.Goal.near16(position.getX(), position.getY() * 16, position.getZ(), 32);
        mode = Mode.PICKUP; launch();
    }

    void startMining(Block[] blocks, Item output, int totalCount, SelectedToolRequirement tool,
                     Set<BlockPos> priorRejectedPositions) {
        if (blocks.length == 0) throw new NavigationFailure("No supported mining blocks for " + output);
        if (priorRejectedPositions.size() > 512) throw new IllegalArgumentException("Mining rejection seed exceeds 512 positions");
        prepare(); mineBlocks = blocks.clone(); this.output = output; targetCount = totalCount; this.tool = tool;
        rejectedMiningTargets.addAll(priorRejectedPositions);
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
        airRecoveryGoal = null; lastAirRecoveryDestination = null; airRecoveryCancellationPending = false;
        observation = NavigationSnapshot.EMPTY; positionObserved = false; pendingBreakFailure = null;
        lastLoggedBreakPosition = miningTarget = null; lastLoggedBreakTool = null;
    }

    private void launch() {
        input.release();
        phaseStartedTick = requestTicks;
        lease = new SettingsLease();
        Settings settings = BaritoneAPI.getSettings();
        boolean airRecovery = mode == Mode.AIR;
        lease.set(settings.allowBreak, !airRecovery && config.allowBreaking);
        lease.set(settings.allowPlace, !airRecovery && config.allowBuilding);
        lease.set(settings.allowInventory, false);
        lease.set(settings.allowParkour, !airRecovery && config.allowParkour);
        lease.set(settings.allowParkourPlace, !airRecovery && config.allowParkour && config.allowBuilding);
        lease.set(settings.allowSprint, true);
        lease.set(settings.allowWaterBucketFall, false);
        if (airRecovery) lease.set(settings.assumeWalkOnWater, false);
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
        if (!airRecovery) actions.prepareScaffoldHotbar(scaffoldItems);
        switch (mode) {
            case MOVE, AIR -> bot.getCustomGoalProcess().setGoalAndPath(routeGoal);
            case PICKUP -> bot.getFollowProcess().pickup(stack -> stack.isOf(output));
            case FOLLOW -> {
                lease.set(settings.followRadius, 1);
                lease.set(settings.followOffsetDistance, 0.0);
                lease.set(settings.followTargetMaxDistance, 64);
                bot.getFollowProcess().follow(followFilter);
            }
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
        if (mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
                || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
        if (mode == Mode.IDLE && !cancelling) return true;
        observeAirRecoveryDestination();
        if (client.player == null || client.world == null) { stop(); return false; }
        if ((mode == Mode.FOLLOW || resumeMode == Mode.FOLLOW)
                && (client.world != followOwnerWorld || client.player != followOwnerPlayer)) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Follow owner player or world changed");
        }
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
            long elapsedNanos = System.nanoTime() - startedNanos;
            RequestLimitCause limitCause = dx * dx + dz * dz > limit * limit ? RequestLimitCause.DISTANCE
                    : requestTicks > maxTicks ? RequestLimitCause.TICKS
                    : elapsedNanos > maxTicks * 50_000_000L ? RequestLimitCause.WALL_TIME : null;
            if (limitCause != null) {
                if (mode == Mode.MINE && bot.getMineProcess().isActive()) rememberRejectedMiningTargets();
                MiningRequestLimit receipt = new MiningRequestLimit(limitCause, requestTicks, maxTicks,
                        elapsedNanos / 1_000_000L, Math.sqrt(dx * dx + dz * dz), limit, rejectedMiningTargets);
                stop(); throw new NavigationFailure(NavigationFailure.Kind.REQUEST_LIMIT,
                        "Mining request reached its " + limitCause + " limit", receipt);
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
        if (mode == Mode.SUSPENDED) { mode = resumeMode; resumeMode = Mode.IDLE; cancellationProcess = null; launch(); }
        if (mode == Mode.IDLE) return true;
        input.release();
        observeConfirmedProgress();
        if (requestTicks % 4 == 0) samplePath();
        if (mode != Mode.AIR) actions.prepareScaffoldHotbar(scaffoldItems);
        boolean satisfied = mode == Mode.MOVE || mode == Mode.AIR
                ? routeGoal.isInGoal(client.player.getBlockPos())
                : mode != Mode.FOLLOW && actions.count(output) >= targetCount;
        if (satisfied) { stop(); return finishCancellation(); }
        if (mode == Mode.DESCEND && new GoalYLevel(miningY).isInGoal(client.player.getBlockPos())) {
            changeMiningPhase(Mode.MINE);
            return false;
        }
        boolean active = switch (mode) {
            case MOVE, AIR, DESCEND -> bot.getCustomGoalProcess().isActive();
            case MINE -> bot.getMineProcess().isActive();
            case PICKUP, FOLLOW -> bot.getFollowProcess().isActive();
            default -> false;
        };
        if (mode == Mode.FOLLOW && failedCalculations >= 4) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Follow route exhausted four native path calculations");
        }
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
        cancellationProcess = bot.getMineProcess().isActive() ? bot.getMineProcess() : bot.getCustomGoalProcess();
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
        if (foreignFollowOwnsProcess()) pendingOwnershipFailure = releaseLostFollowOwnership();
        if (pendingOwnershipFailure != null) return true;
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
        if (mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
                || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
        if (mode == Mode.IDLE || mode == Mode.SUSPENDED) return;
        cancellationProcess = expectedProcessForMode(bot, mode);
        followCancellationPending = mode == Mode.FOLLOW;
        resumeMode = mode; mode = Mode.SUSPENDED;
        cancelling = true; bot.getPathingBehavior().cancelEverything(); input.release();
    }

    void stop() {
        boolean stoppingAirRecovery = mode == Mode.AIR || mode == Mode.SUSPENDED && resumeMode == Mode.AIR;
        if (stoppingAirRecovery || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
        if (bot != null && cancellationProcess == null) cancellationProcess = expectedProcessForMode(bot, mode);
        if (stoppingAirRecovery) airRecoveryCancellationPending = true;
        followCancellationPending |= mode == Mode.FOLLOW || resumeMode == Mode.FOLLOW;
        if (bot != null && followFilter != null && bot.getFollowProcess().currentFilter() == followFilter)
            bot.getFollowProcess().cancel();
        followFilter = null;
        followTargetId = null;
        followOwnerWorld = followOwnerPlayer = null;
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

    private void checkFollowOwnership() {
        if (pendingOwnershipFailure != null) {
            NavigationFailure failure = pendingOwnershipFailure;
            pendingOwnershipFailure = null;
            throw failure;
        }
        if (foreignFollowOwnsProcess()) throw releaseLostFollowOwnership();
    }

    void checkAirRecoveryOwnership() {
        checkFollowOwnership();
        IBaritone activeBot = bot == null ? BaritoneAPI.getProvider().getPrimaryBaritone() : bot;
        var custom = activeBot.getCustomGoalProcess();
        IBaritoneProcess expected = cancellationProcess != null
                ? cancellationProcess : expectedProcessForMode(activeBot, mode);
        if (airRecoveryCancellationPending) expected = custom;

        if ((mode == Mode.AIR && !matchesOwnedCustomGoal(custom, airRecoveryGoal))
                || (mode == Mode.SUSPENDED && resumeMode == Mode.AIR
                && !matchesOwnedCustomGoal(custom, airRecoveryGoal))
                || (mode == Mode.MOVE && routeGoal != null && !matchesOwnedCustomGoal(custom, routeGoal))
                || (cancellationProcess == custom && routeGoal != null && !matchesOwnedCustomGoal(custom, routeGoal))
                || (airRecoveryCancellationPending && !matchesOwnedCustomGoal(custom, airRecoveryGoal)))
            throw releaseLostAirRecoveryOwnership();
        if (hasForeignActiveProcess(activeBot, expected)) throw releaseLostAirRecoveryOwnership();
        var recent = activeBot.getPathingControlManager().mostRecentInControl();
        if (recent.isPresent() && recent.get() != expected && recent.get().isActive())
            throw releaseLostAirRecoveryOwnership();
    }

    private boolean matchesOwnedCustomGoal(ICustomGoalProcess custom, baritone.api.pathing.goals.Goal expectedGoal) {
        return expectedGoal != null && (custom.getGoal() == expectedGoal
                || custom.getGoal() == null && !custom.isActive() && custom.mostRecentGoal() == expectedGoal);
    }

    private IBaritoneProcess expectedProcessForMode(IBaritone activeBot, Mode expectedMode) {
        return switch (expectedMode) {
            case MOVE, AIR, DESCEND -> activeBot.getCustomGoalProcess();
            case MINE -> activeBot.getMineProcess();
            case PICKUP, FOLLOW -> activeBot.getFollowProcess();
            default -> null;
        };
    }

    private boolean hasForeignActiveProcess(IBaritone activeBot, IBaritoneProcess expected) {
        IBaritoneProcess[] processes = {
                activeBot.getCustomGoalProcess(), activeBot.getMineProcess(), activeBot.getFollowProcess(),
                activeBot.getBuilderProcess(), activeBot.getExploreProcess(), activeBot.getFarmProcess(),
                activeBot.getGetToBlockProcess(), activeBot.getElytraProcess()
        };
        for (IBaritoneProcess process : processes)
            if (process != expected && process.isActive()) return true;
        return false;
    }

    private NavigationFailure releaseLostAirRecoveryOwnership() {
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] AIR event=ownership-lost mode={} destination={}", mode, lastAirRecoveryDestination);
        followFilter = null;
        followTargetId = null;
        followOwnerWorld = followOwnerPlayer = null;
        mode = resumeMode = Mode.IDLE;
        cancelling = followCancellationPending = airRecoveryCancellationPending = false;
        cancellationProcess = null;
        input.release();
        observation = NavigationSnapshot.EMPTY;
        miningTarget = null;
        routeGoal = airRecoveryGoal = null;
        lastAirRecoveryDestination = null;
        diagnosticGoal = null;
        pendingBreakFailure = pendingOwnershipFailure = null;
        if (lease != null) { lease.restore(); lease = null; }
        return new NavigationFailure(NavigationFailure.Kind.OWNERSHIP_LOST,
                "Native pathing control changed owner during air recovery");
    }

    private boolean foreignFollowOwnsProcess() {
        if (bot == null || mode != Mode.FOLLOW && resumeMode != Mode.FOLLOW && !followCancellationPending)
            return false;
        Predicate<Entity> current = bot.getFollowProcess().currentFilter();
        return current != null && current != followFilter;
    }

    private NavigationFailure releaseLostFollowOwnership() {
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] FOOD_PURSUIT event=ownership-lost target={}", followTargetId);
        followFilter = null;
        followTargetId = null;
        followOwnerWorld = followOwnerPlayer = null;
        mode = resumeMode = Mode.IDLE;
        cancelling = followCancellationPending = false;
        input.release();
        observation = NavigationSnapshot.EMPTY;
        miningTarget = null;
        routeGoal = null;
        diagnosticGoal = null;
        if (lease != null) { lease.restore(); lease = null; }
        return new NavigationFailure(NavigationFailure.Kind.OWNERSHIP_LOST,
                "Native follow process changed owner");
    }

    boolean finishCancellation() {
        if (mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
                || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
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
        cancelling = followCancellationPending = false; observation = NavigationSnapshot.EMPTY;
        cancellationProcess = null;
        airRecoveryCancellationPending = false;
        airRecoveryGoal = null;
        if (mode == Mode.IDLE && resumeMode == Mode.IDLE && routeGoal instanceof GoalComposite) {
            terrain.beginSearch();
            routeGoal = null;
            diagnosticGoal = null;
        }
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

    List<BlockPos> knownMiningTargets() {
        if (mode != Mode.MINE || bot == null || !bot.getMineProcess().isActive()) return List.of();
        Set<BlockPos> known = new LinkedHashSet<>();
        int inspected = 0;
        for (BlockPos position : miningAccess().lodekeeper$knownMiningTargets()) {
            if (inspected++ >= 512) break;
            if (position != null && !rejectedMiningTargets.contains(position)) known.add(position.toImmutable());
        }
        return List.copyOf(known);
    }

    boolean canReconsiderMiningSource() {
        return mode == Mode.MINE && requestTicks - lastBreakTick > 20;
    }

    long progressToken() { return progressToken; }
    void recordConfirmedWorldAction() {
        progressToken++;
        if (mode == Mode.FOLLOW) failedCalculations = 0;
    }
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
        if (mode == Mode.FOLLOW) return "following the tracked animal";
        if (mode == Mode.AIR) return "recovering air";
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
