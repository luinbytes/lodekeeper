package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.fabric.modern.LodekeeperClient.PreparationStage;
import dev.lodekeeper.fabric.modern.LodekeeperClient.BreakHelperStage;
import dev.lodekeeper.navigation.kernel.api.utils.Rotation;
import dev.lodekeeper.navigation.kernel.pathing.movement.MovementState;
import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
import dev.lodekeeper.navigation.kernel.api.IBaritone;
import dev.lodekeeper.navigation.kernel.api.Settings;
import dev.lodekeeper.navigation.kernel.api.event.events.PathEvent;
import dev.lodekeeper.navigation.kernel.api.event.events.TickEvent;
import dev.lodekeeper.navigation.kernel.api.event.listener.AbstractGameEventListener;
import dev.lodekeeper.navigation.kernel.api.pathing.calc.IPath;
import dev.lodekeeper.navigation.kernel.api.pathing.calc.IPathFinder;
import dev.lodekeeper.navigation.kernel.api.pathing.path.IPathExecutor;
import dev.lodekeeper.navigation.kernel.api.pathing.movement.IMovement;
import dev.lodekeeper.navigation.kernel.api.pathing.movement.MovementStatus;
import dev.lodekeeper.navigation.kernel.api.utils.input.Input;
import dev.lodekeeper.navigation.kernel.pathing.movement.Movement;
import dev.lodekeeper.navigation.kernel.pathing.movement.movements.MovementParkour;
import dev.lodekeeper.navigation.kernel.pathing.movement.movements.MovementAscend;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalBlock;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalComposite;
import dev.lodekeeper.navigation.kernel.api.process.IBaritoneProcess;
import dev.lodekeeper.navigation.kernel.api.process.ICustomGoalProcess;
import dev.lodekeeper.navigation.kernel.api.utils.interfaces.IGoalRenderPos;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalGetToBlock;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalNear;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalRunAway;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalYLevel;
import dev.lodekeeper.navigation.kernel.api.utils.BlockOptionalMetaLookup;
import dev.lodekeeper.core.SelectedToolRequirement;
import dev.lodekeeper.core.MiningDepthPolicy;
import dev.lodekeeper.nav.ActionMovementProgress;
import dev.lodekeeper.nav.ExplorationFrontier;
import dev.lodekeeper.nav.NavigationSnapshot;
import dev.lodekeeper.nav.NavigationSceneSnapshot;
import dev.lodekeeper.nav.Path;
import dev.lodekeeper.nav.StanceProbe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.*;
import java.util.function.Predicate;

/** Owns one upstream process; inventory transactions wait for safe cancellation. */
final class MovementController {
    static final int MAX_RETREAT_HAZARDS = 32;
    private static final int AIR_RECOVERY_MAX_PROBES = 12_000;
    private static final long AIR_RECOVERY_SEARCH_BUDGET_NANOS = 4_000_000L;
    private static final int AIR_RECOVERY_MAX_GOALS = 16;
    private static final int AIR_SWIM_MAX_VISITED = 4_096;
    private static final int AIR_SWIM_MAX_PATH = 64;
    private static final int DEFENSE_HOP_MAX_TICKS = 30;
    private static final double DEFENSE_HOP_LAUNCH_TOLERANCE = 0.125;
    private static final double DEFENSE_HOP_MAX_HORIZONTAL_DISPLACEMENT = 1.25;
    private static final double DEFENSE_HOP_MAX_HORIZONTAL_SPEED = 0.02;
    private static final int[][] AIR_SWIM_STEPS = {
            {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}
    };

    private static final class PlacementGoal implements dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal, IGoalRenderPos {
        private final BlockPos destination;
        private final GoalComposite stances;

        PlacementGoal(BlockPos destination) {
            this.destination = destination.immutable();
            dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[] goals = new dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[12];
            int index = 0;
            for (int dy = -1; dy <= 1; dy++) {
                goals[index++] = new GoalBlock(this.destination.offset(1, dy, 0));
                goals[index++] = new GoalBlock(this.destination.offset(-1, dy, 0));
                goals[index++] = new GoalBlock(this.destination.offset(0, dy, 1));
                goals[index++] = new GoalBlock(this.destination.offset(0, dy, -1));
            }
            stances = new GoalComposite(goals);
        }

        private PlacementGoal(BlockPos destination, dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[] goals) {
            this.destination = destination;
            stances = new GoalComposite(goals);
        }

        PlacementGoal withoutStance(BlockPos rejected) {
            dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[] remaining = Arrays.stream(stances.goals())
                    .filter(goal -> !goal.isInGoal(rejected))
                    .toArray(dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[]::new);
            return remaining.length == 0 ? null : new PlacementGoal(destination, remaining);
        }

        @Override public boolean isInGoal(int x, int y, int z) { return stances.isInGoal(x, y, z); }
        @Override public double heuristic(int x, int y, int z) { return stances.heuristic(x, y, z); }
        @Override public double heuristic() { return stances.heuristic(); }
        @Override public BlockPos getGoalPos() { return destination; }
    }

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

    private enum RetreatDecision {
        PRIOR_GOAL, ORIGIN_DISTANCE, NO_FULL_CHUNK, UNSAFE_SUPPORT, FALLING_ABOVE,
        FEET_FULL, HEAD_FULL, PROBE_UNRESOLVED, NO_FULL_SUPPORT, HAZARD,
        WATER, CLIMBABLE, BODY_BLOCKED, BREAK_REQUIRED, ACCEPTED
    }

    private enum RetreatStop { OFFSETS_EXHAUSTED, CANDIDATE_LIMIT, PROBE_LIMIT, TIME_LIMIT, GOAL_LIMIT }

    private static final class RetreatSelectionDiagnostics {
        private final int[] request;
        private final int[] counts = new int[RetreatDecision.values().length];
        private final int[][] samples = new int[16][6];
        private final BlockState[][] states = new BlockState[16][3];
        private final RetreatDecision[] decisions = new RetreatDecision[16];
        private int visitedColumns, passedColumns, completedCandidates, sampleCount;
        private RetreatStop stop = RetreatStop.OFFSETS_EXHAUSTED;

        RetreatSelectionDiagnostics(BlockPos center, BlockPos origin, int radius, int totalColumns,
                                    int hazards, int maximumClearance) {
            request = new int[] {center.getX(), center.getY(), center.getZ(), origin.getX(), origin.getY(),
                    origin.getZ(), radius, totalColumns, hazards, maximumClearance};
        }

        void column(boolean passed) {
            visitedColumns++;
            if (passed) passedColumns++;
        }

        void complete(RetreatDecision decision, BlockPos candidate, BlockState overhead,
                      BlockState feet, BlockState head, StanceProbe probe) {
            counts[decision.ordinal()]++;
            completedCandidates++;
            if (sampleCount == samples.length) return;
            int index = sampleCount++;
            decisions[index] = decision;
            int[] sample = samples[index];
            sample[0] = candidate.getX();
            sample[1] = candidate.getY();
            sample[2] = candidate.getZ();
            if (probe != null) {
                sample[3] = (probe.loaded ? 1 : 0) | (probe.bodyClear ? 2 : 0)
                        | (probe.fullSupport ? 4 : 0) | (probe.surfaceSupport ? 8 : 0)
                        | (probe.hazard ? 16 : 0) | (probe.water ? 32 : 0) | (probe.climbable ? 64 : 0);
                sample[4] = probe.breakCount;
                sample[5] = 1;
            }
            states[index][0] = overhead;
            states[index][1] = feet;
            states[index][2] = head;
        }

        void emit(int candidates, int probes, int goals, long elapsedNanos) {
            try {
                StringBuilder text = new StringBuilder("RETREAT_SELECTOR phase=selection center=");
                text.append(request[0]).append(',').append(request[1]).append(',').append(request[2])
                        .append(" origin=").append(request[3]).append(',').append(request[4]).append(',').append(request[5])
                        .append(" radius=").append(request[6]).append(" heights=[0,1,-1,2,-2] hazards=").append(request[8])
                        .append(" maximumClearance=").append(request[9]).append(" totalColumns=").append(request[7])
                        .append(" visitedColumns=").append(visitedColumns).append(" clearancePassedColumns=").append(passedColumns)
                        .append(" clearanceRejectedColumns=").append(visitedColumns - passedColumns)
                        .append(" attemptedHeights=").append(candidates).append(" completedHeights=").append(completedCandidates)
                        .append(" probes=").append(probes).append(" goals=").append(goals).append(" stop=").append(stop)
                        .append(" outcome=").append(goals == 0 ? "EMPTY" : "GOALS_SELECTED")
                        .append(" pathLaunch=NOT_STARTED elapsedNanos=").append(elapsedNanos)
                        .append(" timing=INCLUDES_DIAGNOSTIC_OVERHEAD samplePolicy=FIRST_16_COMPLETED")
                        .append(" floorId=UNKNOWN supportSubreason=UNKNOWN firstRejections={");
                for (RetreatDecision decision : RetreatDecision.values()) {
                    if (decision == RetreatDecision.ACCEPTED) continue;
                    text.append(decision).append('=').append(counts[decision.ordinal()]).append(',');
                }
                text.append("} probeBits=[loaded,bodyClear,fullSupport,surfaceSupport,hazard,water,climbable] samples=[");
                for (int i = 0; i < sampleCount; i++) {
                    int[] sample = samples[i];
                    text.append("{pos=").append(sample[0]).append(',').append(sample[1]).append(',').append(sample[2])
                            .append(" decision=").append(decisions[i]).append(" overheadId=").append(blockId(states[i][0]))
                            .append(" feetId=").append(blockId(states[i][1])).append(" headId=").append(blockId(states[i][2]));
                    if (sample[5] == 0) text.append(" probe=NOT_OBSERVED");
                    else text.append(" probe=").append((sample[3] & 1) == 0 ? "UNRESOLVED_OUTPUT" : "RESOLVED_OUTPUT")
                            .append(" rawProbeBits=").append(sample[3]).append(" rawBreakCount=").append(sample[4]);
                    text.append("},");
                }
                org.slf4j.LoggerFactory.getLogger("lodekeeper").info(text.append(']').toString());
            } catch (Throwable ignored) {
                // Diagnostic failure must not prevent the original throw or path launch.
            }
        }

        private static String blockId(BlockState state) {
            if (state == null) return "UNKNOWN";
            String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return id.length() <= 128 ? id : id.substring(0, 128);
        }
    }

    record RetreatThreat(double x, double y, double z, int clearance, double dangerRadius,
                         UUID uuid, EntityType<?> type) { }
    enum RetreatPrefixStatus { WAITING, CLEAR, BLOCKED }
    private enum RetreatPrefixCheckSite { INITIAL, LIVE }
    record RetreatPrefixRejection(BlockPos destination) { }
    record DefenseHop(double x, double y, double z, double maxRise) { }

    private static final class DefenseHopState {
        final DefenseHop plan;
        final Object world, player, camera;
        final int feetY16;
        int ticks = 1;
        boolean airborneObserved;

        DefenseHopState(DefenseHop plan, Object world, Object player, Object camera, int feetY16) {
            this.plan = plan;
            this.world = world;
            this.player = player;
            this.camera = camera;
            this.feetY16 = feetY16;
        }
    }

    private enum Mode { IDLE, MOVE, AIR, MINE, DESCEND, PICKUP, FOLLOW, SUSPENDED }
    private record OwnedPickupTarget(ItemEntity entity, UUID entityId, Item item, int startingCount,
                                     Object ownerWorld, Object ownerPlayer, Object ownerSession) { }
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final BotInput input;
    private final GameTerrain terrain;
    private IBaritone bot;
    private IBaritoneProcess cancellationProcess;
    private Mode mode = Mode.IDLE, resumeMode = Mode.IDLE;
    private boolean cancelling, followCancellationPending, retreatRequest, defenseSettlingPending;
    private List<RetreatThreat> retreatHazards = List.of();
    private RetreatPrefixRejection retreatPrefixRejection;
    private RuntimeException pendingRetreatPrefixFailure;
    private dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal routeGoal;
    private dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal airRecoveryGoal;
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
    private OwnedPickupTarget pickupTarget;
    private Boolean ownedPickupProcessActive;
    private int ownedPickupProcessActiveTick;
    private long progressToken, startedNanos, lastDefenseCancellationLog;
    private double requestX, requestZ;
    private ActionMovementProgress movementProgress;
    private boolean movementProgressAnchored, gatherDiagnosticsEligible;
    private final WaterBreakObserver waterBreak = new WaterBreakObserver();
    private int requestTicks, failedCalculations, lastBreakTick, phaseStartedTick;
    private int shallowPreparationSamples;
    private boolean motionLogAnchorInitialized;
    private double motionLogAnchorX, motionLogAnchorZ;
    private int motionLogAnchorRequestTick, motionLogLastRequestTick, motionLogCount;
    private int miningY = Integer.MIN_VALUE, lastDiscoveryMergeTick = -100, lastScanLogTick = -20;
    private MiningDepthPolicy miningDepthPolicy;
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
    private DefenseHopState defenseHop;

    enum RequestLimitCause { DISTANCE, TICKS, WALL_TIME }
    record MiningRequestLimit(RequestLimitCause cause, int requestTicks, int maximumTicks,
                              long elapsedMillis, double distance, double maximumDistance,
                              Set<BlockPos> rejectedPositions) {
        MiningRequestLimit { rejectedPositions = Set.copyOf(rejectedPositions); }
    }

    static final class NavigationFailure extends IllegalStateException {
        enum Kind { TOOL, PROCESS_ENDED, OWNERSHIP_LOST, REQUEST_LIMIT, PROTECTED_BLOCK, NO_RETREAT_STANCE, OTHER }
        final Kind kind;
        final MiningRequestLimit requestLimit;
        NavigationFailure(String reason) { this(Kind.OTHER, reason); }
        NavigationFailure(Kind kind, String reason) { this(kind, reason, null); }
        NavigationFailure(Kind kind, String reason, MiningRequestLimit requestLimit) {
            super(reason); this.kind = kind; this.requestLimit = requestLimit;
        }
    }

    MovementController(Minecraft client, LodekeeperConfig config, PlayerActions actions,
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

    List<BlockPos> startRetreat(List<RetreatThreat> threats, Set<BlockPos> rejectedGoals, BlockPos origin) {
        if (threats.isEmpty() || threats.size() > MAX_RETREAT_HAZARDS
                || rejectedGoals.size() > 32 || origin == null
                || threats.stream().anyMatch(threat -> threat == null
                        || !Double.isFinite(threat.x()) || !Double.isFinite(threat.y()) || !Double.isFinite(threat.z())
                        || threat.clearance() < 4 || threat.clearance() > 32
                        || !Double.isFinite(threat.dangerRadius())
                        || threat.dangerRadius() != 3.5 && threat.dangerRadius() != 6.0))
            throw new IllegalArgumentException("Retreat requires bounded threat positions and clearance");
        prepare();
        retreatHazards = List.copyOf(threats);
        retreatPrefixRejection = null;
        terrain.beginSearch();
        BlockPos center = client.player.blockPosition();
        List<BlockPos> goals = new ArrayList<>();
        List<BlockPos> offsets = new ArrayList<>();
        int maximumClearance = threats.stream().mapToInt(RetreatThreat::clearance).max().orElseThrow();
        int radius = Math.max(12, maximumClearance);
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            int squared = dx * dx + dz * dz;
            if (squared >= 4 * 4 && squared <= radius * radius) offsets.add(new BlockPos(dx, 0, dz));
        }
        offsets.sort(Comparator.<BlockPos>comparingDouble(offset ->
                        retreatClearanceMargin(center.getX() + offset.getX(), center.getZ() + offset.getZ(), threats))
                .reversed().thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        StanceProbe stance = new StanceProbe();
        RetreatSelectionDiagnostics trace = config.debugLogging
                ? new RetreatSelectionDiagnostics(center, origin, radius, offsets.size(), threats.size(), maximumClearance) : null;
        long searchStarted = System.nanoTime();
        int candidates = 0, probes = 0;
        int[] heights = {0, 1, -1, 2, -2};
        search: for (BlockPos offset : offsets) {
            int x = center.getX() + offset.getX(), z = center.getZ() + offset.getZ();
            if (retreatClearanceMargin(x, z, threats) < 0.0) {
                if (trace != null) trace.column(false);
                continue;
            }
            if (trace != null) trace.column(true);
            for (int dy : heights) {
                if (++candidates > 4_096) {
                    if (trace != null) trace.stop = RetreatStop.CANDIDATE_LIMIT;
                    break search;
                }
                if (probes >= 192) {
                    if (trace != null) trace.stop = RetreatStop.PROBE_LIMIT;
                    break search;
                }
                if (System.nanoTime() - searchStarted >= 8_000_000L) {
                    if (trace != null) trace.stop = RetreatStop.TIME_LIMIT;
                    break search;
                }
                BlockPos candidate = new BlockPos(x, center.getY() + dy, z);
                if (rejectedGoals.contains(candidate)) {
                    if (trace != null) trace.complete(RetreatDecision.PRIOR_GOAL, candidate, null, null, null, null);
                    continue;
                }
                double ox = x + .5 - origin.getX(), oy = candidate.getY() - origin.getY(), oz = z + .5 - origin.getZ();
                if (ox * ox + oy * oy + oz * oz > 30.0 * 30.0) {
                    if (trace != null) trace.complete(RetreatDecision.ORIGIN_DISTANCE, candidate, null, null, null, null);
                    continue;
                }
                if (client.level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
                    if (trace != null) trace.complete(RetreatDecision.NO_FULL_CHUNK, candidate, null, null, null, null);
                    continue;
                }
                if (!actions.safePlacementSupport(candidate.below())) {
                    if (trace != null) trace.complete(RetreatDecision.UNSAFE_SUPPORT, candidate, null, null, null, null);
                    continue;
                }
                BlockState overheadState = client.level.getBlockState(candidate.above(2));
                if (overheadState.getBlock() instanceof FallingBlock) {
                    if (trace != null) trace.complete(RetreatDecision.FALLING_ABOVE, candidate, overheadState, null, null, null);
                    continue;
                }
                BlockState feetState = client.level.getBlockState(candidate);
                var feetShape = feetState.getCollisionShape(client.level, candidate);
                BlockState headState = client.level.getBlockState(candidate.above());
                var headShape = headState.getCollisionShape(client.level, candidate.above());
                if (Block.isShapeFullBlock(feetShape)) {
                    if (trace != null) trace.complete(RetreatDecision.FEET_FULL, candidate, overheadState, feetState, headState, null);
                    continue;
                }
                if (Block.isShapeFullBlock(headShape)) {
                    if (trace != null) trace.complete(RetreatDecision.HEAD_FULL, candidate, overheadState, feetState, headState, null);
                    continue;
                }
                probes++;
                terrain.probeStance16(x, Math.multiplyExact(candidate.getY(), 16), z, stance);
                if (!stance.loaded) {
                    if (trace != null) trace.complete(RetreatDecision.PROBE_UNRESOLVED, candidate, overheadState, feetState, headState, stance);
                    continue;
                }
                if (!stance.fullSupport) {
                    if (trace != null) trace.complete(RetreatDecision.NO_FULL_SUPPORT, candidate, overheadState, feetState, headState, stance);
                    continue;
                }
                if (stance.hazard) {
                    if (trace != null) trace.complete(RetreatDecision.HAZARD, candidate, overheadState, feetState, headState, stance);
                    continue;
                }
                if (stance.water) {
                    if (trace != null) trace.complete(RetreatDecision.WATER, candidate, overheadState, feetState, headState, stance);
                    continue;
                }
                if (stance.climbable) {
                    if (trace != null) trace.complete(RetreatDecision.CLIMBABLE, candidate, overheadState, feetState, headState, stance);
                    continue;
                }
                if (!stance.bodyClear) {
                    if (trace != null) trace.complete(RetreatDecision.BODY_BLOCKED, candidate, overheadState, feetState, headState, stance);
                    continue;
                }
                if (stance.breakCount != 0) {
                    if (trace != null) trace.complete(RetreatDecision.BREAK_REQUIRED, candidate, overheadState, feetState, headState, stance);
                    continue;
                }
                goals.add(candidate);
                if (trace != null) trace.complete(RetreatDecision.ACCEPTED, candidate, overheadState, feetState, headState, stance);
                if (goals.size() == 16) {
                    if (trace != null) trace.stop = RetreatStop.GOAL_LIMIT;
                    break search;
                }
            }
        }
        if (trace != null) trace.emit(candidates, probes, goals.size(), System.nanoTime() - searchStarted);
        if (goals.isEmpty()) throw new NavigationFailure(NavigationFailure.Kind.NO_RETREAT_STANCE,
                "No safe dry retreat stance remains within the bounded search");
        routeGoal = new GoalComposite(goals.stream().map(GoalBlock::new).toArray(dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[]::new));
        diagnosticGoal = null;
        mode = Mode.MOVE;
        retreatRequest = true;
        RetreatSnapshotDiagnostics.register(this, client, config, bot, routeGoal, goals);
        launch();
        return List.copyOf(goals);
    }

    private static double retreatClearanceMargin(int x, int z, List<RetreatThreat> threats) {
        double minimum = Double.POSITIVE_INFINITY;
        for (RetreatThreat threat : threats) {
            double dx = x + .5 - threat.x(), dz = z + .5 - threat.z();
            minimum = Math.min(minimum, dx * dx + dz * dz - threat.clearance() * threat.clearance());
        }
        return minimum;
    }

    enum AirExitPreference { DRY, SURFACE }

    List<BlockPos> startAirRecovery(Set<BlockPos> rejectedGoals, AirExitPreference preference) {
        Objects.requireNonNull(rejectedGoals);
        Objects.requireNonNull(preference);
        if (rejectedGoals.size() > 32) throw new IllegalArgumentException("Air recovery rejection seed exceeds 32 positions");
        if (client.player == null || client.level == null) throw new NavigationFailure("World unavailable");
        checkAirRecoveryOwnership();
        List<BlockPos> goals = new AirRecoverySearch(Set.copyOf(rejectedGoals), preference).findGoals();
        if (goals.isEmpty()) throw new NavigationFailure("No breathable air recovery stance remains within the bounded search");

        checkAirRecoveryOwnership();
        prepare();
        airRecoveryGoal = new GoalComposite(goals.stream().map(GoalBlock::new)
                .toArray(dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[]::new));
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
        private final BlockPos center = client.player.blockPosition();
        private final long startedNanos = System.nanoTime();
        private final Map<Long, Boolean> loadedChunks = new HashMap<>();
        private final List<AirRecoveryCandidate> dryCandidates = new ArrayList<>(AIR_RECOVERY_MAX_GOALS);
        private final List<AirRecoveryCandidate> floatingCandidates = new ArrayList<>(AIR_RECOVERY_MAX_GOALS);
        private final BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos overhead = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
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
            BlockState feetState = client.level.getBlockState(feet);
            BlockState headState = client.level.getBlockState(head);
            if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                    || !clearAirRecoveryCell(feetState, feet) || !clearAirRecoveryCell(headState, head)
                    || !headState.getFluidState().isEmpty()
                    || !(feetState.getFluidState().isEmpty() || feetState.getFluidState().is(FluidTags.WATER))) return;
            if (client.level.getBlockState(overhead).getBlock() instanceof FallingBlock) return;

            boolean waterFeet = feetState.getFluidState().is(FluidTags.WATER);
            if (!inWorld(y - 1)) return;
            support.set(x, y - 1, z);
            BlockState supportState = client.level.getBlockState(support);
            boolean supported = supportState.getFluidState().isEmpty() && !hazardousAirRecoveryState(supportState)
                    && Block.isShapeFullBlock(supportState.getCollisionShape(client.level, support));
            if (!waterFeet && !supported) return;
            int distanceSquared = dx * dx + dy * dy + dz * dz;
            int score = distanceSquared + (supported ? 0 : 3) + (waterFeet ? 2 : 0);
            AirRecoveryCandidate candidate = new AirRecoveryCandidate(feet.immutable(), score,
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
                present = client.level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
                loadedChunks.put(key, present);
            }
            return present;
        }

        private boolean inWorld(int y) { return y >= client.level.getMinY() && y < client.level.getMaxY(); }
    }

    private boolean clearAirRecoveryCell(BlockState state, BlockPos position) {
        return state.getCollisionShape(client.level, position).isEmpty();
    }

    private static boolean hazardousAirRecoveryState(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
    }

    List<BlockPos> airSwimRoute() {
        checkAirRecoveryOwnership();
        if (client.player == null || client.level == null) throw new NavigationFailure("World unavailable");
        IBaritone activeBot = bot == null ? OwnedKernelAPI.getProvider().getPrimaryBaritone() : bot;
        var pathing = activeBot.getPathingBehavior();
        if (mode != Mode.IDLE || resumeMode != Mode.IDLE || cancelling || cancellationProcess != null
                || airRecoveryCancellationPending || lease != null || pathing.hasPath() || pathing.isPathing()
                || pathing.getInProgress().isPresent())
            throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Native movement cancellation must finish before air-swim routing");

        BlockPos start = client.player.blockPosition();
        AirSwimSearch search = new AirSwimSearch(start);
        List<BlockPos> route = search.findRoute();
        airSwimOrigin = route.isEmpty() ? null : start.immutable();
        return route;
    }

    /** Revalidates a planned swim step against loaded native collision and fluid state only. */
    boolean airSwimStepClear(BlockPos feetPosition) {
        if (feetPosition == null || airSwimOrigin == null || client.player == null || client.level == null
                || !withinAirSwimBounds(feetPosition, airSwimOrigin)) return false;
        int x = feetPosition.getX(), y = feetPosition.getY(), z = feetPosition.getZ();
        if (!airSwimChunkLoaded(x, z) || !airSwimInWorld(y - 1)
                || !airSwimInWorld(y) || !airSwimInWorld(y + 1) || !airSwimInWorld(y + 2)) return false;

        BlockPos headPosition = new BlockPos(x, y + 1, z);
        BlockPos supportPosition = new BlockPos(x, y - 1, z);
        BlockPos overheadPosition = new BlockPos(x, y + 2, z);
        BlockState feetState = client.level.getBlockState(feetPosition);
        BlockState headState = client.level.getBlockState(headPosition);
        BlockState supportState = client.level.getBlockState(supportPosition);
        if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                || hazardousAirRecoveryState(supportState)
                || !clearAirRecoveryCell(feetState, feetPosition)
                || !clearAirRecoveryCell(headState, headPosition)
                || client.level.getBlockState(overheadPosition).getBlock() instanceof FallingBlock
                || supportState.getBlock() instanceof FallingBlock) return false;

        boolean feetWater = feetState.getFluidState().is(FluidTags.WATER);
        boolean feetDry = feetState.getFluidState().isEmpty();
        boolean headWater = headState.getFluidState().is(FluidTags.WATER);
        boolean headDry = headState.getFluidState().isEmpty();
        if ((!feetWater && !feetDry) || (!headWater && !headDry)) return false;
        boolean supportedDry = supportState.getFluidState().isEmpty()
                && Block.isShapeFullBlock(supportState.getCollisionShape(client.level, supportPosition));
        boolean aboveWater = supportState.getFluidState().is(FluidTags.WATER);
        return feetWater || supportedDry || feetDry && headDry && aboveWater;
    }

    private final class AirSwimSearch {
        private final BlockPos origin;
        private final long startedNanos = System.nanoTime();
        private final Map<Long, Boolean> loadedChunks = new HashMap<>();
        private final Map<BlockPos, BlockPos> parents = new HashMap<>();
        private final ArrayDeque<AirSwimNode> frontier = new ArrayDeque<>();
        private final BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos overhead = new BlockPos.MutableBlockPos();
        private int probes;
        private boolean exhausted;
        private BlockPos surfaceFallback;

        private AirSwimSearch(BlockPos origin) { this.origin = origin.immutable(); }

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
            BlockState feetState = client.level.getBlockState(feet);
            BlockState headState = client.level.getBlockState(head);
            BlockState supportState = client.level.getBlockState(support);
            if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                    || hazardousAirRecoveryState(supportState) || !clearAirRecoveryCell(feetState, feet)
                    || !clearAirRecoveryCell(headState, head)
                    || client.level.getBlockState(overhead).getBlock() instanceof FallingBlock
                    || supportState.getBlock() instanceof FallingBlock) return null;

            boolean feetWater = feetState.getFluidState().is(FluidTags.WATER);
            boolean feetDry = feetState.getFluidState().isEmpty();
            boolean headWater = headState.getFluidState().is(FluidTags.WATER);
            boolean headDry = headState.getFluidState().isEmpty();
            if ((!feetWater && !feetDry) || (!headWater && !headDry)) return null;

            boolean supportedDry = supportState.getFluidState().isEmpty()
                    && Block.isShapeFullBlock(supportState.getCollisionShape(client.level, support));
            boolean aboveWater = supportState.getFluidState().is(FluidTags.WATER);
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
                present = client.level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
                loadedChunks.put(key, present);
            }
            return present;
        }

        private List<BlockPos> reconstruct(BlockPos destination) {
            List<BlockPos> route = new ArrayList<>();
            BlockPos cursor = destination;
            while (!cursor.equals(origin)) {
                if (route.size() >= AIR_SWIM_MAX_PATH) return List.of();
                route.add(cursor.immutable());
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
        return client.level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) != null;
    }

    private boolean airSwimInWorld(int y) { return y >= client.level.getMinY() && y < client.level.getMaxY(); }

    boolean debugLogging() { return config.debugLogging; }

    BlockPos retreatDestination() {
        if (mode != Mode.MOVE || bot == null || bot.getPathingBehavior().getCurrent() == null) return null;
        return bot.getPathingBehavior().getCurrent().getPath().getDest().immutable();
    }

    RetreatPrefixStatus checkRetreatPrefix(List<RetreatThreat> threats) {
        return checkRetreatPrefix(threats, RetreatPrefixCheckSite.LIVE);
    }

    private RetreatPrefixStatus checkRetreatPrefix(List<RetreatThreat> threats, RetreatPrefixCheckSite site) {
        if (!retreatRequest || mode != Mode.MOVE || bot == null) return RetreatPrefixStatus.WAITING;
        checkAirRecoveryOwnership();
        var current = bot.getPathingBehavior().getCurrent();
        if (current == null || current.getPath() == null) return RetreatPrefixStatus.WAITING;
        var path = current.getPath();
        var positions = path.positions();
        if (positions.isEmpty()) return RetreatPrefixStatus.WAITING;
        int index = current.getPosition();
        if (index < 0 || index >= positions.size()) {
            if (config.debugLogging) logRetreatPrefixRejection(site, "INVALID_INDEX", current, path, index, positions.size(),
                    "player=" + client.player.getX() + "," + client.player.getY() + "," + client.player.getZ());
            return RetreatPrefixStatus.BLOCKED;
        }

        double startX = client.player.getX(), startY = client.player.getY(), startZ = client.player.getZ();
        double fromX = startX, fromY = startY, fromZ = startZ;
        int checked = 0;
        for (int i = index + 1; i < positions.size() && checked < 4; i++, checked++) {
            var position = positions.get(i);
            double toX = position.getX() + .5, toY = position.getY(), toZ = position.getZ() + .5;
            for (RetreatThreat threat : threats) {
                double currentDistance = distance(startX, startY, startZ, threat.x(), threat.y(), threat.z());
                double segmentDistance = distanceToSegment(fromX, fromY, fromZ, toX, toY, toZ,
                        threat.x(), threat.y(), threat.z());
                if (currentDistance < threat.dangerRadius()) {
                    if (segmentDistance < currentDistance - .5) {
                        if (config.debugLogging) logRetreatPrefixRejection(site, "APPROACH_WHILE_INSIDE", current, path, index, positions.size(),
                                "player=" + startX + "," + startY + "," + startZ
                                        + " from=" + fromX + "," + fromY + "," + fromZ + " to=" + toX + "," + toY + "," + toZ
                                        + " nodeIndex=" + i + " segmentOrdinal=" + (checked + 1)
                                        + " hazardUuid=" + threat.uuid() + " hazardType=" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(threat.type())
                                        + " hazard=" + threat.x() + "," + threat.y() + "," + threat.z()
                                        + " dangerRadius=" + threat.dangerRadius() + " currentDistance=" + currentDistance
                                        + " segmentDistance=" + segmentDistance + " hazardCount=" + threats.size());
                        return RetreatPrefixStatus.BLOCKED;
                    }
                } else if (segmentDistance < threat.dangerRadius()) {
                    if (config.debugLogging) logRetreatPrefixRejection(site, "ENTER_DANGER_RADIUS", current, path, index, positions.size(),
                            "player=" + startX + "," + startY + "," + startZ
                                    + " from=" + fromX + "," + fromY + "," + fromZ + " to=" + toX + "," + toY + "," + toZ
                                    + " nodeIndex=" + i + " segmentOrdinal=" + (checked + 1)
                                    + " hazardUuid=" + threat.uuid() + " hazardType=" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(threat.type())
                                    + " hazard=" + threat.x() + "," + threat.y() + "," + threat.z()
                                    + " dangerRadius=" + threat.dangerRadius() + " currentDistance=" + currentDistance
                                    + " segmentDistance=" + segmentDistance + " hazardCount=" + threats.size());
                    return RetreatPrefixStatus.BLOCKED;
                }
            }
            fromX = toX;
            fromY = toY;
            fromZ = toZ;
        }
        return RetreatPrefixStatus.CLEAR;
    }

    private void logRetreatPrefixRejection(RetreatPrefixCheckSite site, String reason, IPathExecutor current,
                                          IPath path, int index, int positionCount, String detail) {
        var destination = path.getDest();
        StringBuilder goals = new StringBuilder("[");
        if (routeGoal instanceof GoalComposite composite) {
            var requestedGoals = composite.goals();
            for (int i = 0; i < requestedGoals.length && i < 16; i++) {
                if (requestedGoals[i] instanceof GoalBlock goal) {
                    if (goals.length() > 1) goals.append(';');
                    goals.append(goal.x).append(',').append(goal.y).append(',').append(goal.z);
                }
            }
        }
        goals.append(']');
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] RETREAT_PREFIX_REJECT site={} reason={} executorIdentity={} pathIdentity={} pathIndex={} positionCount={} destination={},{},{} mode={} retreatRequest={} cancelling={} cancellationProcessIdentity={} leaseIdentity={} botIdentity={} processIdentity={} playerIdentity={} worldIdentity={} routeGoalIdentity={} requestTicks={} requestStartedNanos={} progressToken={} ownershipCheck=PASSED goals={} {}",
                site, reason, System.identityHashCode(current), System.identityHashCode(path), index, positionCount,
                destination.getX(), destination.getY(), destination.getZ(), mode, retreatRequest, cancelling,
                System.identityHashCode(cancellationProcess), System.identityHashCode(lease), System.identityHashCode(bot),
                System.identityHashCode(bot.getCustomGoalProcess()), System.identityHashCode(client.player),
                System.identityHashCode(client.level), System.identityHashCode(routeGoal), requestTicks, startedNanos, progressToken, goals, detail);
    }

    void resetRetreatPrefix() {
        retreatHazards = List.of();
        retreatPrefixRejection = null;
        pendingRetreatPrefixFailure = null;
    }

    void updateRetreatHazards(List<RetreatThreat> threats) {
        if (threats == null || threats.size() > MAX_RETREAT_HAZARDS || threats.stream().anyMatch(threat -> threat == null
                || !Double.isFinite(threat.x()) || !Double.isFinite(threat.y()) || !Double.isFinite(threat.z())
                || threat.clearance() < 4 || threat.clearance() > 32
                || !Double.isFinite(threat.dangerRadius())
                || threat.dangerRadius() != 3.5 && threat.dangerRadius() != 6.0))
            throw new IllegalArgumentException("Retreat hazard snapshot exceeds its bounded finite shape");
        retreatHazards = List.copyOf(threats);
    }

    RetreatPrefixRejection takeRetreatPrefixRejection() {
        RetreatPrefixRejection rejection = retreatPrefixRejection;
        retreatPrefixRejection = null;
        return rejection;
    }

    RuntimeException takeRetreatPrefixFailure() {
        RuntimeException failure = pendingRetreatPrefixFailure;
        pendingRetreatPrefixFailure = null;
        return failure;
    }

    private void checkInitialRetreatPath() {
        if (!retreatRequest) return;
        try {
            if (checkRetreatPrefix(retreatHazards, RetreatPrefixCheckSite.INITIAL) != RetreatPrefixStatus.BLOCKED) return;
            BlockPos destination = retreatDestination();
            if (destination == null || routeGoal == null || !routeGoal.isInGoal(destination)) destination = null;
            stopForDefense();
            bot.getPathingBehavior().forceCancel();
            retreatPrefixRejection = new RetreatPrefixRejection(destination);
        } catch (RuntimeException failure) {
            pendingRetreatPrefixFailure = failure;
            if (failure instanceof NavigationFailure navigationFailure
                    && navigationFailure.kind == NavigationFailure.Kind.OWNERSHIP_LOST) return;
            try {
                stopForDefense();
                bot.getPathingBehavior().forceCancel();
            } catch (RuntimeException cancellationFailure) {
                failure.addSuppressed(cancellationFailure);
            }
        }
    }

    private static double distance(double x, double y, double z, double otherX, double otherY, double otherZ) {
        double dx = x - otherX, dy = y - otherY, dz = z - otherZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distanceToSegment(double fromX, double fromY, double fromZ,
                                            double toX, double toY, double toZ,
                                            double pointX, double pointY, double pointZ) {
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        double t = lengthSquared == 0.0 ? 0.0
                : Math.max(0.0, Math.min(1.0,
                ((pointX - fromX) * dx + (pointY - fromY) * dy + (pointZ - fromZ) * dz) / lengthSquared));
        return distance(fromX + t * dx, fromY + t * dy, fromZ + t * dz, pointX, pointY, pointZ);
    }

    BlockPos airRecoveryDestination() {
        observeAirRecoveryDestination();
        return lastAirRecoveryDestination == null ? null : lastAirRecoveryDestination.immutable();
    }

    private void observeAirRecoveryDestination() {
        if (airRecoveryGoal == null || bot == null) return;
        var current = bot.getPathingBehavior().getCurrent();
        if (current == null) return;
        BlockPos destination = current.getPath().getDest();
        if (airRecoveryGoal.isInGoal(destination)) lastAirRecoveryDestination = destination.immutable();
    }

    void startInteraction(BlockPos target) {
        startMove(new GoalGetToBlock(target), dev.lodekeeper.nav.Goal.near16(target.getX(), target.getY() * 16, target.getZ(), 32));
    }

    void startPlacement(BlockPos target) {
        startMove(new PlacementGoal(target), dev.lodekeeper.nav.Goal.near16(target.getX(), target.getY() * 16, target.getZ(), 32));
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] STATION_ROUTE event=start destination={} stances=12", target);
    }

    private void startMove(dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal goal, dev.lodekeeper.nav.Goal diagnostic) {
        prepare(); routeGoal = goal; diagnosticGoal = diagnostic; mode = Mode.MOVE;
        launch();
    }

    void startFollowing(Animal animal, Predicate<Animal> eligible) {
        Objects.requireNonNull(animal);
        Objects.requireNonNull(eligible);
        prepare();
        followTargetId = animal.getUUID();
        followOwnerWorld = client.level;
        followOwnerPlayer = client.player;
        followFilter = entity -> mode == Mode.FOLLOW && client.level == followOwnerWorld
                && client.player == followOwnerPlayer && entity instanceof Animal candidate
                && candidate.getUUID().equals(followTargetId) && eligible.test(candidate);
        mode = Mode.FOLLOW;
        launch();
    }

    void startPickup(ItemEntity item) {
        prepare(); output = item.getItem().getItem(); targetCount = actions.count(output) + 1;
        BlockPos position = item.blockPosition();
        diagnosticGoal = dev.lodekeeper.nav.Goal.near16(position.getX(), position.getY() * 16, position.getZ(), 32);
        mode = Mode.PICKUP; launch();
    }

    void startOwnedPickup(ItemEntity item, Object ownerSession) {
        if (item == null || !item.isAlive() || ownerSession == null)
            throw new NavigationFailure("Owned pickup requires a live item and recovery session");
        prepare();
        Item itemType = item.getItem().getItem();
        pickupTarget = new OwnedPickupTarget(item, item.getUUID(), itemType, item.getItem().getCount(),
                client.level, client.player, ownerSession);
        output = itemType;
        targetCount = 0;
        followTargetId = pickupTarget.entityId();
        followOwnerWorld = pickupTarget.ownerWorld();
        followOwnerPlayer = pickupTarget.ownerPlayer();
        ownedPickupProcessActive = null;
        ownedPickupProcessActiveTick = 0;
        OwnedPickupTarget target = pickupTarget;
        followFilter = entity -> mode == Mode.PICKUP && pickupTarget == target
                && target.ownerSession() == ownerSession
                && client.level == target.ownerWorld() && client.player == target.ownerPlayer()
                && entity == target.entity() && entity instanceof ItemEntity candidate
                && candidate.getUUID().equals(target.entityId()) && candidate.isAlive()
                && candidate.getItem().getItem() == target.item();
        BlockPos position = item.blockPosition();
        diagnosticGoal = dev.lodekeeper.nav.Goal.near16(position.getX(), position.getY() * 16, position.getZ(), 32);
        mode = Mode.PICKUP;
        launch();
    }

    String ownedPickupDiagnostic(ItemEntity entity, Object ownerSession) {
        OwnedPickupTarget target = pickupTarget;
        if (!config.debugLogging || target == null || target.entity() != entity
                || target.ownerSession() != ownerSession || client.player == null
                || client.player != target.ownerPlayer() || client.level != target.ownerWorld()
                || bot == null || bot.getPlayerContext().player() != client.player
                || bot.getPlayerContext().world() != client.level) {
            return "nativeContextMatches=false";
        }
        var pathing = bot.getPathingBehavior();
        var goal = pathing.getGoal();
        var executor = pathing.getCurrent();
        var path = executor == null ? null : executor.getPath();
        var feet = bot.getPlayerContext().playerFeet();
        var follow = bot.getFollowProcess();
        var following = follow.following();
        var controlling = bot.getPathingControlManager().mostRecentInControl().orElse(null);
        String goalText = goal == null ? "none" : goal.toString().replace('\n', ' ').replace('\r', ' ');
        if (goalText.length() > 256) goalText = goalText.substring(0, 256);
        return "nativeContextMatches=true pinnedUUID=" + target.entityId()
                + " pinnedStartingCount=" + target.startingCount() + " nativeMode=" + mode
                + " nativeGoal=" + goalText + " kernelPlayerFeet=" + feet
                + " goalInPlayerFeet=" + (goal == null ? "unavailable" : goal.isInGoal(feet))
                + " followRadius=" + OwnedKernelAPI.getSettings().followRadius.value
                + " processActiveObserved=" + ownedPickupProcessActive
                + " processActiveObservedTick=" + ownedPickupProcessActiveTick
                + " processActiveCached=" + (follow.currentFilter() != null && following != null && !following.isEmpty())
                + " followFilterMatches=" + (follow.currentFilter() == followFilter)
                + " followControlling=" + (controlling == follow)
                + " controllingProcess=" + (controlling == null ? "none" : controlling.getClass().getSimpleName())
                + " searching=" + pathing.getInProgress().isPresent()
                + " hasPath=" + pathing.hasPath() + " pathing=" + pathing.isPathing()
                + " executor=" + (executor == null ? "none" : executor.getClass().getSimpleName())
                + " pathIndex=" + (executor == null ? -1 : executor.getPosition())
                + " pathLength=" + (path == null ? 0 : path.positions().size())
                + " requestTicks=" + requestTicks + " failedCalculations=" + failedCalculations;
    }

    void startMining(Block[] blocks, Item output, int totalCount, SelectedToolRequirement tool,
                     Set<BlockPos> priorRejectedPositions) {
        if (blocks.length == 0) throw new NavigationFailure("No supported mining blocks for " + output);
        if (priorRejectedPositions.size() > 512) throw new IllegalArgumentException("Mining rejection seed exceeds 512 positions");
        prepare(); mineBlocks = blocks.clone(); this.output = output; targetCount = totalCount; this.tool = tool;
        rejectedMiningTargets.addAll(priorRejectedPositions);
        if (tool != null && !actions.prepareMiningTool(tool, mineBlocks[0].defaultBlockState(), output)) {
            throw new NavigationFailure(NavigationFailure.Kind.TOOL, "Required mining tool is unavailable or worn: " + tool.item());
        }
        int deficit = Math.max(0, totalCount - actions.count(output));
        boolean vanillaDiamonds = output == Items.DIAMOND && Arrays.stream(mineBlocks)
                .allMatch(block -> block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE);
        miningDepthPolicy = MiningDepthPolicy.select(deficit, vanillaDiamonds, config.allowExploration,
                client.level.getMinY(), OwnedKernelAPI.getSettings().maxYLevelWhileMining.value);
        rejectedMiningTargets.removeIf(position -> !withinMiningDepth(position));
        miningY = miningDepthPolicy.bulkDiamonds() ? miningDepthPolicy.desiredY() : miningLevel();
        mode = miningDepthPolicy.shouldDescend((int) Math.floor(client.player.getY())) ? Mode.DESCEND : Mode.MINE;
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] MINING_DEPTH policy={} desiredY={} ceiling={} deficit={} phase={} blocks={}",
                miningDepthPolicy.kind(), miningY, miningDepthPolicy.maximumY(), deficit, mode, Arrays.toString(mineBlocks));
        launch();
    }

    private void prepare() {
        RetreatSnapshotDiagnostics.clear(this);
        if (client.player == null || client.level == null) throw new NavigationFailure("World unavailable");
        stop();
        if (!finishCancellation()) throw new NavigationFailure("Finishing previous movement before starting a new route");
        if (bot == null) {
            bot = OwnedKernelAPI.getProvider().getPrimaryBaritone();
            if (bot == null) throw new NavigationFailure("Owned navigation is unavailable");
            bot.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
                @Override public void onPathEvent(PathEvent event) {
                    RetreatSnapshotDiagnostics.pathEvent(MovementController.this, bot, event);
                    if (event == PathEvent.CALC_FINISHED_NOW_EXECUTING) waterBreak.select();
                    if (mode == Mode.IDLE && !cancelling) return;
                    if (event == PathEvent.CALC_FAILED) failedCalculations++;
                    if (event == PathEvent.CALC_FINISHED_NOW_EXECUTING) checkInitialRetreatPath();
                    if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                            "[Lodekeeper] NAV backend=owned-navigation mode={} event={} elapsedMs={}",
                            mode, event, (System.nanoTime() - startedNanos) / 1_000_000);
                    if (config.debugLogging && event == PathEvent.CALC_STARTED && client.player != null)
                        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                                "[Lodekeeper] NAV calcTool={} slot={}",
                                GameCatalog.id(client.player.getMainHandItem().getItem()), client.player.getInventory().getSelectedSlot());
                }
                @Override public void onTick(TickEvent event) { waterBreak.coverage(event); }
            });
        }
        input.release(); actions.cancel();
        retreatRequest = false;
        resetRetreatPrefix();
        startedNanos = System.nanoTime(); requestTicks = failedCalculations = 0;
        shallowPreparationSamples = 0;
        motionLogAnchorInitialized = false;
        motionLogAnchorX = motionLogAnchorZ = 0;
        motionLogAnchorRequestTick = motionLogLastRequestTick = motionLogCount = 0;
        lastBreakTick = lastDiscoveryMergeTick = -100; lastScanLogTick = -20;
        phaseStartedTick = 0; miningY = Integer.MIN_VALUE; miningDepthPolicy = null;
        scannedMiningChunks.clear(); rejectedMiningTargets.clear(); miningDiscovery = null;
        discoveredMiningTargets.clear(); pendingMiningTargets.clear();
        requestX = client.player.getX(); requestZ = client.player.getZ();
        routeGoal = null; diagnosticGoal = null; mineBlocks = new Block[0]; output = null; tool = null; pickupTarget = null;
        airRecoveryGoal = null; lastAirRecoveryDestination = null; airRecoveryCancellationPending = false;
        observation = NavigationSnapshot.EMPTY; pendingBreakFailure = null;
        lastLoggedBreakPosition = miningTarget = null; lastLoggedBreakTool = null;
    }

    private long nativeDebugWindow;
    private int nativeDebugMessages;
    private void logNativeDebug(String message) {
        long now = System.nanoTime();
        if (now - nativeDebugWindow >= 2_000_000_000L) {
            nativeDebugWindow = now;
            nativeDebugMessages = 0;
        }
        if (nativeDebugMessages++ < 8)
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info("[Lodekeeper] KERNEL {}", message);
    }

    private void launch() {
        if (mode == Mode.MINE || mode == Mode.DESCEND) {
            refreshMiningDepth();
            if (miningDepthPolicy.bulkDiamonds() && (int) Math.floor(client.player.getY())
                    > miningDepthPolicy.effectiveMaximumY(OwnedKernelAPI.getSettings().maxYLevelWhileMining.value))
                mode = Mode.DESCEND;
        }
        input.release();
        phaseStartedTick = requestTicks;
        lease = new SettingsLease();
        Settings settings = OwnedKernelAPI.getSettings();
        lease.set(settings.chatDebug, config.debugLogging);
        lease.set(settings.logger, message -> logNativeDebug(message.getString()));
        boolean airRecovery = mode == Mode.AIR;
        lease.set(settings.allowBreak, !airRecovery && !retreatRequest && config.allowBreaking);
        lease.set(settings.allowPlace, !airRecovery && !retreatRequest && config.allowBuilding);
        lease.set(settings.allowInventory, false);
        if (retreatRequest) {
            lease.set(settings.planningTickLookahead, 0);
            lease.set(settings.splicePath, false);
        }
        lease.set(settings.allowParkour, !airRecovery && config.allowParkour);
        lease.set(settings.allowParkourPlace, !airRecovery && !retreatRequest
                && config.allowParkour && config.allowParkourPlace && config.allowBuilding);
        lease.set(settings.allowSprint, config.allowSprint);
        lease.set(settings.allowDiagonalAscend, !airRecovery && config.allowDiagonalAscend);
        lease.set(settings.allowDiagonalDescend, !airRecovery && config.allowDiagonalDescend);
        lease.set(settings.avoidance, !airRecovery && !retreatRequest && config.avoidance);
        lease.set(settings.mobAvoidanceRadius, config.mobAvoidanceRadius);
        lease.set(settings.mobSpawnerAvoidanceRadius, config.spawnerAvoidanceRadius);
        lease.set(settings.allowWaterBucketFall, false);
        if (airRecovery) lease.set(settings.assumeWalkOnWater, false);
        lease.set(settings.maxFallHeightNoWater, config.maxFallHeight);
        lease.set(settings.autoTool, true);
        lease.set(settings.assumeExternalAutoTool, true);
        lease.set(settings.itemSaver, true);
        lease.set(settings.itemSaverThreshold, tool == null ? 1 : Math.max(1, tool.minimumDurability() - 1));
        lease.set(settings.renderPath, false); lease.set(settings.renderGoal, false);
        lease.set(settings.renderSelection, false); lease.set(settings.renderSelectionBoxes, false);
        lease.set(settings.mineScanDroppedItems, true);
        lease.set(settings.exploreForBlocks, config.allowExploration);
        lease.set(settings.legitMine, false);
        if (miningDepthPolicy != null && miningDepthPolicy.bulkDiamonds())
            lease.set(settings.maxYLevelWhileMining,
                    miningDepthPolicy.effectiveMaximumY(settings.maxYLevelWhileMining.value));
        lease.set(settings.mineGoalUpdateInterval, config.mineGoalUpdateTicks);
        lease.set(settings.primaryTimeoutMS, (long) config.pathInitialSearchMillis);
        lease.set(settings.failureTimeoutMS, (long) Math.max(config.pathInitialSearchMillis, config.pathInitialFailureMillis));
        lease.set(settings.planAheadPrimaryTimeoutMS, (long) config.pathContinuationSearchMillis);
        lease.set(settings.planAheadFailureTimeoutMS, (long) Math.max(config.pathContinuationSearchMillis, config.pathContinuationFailureMillis));
        for (var preference : dev.lodekeeper.core.NavigationPreferenceCatalog
                .nativeValues(config.navigationPreferences).entrySet()) {
            switch (preference.getKey()) {
                case "allowPlaceInFluidsSource" -> lease.set(settings.allowPlaceInFluidsSource, (Boolean) preference.getValue());
                case "allowPlaceInFluidsFlow" -> lease.set(settings.allowPlaceInFluidsFlow, (Boolean) preference.getValue());
                case "allowDownward" -> lease.set(settings.allowDownward, (Boolean) preference.getValue());
                case "allowWalkOnBottomSlab" -> lease.set(settings.allowWalkOnBottomSlab, (Boolean) preference.getValue());
                case "allowParkourAscend" -> lease.set(settings.allowParkourAscend, (Boolean) preference.getValue());
                case "allowJumpAtBuildLimit" -> lease.set(settings.allowJumpAtBuildLimit, (Boolean) preference.getValue());
                case "sprintAscends" -> lease.set(settings.sprintAscends, (Boolean) preference.getValue());
                case "sprintInWater" -> lease.set(settings.sprintInWater, (Boolean) preference.getValue());
                case "overshootTraverse" -> lease.set(settings.overshootTraverse, (Boolean) preference.getValue());
                case "strictLiquidCheck" -> lease.set(settings.strictLiquidCheck, (Boolean) preference.getValue());
                case "avoidUpdatingFallingBlocks" -> lease.set(settings.avoidUpdatingFallingBlocks, (Boolean) preference.getValue());
                case "pauseMiningForFallingBlocks" -> lease.set(settings.pauseMiningForFallingBlocks, (Boolean) preference.getValue());
                case "cutoffAtLoadBoundary" -> lease.set(settings.cutoffAtLoadBoundary, (Boolean) preference.getValue());
                case "costVerificationLookahead" -> lease.set(settings.costVerificationLookahead, (Integer) preference.getValue());
                case "maxCostIncrease" -> lease.set(settings.maxCostIncrease, (Double) preference.getValue());
                case "splicePath" -> lease.set(settings.splicePath, (Boolean) preference.getValue());
                case "blacklistClosestOnFailure" -> lease.set(settings.blacklistClosestOnFailure, (Boolean) preference.getValue());
                case "considerPotionEffects" -> lease.set(settings.considerPotionEffects, (Boolean) preference.getValue());
                case "blockPlacementPenalty" -> lease.set(settings.blockPlacementPenalty, (Double) preference.getValue());
                case "blockBreakAdditionalPenalty" -> lease.set(settings.blockBreakAdditionalPenalty, (Double) preference.getValue());
                case "jumpPenalty" -> lease.set(settings.jumpPenalty, (Double) preference.getValue());
                case "mobAvoidanceCoefficient" -> lease.set(settings.mobAvoidanceCoefficient, (Double) preference.getValue());
                case "mobSpawnerAvoidanceCoefficient" -> lease.set(settings.mobSpawnerAvoidanceCoefficient, (Double) preference.getValue());
                default -> throw new IllegalStateException("Unmapped advanced navigation option: " + preference.getKey());
            }
        }
        applyProtection();
        if (!airRecovery && !retreatRequest) actions.prepareScaffoldHotbar(scaffoldItems);
        switch (mode) {
            case MOVE, AIR -> bot.getCustomGoalProcess().setGoalAndPath(routeGoal);
            case PICKUP -> {
                if (pickupTarget == null) bot.getFollowProcess().pickup(stack -> stack.is(output));
                else {
                    lease.set(settings.followRadius, 0);
                    lease.set(settings.followOffsetDistance, 0.0);
                    lease.set(settings.followTargetMaxDistance, 64);
                    bot.getFollowProcess().follow(followFilter);
                }
            }
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
                LinkedHashSet<BlockPos> seed = new LinkedHashSet<>();
                for (BlockPos position : discoveredMiningTargets) {
                    if (seed.size() == 64) break;
                    if (validMiningDiscovery(position) && !rejectedMiningTargets.contains(position)) seed.add(position.immutable());
                }
                for (BlockPos position : pendingMiningTargets) {
                    if (seed.size() == 64) break;
                    if (validMiningDiscovery(position) && !rejectedMiningTargets.contains(position)) seed.add(position.immutable());
                }
                var admission = ownedMiningTargets().replaceOwnedMiningTargets(seed, rejectedMiningTargets);
                if (admission.isEmpty()) throw new NavigationFailure("Mining process has no current owned world session");
                logMiningScan("native-mine", System.nanoTime() - scanStarted, -1);
            }
            default -> throw new IllegalStateException("No navigation request to launch");
        }
    }

    private void applyProtection() {
        Settings settings = OwnedKernelAPI.getSettings();
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
        if (retreatRequest || mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
                || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
        if (mode == Mode.IDLE && !cancelling) return true;
        observeAirRecoveryDestination();
        if (client.player == null || client.level == null) { stop(); return false; }
        if ((mode == Mode.FOLLOW || resumeMode == Mode.FOLLOW || mode == Mode.PICKUP && pickupTarget != null)
                && (client.level != followOwnerWorld || client.player != followOwnerPlayer)) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.OWNERSHIP_LOST,
                    "Owned follow target player or world changed");
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
        if (miningRequest) {
            int previousMiningY = miningY;
            refreshMiningDepth();
            if (miningDepthPolicy.bulkDiamonds()) {
                boolean aboveCeiling = (int) Math.floor(client.player.getY())
                        > miningDepthPolicy.effectiveMaximumY(OwnedKernelAPI.getSettings().maxYLevelWhileMining.value);
                if (mode == Mode.SUSPENDED) {
                    if (aboveCeiling) resumeMode = Mode.DESCEND;
                } else if (previousMiningY != miningY) {
                    changeMiningPhase(aboveCeiling || mode == Mode.DESCEND ? Mode.DESCEND : Mode.MINE);
                    return false;
                }
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
        if (mode != Mode.AIR && !retreatRequest) actions.prepareScaffoldHotbar(scaffoldItems);
        boolean satisfied = mode == Mode.MOVE || mode == Mode.AIR
                ? routeGoal.isInGoal(client.player.blockPosition())
                : mode == Mode.PICKUP && pickupTarget != null
                        ? !pickupTarget.entity().isAlive()
                                || pickupTarget.entity().getItem().getCount() < pickupTarget.startingCount()
                        : mode != Mode.FOLLOW && actions.count(output) >= targetCount;
        if (mode == Mode.MOVE && satisfied && routeGoal instanceof PlacementGoal placement) {
            satisfied = actions.canPlaceAt(placement.destination);
            if (satisfied && config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] STATION_ROUTE event=arrival destination={} player={}",
                    placement.destination, client.player.blockPosition());
        }
        if (satisfied) { stop(); return finishCancellation(); }
        if (mode == Mode.DESCEND && new GoalYLevel(miningY).isInGoal(client.player.blockPosition())) {
            changeMiningPhase(Mode.MINE);
            return false;
        }
        boolean active = switch (mode) {
            case MOVE, AIR, DESCEND -> bot.getCustomGoalProcess().isActive();
            case MINE -> bot.getMineProcess().isActive();
            case PICKUP, FOLLOW -> bot.getFollowProcess().isActive();
            default -> false;
        };
        if (config.debugLogging && mode == Mode.PICKUP && pickupTarget != null) {
            ownedPickupProcessActive = active;
            ownedPickupProcessActiveTick = requestTicks;
        }
        if (mode == Mode.PICKUP && pickupTarget != null && failedCalculations >= 2) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Owned station pickup route exhausted two native path calculations");
        }
        if (mode == Mode.FOLLOW && failedCalculations >= 4) {
            stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Follow route exhausted four native path calculations");
        }
        var pathing = bot.getPathingBehavior();
        boolean waiting = !pathing.hasPath() && pathing.getInProgress().isEmpty();
        if (mode == Mode.MOVE && !active && waiting && !pathing.isPathing()
                && routeGoal instanceof PlacementGoal placement && placement.isInGoal(client.player.blockPosition())) {
            checkAirRecoveryOwnership();
            BlockPos rejected = client.player.blockPosition();
            PlacementGoal remaining = placement.withoutStance(rejected);
            if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] STATION_ROUTE event=stance-rejected destination={} stance={} remaining={}",
                    placement.destination, rejected, remaining == null ? 0 : remaining.stances.goals().length);
            if (remaining == null) {
                stop(); throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                        "Station placement exhausted its twelve native approach stances");
            }
            routeGoal = remaining;
            bot.getCustomGoalProcess().setGoalAndPath(routeGoal);
            return false;
        }
        boolean exploring = mode == Mode.MINE && active && pathing.getGoal() instanceof GoalRunAway
                && requestTicks - phaseStartedTick >= 5;
        if (miningY != Integer.MIN_VALUE && (mode == Mode.MINE || mode == Mode.DESCEND)) {
            if (mode == Mode.MINE && active) rememberRejectedMiningTargets();
            scanOneMiningChunk();
            pendingMiningTargets.removeIf(position -> !validMiningDiscovery(position)
                    || rejectedMiningTargets.contains(position));
            if (mode == Mode.MINE && active && requestTicks - lastDiscoveryMergeTick >= 4)
                mergeMiningDiscoveries();
            if ((mode == Mode.DESCEND && !miningDepthPolicy.bulkDiamonds()
                    || mode == Mode.MINE && !active) && !pendingMiningTargets.isEmpty()) {
                changeMiningPhase(Mode.MINE);
                return false;
            }
        }
        if (exploring && !miningDepthPolicy.bulkDiamonds()
                && knownMiningTargets().isEmpty()
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

    private void refreshMiningDepth() {
        int maximumY = miningDepthPolicy.effectiveMaximumY(OwnedKernelAPI.getSettings().maxYLevelWhileMining.value);
        if (maximumY <= client.level.getMinY()) {
            stop();
            throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Mining ceiling leaves no usable depth above the world floor");
        }
        if (miningDepthPolicy.bulkDiamonds())
            miningY = miningDepthPolicy.effectiveDesiredY(OwnedKernelAPI.getSettings().maxYLevelWhileMining.value);
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
        cancellationProcess = expectedProcessForMode(bot, mode);
        resumeMode = next; mode = Mode.SUSPENDED;
        cancelling = true; bot.getPathingBehavior().cancelEverything(); input.release();
    }

    private static long miningChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private boolean withinMiningDepth(BlockPos position) {
        int maximumY = OwnedKernelAPI.getSettings().maxYLevelWhileMining.value;
        if (miningDepthPolicy != null && miningDepthPolicy.bulkDiamonds())
            maximumY = miningDepthPolicy.effectiveMaximumY(maximumY);
        return position.getY() <= maximumY;
    }

    private boolean validMiningDiscovery(BlockPos position) {
        if (!withinMiningDepth(position)) return false;
        if (client.level.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) == null) return false;
        double limit = config.allowExploration ? config.explorationDistance : config.searchRadius;
        double dx = position.getX() + .5 - requestX, dz = position.getZ() + .5 - requestZ;
        if (dx * dx + dz * dz > limit * limit) return false;
        var state = client.level.getBlockState(position);
        return Arrays.asList(mineBlocks).contains(state.getBlock()) && !state.hasBlockEntity()
                && !protectedBlocks.contains(state.getBlock()) && state.getDestroySpeed(client.level, position) >= 0;
    }

    private dev.lodekeeper.navigation.kernel.api.process.OwnedMiningTargets ownedMiningTargets() {
        if (!(bot.getMineProcess() instanceof dev.lodekeeper.navigation.kernel.api.process.OwnedMiningTargets access))
            throw new NavigationFailure("The owned mining API is unavailable");
        return access;
    }

    private void rememberRejectedMiningTargets() {
        var snapshot = ownedMiningTargets().ownedMiningTargetsSnapshot();
        if (snapshot.isEmpty()) return;
        for (BlockPos position : snapshot.get().rejections()) {
            if (!withinMiningDepth(position)) continue;
            BlockPos immutable = position.immutable();
            rejectedMiningTargets.remove(immutable);
            rejectedMiningTargets.add(immutable);
            if (rejectedMiningTargets.size() > 512) rejectedMiningTargets.remove(rejectedMiningTargets.iterator().next());
        }
    }

    private void mergeMiningDiscoveries() {
        var access = ownedMiningTargets();
        var before = access.ownedMiningTargetsSnapshot();
        if (before.isEmpty()) return;
        List<BlockPos> existingTargets = before.get().targets();
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        for (BlockPos existing : existingTargets) {
            if (merged.size() == 64) break;
            if (withinMiningDepth(existing) && !rejectedMiningTargets.contains(existing)) merged.add(existing);
        }
        LinkedHashSet<BlockPos> attempted = new LinkedHashSet<>();
        for (BlockPos fresh : pendingMiningTargets) {
            if (merged.size() == 64) break;
            if (!rejectedMiningTargets.contains(fresh) && validMiningDiscovery(fresh) && merged.add(fresh)) attempted.add(fresh);
        }
        var after = access.admitOwnedMiningTargets(attempted, rejectedMiningTargets);
        if (after.isEmpty()) return;
        Set<BlockPos> admittedTargets = Set.copyOf(after.get().targets());
        Set<BlockPos> rejectedTargets = Set.copyOf(after.get().rejections());
        pendingMiningTargets.removeIf(position -> rejectedMiningTargets.contains(position)
                || admittedTargets.contains(position) || rejectedTargets.contains(position));
        lastDiscoveryMergeTick = requestTicks;
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] NAV discovered={} retained={} rejected={} requestTicks={} phase={}",
                (int) after.get().targets().stream().filter(position -> !existingTargets.contains(position)).count(),
                after.get().targets().size(), rejectedMiningTargets.size(), requestTicks, mode);
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
                if (distance >= bestDistance || client.level.getChunk(cx, cz, ChunkStatus.FULL, false) == null) continue;
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
                || !bot.getInputOverrideHandler().isInputForcedDown(dev.lodekeeper.navigation.kernel.api.utils.input.Input.CLICK_LEFT)) return true;
        if (cancelling || retreatRequest || mode == Mode.IDLE || mode == Mode.SUSPENDED || !config.allowBreaking) return false;
        if (client.level == null || client.player == null) return false;
        if (config.pauseOnScreen && GameApi.screen(client) != null) return false;
        var state = client.level.getBlockState(position);
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
        if (ownsNativeBreak(position)) actions.recordOwnedNavigationBreak(position);
        lastBreakTick = requestTicks;
        return true;
    }

    private boolean ownsNativeBreak(BlockPos position) {
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        if (owner == null || !owner.isCurrent(session) || session.world() != client.level
                || bot != owner.getPrimaryBaritone() || lease == null || cancelling || retreatRequest
                || mode == Mode.IDLE || mode == Mode.SUSPENDED || pendingOwnershipFailure != null
                || !bot.getInputOverrideHandler().isInputForcedDown(dev.lodekeeper.navigation.kernel.api.utils.input.Input.CLICK_LEFT)) return false;
        var expected = expectedProcessForMode(bot, mode);
        if (expected == null || !expected.isActive() || hasForeignActiveProcess(bot, expected)) return false;
        var controlling = bot.getPathingControlManager().mostRecentInControl();
        return controlling.isPresent() && controlling.get() == expected
                && dev.lodekeeper.navigation.kernel.OwnedMutationGuard.executeBreak(owner, position);
    }

    boolean maySettleWaterPreparation(Movement movement, MovementState state, BlockPos block) {
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        if (owner == null || !client.isSameThread() || !owner.isCurrent(session) || session.world() != client.level
                || client.player == null || client.gameMode == null || bot == null || bot != owner.getPrimaryBaritone()
                || bot.getPlayerContext().player() != client.player || bot.getPlayerContext().world() != client.level
                || client.getCameraEntity() != client.player || !client.player.isAlive() || client.player.isRemoved()
                || mode != Mode.MINE || resumeMode != Mode.IDLE || lease == null || !lease.isCurrent()
                || cancelling || cancellationProcess != null || retreatRequest || defenseHop != null
                || followCancellationPending || airRecoveryCancellationPending || defenseSettlingPending
                || pendingBreakFailure != null || pendingOwnershipFailure != null || pendingRetreatPrefixFailure != null
                || !config.allowBreaking || output == null || actions.count(output) >= targetCount
                || tool != null && !actions.hasTool(tool) || defenseHopManualInput()
                || GameApi.screen(client) != null || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || !dev.lodekeeper.navigation.kernel.OwnedMutationGuard.safeEquipment(client.player)
                || !(movement instanceof MovementAscend) || state == null || state.getStatus() != MovementStatus.PREPPING
                || block == null || !Arrays.asList(movement.toBreakAll()).contains(block)) return false;
        var expected = bot.getMineProcess();
        var controlling = bot.getPathingControlManager().mostRecentInControl();
        if (!expected.isActive() || hasForeignActiveProcess(bot, expected)
                || controlling.isEmpty() || controlling.get() != expected) return false;
        var executor = bot.getPathingBehavior().getCurrent();
        if (executor == null) return false;
        var path = executor.getPath();
        int index = executor.getPosition();
        if (path == null || index < 0 || index >= path.movements().size() || path.movements().get(index) != movement)
            return false;
        for (Input key : new Input[] {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT,
                Input.SNEAK, Input.SPRINT, Input.JUMP, Input.CLICK_RIGHT})
            if (Boolean.TRUE.equals(state.getInputStates().get(key))) return false;
        var player = client.player;
        var velocity = player.getDeltaMovement();
        if (player.isPassenger() || player.isSwimming() || player.isCrouching() || player.getAbilities().flying
                || player.isFallFlying() || player.onClimbable() || player.isInWall() || player.horizontalCollision
                || velocity.x != 0.0 || velocity.z != 0.0 || !Double.isFinite(velocity.y)
                || player.isUnderWater() || player.getAirSupply() <= player.getMaxAirSupply() * 2 / 3
                || !(player.getHealth() > config.pauseBelowHealth)) return false;
        if (!client.level.hasChunkAt(block)) return false;
        BlockState target = client.level.getBlockState(block);
        if (target.isAir() || target.hasBlockEntity() || target.getBlock().hasDynamicShape() || protectedBlocks.contains(target.getBlock())
                || OwnedKernelAPI.getSettings().blocksToDisallowBreaking.value.contains(target.getBlock())
                || target.getDestroySpeed(client.level, block) < 0
                || target.getCollisionShape(client.level, block, net.minecraft.world.phys.shapes.CollisionContext.of(player)).isEmpty()
                || !dev.lodekeeper.navigation.kernel.OwnedMutationGuard.executeBreak(owner, block)) return false;
        if (!terrain.shallowWaterPreparationSafe(movement.getSrc().getY())) return false;
        observeShallowPreparation(movement, block);
        return true;
    }

    private void observeShallowPreparation(Movement movement, BlockPos block) {
        try {
            if (!config.debugLogging || shallowPreparationSamples >= 16) return;
            shallowPreparationSamples++;
            var player = client.player;
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] SHALLOW_PREPARATION sample={} requestNanos={} requestTick={} src={} dest={} target={} decision=OMIT_AUTOMATIC_JUMP onGround={} y={} eye={} settledEyeY={} bbox={} velocity={} air={} health={} flowProof=ALL_FOOTPRINT_SOURCE_ZERO floorProof=FULL_STABLE sweepProof=CLEAR_LOADED_HALO",
                    shallowPreparationSamples, startedNanos, requestTicks, movement.getSrc(), movement.getDest(), block,
                    player.onGround(), player.getY(), player.getEyePosition(),
                    player.getEyePosition().y + (movement.getSrc().getY() - player.getY()),
                    player.getBoundingBox(), player.getDeltaMovement(), player.getAirSupply(), player.getHealth());
        } catch (Throwable ignored) {
        }
    }

    boolean yieldMineToManualInput() {
        if (mode != Mode.MINE || !defenseHopManualInput()) return false;
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = owner == null ? null : owner.captureSession();
        if (owner == null || !client.isSameThread() || !owner.isCurrent(session) || session.world() != client.level
                || client.player == null || bot == null || bot != owner.getPrimaryBaritone()
                || bot.getPlayerContext().player() != client.player || bot.getPlayerContext().world() != client.level
                || lease == null || cancelling || cancellationProcess != null || pendingOwnershipFailure != null)
            return false;
        var expected = bot.getMineProcess();
        var controlling = bot.getPathingControlManager().mostRecentInControl();
        if (!expected.isActive() || hasForeignActiveProcess(bot, expected)
                || controlling.isPresent() && controlling.get() != expected) return false;
        suspend();
        if (!owner.isCurrent(session) || owner.getPrimaryBaritone() != bot
                || hasForeignActiveProcess(bot, expected))
            throw new NavigationFailure(NavigationFailure.Kind.OWNERSHIP_LOST, "Mining owner changed during manual yield");
        var pathing = bot.getPathingBehavior();
        pathing.forceCancel();
        ((dev.lodekeeper.navigation.kernel.Baritone) bot).getInputOverrideHandler().restoreOwnedInput();
        if (expected.isActive() || pathing.getCurrent() != null || pathing.getNext() != null
                || pathing.isPathing() || pathing.getInProgress().isPresent()
                || client.player.input instanceof dev.lodekeeper.navigation.kernel.utils.PlayerMovementInput)
            throw new NavigationFailure(NavigationFailure.Kind.OWNERSHIP_LOST, "Mining manual yield did not drain native work");
        for (Input key : Input.values()) if (bot.getInputOverrideHandler().isInputForcedDown(key))
            throw new NavigationFailure(NavigationFailure.Kind.OWNERSHIP_LOST, "Mining manual yield left forced input");
        finishCancellation();
        return true;
    }

    boolean backfillInputClear() { return !defenseHopManualInput(); }

    boolean backfillIdle() {
        return defenseHopNativeDrained() && !defenseHopManualInput() && defenseHopPhysicalStartReady();
    }

    void suspend() {
        waterBreak.close("SUSPEND");
        rebaseMovementProgress();
        stopDefenseHop();
        if (retreatRequest || mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
                || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
        if (mode == Mode.IDLE || mode == Mode.SUSPENDED) return;
        cancellationProcess = expectedProcessForMode(bot, mode);
        followCancellationPending = mode == Mode.FOLLOW;
        resumeMode = mode; mode = Mode.SUSPENDED;
        cancelling = true; bot.getPathingBehavior().cancelEverything(); input.release();
    }

    DefenseHop planDefenseHop() {
        checkAirRecoveryOwnership();
        if (defenseHop != null || !defenseHopNativeDrained() || !defenseHopCameraReady()
                || !client.player.onGround()) return null;
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        double maxRise = terrain.defenseHopMaxRise(x, y, z);
        return Double.isFinite(maxRise) && maxRise > 0.0 ? new DefenseHop(x, y, z, maxRise) : null;
    }

    boolean startDefenseHop(DefenseHop plan) {
        checkAirRecoveryOwnership();
        if (defenseHopManualInput())
            throw new NavigationFailure(NavigationFailure.Kind.OWNERSHIP_LOST, "Manual input has priority over defense hop");
        if (plan == null || defenseHop != null || !defenseHopNativeDrained() || !defenseHopCameraReady()
                || !client.player.onGround() || !defenseHopPhysicalStartReady()) return false;
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        double dx = x - plan.x(), dy = y - plan.y(), dz = z - plan.z();
        int feetY16 = GameTerrain.quantizedFeetY16(plan.y());
        if (!Double.isFinite(plan.x()) || !Double.isFinite(plan.y()) || !Double.isFinite(plan.z())
                || !Double.isFinite(plan.maxRise()) || plan.maxRise() <= 0.0
                || feetY16 == GameTerrain.INVALID_FEET_Y16 || Math.floorMod(feetY16, 16) != 0
                || Math.sqrt(dx * dx + dy * dy + dz * dz) > DEFENSE_HOP_LAUNCH_TOLERANCE
                || !terrain.defenseHopPoseSafe(plan.x(), plan.y(), plan.z(), plan.maxRise(), x, y, z)
                || !terrain.defenseHopLandingSafe(x, y, z, feetY16)) return false;

        input.acquire(client);
        if (client.player.input != input) {
            input.release();
            return false;
        }
        defenseHop = new DefenseHopState(plan, client.level, client.player, client.getCameraEntity(), feetY16);
        input.drive(0.0f, 0.0f, true, false);
        return true;
    }

    boolean tickDefenseHop() {
        DefenseHopState active = defenseHop;
        if (active == null) throw new NavigationFailure("Defense hop tick without an active hop");
        try {
            checkAirRecoveryOwnership();
        } catch (NavigationFailure failure) {
            stopDefenseHop();
            throw failure;
        }
        if (client.player != active.player || client.level != active.world
                || client.getCameraEntity() != active.camera || client.getCameraEntity() != client.player)
            throw abortDefenseHop(NavigationFailure.Kind.OWNERSHIP_LOST, "Defense hop player, world, or camera changed");
        if (client.player.input != input)
            throw abortDefenseHop(NavigationFailure.Kind.OWNERSHIP_LOST, "Defense hop input ownership changed");
        if (!defenseHopNativeDrained())
            throw abortDefenseHop(NavigationFailure.Kind.OWNERSHIP_LOST, "Native work resumed during defense hop");

        if (defenseHopManualInput())
            throw abortDefenseHop(NavigationFailure.Kind.OWNERSHIP_LOST, "Manual input has priority over defense hop");
        input.idle();
        if (++active.ticks > DEFENSE_HOP_MAX_TICKS)
            throw abortDefenseHop(NavigationFailure.Kind.OTHER, "Defense hop exceeded its 30-tick bound");
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        double dx = x - active.plan.x(), dz = z - active.plan.z();
        double rise = y - active.plan.y();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.hypot(dx, dz) > DEFENSE_HOP_MAX_HORIZONTAL_DISPLACEMENT
                || rise < -DEFENSE_HOP_LAUNCH_TOLERANCE
                || rise > active.plan.maxRise() + DEFENSE_HOP_LAUNCH_TOLERANCE)
            throw abortDefenseHop(NavigationFailure.Kind.OTHER, "Defense hop left its bounded motion envelope");
        if (!terrain.defenseHopPoseSafe(active.plan.x(), active.plan.y(), active.plan.z(), active.plan.maxRise(), x, y, z))
            throw abortDefenseHop(NavigationFailure.Kind.OTHER, "Defense hop native terrain or actual pose proof failed");
        if (!client.player.onGround()) {
            active.airborneObserved = true;
            return false;
        }
        if (!active.airborneObserved) return false;
        if (!terrain.defenseHopLandingSafe(x, y, z, active.feetY16))
            throw abortDefenseHop(NavigationFailure.Kind.OTHER, "Defense hop landing support proof failed");
        stopDefenseHop();
        return true;
    }

    void stopDefenseHop() {
        if (defenseHop == null) return;
        input.release();
        defenseHop = null;
    }

    private NavigationFailure abortDefenseHop(NavigationFailure.Kind kind, String reason) {
        stopDefenseHop();
        return new NavigationFailure(kind, reason);
    }

    private boolean defenseHopManualInput() {
        var options = client.options;
        return options.keyAttack.isDown()
                || options.keyUse.isDown()
                || options.keyUp.isDown()
                || options.keyDown.isDown()
                || options.keyLeft.isDown()
                || options.keyRight.isDown()
                || options.keyJump.isDown()
                || options.keyShift.isDown()
                || options.keySprint.isDown();
    }

    private boolean defenseHopCameraReady() {
        return client.player != null && client.level != null && client.getCameraEntity() == client.player;
    }

    private boolean defenseHopPhysicalStartReady() {
        var velocity = client.player.getDeltaMovement();
        double horizontalSpeed = Math.hypot(velocity.x, velocity.z);
        return Double.isFinite(velocity.x) && Double.isFinite(velocity.z)
                && horizontalSpeed <= DEFENSE_HOP_MAX_HORIZONTAL_SPEED;
    }

    private boolean defenseHopNativeDrained() {
        if (!defenseHopCameraReady() || mode != Mode.IDLE || resumeMode != Mode.IDLE || cancelling
                || cancellationProcess != null || followCancellationPending || airRecoveryCancellationPending
                || lease != null) return false;
        IBaritone activeBot = bot == null ? OwnedKernelAPI.getProvider().getPrimaryBaritone() : bot;
        var pathing = activeBot.getPathingBehavior();
        if (pathing.hasPath() || pathing.isPathing() || pathing.getInProgress().isPresent()) return false;
        IBaritoneProcess[] processes = {
                activeBot.getCustomGoalProcess(), activeBot.getMineProcess(), activeBot.getFollowProcess(),
                activeBot.getBuilderProcess(), activeBot.getExploreProcess(), activeBot.getFarmProcess(),
                activeBot.getGetToBlockProcess(), activeBot.getElytraProcess()
        };
        for (IBaritoneProcess process : processes) if (process.isActive()) return false;
        for (dev.lodekeeper.navigation.kernel.api.utils.input.Input key : dev.lodekeeper.navigation.kernel.api.utils.input.Input.values())
            if (activeBot.getInputOverrideHandler().isInputForcedDown(key)) return false;
        return true;
    }

    void stopForDefense() {
        checkAirRecoveryOwnership();
        stop();
    }

    void stop() {
        waterBreak.close("STOP");
        RetreatSnapshotDiagnostics.clear(this);
        rebaseMovementProgress();
        stopDefenseHop();
        boolean stoppingAirRecovery = mode == Mode.AIR || mode == Mode.SUSPENDED && resumeMode == Mode.AIR;
        if (retreatRequest || stoppingAirRecovery || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
        if (bot != null && cancellationProcess == null) cancellationProcess = expectedProcessForMode(bot, mode);
        if (stoppingAirRecovery) airRecoveryCancellationPending = true;
        followCancellationPending |= mode == Mode.FOLLOW || resumeMode == Mode.FOLLOW
                || mode == Mode.PICKUP && pickupTarget != null
                || resumeMode == Mode.PICKUP && pickupTarget != null;
        if (bot != null && followFilter != null && bot.getFollowProcess().currentFilter() == followFilter)
            bot.getFollowProcess().cancel();
        followFilter = null;
        followTargetId = null;
        followOwnerWorld = followOwnerPlayer = null;
        pickupTarget = null;
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
        IBaritone activeBot = bot == null ? OwnedKernelAPI.getProvider().getPrimaryBaritone() : bot;
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

    private boolean matchesOwnedCustomGoal(ICustomGoalProcess custom,
                                           dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal expectedGoal) {
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
        waterBreak.close("OWNERSHIP_LOST");
        stopDefenseHop();
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
        if (bot == null || mode != Mode.FOLLOW && resumeMode != Mode.FOLLOW
                && !(mode == Mode.PICKUP && pickupTarget != null)
                && !(resumeMode == Mode.PICKUP && pickupTarget != null) && !followCancellationPending)
            return false;
        Predicate<Entity> current = bot.getFollowProcess().currentFilter();
        return current != null && current != followFilter;
    }

    private NavigationFailure releaseLostFollowOwnership() {
        waterBreak.close("OWNERSHIP_LOST");
        stopDefenseHop();
        if (config.debugLogging) org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] FOLLOW event=ownership-lost mode={} target={}", mode, followTargetId);
        followFilter = null;
        followTargetId = null;
        followOwnerWorld = followOwnerPlayer = null;
        pickupTarget = null;
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
        if (!finishCancellation(false) || defenseSettlingPending && !physicalCancellationReady()) return false;
        defenseSettlingPending = false;
        return true;
    }

    boolean finishCancellationForDefense() {
        boolean ready = finishCancellation(true);
        defenseSettlingPending |= ready;
        return ready;
    }

    private boolean finishCancellation(boolean defenseOnly) {
        if (defenseOnly || mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
                || airRecoveryCancellationPending) checkAirRecoveryOwnership();
        else checkFollowOwnership();
        if (!cancelling) {
            if (defenseOnly) {
                var activeBot = bot == null ? OwnedKernelAPI.getProvider().getPrimaryBaritone() : bot;
                var pathing = activeBot.getPathingBehavior();
                if (pathing.hasPath() || pathing.isPathing() || pathing.getInProgress().isPresent()) {
                    logDefenseCancellation("native-work");
                    return false;
                }
                if (!physicalCancellationReady()) logDefenseCancellation("physical-settling");
            }
            return true;
        }
        var pathing = bot.getPathingBehavior();
        boolean hasWork = pathing.hasPath() || pathing.isPathing() || pathing.getInProgress().isPresent();
        boolean cancelled = !hasWork || pathing.cancelEverything();
        if (client.player != null && client.level != null) {
            if (!cancelled || bot.getPathingBehavior().hasPath() || bot.getPathingBehavior().isPathing()
                    || bot.getPathingBehavior().getInProgress().isPresent()) {
                if (defenseOnly) logDefenseCancellation("native-work");
                return false;
            }
            if (!physicalCancellationReady()) {
                if (defenseOnly) logDefenseCancellation("physical-settling");
                return defenseOnly;
            }
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

    private boolean physicalCancellationReady() {
        if (client.player == null || client.level == null) return true;
        var velocity = client.player.getDeltaMovement();
        boolean carriedByFluidOrClimb = client.player.isInWater() || client.player.onClimbable();
        return carriedByFluidOrClimb || client.player.onGround()
                && !(velocity.x * velocity.x + velocity.z * velocity.z > .0004);
    }

    private void logDefenseCancellation(String blockedBy) {
        long now = System.nanoTime();
        if (!config.debugLogging || now - lastDefenseCancellationLog < 1_000_000_000L) return;
        lastDefenseCancellationLog = now;
        var activeBot = bot == null ? OwnedKernelAPI.getProvider().getPrimaryBaritone() : bot;
        var pathing = activeBot.getPathingBehavior();
        var velocity = client.player.getDeltaMovement();
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] DEFENSE_DRAIN blockedBy={} hasPath={} pathing={} calculating={} ground={} horizontalSpeed={}",
                blockedBy, pathing.hasPath(), pathing.isPathing(), pathing.getInProgress().isPresent(),
                client.player.onGround(), Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z));
    }

    void shutdownOwnedNavigation() {
        waterBreak.close("SHUTDOWN");
        stopDefenseHop();
        dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.shutdown();
    }

    List<BlockPos> knownMiningTargets() {
        if (mode != Mode.MINE || bot == null || !bot.getMineProcess().isActive()) return List.of();
        var snapshot = ownedMiningTargets().ownedMiningTargetsSnapshot();
        if (snapshot.isEmpty()) return List.of();
        LinkedHashSet<BlockPos> known = new LinkedHashSet<>();
        for (BlockPos position : snapshot.get().targets()) {
            if (withinMiningDepth(position) && !rejectedMiningTargets.contains(position))
                known.add(position.immutable());
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
    boolean attachMovementProgress(ActionMovementProgress progress, boolean gather) {
        gatherDiagnosticsEligible = gather;
        if (!gather || movementProgress != progress) waterBreak.close("DEMAND_CHANGED");
        if (movementProgress == progress) return false;
        rebaseMovementProgress();
        movementProgress = progress;
        movementProgressAnchored = false;
        rebaseMovementProgress();
        return true;
    }

    private Long movementProgressCell() {
        if (client.player == null || client.level == null) return null;
        double x = client.player.getX(), y = client.player.getY(), z = client.player.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        x = Math.floor(x); y = Math.floor(y); z = Math.floor(z);
        if (x < -33_554_432 || x > 33_554_431 || z < -33_554_432 || z > 33_554_431
                || y < -2_048 || y > 2_047) return null;
        return dev.lodekeeper.nav.Position.pack((int) x, (int) y, (int) z);
    }

    private void rebaseMovementProgress() {
        if (movementProgress == null) return;
        Long cell = movementProgressCell();
        movementProgressAnchored = cell != null;
        if (cell != null) movementProgress.rebase(cell);
    }

    void observeConfirmedProgress() {
        if (movementProgress == null) return;
        Long cell = movementProgressCell();
        if (cell == null) { movementProgressAnchored = false; return; }
        if (!movementProgressAnchored || mode == Mode.IDLE || mode == Mode.SUSPENDED || cancelling) {
            movementProgress.rebase(cell);
            movementProgressAnchored = true;
        } else if (movementProgress.observe(cell)) progressToken++;
    }

    private String storedClientHit() {
        var hit = client.hitResult;
        return hit instanceof net.minecraft.world.phys.BlockHitResult block
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? "BLOCK/" + block.getBlockPos() + "/" + block.getDirection()
                : hit == null ? "NONE" : hit.getType().name();
    }

    void observeNativeBreak(Object gameMode, int stage, boolean start, boolean value, BlockPos position,
                            net.minecraft.core.Direction face, BlockPos target, float progress, int delay, boolean destroying) {
        try { waterBreak.nativeCall(gameMode, stage, start, value, position, face, target, progress, delay, destroying); }
        catch (RuntimeException failure) { waterBreak.close("DIAGNOSTIC_FAILURE"); }
    }

    void observePreparation(Object source, PreparationStage stage, boolean result, MovementState state,
                            BlockPos block, Optional<Rotation> reachable, Rotation effective, Rotation desired) {
        try { waterBreak.preparation(source, stage, result, state, block, reachable, effective, desired); }
        catch (RuntimeException failure) {
            try { waterBreak.close("DIAGNOSTIC_FAILURE"); } catch (RuntimeException ignored) { }
        }
    }

    void observeBreakHelper(Object source, BreakHelperStage stage, boolean result,
                            net.minecraft.world.phys.HitResult ray, boolean leftClick, int delay, boolean wasHitting) {
        try { waterBreak.helper(source, stage, result, ray, leftClick, delay, wasHitting); }
        catch (RuntimeException failure) {
            try { waterBreak.close("DIAGNOSTIC_FAILURE"); } catch (RuntimeException ignored) { }
        }
    }

    private final class WaterBreakObserver {
        private enum State { CLOSED, WAITING, CAPTURING }
        private State state = State.CLOSED;
        private boolean spent, partial, previousReturn, unmatched;
        private long triggeredNanos;
        private int entries, callbacks, lastCount = -1, intervalStart;
        private float previousProgress;
        private dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner;
        private dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session;
        private Object world, player, gameMode;
        private ActionMovementProgress ledger;
        private IBaritone observedBot;
        private SettingsLease observedLease;
        private IBaritoneProcess process;
        private IPathExecutor executor;
        private IPath path;
        private IMovement movement;
        private int index;
        private NativeFrame outer, child;

        private Object breakHelper;
        private PreparationFrame preparation;
        private HelperFrame helper;
        private int preparationCalls, helperCalls, preparationOrdinal, helperOrdinal;
        private boolean admissionUnknown;

        private static final class PreparationFrame {
            final int ordinal;
            PreparationFrame(int ordinal) { this.ordinal = ordinal; }
            String before, after = "UNKNOWN", block = "UNKNOWN", desired, effective;
            Boolean reachable, looking, close, returned;
            int reaches, looks, comparisons;
            PreparationStage exit;
            boolean unknown;

            String summary() {
                String branch = exit == null || unknown ? "UNKNOWN" : exit.name();
                if (exit == PreparationStage.REACHABLE && !unknown) {
                    branch += "/" + (Boolean.TRUE.equals(looking) ? "LOOKING"
                            : Boolean.TRUE.equals(close) ? "ROTATION_CLOSE" : "WAIT_ROTATION");
                }
                return "ordinal=" + ordinal + "/" + branch + "/" + (exit == null ? "INCOMPLETE" : "FINISHED")
                        + " status=" + before + "/" + after + " block=" + block
                        + " queries=" + reaches + "/" + looks + "/" + comparisons
                        + " reachable=" + observed(reachable, exit != null && !unknown && reaches == 0)
                        + " looking=" + observed(looking, exit != null && !unknown && looks == 0)
                        + " close=" + observed(close, exit != null && !unknown && comparisons == 0)
                        + " desired=" + (desired != null ? desired : exit != null && !unknown ? "NOT_EVALUATED" : "UNKNOWN")
                        + " effective=" + (effective != null ? effective : exit != null && !unknown ? "NOT_EVALUATED" : "UNKNOWN")
                        + " returned=" + observed(returned, false);
            }
        }

        private static final class HelperFrame {
            final int ordinal;
            HelperFrame(int ordinal) { this.ordinal = ordinal; }
            boolean leftClick, beforeHitting, afterHitting, blockRay, ordered, unknown;
            int beforeDelay, afterDelay, rays, guards, choices;
            String ray, branch = "UNKNOWN";
            Boolean guard, choice;
            BreakHelperStage exit;

            String summary() {
                return "ordinal=" + ordinal + "/" + (unknown || exit == null ? "UNKNOWN" : branch)
                        + "/" + (exit == null ? "INCOMPLETE" : "FINISHED") + " leftClick=" + leftClick
                        + " delay=" + beforeDelay + "/" + (exit == null ? "UNKNOWN" : afterDelay)
                        + " wasHitting=" + beforeHitting + "/" + (exit == null ? "UNKNOWN" : afterHitting)
                        + " queries=" + rays + "/" + guards + "/" + choices
                        + " ray=" + (ray != null ? ray : exit == BreakHelperStage.DELAY && !unknown ? "NOT_EVALUATED" : "UNKNOWN")
                        + " guard=" + observed(guard, exit != null && !unknown && guards == 0)
                        + " choice=" + observed(choice, exit != null && !unknown && choices == 0);
            }
        }

        private static String observed(Boolean value, boolean notEvaluated) {
            return value != null ? value.toString() : notEvaluated ? "NOT_EVALUATED" : "UNKNOWN";
        }
        private String angles(Rotation rotation) { return rotation.getYaw() + "/" + rotation.getPitch(); }

        private boolean admissionActive() {
            if (state != State.CAPTURING) return false;
            if (owner != dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() || !owner.isCurrent(session)
                    || world != client.level || player != client.player || gameMode != client.gameMode
                    || ledger != movementProgress || observedBot != bot || observedLease != lease || cancelling
                    || cancellationProcess != null || retreatRequest || mode == Mode.IDLE || mode == Mode.SUSPENDED
                    || pendingOwnershipFailure != null) {
                close("CONTEXT_INVALID"); return false;
            }
            return true;
        }

        void preparation(Object source, PreparationStage stage, boolean result, MovementState state,
                         BlockPos block, Optional<Rotation> reachable, Rotation effective, Rotation desired) {
            if (this.state != State.CAPTURING || source != movement || !admissionActive()) return;
            if (stage == PreparationStage.HEAD) {
                admissionUnknown |= preparation != null && preparation.exit == null || helper != null;
                preparation = null;
                preparationCalls++;
                preparation = new PreparationFrame(++preparationOrdinal); preparation.before = state.getStatus().name();
                return;
            }
            PreparationFrame frame = preparation;
            if (frame == null) { admissionUnknown = true; return; }
            if (frame.exit != null) { admissionUnknown = true; return; }
            switch (stage) {
                case REACHABILITY -> {
                    frame.unknown |= ++frame.reaches != 1 || frame.looks != 0 || frame.comparisons != 0;
                    frame.block = Long.toString(block.asLong()); frame.reachable = reachable.isPresent();
                    if (frame.reachable) frame.desired = angles(reachable.get());
                }
                case LOOKING_AT -> {
                    frame.unknown |= ++frame.looks != 1 || !Boolean.TRUE.equals(frame.reachable) || frame.comparisons != 0
                            || !frame.block.equals(Long.toString(block.asLong()));
                    frame.looking = result;
                }
                case ROTATION_CLOSE -> {
                    frame.unknown |= ++frame.comparisons != 1 || !Boolean.FALSE.equals(frame.looking);
                    frame.close = result; frame.effective = angles(effective); frame.desired = angles(desired);
                }
                default -> {
                    frame.exit = stage; frame.returned = result; frame.after = state.getStatus().name();
                    frame.unknown |= switch (stage) {
                        case WAITING, UNREACHABLE, CLEAR -> !result || frame.reaches != 0 || frame.looks != 0 || frame.comparisons != 0;
                        case FALLING_WAIT -> result || frame.reaches != 0 || frame.looks != 0 || frame.comparisons != 0;
                        case FALLBACK -> result || frame.reaches != 1 || !Boolean.FALSE.equals(frame.reachable)
                                || frame.looks != 0 || frame.comparisons != 0;
                        case REACHABLE -> result || frame.reaches != 1 || !Boolean.TRUE.equals(frame.reachable)
                                || frame.looks != 1 || frame.comparisons != (Boolean.TRUE.equals(frame.looking) ? 0 : 1);
                        default -> true;
                    };
                    if (stage == PreparationStage.FALLBACK) {
                        var fallback = state.getTarget().getRotation();
                        frame.desired = fallback.isPresent() ? angles(fallback.get()) : "UNKNOWN";
                        frame.unknown |= fallback.isEmpty();
                    }
                }
            }
        }

        void helper(Object source, BreakHelperStage stage, boolean result, net.minecraft.world.phys.HitResult ray,
                    boolean leftClick, int delay, boolean wasHitting) {
            if (state != State.CAPTURING || source != breakHelper || !admissionActive()) return;
            if (stage == BreakHelperStage.HEAD) {
                admissionUnknown |= helper != null && helper.exit == null;
                helper = null;
                helperCalls++;
                helper = new HelperFrame(++helperOrdinal); helper.leftClick = leftClick;
                helper.beforeDelay = delay; helper.beforeHitting = wasHitting;
                helper.ordered = preparationCalls == 1 && preparation != null && preparation.exit != null && !preparation.unknown;
                return;
            }
            HelperFrame frame = helper;
            if (frame == null) { admissionUnknown = true; return; }
            if (frame.exit != null) { admissionUnknown = true; return; }
            switch (stage) {
                case RAY -> {
                    frame.unknown |= ++frame.rays != 1 || frame.beforeDelay > 0 || frame.guards != 0 || frame.choices != 0;
                    frame.blockRay = ray != null && ray.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK;
                    frame.ray = ray == null ? "EVALUATED_NULL" : ray instanceof net.minecraft.world.phys.BlockHitResult hit
                            ? ray.getType() + "/" + hit.getBlockPos().asLong() + "/" + hit.getDirection() : ray.getType().name();
                }
                case GUARD -> {
                    frame.unknown |= ++frame.guards != 1 || frame.rays != 1 || !frame.leftClick || !frame.blockRay || frame.choices != 0;
                    frame.guard = result;
                }
                case CHOICE -> {
                    frame.unknown |= ++frame.choices != 1 || !Boolean.TRUE.equals(frame.guard);
                    frame.choice = result;
                }
                default -> {
                    frame.exit = stage; frame.afterDelay = delay; frame.afterHitting = wasHitting;
                    frame.unknown |= leftClick != frame.leftClick;
                    if (stage == BreakHelperStage.DELAY) {
                        frame.branch = "DELAY";
                        frame.unknown |= frame.beforeDelay <= 0 || frame.rays != 0 || frame.guards != 0 || frame.choices != 0;
                    } else if (stage == BreakHelperStage.GUARD_DENIED) {
                        frame.branch = "GUARD_REJECTED";
                        frame.unknown |= frame.guards != 1 || !Boolean.FALSE.equals(frame.guard) || frame.choices != 0;
                    } else if (stage == BreakHelperStage.NORMAL) {
                        frame.branch = !frame.leftClick ? "INPUT_RELEASED" : !frame.blockRay ? "RAY_MISS"
                                : Boolean.TRUE.equals(frame.choice) ? "START" : "CONTINUE";
                        frame.unknown |= frame.beforeDelay > 0 || frame.rays != 1 || (frame.leftClick && frame.blockRay
                                ? frame.guards != 1 || !Boolean.TRUE.equals(frame.guard) || frame.choices != 1
                                : frame.guards != 0 || frame.choices != 0);
                    } else frame.unknown = true;
                }
            }
        }

        private String helperOverlap() {
            return helperCalls == 1 && helper != null && helper.exit == null && !helper.unknown
                    && !admissionUnknown && helper.choices == 1 ? "OPEN/" + helper.ordinal + "/" + (helper.choice ? "START" : "CONTINUE") : "UNKNOWN";
        }

        private String admissionSummary() {
            String pairing = admissionUnknown || preparationCalls > 1 || helperCalls > 1 ? "UNKNOWN/MULTIPLE"
                    : preparation == null || helper == null || preparation.exit == null || helper.exit == null
                    || preparation.unknown || helper.unknown || !helper.ordered ? "UNKNOWN" : callbacks == 0 ? "PARTIAL" : "ORDERED";
            return " admissionPair=" + pairing + " preparationCalls=" + preparationCalls + " helperCalls=" + helperCalls
                    + " preparation={" + (preparation == null ? "NOT_OBSERVED" : preparation.summary())
                    + "} helper={" + (helper == null ? "NOT_OBSERVED" : helper.summary()) + "}";
        }

        private void clearAdmissionInterval() {
            preparation = null; helper = null; preparationCalls = helperCalls = 0; admissionUnknown = false;
        }

        private final class NativeFrame {
            final boolean start;
            final int sequence, parent, depth, adapterTick = requestTicks;
            final long nanos = System.nanoTime();
            final String call, input, entry, gap, helperContext;
            String before = "UNKNOWN", gate = "UNKNOWN";
            float baseline = Float.NaN;
            boolean completed, nested;
            NativeFrame(boolean start, int sequence, BlockPos position, net.minecraft.core.Direction face,
                        BlockPos target, float progress, int delay, boolean destroying) {
                this.start = start; this.sequence = sequence; helperContext = helperOverlap();
                parent = outer == null ? 0 : outer.sequence; depth = outer == null ? 1 : 2;
                call = position + "/" + face;
                input = call + " inWater=" + client.player.isInWater() + " nativeY=" + client.player.getY()
                        + " attack=" + observedBot.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                        + " clientStoredHit=" + storedClientHit() + " executor=" + System.identityHashCode(executor)
                        + " path=" + System.identityHashCode(path) + " index=" + index + " movement=" + System.identityHashCode(movement)
                        + " src=" + movement.getSrc() + " dest=" + movement.getDest() + " requestNanos=" + startedNanos;
                entry = snapshot(target, progress, delay, destroying);
                gap = parent == 0 && previousReturn ? Float.toString(progress - previousProgress) : "UNKNOWN";
            }
        }

        private boolean eligible() {
            var runtime = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
            if (!config.debugLogging || !gatherDiagnosticsEligible || movementProgress == null
                    || runtime == null || runtime.isClosed() || client.player == null || client.level == null
                    || client.gameMode == null || GameApi.screen(client) != null || bot == null || lease == null
                    || cancelling || cancellationProcess != null || retreatRequest || mode == Mode.IDLE || mode == Mode.SUSPENDED
                    || pendingOwnershipFailure != null) return false;
            var currentSession = runtime.captureSession();
            var expected = expectedProcessForMode(bot, mode);
            var controlling = bot.getPathingControlManager().mostRecentInControl();
            return runtime.isCurrent(currentSession) && currentSession.world() == client.level
                    && runtime.getPrimaryBaritone() == bot && expected != null && expected.isActive()
                    && !hasForeignActiveProcess(bot, expected) && controlling.isPresent() && controlling.get() == expected;
        }

        private boolean valid() {
            if (state == State.CLOSED) return false;
            String reason = System.nanoTime() - triggeredNanos >= 20_000_000_000L ? "WALL_TIME" : null;
            if (reason == null && (!eligible() || owner != dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current()
                    || !owner.isCurrent(session) || world != client.level || player != client.player || gameMode != client.gameMode
                    || ledger != movementProgress || observedBot != bot || observedLease != lease
                    || process != expectedProcessForMode(bot, mode))) reason = "CONTEXT_INVALID";
            if (reason == null && state == State.CAPTURING && (executor != bot.getPathingBehavior().getCurrent()
                    || executor.getPath() != path || executor.getPosition() != index
                    || index >= path.movements().size() || path.movements().get(index) != movement)) reason = "MOVEMENT_ENDED";
            if (reason != null) { close(reason); return false; }
            return true;
        }

        void trigger() {
            if (spent) return;
            try {
                if (!eligible()) return;
                var current = bot.getPathingBehavior().getCurrent();
                if (current == null || current.getPosition() < 0 || current.getPosition() >= current.getPath().movements().size()
                        || !(current.getPath().movements().get(current.getPosition()) instanceof MovementAscend)
                        || !client.player.isInWater()) return;
                spent = true; state = State.WAITING; triggeredNanos = System.nanoTime();
                owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current(); session = owner.captureSession();
                world = client.level; player = client.player; gameMode = client.gameMode; ledger = movementProgress;
                observedBot = bot; observedLease = lease; process = expectedProcessForMode(bot, mode);
                emit("HEADER triggerRequestNanos=" + startedNanos + " triggerTick=" + requestTicks + " progressToken=" + progressToken
                        + " sessionGeneration=" + session.generation() + " ledger=" + System.identityHashCode(ledger)
                        + " player=" + System.identityHashCode(player) + " world=" + System.identityHashCode(world)
                        + " gameMode=" + System.identityHashCode(gameMode) + " bot=" + System.identityHashCode(observedBot)
                        + " lease=" + System.identityHashCode(observedLease) + " process=" + System.identityHashCode(process)
                        + " wait=NEXT_CALC_FINISHED_NOW_EXECUTING water=REQUIRED_AT_ADMISSION"
                        + " snapshot=target/progress/delay/isDestroying phase=UNKNOWN upstreamGuards=UNKNOWN itemComparison=UNKNOWN helperRaycast=UNKNOWN");
            } catch (RuntimeException failure) { close("DIAGNOSTIC_FAILURE"); }
        }

        void select() {
            try {
                if (!valid() || state != State.WAITING) return;
                executor = bot.getPathingBehavior().getCurrent();
                if (executor == null) { close("MISSING_EXECUTOR"); return; }
                path = executor.getPath(); index = executor.getPosition();
                if (index != 0 || path.movements().isEmpty() || !(path.movements().get(0) instanceof MovementAscend)
                        || !client.player.isInWater()) { close("NEXT_CONTEXT_INELIGIBLE"); return; }
                movement = path.movements().get(0);
                breakHelper = ((dev.lodekeeper.navigation.kernel.Baritone) observedBot).getInputOverrideHandler().getBlockBreakHelper();
                clearAdmissionInterval(); preparationOrdinal = helperOrdinal = 0; state = State.CAPTURING;
                // The path event precedes the update; the first call interval has no late-PRE boundary yet.
                partial = true;
            } catch (RuntimeException failure) { close("DIAGNOSTIC_FAILURE"); }
        }

        void coverage(TickEvent event) {
            try {
                if (!valid() || state != State.CAPTURING) return;
                if (event.getType() != TickEvent.Type.IN) { close("TICK_OUT"); return; }
                if (unmatched) { close("UNMATCHED_CALL"); return; }
                if (outer != null && !outer.completed || child != null && !child.completed) { close("INCOMPLETE_CALL"); return; }
                outer = child = null;
                if (lastCount != -1 && event.getCount() != lastCount + 1) { close("TICK_GAP"); return; }
                emit("COVERAGE nativeTick=" + event.getCount() + " state=" + event.getState() + " adapterTick=" + requestTicks
                        + " interval=CALLS_SINCE_PREVIOUS_LATE_PRE_CALLBACK previousNativeTick=" + lastCount
                        + " firstSequence=" + (intervalStart + 1) + " lastSequence=" + entries + " calls=" + (entries - intervalStart)
                        + " inWater=" + client.player.isInWater() + " nativeY=" + client.player.getY()
                        + " attack=" + observedBot.getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT)
                        + " clientStoredHit=" + storedClientHit()
                        + " executor=" + System.identityHashCode(executor) + " index=" + index + " movement=" + System.identityHashCode(movement)
                        + " src=" + movement.getSrc() + " dest=" + movement.getDest() + " coverage=" + (partial ? "PARTIAL" : "COMPLETE")
                        + admissionSummary());
                clearAdmissionInterval();
                lastCount = event.getCount(); intervalStart = entries;
                if (++callbacks >= 160) close("TICK_BUDGET");
                else if (entries >= 200) close("TRUNCATED_CALL_BUDGET");
            } catch (RuntimeException failure) { close("DIAGNOSTIC_FAILURE"); }
        }

        void nativeCall(Object controller, int stage, boolean start, boolean value, BlockPos position,
                        net.minecraft.core.Direction face, BlockPos target, float progress, int delay, boolean destroying) {
            if (!valid() || state != State.CAPTURING) return;
            if (controller != gameMode) { close("GAME_MODE_MISMATCH"); return; }
            if (stage == 0) {
                if (outer != null && outer.completed) outer = child = null;
                if (outer != null && (outer.start || !start || child != null && !child.completed)) { close("UNEXPECTED_DEPTH"); return; }
                if (outer == null && entries >= 200) return;
                int sequence = entries < 200 ? ++entries : 0;
                NativeFrame frame = new NativeFrame(start, sequence, position, face, target, progress, delay, destroying);
                if (outer == null) outer = frame;
                else { outer.nested = true; child = frame; } // Sequence zero shields an admitted parent from an unobserved child.
                return;
            }
            if (!start && child != null && child.completed) child = null;
            NativeFrame frame = child == null ? outer : child;
            if (frame == null || frame.start != start || !frame.call.equals(position + "/" + face)) { partial = unmatched = true; return; }
            if (stage == 1 && !frame.completed) {
                frame.gate = Boolean.toString(value); frame.before = snapshot(target, progress, delay, destroying); frame.baseline = progress;
                return;
            }
            if (!frame.completed) {
                finish(frame, stage == 3 ? "NOT_RUN" : "RETURN", Boolean.toString(value), snapshot(target, progress, delay, destroying), progress);
                if (frame == outer) { previousReturn = true; previousProgress = progress; }
            }
            // A canceled HEAD leaves a tombstone for a synthetic RETURN, or for the parent's next boundary.
            if (stage == 2) { if (frame == child) child = null; else outer = null; }
            if (entries >= 200 && (outer == null || outer.completed)) close("TRUNCATED_CALL_BUDGET");
        }

        private String snapshot(BlockPos target, float progress, int delay, boolean destroying) {
            return target + "/" + progress + "/" + delay + "/" + destroying;
        }

        private void finish(NativeFrame frame, String body, String result, String after, float progress) {
            frame.completed = true;
            if (frame.sequence == 0) return;
            emit("CALL sequence=" + frame.sequence + " parent=" + frame.parent + " depth=" + frame.depth
                    + " operation=" + (frame.start ? "START" : "CONTINUE") + " adapterTick=" + frame.adapterTick
                    + " entryNanos=" + frame.nanos + " exitNanos=" + System.nanoTime() + " " + frame.input
                    + " entry=" + frame.entry + " postGate=" + frame.before + " after=" + after + " gate=" + frame.gate
                    + " body=" + body + " returned=" + result + " delta=" + (progress - frame.baseline)
                    + " overlap=" + (frame.nested || frame.parent != 0 ? "NESTED" : "NONE")
                    + " gapProgress=" + frame.gap + " gapCause=UNKNOWN damageIncrement=UNKNOWN helperOverlap=" + (body.equals("INCOMPLETE") ? "UNKNOWN" : frame.helperContext));
        }

        void close(String reason) {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
            clearAdmissionInterval(); breakHelper = null; preparationOrdinal = helperOrdinal = 0;
            try {
                if (child != null && !child.completed) finish(child, "INCOMPLETE", "UNKNOWN", "UNKNOWN", Float.NaN);
                if (outer != null && !outer.completed) finish(outer, "INCOMPLETE", "UNKNOWN", "UNKNOWN", Float.NaN);
                emit("TERMINAL reason=" + reason + " entries=" + entries + " callbacks=" + callbacks
                        + " coverage=" + (partial || reason.startsWith("TRUNCATED") ? "PARTIAL" : "UNKNOWN")
                        + " callsSinceLastLatePre=" + (entries - intervalStart) + " remainder=UNKNOWN");
            } catch (RuntimeException ignored) {
            } finally {
                owner = null; session = null; world = player = gameMode = null; ledger = null;
                observedBot = null; observedLease = null; process = null; executor = null; path = null; movement = null; outer = child = null;
                partial = previousReturn = unmatched = false; triggeredNanos = 0; previousProgress = 0;
                entries = callbacks = intervalStart = index = 0; lastCount = -1;
            }
        }

        private void emit(String record) {
            try { org.slf4j.LoggerFactory.getLogger("lodekeeper").info("[Lodekeeper] WATER_BREAK {}", record); }
            catch (RuntimeException ignored) { }
        }
    }

    private void samplePath() {
        var pathing = bot.getPathingBehavior();
        var current = pathing.getCurrent();
        IPath upstream = current == null ? null : current.getPath();
        int index = current == null ? 0 : current.getPosition();
        if (config.debugLogging && client.player != null) {
            double x = client.player.getX();
            double z = client.player.getZ();
            if (!motionLogAnchorInitialized) {
                motionLogAnchorInitialized = true;
                motionLogAnchorX = x;
                motionLogAnchorZ = z;
                motionLogAnchorRequestTick = requestTicks;
            } else {
                double dx = x - motionLogAnchorX;
                double dz = z - motionLogAnchorZ;
                if (dx * dx + dz * dz >= 0.0625) {
                    motionLogAnchorX = x;
                    motionLogAnchorZ = z;
                    motionLogAnchorRequestTick = requestTicks;
                }
            }
        }
        var calculation = pathing.getInProgress();
        boolean searching = calculation.isPresent();
        IPathFinder.SearchPreview searchPreview = calculation
                .map(pathfinder -> pathfinder.searchPreview())
                .orElse(IPathFinder.SearchPreview.EMPTY);
        miningTarget = mode == Mode.MINE && upstream != null
                ? miningTarget(upstream.getGoal(), upstream.getDest(), 0) : null;
        Path path = null;
        NavigationSceneSnapshot scene = NavigationSceneSnapshot.EMPTY;
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
            var movements = upstream.movements();
            if (config.debugLogging && current != null && index >= 0 && index < movements.size()
                    && index + 1 < positions.size() && motionLogAnchorInitialized) {
                int stallTicks = requestTicks - motionLogAnchorRequestTick;
                if (stallTicks >= 80) waterBreak.trigger();
                if (stallTicks >= 80 && motionLogCount < 8
                        && (motionLogCount == 0 || requestTicks - motionLogLastRequestTick >= 80)) {
                    IMovement movement = movements.get(index);
                    var source = movement.getSrc();
                    var destination = movement.getDest();
                    boolean sourceLoaded = client.level.getChunk(
                            source.getX() >> 4, source.getZ() >> 4, ChunkStatus.FULL, false) != null;
                    boolean destinationLoaded = client.level.getChunk(
                            destination.getX() >> 4, destination.getZ() >> 4, ChunkStatus.FULL, false) != null;
                    String sourceFluid = sourceLoaded ? client.level.getFluidState(source).toString() : "unloaded";
                    String destinationFluid = destinationLoaded ? client.level.getFluidState(destination).toString() : "unloaded";
                    String destinationHead = destinationLoaded
                            ? client.level.getBlockState(destination.above()).toString() : "unloaded";
                    String destinationHead2 = destinationLoaded
                            ? client.level.getBlockState(destination.above(2)).toString() : "unloaded";
                    var player = client.player;
                    var velocity = player.getDeltaMovement();
                    var inputOverrides = bot.getInputOverrideHandler();
                    motionLogLastRequestTick = requestTicks;
                    motionLogCount++;
                    org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                            "[Lodekeeper] NAV_MOTION mode={} requestTicks={} stallTicks={} pathIndex={} pathLength={} executor={} movement={} src={} dest={} nativeXYZ=({},{},{}) blockFeet={} kernelPlayerFeet={} pose={} bbox={} velocity=({},{},{}) onGround={} horizontalCollision={} verticalCollision={} srcFluid={} destFluid={} destHead={} destHead2={} yaw={} pitch={} inWater={} underWater={} inputClass={} actualSideways={} actualForward={} actualJump={} actualSneak={} progressToken={} outputCount={} jump={} forward={} back={} sneak={} attack={} clientStoredHit={}",
                            mode, requestTicks, stallTicks, index, positions.size(), System.identityHashCode(current), movement.getClass().getSimpleName(),
                            source, destination, player.getX(), player.getY(), player.getZ(), player.blockPosition(),
                            bot.getPlayerContext().playerFeet(), player.getPose(), player.getBoundingBox(),
                            velocity.x, velocity.y, velocity.z, player.onGround(), player.horizontalCollision,
                            player.verticalCollision, sourceFluid, destinationFluid, destinationHead, destinationHead2,
                            player.getYRot(), player.getXRot(), player.isInWater(), player.isUnderWater(),
                            player.input.getClass().getSimpleName(), player.input.getMoveVector().x, player.input.getMoveVector().y,
                            player.input.keyPresses.jump(), player.input.keyPresses.shift(), progressToken,
                            output == null ? 0 : actions.count(output),
                            inputOverrides.isInputForcedDown(Input.JUMP), inputOverrides.isInputForcedDown(Input.MOVE_FORWARD),
                            inputOverrides.isInputForcedDown(Input.MOVE_BACK), inputOverrides.isInputForcedDown(Input.SNEAK),
                            inputOverrides.isInputForcedDown(Input.CLICK_LEFT), storedClientHit());
                }
            }
            int movementCount = Math.min(NavigationSceneSnapshot.MAX_MOVEMENTS,
                    Math.min(Math.max(0, size - 1), Math.max(0, movements.size() - first)));
            byte[] movementKinds = new byte[movementCount];
            ArrayList<NavigationSceneSnapshot.WorldAction> nativeActions = new ArrayList<>(NavigationSceneSnapshot.MAX_ACTIONS);
            for (int i = 0; i < movementCount; i++) {
                IMovement movement = movements.get(first + i);
                if (movement instanceof MovementParkour) movementKinds[i] = NavigationSceneSnapshot.MOVEMENT_PARKOUR;
                if (i != 0 || !(movement instanceof Movement nativeMovement)) continue;
                // The path executor's populated caches already contain its checked next targets.
                if (nativeMovement.toBreakCached != null) {
                    for (var position : nativeMovement.toBreakCached) {
                        if (nativeActions.size() == NavigationSceneSnapshot.MAX_ACTIONS) break;
                        nativeActions.add(new NavigationSceneSnapshot.WorldAction(
                                dev.lodekeeper.nav.Position.pack(position.getX(), position.getY(), position.getZ()),
                                NavigationSceneSnapshot.ActionKind.BREAK,
                                NavigationSceneSnapshot.ActionEvidence.PLANNED_NATIVE));
                    }
                }
                if (nativeMovement.toPlaceCached != null) {
                    for (var position : nativeMovement.toPlaceCached) {
                        if (nativeActions.size() == NavigationSceneSnapshot.MAX_ACTIONS) break;
                        nativeActions.add(new NavigationSceneSnapshot.WorldAction(
                                dev.lodekeeper.nav.Position.pack(position.getX(), position.getY(), position.getZ()),
                                NavigationSceneSnapshot.ActionKind.PLACE,
                                NavigationSceneSnapshot.ActionEvidence.PLANNED_NATIVE));
                    }
                }
            }
            scene = new NavigationSceneSnapshot(movementKinds,
                    nativeActions.toArray(NavigationSceneSnapshot.WorldAction[]::new),
                    new NavigationSceneSnapshot.Marker[0]);
        }
        int searchNodeCount = searchPreview.nodeCount();
        long[] nodePositions = new long[searchNodeCount];
        byte[] nodeFractions = new byte[searchNodeCount];
        boolean[] nodeClosed = new boolean[searchNodeCount];
        for (int i = 0; i < searchNodeCount; i++) {
            nodePositions[i] = dev.lodekeeper.nav.Position.pack(
                    searchPreview.nodeX(i), searchPreview.nodeY(i), searchPreview.nodeZ(i));
            nodeClosed[i] = !searchPreview.nodeIsOpen(i);
        }
        observation = new NavigationSnapshot(path, 0, searchPreview.expandedNodes(),
                searchPreview.discoveredNodes(), searchPreview.frontierSize(), System.nanoTime() - startedNanos,
                requestTicks, failedCalculations, searching, nodePositions, nodeFractions, nodeClosed, scene);
    }

    private BlockPos miningTarget(dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal goal, BlockPos destination, int depth) {
        if (depth > 2 || goal == null || !goal.isInGoal(destination)) return null;
        if (goal instanceof GoalComposite composite) {
            for (var child : composite.goals()) {
                BlockPos result = miningTarget(child, destination, depth + 1);
                if (result != null) return result;
            }
        } else if (goal instanceof IGoalRenderPos rendered) {
            BlockPos position = rendered.getGoalPos();
            if (client.level.getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) == null) return null;
            for (int dy : new int[]{0, 1, -1, 2}) {
                BlockPos candidate = position.offset(0, dy, 0);
                if (Arrays.asList(mineBlocks).contains(client.level.getBlockState(candidate).getBlock())) return candidate.immutable();
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
        boolean isCurrent() {
            return assigned.entrySet().stream().allMatch(entry -> Objects.equals(entry.getKey().value, entry.getValue()));
        }
        void restore() { originals.forEach((setting, value) -> restoreOne(setting, value)); }
        @SuppressWarnings("unchecked") private <T> void restoreOne(Settings.Setting<T> setting, Object value) {
            if (Objects.equals(setting.value, assigned.get(setting))) setting.value = (T) value;
        }
    }
}
