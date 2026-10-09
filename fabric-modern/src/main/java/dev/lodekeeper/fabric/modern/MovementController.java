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
    private static final int AIR_PLANNING_MAX_WORK = 128;
    private static final int AIR_PLANNING_MAX_REANCHORS = 8;
    private static final int AIR_SWIM_MAX_VISITED = 4_096;
    private static final int AIR_SWIM_MAX_PATH = 64;
    private static final int AIR_SWIM_MAX_OBSTACLE_INSPECTIONS = 128;
    private static final int AIR_SWIM_MAX_COLLISION_SHAPES = 128;
    private static final double AIR_SWIM_VEHICLE_PUSH_REACH = .20000000298023224;
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
    private record AirSwimObstacle(net.minecraft.world.phys.AABB hardBounds, net.minecraft.world.phys.AABB pushBounds) { }
    private record AirSwimObstacles(List<AirSwimObstacle> bodies, net.minecraft.world.phys.AABB coverage,
                                    Set<Long> loadedChunks, boolean complete) {
        AirSwimObstacles { bodies = List.copyOf(bodies); loadedChunks = Set.copyOf(loadedChunks); }

        boolean covers(net.minecraft.world.phys.AABB body) {
            var query = body.inflate(AIR_SWIM_VEHICLE_PUSH_REACH, 0, AIR_SWIM_VEHICLE_PUSH_REACH);
            if (query.minX < coverage.minX || query.minY < coverage.minY || query.minZ < coverage.minZ
                    || query.maxX > coverage.maxX || query.maxY > coverage.maxY || query.maxZ > coverage.maxZ) return false;
            // Native block collision iteration also reads neighboring block cells.
            var blocks = body.inflate(1, 0, 1);
            for (int x = ((int) Math.floor(blocks.minX)) >> 4; x <= ((int) Math.floor(blocks.maxX)) >> 4; x++)
                for (int z = ((int) Math.floor(blocks.minZ)) >> 4; z <= ((int) Math.floor(blocks.maxZ)) >> 4; z++)
                    if (!loadedChunks.contains(((long) x << 32) | (z & 0xffffffffL))) return false;
            return true;
        }

        boolean hardClear(net.minecraft.world.phys.AABB body) {
            if (!covers(body)) return false;
            for (var obstacle : bodies)
                if (obstacle.hardBounds() != null && obstacle.hardBounds().intersects(body)) return false;
            return true;
        }

        boolean softOverlap(net.minecraft.world.phys.AABB body) {
            for (var obstacle : bodies)
                if (obstacle.pushBounds() != null && obstacle.pushBounds().intersects(body)) return true;
            return false;
        }

        boolean clear(net.minecraft.world.phys.AABB body) { return hardClear(body) && !softOverlap(body); }

        boolean edgeClear(net.minecraft.world.phys.AABB from, net.minecraft.world.phys.AABB to, net.minecraft.world.phys.AABB swept, boolean separatingStart) {
            if (!hardClear(swept)) return false;
            for (var obstacle : bodies) {
                var push = obstacle.pushBounds();
                if (push != null && push.intersects(swept)
                        && !(separatingStart && push.intersects(from) && separates(from, to, push))) return false;
            }
            return true;
        }

        private static boolean separates(net.minecraft.world.phys.AABB from, net.minecraft.world.phys.AABB to, net.minecraft.world.phys.AABB obstacle) {
            if (obstacle.intersects(to)) return false;
            double dx = to.minX - from.minX, dz = to.minZ - from.minZ;
            // Each moving horizontal axis must point away from the overlapping body's center.
            // Its interval penetration therefore never increases; at least one axis must fully exit.
            if (dx * (from.minX + from.maxX - obstacle.minX - obstacle.maxX) < 0
                    || dz * (from.minZ + from.maxZ - obstacle.minZ - obstacle.maxZ) < 0) return false;
            return dx < 0 && to.maxX <= obstacle.minX || dx > 0 && to.minX >= obstacle.maxX
                    || dz < 0 && to.maxZ <= obstacle.minZ || dz > 0 && to.minZ >= obstacle.maxZ;
        }
    }
    private static final class AirSwimCoverageFailure extends IllegalStateException {
        AirSwimCoverageFailure(String reason) { super("Air swim obstacle coverage is unavailable: " + reason); }
    }

    private static final Comparator<AirRecoveryCandidate> AIR_RECOVERY_CANDIDATE_ORDER =
            Comparator.comparingInt(AirRecoveryCandidate::score)
                    .thenComparingInt(AirRecoveryCandidate::distanceSquared)
                    .thenComparing(candidate -> !candidate.supported())
                    .thenComparingInt(candidate -> -candidate.position().getY())
                    .thenComparingInt(candidate -> candidate.position().getX())
                    .thenComparingInt(candidate -> candidate.position().getZ());

    private enum RetreatDecision {
        WORLD_HEIGHT, PRIOR_GOAL, ORIGIN_DISTANCE, NO_FULL_CHUNK, UNSAFE_SUPPORT, FALLING_ABOVE,
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
        private int visitedColumns, passedColumns, extraColumnVisits, extraPassedColumnVisits, reachedLayer;
        private int completedCandidates, sampleCount;
        private RetreatStop stop = RetreatStop.OFFSETS_EXHAUSTED;

        RetreatSelectionDiagnostics(BlockPos center, BlockPos origin, int radius, int totalColumns,
                                    int hazards, int maximumClearance) {
            request = new int[] {center.getX(), center.getY(), center.getZ(), origin.getX(), origin.getY(),
                    origin.getZ(), radius, totalColumns, hazards, maximumClearance};
        }

        void column(boolean passed, int layer) {
            if (layer > 2) {
                extraColumnVisits++;
                if (passed) extraPassedColumnVisits++;
                return;
            }
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
            if (candidate == null) {
                sample[5] = -1;
                return;
            }
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
                        .append(" radius=").append(request[6]).append(" baseHeights=[0,1,-1,2,-2]")
                        .append(" extraLayers=3..").append(request[6]).append(" extraOrder=RANKED_OFFSETS_THEN_[+k,-k]")
                        .append(" fallbackEntered=").append(reachedLayer > 0).append(" reachedLayer=").append(reachedLayer)
                        .append(" hazards=").append(request[8])
                        .append(" maximumClearance=").append(request[9]).append(" totalColumns=").append(request[7])
                        .append(" visitedColumns=").append(visitedColumns).append(" clearancePassedColumns=").append(passedColumns)
                        .append(" clearanceRejectedColumns=").append(visitedColumns - passedColumns)
                        .append(" columnPolicy=DISTINCT_BASE extraColumnVisits=").append(extraColumnVisits)
                        .append(" extraClearancePassedVisits=").append(extraPassedColumnVisits)
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
                    if (sample[5] == -1) {
                        text.append("{pos=OUT_OF_WORLD decision=").append(decisions[i]).append("},");
                        continue;
                    }
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

    enum InputLeaseState { ACTIVE, RESTORING, LOST }

    static final class InputLease {
        private final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner;
        private final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session;
        private final Object player, world;
        private final IBaritone bot;
        private final SettingsLease settings;
        private final Mode kind;
        private final IBaritoneProcess process;
        private final dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal goal;
        private final Predicate<Entity> filter;
        private InputLease(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                           dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session,
                           Object player, Object world, IBaritone bot, SettingsLease settings,
                           Mode kind, IBaritoneProcess process,
                           dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal goal, Predicate<Entity> filter) {
            this.owner = owner; this.session = session; this.player = player; this.world = world;
            this.bot = bot; this.settings = settings; this.kind = kind; this.process = process;
            this.goal = goal; this.filter = filter;
        }
    }

    enum RouteEffects { CONFIGURED, MOVEMENT_ONLY }
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
    private RouteEffects routeEffects = RouteEffects.CONFIGURED;
    private boolean cancelling, followCancellationPending, retreatRequest, defenseSettlingPending;
    private List<RetreatThreat> retreatHazards = List.of();
    private RetreatPrefixRejection retreatPrefixRejection;
    private RuntimeException pendingRetreatPrefixFailure;
    private dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal routeGoal;
    private dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal airRecoveryGoal;
    private BlockPos lastAirRecoveryDestination;
    private BlockPos airSwimOrigin;
    private BlockPos airSwimSeparationStep;
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
    private int followRadius = 1;
    private dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal preparedTravelGoal;
    private Object preparedTravelWorld, preparedTravelPlayer;
    private dev.lodekeeper.navigation.kernel.OwnedKernelRuntime preparedTravelOwner;
    private dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session preparedTravelSession;
    private java.util.function.BooleanSupplier travelEffectAuthority;
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
    private int descentMotionLogLastRequestTick, descentMotionLogCount;
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

    InputLease captureCropInputLease(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                                    dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session,
                                    Object provenanceSession, boolean pickup) {
        if (routeEffects != RouteEffects.MOVEMENT_ONLY || mode != (pickup ? Mode.PICKUP : Mode.MOVE)) return null;
        if (pickup && (pickupTarget == null || pickupTarget.ownerSession() != provenanceSession
                || pickupTarget.ownerPlayer() != client.player || pickupTarget.ownerWorld() != client.level)) return null;
        return captureInputLease(owner, session);
    }

    InputLease captureAirInputLease(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                                   dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session) {
        return mode == Mode.AIR ? captureInputLease(owner, session) : null;
    }

    boolean manualAirInputQuiescent(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                                    dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session) {
        if (owner == null || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != owner
                || !owner.isCurrent(session) || session.world() != client.level || client.player == null
                || pendingOwnershipFailure != null || mode != Mode.IDLE || resumeMode != Mode.IDLE || lease != null
                || cancelling || cancellationProcess != null || followCancellationPending || airRecoveryCancellationPending) return false;
        var activeBot = owner.getPrimaryBaritone();
        return activeBot != null && (bot == null || bot == activeBot)
                && activeBot.getPlayerContext().player() == client.player
                && activeBot.getPlayerContext().world() == client.level && actions.cropNativeQuiescent(owner);
    }

    private InputLease captureInputLease(dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner,
                                        dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session) {
        try {
            if (owner == null || lease == null || bot == null || client.player == null || client.level == null
                    || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != owner
                    || !owner.isCurrent(session) || session.world() != client.level || owner.getPrimaryBaritone() != bot
                    || bot.getPlayerContext().player() != client.player || bot.getPlayerContext().world() != client.level
                    || cancelling || mode != Mode.MOVE && mode != Mode.PICKUP && mode != Mode.AIR) return null;
            var expected = expectedProcessForMode(bot, mode);
            var goal = mode == Mode.AIR ? airRecoveryGoal : mode == Mode.MOVE ? routeGoal : null;
            InputLease captured = new InputLease(owner, session, client.player, client.level, bot, lease,
                    mode, expected, goal, mode == Mode.PICKUP ? followFilter : null);
            return observeInputLease(captured) == InputLeaseState.ACTIVE ? captured : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    InputLeaseState observeInputLease(InputLease captured) {
        if (captured == null || pendingOwnershipFailure != null || captured.owner == null
                || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != captured.owner
                || !captured.owner.isCurrent(captured.session) || captured.session.world() != captured.world
                || client.player != captured.player || client.level != captured.world || bot != captured.bot
                || captured.owner.getPrimaryBaritone() != bot || bot.getPlayerContext().player() != captured.player
                || bot.getPlayerContext().world() != captured.world || captured.settings == null
                || captured.process == null || lease != null && lease != captured.settings
                || cancellationProcess != null && cancellationProcess != captured.process
                || hasForeignInputProcess(bot, captured.process)) return InputLeaseState.LOST;
        var recent = bot.getPathingControlManager().mostRecentInControl();
        if (recent.isPresent() && recent.get() != captured.process && inputProcessActive(bot, recent.get())) return InputLeaseState.LOST;
        if (captured.kind == Mode.PICKUP) {
            var currentFilter = bot.getFollowProcess().currentFilter();
            if (captured.filter == null || currentFilter != null && currentFilter != captured.filter) return InputLeaseState.LOST;
        } else if (!matchesOwnedCustomGoal(bot.getCustomGoalProcess(), captured.goal)) return InputLeaseState.LOST;
        Mode current = mode == Mode.SUSPENDED ? resumeMode : mode;
        if (lease == captured.settings && current == captured.kind && !cancelling) {
            if (captured.kind != Mode.AIR && routeEffects != RouteEffects.MOVEMENT_ONLY) return InputLeaseState.LOST;
            return InputLeaseState.ACTIVE;
        }
        if ((mode == Mode.IDLE && resumeMode == Mode.IDLE || mode == Mode.SUSPENDED && resumeMode == captured.kind)
                && (lease == null || lease == captured.settings)) return InputLeaseState.RESTORING;
        return InputLeaseState.LOST;
    }

    private boolean hasForeignInputProcess(IBaritone activeBot, IBaritoneProcess expected) {
        IBaritoneProcess[] processes = {
                activeBot.getCustomGoalProcess(), activeBot.getMineProcess(), activeBot.getFollowProcess(),
                activeBot.getBuilderProcess(), activeBot.getExploreProcess(), activeBot.getFarmProcess(),
                activeBot.getGetToBlockProcess(), activeBot.getElytraProcess()
        };
        for (var process : processes) if (process != expected && inputProcessActive(activeBot, process)) return true;
        return false;
    }
    private boolean inputProcessActive(IBaritone activeBot, IBaritoneProcess process) {
        // Follow.isActive scans and writes its entity cache. A stored filter is a conservative read-only witness.
        if (process == activeBot.getFollowProcess()) return activeBot.getFollowProcess().currentFilter() != null;
        if (process == activeBot.getCustomGoalProcess() || process == activeBot.getMineProcess()
                || process == activeBot.getBuilderProcess() || process == activeBot.getExploreProcess()
                || process == activeBot.getFarmProcess() || process == activeBot.getGetToBlockProcess()
                || process == activeBot.getElytraProcess()) return process.isActive();
        return true;
    }

    void updateProtection(Set<Item> reserved, Set<Block> protectedBlocks) {
        if (routeEffects == RouteEffects.MOVEMENT_ONLY && lease != null) checkAirRecoveryOwnership();
        this.reserved = Set.copyOf(reserved);
        this.protectedBlocks = Set.copyOf(protectedBlocks);
        if (lease != null) applyProtection();
    }

    void startCoordinate(BlockPos target, RouteEffects effects, java.util.function.BooleanSupplier authority) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(effects);
        Objects.requireNonNull(authority);
        if (!authority.getAsBoolean()) throw new NavigationFailure("Travel authority expired before preparation");
        if (!GameApi.supportsTravel() || effects != RouteEffects.MOVEMENT_ONLY)
            throw new NavigationFailure("Coordinate travel requires the movement-only profile");
        checkAirRecoveryOwnership();
        prepare();
        routeEffects = effects;
        travelEffectAuthority = authority;
        routeGoal = new GoalBlock(target);
        preparedTravelGoal = routeGoal;
        preparedTravelWorld = client.level;
        preparedTravelPlayer = client.player;
        preparedTravelOwner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        preparedTravelSession = preparedTravelOwner.captureSession();
        diagnosticGoal = dev.lodekeeper.nav.Goal.exact16(target.getX(), target.getY() * 16, target.getZ());
        mode = Mode.MOVE;
        launch();
    }

    void startExploration(ExplorationFrontier.Waypoint waypoint, RouteEffects effects, java.util.function.BooleanSupplier authority) {
        Objects.requireNonNull(waypoint);
        startCoordinate(new BlockPos(waypoint.x(), waypoint.y(), waypoint.z()), effects, authority);
        diagnosticGoal = dev.lodekeeper.nav.Goal.exact16(waypoint.x(), Math.toIntExact(waypoint.feetY16()), waypoint.z());
    }

    void startFollowingPlayer(UUID id, net.minecraft.world.entity.Entity target, RouteEffects effects, java.util.function.BooleanSupplier authority) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(effects);
        Objects.requireNonNull(authority);
        if (!authority.getAsBoolean()) throw new NavigationFailure("Travel authority expired before preparation");
        if (!GameApi.supportsTravel() || target == null || !id.equals(target.getUUID()) || target.level() != client.level
                || !target.isAlive() || target.isRemoved() || effects != RouteEffects.MOVEMENT_ONLY)
            throw new NavigationFailure("Player follow requires one live pinned player and the movement-only profile");
        checkAirRecoveryOwnership();
        prepare();
        routeEffects = effects;
        travelEffectAuthority = authority;
        followTargetId = id;
        followOwnerWorld = client.level;
        followOwnerPlayer = client.player;
        followRadius = 2;
        var sessionOwner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var session = sessionOwner.captureSession();
        preparedTravelGoal = null;
        preparedTravelWorld = client.level; preparedTravelPlayer = client.player;
        preparedTravelOwner = sessionOwner; preparedTravelSession = session;
        var network = client.getConnection();
        var connection = network == null ? null : network.getConnection();
        followFilter = entity -> mode == Mode.FOLLOW && entity == target && id.equals(entity.getUUID())
                && entity.level() == followOwnerWorld && entity.isAlive() && !entity.isRemoved()
                && client.level == followOwnerWorld && client.player == followOwnerPlayer
                && dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() == sessionOwner
                && sessionOwner.isCurrent(session) && session.world() == followOwnerWorld
                && network != null && client.getConnection() == network && network.getConnection() == connection
                && connection != null && connection.isConnected()
                && Double.isFinite(entity.distanceToSqr(client.player))
                && entity.distanceToSqr(client.player) <= 4096;
        mode = Mode.FOLLOW;
        launch();
    }

    void abandonRequestContext() { releaseLostAirRecoveryOwnership(); bot = null; }

    boolean followGoalSettled(Entity target, UUID targetId) {
        if (mode != Mode.FOLLOW || routeEffects != RouteEffects.MOVEMENT_ONLY
                || lease == null || cancelling || followCancellationPending || target == null || targetId == null
                || !targetId.equals(followTargetId) || followFilter == null || bot == null
                || client.player == null || client.level == null
                || client.player != followOwnerPlayer || client.level != followOwnerWorld
                || target.level() != followOwnerWorld || !targetId.equals(target.getUUID())
                || !target.isAlive() || target.isRemoved()
                || bot.getPlayerContext().player() != client.player
                || bot.getPlayerContext().world() != client.level
                || OwnedKernelAPI.getSettings().followRadius.value != followRadius
                || OwnedKernelAPI.getSettings().followOffsetDistance.value != 0.0
                || bot.getPathingControlManager().mostRecentInControl().orElse(null) != bot.getFollowProcess()
                || bot.getFollowProcess().currentFilter() != followFilter
                || !followFilter.test(target)) return false;
        var following = bot.getFollowProcess().following();
        if (following == null || following.size() != 1 || following.get(0) != target) return false;
        var nativeGoal = bot.getPathingBehavior().getGoal();
        var feet = bot.getPlayerContext().playerFeet();
        BlockPos targetFeet = target.blockPosition();
        GoalNear acceptedGoal = new GoalNear(targetFeet, followRadius);
        return nativeGoal != null && nativeGoal.isInGoal(feet)
                && acceptedGoal.isInGoal(feet.getX(), feet.getY(), feet.getZ());
    }

    boolean travelReleased() {
        return mode == Mode.IDLE && resumeMode == Mode.IDLE && !cancelling && lease == null
                && !followCancellationPending && cancellationProcess == null && !airRecoveryCancellationPending;
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
        search: for (int layer = 2; layer <= radius; layer++) {
            if (layer > 2) {
                if (candidates >= 4_096) {
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
                heights = new int[] {layer, -layer};
                if (trace != null) trace.reachedLayer = layer;
            }
            for (BlockPos offset : offsets) {
                if (layer > 2 && System.nanoTime() - searchStarted >= 8_000_000L) {
                    if (trace != null) trace.stop = RetreatStop.TIME_LIMIT;
                    break search;
                }
                int x = center.getX() + offset.getX(), z = center.getZ() + offset.getZ();
                if (retreatClearanceMargin(x, z, threats) < 0.0) {
                    if (trace != null) trace.column(false, layer);
                    continue;
                }
                if (trace != null) trace.column(true, layer);
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
                    BlockPos candidate;
                    if (layer == 2) candidate = new BlockPos(x, center.getY() + dy, z);
                    else {
                        long candidateY = (long) center.getY() + dy;
                        if (candidateY - 1 < client.level.getMinY() || candidateY + 2 > client.level.getMaxY()) {
                            if (trace != null) trace.complete(RetreatDecision.WORLD_HEIGHT, null, null, null, null, null);
                            continue;
                        }
                        candidate = new BlockPos(x, (int) candidateY, z);
                    }
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
            if (!goals.isEmpty()) break search;
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
    enum AirPlanKind { SWIM, NATIVE }
    sealed interface AirPlanResult permits AirPlanPending, AirPlanReady, AirPlanRefused { }
    record AirPlanPending(String stage, int probes, int slices, int work) implements AirPlanResult { }
    record AirPlanReady(AirPlanKind kind, List<BlockPos> positions) implements AirPlanResult {
        AirPlanReady { positions = List.copyOf(positions); }
    }
    record AirPlanRefused(String reason) implements AirPlanResult { }

    // A slice limits cooperative observation work; native calls themselves cannot be preempted.
    private static final class AirPlanningYield extends RuntimeException {
        AirPlanningYield() { super(null, null, false, false); }
    }
    private static final class AirPlanningSlice {
        private final long deadline;
        private int work;
        AirPlanningSlice(long deadline) { this.deadline = deadline; }
        void check() { if (System.nanoTime() > deadline) throw new AirPlanningYield(); }
        void observation() {
            check();
            if (work >= AIR_PLANNING_MAX_WORK) throw new AirPlanningYield();
            work++;
        }
    }
    private record AirBodyKey(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        AirBodyKey(net.minecraft.world.phys.AABB box) { this(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ); }
    }
    private record AirCellShape(boolean empty, boolean full) { }
    private AirPlanning airPlanning;

    boolean beginAirPlanning(boolean swim, Set<BlockPos> rejectedGoals, AirExitPreference preference, long deadline) {
        if (System.nanoTime() > deadline) return false;
        Objects.requireNonNull(rejectedGoals); Objects.requireNonNull(preference);
        if (rejectedGoals.size() > 32) throw new IllegalArgumentException("Air recovery rejection seed exceeds 32 positions");
        requireAirPlanningIdle();
        discardAirPlanning();
        airPlanning = new AirPlanning(swim, Set.copyOf(rejectedGoals), preference);
        return true;
    }

    void discardAirPlanning() { airPlanning = null; airSwimOrigin = airSwimSeparationStep = null; }

    private void requireAirPlanningIdle() {
        if (!client.isSameThread()) throw new NavigationFailure("Air planning requires the client thread");
        checkAirRecoveryOwnership();
        if (client.player == null || client.level == null) throw new NavigationFailure("World unavailable");
        IBaritone activeBot = bot == null ? OwnedKernelAPI.getProvider().getPrimaryBaritone() : bot;
        var pathing = activeBot.getPathingBehavior();
        if (mode != Mode.IDLE || resumeMode != Mode.IDLE || cancelling || cancellationProcess != null
                || airRecoveryCancellationPending || lease != null || pathing.hasPath() || pathing.isPathing()
                || pathing.getInProgress().isPresent())
            throw new NavigationFailure(NavigationFailure.Kind.PROCESS_ENDED,
                    "Native movement cancellation must finish before air planning");
    }

    AirPlanResult pollAirPlanning(long deadline) {
        AirPlanning plan = airPlanning;
        if (plan == null) return new AirPlanRefused("Air planning has no retained owner");
        AirPlanningSlice slice = new AirPlanningSlice(deadline);
        plan.slices++;
        try {
            slice.observation();
            requireAirPlanningIdle();
            slice.check();
            plan.requireCurrent(slice);
            AirPlanResult result = plan.advance(slice);
            slice.observation();
            checkAirRecoveryOwnership();
            plan.requireCurrent(slice);
            plan.diagnostic(slice, "admission");
            slice.check();
            if (result instanceof AirPlanReady ready && ready.kind() == AirPlanKind.NATIVE) {
                // Prepare without taking a settings lease, then repeat fresh admission if it used this slice.
                if (!plan.prepared) {
                    prepare(); plan.prepared = true;
                    slice.check();
                    plan.requireCurrent(slice);
                    plan.admitNative(slice);
                }
                slice.observation();
                checkAirRecoveryOwnership();
                plan.requireCurrent(slice);
                var admittedGoal = new GoalComposite(ready.positions().stream().map(GoalBlock::new)
                        .toArray(dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal[]::new));
                slice.check();
                airRecoveryGoal = admittedGoal;
                lastAirRecoveryDestination = null;
                routeGoal = airRecoveryGoal; diagnosticGoal = null; mode = Mode.AIR;
                // Commit follows the final check; elapsed time after effects begin is never converted to Pending.
                launch();
            }
            if (result instanceof AirPlanReady ready && ready.kind() == AirPlanKind.SWIM) {
                airSwimOrigin = plan.origin;
                airSwimSeparationStep = plan.swim.startingSoftOverlap ? ready.positions().get(0) : null;
            }
            if (result instanceof AirPlanReady) airPlanning = null;
            return result;
        } catch (AirPlanningYield elapsed) {
            plan.diagnostic(slice, "yield");
            return new AirPlanPending(plan.stage(), plan.probes(), plan.slices, slice.work);
        } catch (AirSwimCoverageFailure refused) {
            discardAirPlanning(); plan.diagnostic(slice, "refused");
            return new AirPlanRefused(refused.getMessage());
        } catch (RuntimeException failed) {
            discardAirPlanning();
            throw failed;
        }
    }

    private final class AirPlanning {
        private final Object player = client.player, world = client.level, network = client.getConnection();
        private final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        private final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session = owner == null ? null : owner.captureSession();
        private final long policyGeneration = owner == null ? -1 : owner.policyGeneration();
        private final BlockPos episodeOrigin = client.player.blockPosition().immutable();
        private final net.minecraft.world.phys.AABB authorityBounds = client.player.getBoundingBox();
        private final Object pose = client.player.getPose();
        private final double episodeX = client.player.getX(), episodeY = client.player.getY(), episodeZ = client.player.getZ();
        private final net.minecraft.world.phys.AABB episodeCoverage = authorityBounds.move(episodeOrigin.getX() - 12 + .5 - episodeX,
                episodeOrigin.getY() - 1 - episodeY, episodeOrigin.getZ() - 12 + .5 - episodeZ).expandTowards(24, 17, 24);
        private BlockPos origin = episodeOrigin;
        private net.minecraft.world.phys.AABB bounds = authorityBounds;
        private double x = episodeX, y = episodeY, z = episodeZ;
        private final boolean manual;
        private final Set<BlockPos> rejected;
        private final AirExitPreference preference;
        private AirSwimSearch swim;
        private AirRecoverySearch nativeSearch;
        private AirSwimObstacles obstacles;
        // These complete observations are forecasts only and never satisfy final admission or LIVE checks.
        private final Map<BlockPos, BlockState> states = new HashMap<>();
        private final Map<BlockPos, AirCellShape> shapes = new HashMap<>();
        private final Map<AirBodyKey, Boolean> blockBodies = new HashMap<>();
        private List<BlockPos> route;
        private BlockPos endpoint;
        private boolean prepared;
        private int slices, diagnosticSamples, reanchors, retiredProbes, retiredVisited;
        private String reanchorReason = "none", previousAnchor = "none";
        private long diagnosticAt;

        AirPlanning(boolean manual, Set<BlockPos> rejected, AirExitPreference preference) {
            this.manual = manual; this.rejected = rejected; this.preference = preference;
            if (manual) swim = new AirSwimSearch(this, origin);
            else nativeSearch = new AirRecoverySearch(this, rejected, preference);
        }

        private boolean authorityCurrent() {
            if (client.player != player || client.level != world || client.getConnection() != network
                    || owner == null || dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current() != owner
                    || !owner.isCurrent(session) || session.world() != world || owner.policyGeneration() != policyGeneration
                    || !Objects.equals(pose, client.player.getPose())) return false;
            var actual = client.player.getBoundingBox();
            // Only bounding-box subtraction rounding is tolerated; collision checks use the exact actual body.
            return Math.abs((actual.maxX - actual.minX) - (authorityBounds.maxX - authorityBounds.minX)) <= 1e-9
                    && Math.abs((actual.maxY - actual.minY) - (authorityBounds.maxY - authorityBounds.minY)) <= 1e-9
                    && Math.abs((actual.maxZ - actual.minZ) - (authorityBounds.maxZ - authorityBounds.minZ)) <= 1e-9;
        }

        void requireCurrent(AirPlanningSlice slice) {
            slice.check();
            if (!authorityCurrent()) throw new AirSwimCoverageFailure("retained planning authority, policy, pose or body changed");
            BlockPos actualOrigin = client.player.blockPosition();
            double actualX = client.player.getX(), actualY = client.player.getY(), actualZ = client.player.getZ();
            double horizontalX = actualX - episodeX, horizontalZ = actualZ - episodeZ;
            if (actualOrigin.getX() != episodeOrigin.getX() || actualOrigin.getZ() != episodeOrigin.getZ()
                    || horizontalX * horizontalX + horizontalZ * horizontalZ > .0625
                    || !withinDomain(actualOrigin) || !withinDomain(client.player.getBoundingBox()))
                throw new AirSwimCoverageFailure("retained planning position left its original horizontal limit or domain");
            double dx = actualX - x, dy = actualY - y, dz = actualZ - z;
            if (dx * dx + dz * dz > .0625)
                throw new AirSwimCoverageFailure("retained planning horizontal anchor limit changed");
            if (actualOrigin.equals(origin) && dx * dx + dy * dy + dz * dz <= .0625) return;

            // A new positional forecast stays under this owner's original authority and cumulative budgets.
            slice.observation();
            requireAirPlanningIdle();
            slice.check();
            if (!authorityCurrent()) throw new AirSwimCoverageFailure("planning authority changed before reanchor");
            if (reanchors >= AIR_PLANNING_MAX_REANCHORS) throw new AirSwimCoverageFailure("planning position reanchor limit");
            reanchorReason = actualOrigin.equals(origin) ? "vertical-drift" : "vertical-cell";
            previousAnchor = x + "," + y + "," + z;
            retiredProbes = probes(); retiredVisited = visited(); reanchors++;
            origin = actualOrigin.immutable(); bounds = client.player.getBoundingBox();
            x = actualX; y = actualY; z = actualZ;
            obstacles = null; states.clear(); shapes.clear(); blockBodies.clear();
            route = null; endpoint = null; prepared = false;
            airSwimOrigin = airSwimSeparationStep = null;
            swim = manual ? new AirSwimSearch(this, origin) : null;
            nativeSearch = manual ? null : new AirRecoverySearch(this, rejected, preference);
            diagnostic(slice, "reanchor");
            // Never advance or admit movement in a reanchor tick, even if the slice has time left.
            throw new AirPlanningYield();
        }

        boolean withinDomain(BlockPos position) {
            return withinAirSwimBounds(position, episodeOrigin);
        }

        private boolean withinDomain(net.minecraft.world.phys.AABB body) {
            return finiteAirSwimBox(body) && body.minX >= episodeCoverage.minX && body.minY >= episodeCoverage.minY
                    && body.minZ >= episodeCoverage.minZ && body.maxX <= episodeCoverage.maxX
                    && body.maxY <= episodeCoverage.maxY && body.maxZ <= episodeCoverage.maxZ;
        }

        AirPlanResult advance(AirPlanningSlice slice) {
            slice.check();
            if (obstacles == null) {
                slice.observation();
                obstacles = airSwimObstacles(episodeCoverage, slice.deadline, true);
                slice.check();
            }
            if (swim != null && route == null) {
                route = swim.advance(slice);
                slice.check();
            }
            if (route != null && !route.isEmpty()) {
                slice.observation();
                if (!observeAirSwimStep(route.get(0), slice, origin,
                        swim.startingSoftOverlap ? route.get(0) : null)) {
                    slice.check();
                    throw new AirSwimCoverageFailure("fresh first swim edge is obstructed or uncovered");
                }
                slice.check();
                return new AirPlanReady(AirPlanKind.SWIM, route);
            }
            if (nativeSearch == null) {
                slice.check();
                nativeSearch = new AirRecoverySearch(this, rejected, preference);
                slice.check();
            }
            if (endpoint == null) {
                endpoint = nativeSearch.advance(slice);
                slice.check();
            }
            admitNative(slice);
            return new AirPlanReady(AirPlanKind.NATIVE, List.of(endpoint));
        }

        void admitNative(AirPlanningSlice slice) {
            slice.observation();
            var actual = client.player.getBoundingBox();
            var destination = actual.move(endpoint.getX() + .5 - client.player.getX(),
                    endpoint.getY() - client.player.getY(), endpoint.getZ() + .5 - client.player.getZ());
            if (!withinDomain(actual) || !withinDomain(destination))
                throw new AirSwimCoverageFailure("fresh native AIR body or endpoint left the original domain");
            // A complete fresh query for each offered endpoint and current body; native full-path clearance is unproved.
            var fresh = airSwimObstacles(actual, slice.deadline);
            boolean currentClear = fresh.clear(actual) && airSwimBlockClear(actual, slice.deadline, fresh);
            slice.check();
            if (!currentClear) throw new AirSwimCoverageFailure("fresh native AIR starting body is obstructed or uncovered");
            slice.observation();
            fresh = airSwimObstacles(destination, slice.deadline);
            boolean endpointClear = fresh.clear(destination) && airSwimBlockClear(destination, slice.deadline, fresh)
                    && nativeSearch.freshEndpoint(endpoint, slice);
            slice.check();
            if (!endpointClear) throw new AirSwimCoverageFailure("fresh native AIR endpoint is obstructed or uncovered");
        }

        BlockState state(BlockPos position, AirPlanningSlice slice) {
            BlockState result = states.get(position);
            if (result == null) {
                slice.observation();
                result = client.level.getBlockState(position);
                states.put(position.immutable(), result);
                slice.check();
            }
            return result;
        }

        AirCellShape shape(BlockPos position, AirPlanningSlice slice) {
            AirCellShape result = shapes.get(position);
            if (result == null) {
                BlockState state = state(position, slice);
                slice.observation();
                var shape = state.getCollisionShape(client.level, position);
                result = new AirCellShape(shape.isEmpty(), Block.isShapeFullBlock(shape));
                shapes.put(position.immutable(), result);
                slice.check();
            }
            return result;
        }

        boolean blockClear(net.minecraft.world.phys.AABB body, AirPlanningSlice slice) {
            AirBodyKey key = new AirBodyKey(body);
            Boolean result = blockBodies.get(key);
            if (result == null) {
                slice.observation();
                result = airSwimBlockClear(body, slice.deadline, obstacles, true);
                blockBodies.put(key, result);
                slice.check();
            }
            return result;
        }

        String stage() { return route != null && !route.isEmpty() ? "swim-admission"
                : endpoint != null ? "native-admission" : nativeSearch != null ? nativeSearch.stage() : swim.stage(); }
        int probes() { return retiredProbes + (swim == null ? 0 : swim.probes) + (nativeSearch == null ? 0 : nativeSearch.probes); }
        int visited() { return retiredVisited + (swim == null ? 0 : swim.parents.size()); }
        void diagnostic(AirPlanningSlice slice, String outcome) {
            if (!config.debugLogging || diagnosticSamples >= 16) return;
            long now = System.nanoTime();
            if (diagnosticSamples != 0 && now - diagnosticAt < 1_000_000_000L
                    && !outcome.equals("refused") && !outcome.equals("reanchor")) return;
            diagnosticAt = now; diagnosticSamples++;
            logNativeDebug("AIR_PLAN outcome=" + outcome + " stage=" + stage() + " probes=" + probes()
                    + " slices=" + slices + " work=" + slice.work + " workCap=" + AIR_PLANNING_MAX_WORK
                    + " sliceBudgetMs=4 expired=" + (now > slice.deadline)
                    + " visited=" + visited() + " reanchors=" + reanchors + " reanchorCap=" + AIR_PLANNING_MAX_REANCHORS
                    + " reanchorReason=" + reanchorReason + " previousAnchor=" + previousAnchor
                    + " episodeAnchor=" + episodeX + "," + episodeY + "," + episodeZ
                    + " anchor=" + x + "," + y + "," + z + " actual="
                    + (client.player == null ? "unavailable" : client.player.getX() + "," + client.player.getY() + "," + client.player.getZ()));
        }
    }
    private final class AirRecoverySearch {
        private final AirPlanning plan;
        private final Set<BlockPos> rejectedGoals;
        private final AirExitPreference preference;
        private final BlockPos center;
        private final List<AirRecoveryCandidate> dryCandidates = new ArrayList<>(AIR_RECOVERY_MAX_GOALS);
        private final List<AirRecoveryCandidate> floatingCandidates = new ArrayList<>(AIR_RECOVERY_MAX_GOALS);
        private final List<AirRecoveryOffset> offsets = new ArrayList<>();
        private int offsetIndex, verticalIndex, probes;
        private boolean probeStarted, finished;

        AirRecoverySearch(AirPlanning plan, Set<BlockPos> rejectedGoals, AirExitPreference preference) {
            this.plan = plan; this.rejectedGoals = rejectedGoals; this.preference = preference; center = plan.origin;
            offsets.add(new AirRecoveryOffset(0, 0, 0));
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                int distanceSquared = dx * dx + dz * dz;
                if ((dx != 0 || dz != 0) && distanceSquared <= 12 * 12)
                    offsets.add(new AirRecoveryOffset(dx, dz, distanceSquared));
            }
            offsets.sort(Comparator.comparingInt(AirRecoveryOffset::distanceSquared)
                    .thenComparingInt(AirRecoveryOffset::x).thenComparingInt(AirRecoveryOffset::z));
        }

        String stage() { return "native-offset-" + offsetIndex + "-vertical-" + verticalIndex; }

        BlockPos advance(AirPlanningSlice slice) {
            if (!plan.obstacles.clear(plan.bounds) || !plan.blockClear(plan.bounds, slice))
                throw new AirSwimCoverageFailure("native AIR starting body is obstructed or uncovered");
            while (!finished && offsetIndex < offsets.size()) {
                slice.check();
                AirRecoveryOffset offset = offsets.get(offsetIndex);
                if (!probeStarted) {
                    if (plan.probes() >= AIR_RECOVERY_MAX_PROBES) { finished = true; break; }
                    slice.observation(); probes++; probeStarted = true;
                }
                int dy = verticalIndex == 0 ? 0 : verticalIndex == 1 ? 1 : verticalIndex == 2 ? -1 : verticalIndex - 1;
                AirRecoveryCandidate candidate = inspect(offset.x(), offset.z(), dy, slice, true);
                if (candidate != null) {
                    boolean water = plan.state(candidate.position(), slice).getFluidState().is(FluidTags.WATER);
                    List<AirRecoveryCandidate> ranked = water ? floatingCandidates : dryCandidates;
                    int index = Collections.binarySearch(ranked, candidate, AIR_RECOVERY_CANDIDATE_ORDER);
                    if (index < 0) index = -index - 1;
                    ranked.add(index, candidate);
                    if (ranked.size() > AIR_RECOVERY_MAX_GOALS) ranked.remove(ranked.size() - 1);
                }
                probeStarted = false;
                if (++verticalIndex == 18) {
                    verticalIndex = 0; offsetIndex++;
                    List<AirRecoveryCandidate> preferred = preference == AirExitPreference.DRY ? dryCandidates : floatingCandidates;
                    if (preferred.size() == AIR_RECOVERY_MAX_GOALS
                            && offset.distanceSquared() > preferred.get(preferred.size() - 1).score()) finished = true;
                }
                slice.check();
            }
            List<AirRecoveryCandidate> selected = preference == AirExitPreference.DRY ? dryCandidates : floatingCandidates;
            if (selected.isEmpty()) selected = preference == AirExitPreference.DRY ? floatingCandidates : dryCandidates;
            if (selected.isEmpty()) throw new AirSwimCoverageFailure("no breathable native AIR stance within the bounded search");
            // Select a complete deterministic set before fresh admission, never a deadline-truncated prefix.
            BlockPos endpoint = selected.get(0).position();
            slice.check();
            return endpoint;
        }

        boolean freshEndpoint(BlockPos endpoint, AirPlanningSlice slice) {
            return inspect(endpoint.getX() - center.getX(), endpoint.getZ() - center.getZ(),
                    endpoint.getY() - center.getY(), slice, false) != null;
        }

        private AirRecoveryCandidate inspect(int dx, int dz, int dy, AirPlanningSlice slice, boolean forecast) {
            int x = center.getX() + dx, y = center.getY() + dy, z = center.getZ() + dz;
            if (!inWorld(y) || !inWorld(y + 1) || !inWorld(y + 2) || !inWorld(y - 1)) return null;
            BlockPos feet = new BlockPos(x, y, z), head = feet.above(), overhead = feet.above(2), support = feet.below();
            if (!plan.withinDomain(feet) || rejectedGoals.contains(feet) || !plan.obstacles.loadedChunks().contains(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL))) return null;
            BlockState feetState = forecast ? plan.state(feet, slice) : freshAirState(feet, slice);
            BlockState headState = forecast ? plan.state(head, slice) : freshAirState(head, slice);
            if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                    || !cellShape(feet, feetState, slice, forecast).empty() || !cellShape(head, headState, slice, forecast).empty()
                    || !headState.getFluidState().isEmpty()
                    || !(feetState.getFluidState().isEmpty() || feetState.getFluidState().is(FluidTags.WATER))) return null;
            BlockState overheadState = forecast ? plan.state(overhead, slice) : freshAirState(overhead, slice);
            if (overheadState.getBlock() instanceof FallingBlock) return null;
            boolean waterFeet = feetState.getFluidState().is(FluidTags.WATER);
            BlockState supportState = forecast ? plan.state(support, slice) : freshAirState(support, slice);
            boolean supported = supportState.getFluidState().isEmpty() && !hazardousAirRecoveryState(supportState)
                    && cellShape(support, supportState, slice, forecast).full();
            if (!waterFeet && !supported) return null;
            var body = plan.bounds.move(x + .5 - plan.x, y - plan.y, z + .5 - plan.z);
            if (forecast && (!plan.obstacles.clear(body) || !plan.blockClear(body, slice))) return null;
            int distanceSquared = dx * dx + dy * dy + dz * dz;
            return new AirRecoveryCandidate(feet, distanceSquared + (supported ? 0 : 3) + (waterFeet ? 2 : 0),
                    distanceSquared, supported);
        }

        private AirCellShape cellShape(BlockPos position, BlockState state, AirPlanningSlice slice, boolean forecast) {
            if (forecast) return plan.shape(position, slice);
            return freshAirShape(state, position, slice);
        }

        private boolean inWorld(int y) { return y >= client.level.getMinY() && y < client.level.getMaxY(); }
    }

    private BlockState freshAirState(BlockPos position, AirPlanningSlice slice) {
        slice.observation();
        BlockState state = client.level.getBlockState(position);
        slice.check();
        return state;
    }
    private static boolean hazardousAirRecoveryState(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
    }

    long airSwimObservationDeadline() { return System.nanoTime() + AIR_RECOVERY_SEARCH_BUDGET_NANOS; }

    enum LiveAirResult { CLEAR, BLOCKED, REFUSED, PENDING }
    static final class LiveAirSlice {
        private final AirPlanningSlice observation;
        private boolean yielded;
        private String refusalReason;
        private LiveAirSlice(long deadline) { observation = new AirPlanningSlice(deadline); }
        boolean pending() {
            if (deadlineExpired()) yielded = true;
            return yielded;
        }
        boolean deadlineExpired() { return System.nanoTime() > observation.deadline; }
        int work() { return observation.work; }
        String refusalReason() { return refusalReason; }
    }

    LiveAirSlice beginLiveAirSlice() { return new LiveAirSlice(airSwimObservationDeadline()); }

    /** Revalidates actual native terrain/body using the one slice shared by the whole swimming tick. */
    LiveAirResult observeLiveAirStep(BlockPos feetPosition, LiveAirSlice live) {
        if (live.pending()) return LiveAirResult.PENDING;
        try {
            boolean clear = observeAirSwimStep(feetPosition, live.observation, airSwimOrigin, airSwimSeparationStep);
            live.observation.check();
            return clear ? LiveAirResult.CLEAR : LiveAirResult.BLOCKED;
        } catch (AirPlanningYield pending) {
            live.yielded = true;
            return LiveAirResult.PENDING;
        } catch (AirSwimCoverageFailure refused) {
            live.refusalReason = refused.getMessage();
            return LiveAirResult.REFUSED;
        }
    }

    LiveAirResult finishLiveAirAdmission(LiveAirSlice live) {
        if (live.pending()) return LiveAirResult.PENDING;
        try {
            live.observation.observation();
            checkAirRecoveryOwnership();
            live.observation.check();
            return LiveAirResult.CLEAR;
        } catch (AirPlanningYield pending) {
            live.yielded = true;
            return LiveAirResult.PENDING;
        }
    }

    private boolean observeAirSwimStep(BlockPos feetPosition, AirPlanningSlice slice, BlockPos origin, BlockPos separationStep) {
        slice.check();
        if (feetPosition == null || origin == null || client.player == null || client.level == null
                || !withinAirSwimBounds(feetPosition, origin))
            throw new AirSwimCoverageFailure("current swim context or domain is unavailable");
        int x = feetPosition.getX(), y = feetPosition.getY(), z = feetPosition.getZ();
        if (!airSwimInWorld(y - 1) || !airSwimInWorld(y) || !airSwimInWorld(y + 1) || !airSwimInWorld(y + 2))
            throw new AirSwimCoverageFailure("current swim height is outside the world");
        slice.observation();
        boolean loaded = airSwimChunkLoaded(x, z);
        slice.check();
        if (!loaded) throw new AirSwimCoverageFailure("current swim chunk is unavailable");

        BlockPos headPosition = new BlockPos(x, y + 1, z);
        BlockPos supportPosition = new BlockPos(x, y - 1, z);
        BlockPos overheadPosition = new BlockPos(x, y + 2, z);
        BlockState feetState = freshAirState(feetPosition, slice);
        BlockState headState = freshAirState(headPosition, slice);
        BlockState supportState = freshAirState(supportPosition, slice);
        if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                || hazardousAirRecoveryState(supportState)
                || !freshAirShape(feetState, feetPosition, slice).empty()
                || !freshAirShape(headState, headPosition, slice).empty()
                || freshAirState(overheadPosition, slice).getBlock() instanceof FallingBlock
                || supportState.getBlock() instanceof FallingBlock) return false;

        boolean feetWater = feetState.getFluidState().is(FluidTags.WATER);
        boolean feetDry = feetState.getFluidState().isEmpty();
        boolean headWater = headState.getFluidState().is(FluidTags.WATER);
        boolean headDry = headState.getFluidState().isEmpty();
        if ((!feetWater && !feetDry) || (!headWater && !headDry)) return false;
        boolean supportedDry = supportState.getFluidState().isEmpty()
                && freshAirShape(supportState, supportPosition, slice).full();
        boolean aboveWater = supportState.getFluidState().is(FluidTags.WATER);
        if (!(feetWater || supportedDry || feetDry && headDry && aboveWater)) return false;
        slice.observation();
        var body = client.player.getBoundingBox();
        double dx = x + .5 - client.player.getX(), dy = y - client.player.getY(), dz = z + .5 - client.player.getZ();
        var destination = body.move(dx, dy, dz);
        var swept = body.expandTowards(dx, dy, dz);
        slice.check(); slice.observation();
        var obstacles = airSwimObstacles(swept, slice.deadline);
        if (!obstacles.covers(swept)) throw new AirSwimCoverageFailure("current swim sweep has incomplete coverage");
        if (!obstacles.edgeClear(body, destination, swept, feetPosition.equals(separationStep))) return false;
        slice.observation();
        boolean clear = airSwimBlockClear(swept, slice.deadline, obstacles);
        slice.check();
        return clear;
    }

    private AirCellShape freshAirShape(BlockState state, BlockPos position, AirPlanningSlice slice) {
        slice.observation();
        var shape = state.getCollisionShape(client.level, position);
        var result = new AirCellShape(shape.isEmpty(), Block.isShapeFullBlock(shape));
        slice.check();
        return result;
    }

    private boolean finiteAirSwimBox(net.minecraft.world.phys.AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.maxX >= box.minX && box.maxY >= box.minY && box.maxZ >= box.minZ;
    }

    private boolean airSwimBlockClear(net.minecraft.world.phys.AABB body, long deadline, AirSwimObstacles obstacles) {
        return airSwimBlockClear(body, deadline, obstacles, false);
    }

    private boolean airSwimBlockClear(net.minecraft.world.phys.AABB body, long deadline, AirSwimObstacles obstacles, boolean forecast) {
        if (!obstacles.covers(body)) return false;
        if (System.nanoTime() > deadline) throw new AirPlanningYield();
        int shapes = 0;
        var collisions = client.level.getBlockCollisions(client.player, body).iterator();
        while (true) {
            if (System.nanoTime() > deadline) throw new AirPlanningYield();
            boolean more = collisions.hasNext();
            if (!more) break; // A complete empty result may be saved as an expired forecast only.
            if (System.nanoTime() > deadline) throw new AirPlanningYield();
            var collision = collisions.next();
            if (++shapes > AIR_SWIM_MAX_COLLISION_SHAPES) throw new AirSwimCoverageFailure("block collision shape cap");
            if (System.nanoTime() > deadline) throw new AirPlanningYield();
            if (!collision.isEmpty()) return false;
        }
        if (!forecast && System.nanoTime() > deadline) throw new AirPlanningYield();
        return true;
    }

    private AirSwimObstacles airSwimObstacles(net.minecraft.world.phys.AABB bodyCoverage, long deadline) {
        return airSwimObstacles(bodyCoverage, deadline, false);
    }

    private AirSwimObstacles airSwimObstacles(net.minecraft.world.phys.AABB bodyCoverage, long deadline, boolean forecast) {
        if (System.nanoTime() > deadline) throw new AirPlanningYield();
        var coverage = bodyCoverage.inflate(AIR_SWIM_VEHICLE_PUSH_REACH, 0, AIR_SWIM_VEHICLE_PUSH_REACH);
        var chunkCoverage = coverage.inflate(1, 0, 1);
        if (!finiteAirSwimBox(chunkCoverage) || chunkCoverage.maxX - chunkCoverage.minX > 32
                || chunkCoverage.maxY - chunkCoverage.minY > 24 || chunkCoverage.maxZ - chunkCoverage.minZ > 32)
            throw new AirSwimCoverageFailure("invalid or oversized native bounds");
        Set<Long> loadedChunks = new HashSet<>();
        boolean complete = true;
        int minChunkX = ((int) Math.floor(chunkCoverage.minX)) >> 4, maxChunkX = ((int) Math.floor(chunkCoverage.maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(chunkCoverage.minZ)) >> 4, maxChunkZ = ((int) Math.floor(chunkCoverage.maxZ)) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (System.nanoTime() > deadline) throw new AirPlanningYield();
                if (client.level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null)
                    loadedChunks.add(((long) chunkX << 32) | (chunkZ & 0xffffffffL));
                else complete = false;
            }
        if (System.nanoTime() > deadline) throw new AirPlanningYield();
        int[] inspected = {0};
        // Living entities can initiate a push even when their own isPushable recipient predicate is false.
        var entities = client.level.getEntities(client.player, coverage, entity -> {
            if (++inspected[0] > AIR_SWIM_MAX_OBSTACLE_INSPECTIONS) throw new AirSwimCoverageFailure("entity inspection cap");
            if (System.nanoTime() > deadline) throw new AirPlanningYield();
            return !entity.isRemoved() && !entity.isSpectator()
                    && (entity instanceof net.minecraft.world.entity.LivingEntity || entity.isPushable() || client.player.canCollideWith(entity));
        });
        List<AirSwimObstacle> bodies = new ArrayList<>(entities.size());
        for (var entity : entities) {
            if (System.nanoTime() > deadline) throw new AirPlanningYield();
            var bounds = entity.getBoundingBox();
            if (System.nanoTime() > deadline) throw new AirPlanningYield();
            if (!finiteAirSwimBox(bounds)) throw new AirSwimCoverageFailure("invalid entity bounds");
            var raw = new net.minecraft.world.phys.AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
            if (System.nanoTime() > deadline) throw new AirPlanningYield();
            boolean living = entity instanceof net.minecraft.world.entity.LivingEntity;
            boolean hard = client.player.canCollideWith(entity);
            // Boats and minecarts are nonliving pushable/hard entities with a native .2 horizontal push query.
            // Applying that envelope to other admitted nonliving entities is a conservative rejection.
            var push = living ? raw : raw.inflate(AIR_SWIM_VEHICLE_PUSH_REACH, 0, AIR_SWIM_VEHICLE_PUSH_REACH);
            bodies.add(new AirSwimObstacle(hard ? raw : null, push));
        }
        var result = new AirSwimObstacles(bodies, coverage, loadedChunks, complete);
        if (!forecast && System.nanoTime() > deadline) throw new AirPlanningYield();
        return result;
    }

    private final class AirSwimSearch {
        private final AirPlanning plan;
        private final BlockPos origin;
        private final Map<BlockPos, BlockPos> parents = new HashMap<>();
        private final ArrayDeque<AirSwimNode> frontier = new ArrayDeque<>();
        private int probes, neighborIndex;
        private boolean exhausted, initialized, originDry, startingSoftOverlap, entityBlocked, bodyBlocked, incomplete;
        private BlockPos surfaceFallback, pendingNext;
        private AirSwimNode current;
        private AirSwimCell pendingCell;
        private boolean inspected;

        AirSwimSearch(AirPlanning plan, BlockPos origin) { this.plan = plan; this.origin = origin.immutable(); }
        String stage() { return !initialized ? "swim-origin" : "swim-neighbor-" + neighborIndex; }

        List<BlockPos> advance(AirPlanningSlice slice) {
            if (!initialized) {
                if (!plan.obstacles.hardClear(plan.bounds) || !plan.blockClear(plan.bounds, slice))
                    throw new AirSwimCoverageFailure("starting body has incomplete coverage or a hard collision");
                startingSoftOverlap = plan.obstacles.softOverlap(plan.bounds);
                AirSwimCell start = inspect(origin, slice);
                if (start == null) return emptyResult(slice);
                if (plan.visited() >= AIR_SWIM_MAX_VISITED) throw new AirSwimCoverageFailure("cumulative swim visited limit");
                parents.put(origin, null); frontier.addLast(new AirSwimNode(origin, 0)); initialized = true;
                if (start.surface() && !startingSoftOverlap) surfaceFallback = origin;
                originDry = start.dryExit() && !startingSoftOverlap;
                slice.check();
            }
            if (originDry) return emptyResult(slice);
            while (!exhausted) {
                slice.check();
                if (current == null) {
                    if (frontier.isEmpty()) break;
                    slice.observation();
                    current = frontier.removeFirst(); neighborIndex = 0;
                    if (current.depth() >= AIR_SWIM_MAX_PATH) { current = null; continue; }
                }
                if (neighborIndex == AIR_SWIM_STEPS.length) { current = null; continue; }
                if (pendingNext == null) {
                    if (plan.probes() >= AIR_RECOVERY_MAX_PROBES) { exhausted = true; break; }
                    slice.observation(); probes++;
                    int[] step = AIR_SWIM_STEPS[neighborIndex];
                    pendingNext = new BlockPos(current.position().getX() + step[0],
                            current.position().getY() + step[1], current.position().getZ() + step[2]);
                    if (!withinAirSwimBounds(pendingNext, origin) || !plan.withinDomain(pendingNext)
                            || parents.containsKey(pendingNext)) { nextNeighbor(); continue; }
                    if (plan.visited() >= AIR_SWIM_MAX_VISITED) { exhausted = true; break; }
                }
                if (!inspected) { pendingCell = inspect(pendingNext, slice); inspected = true; }
                if (pendingCell == null) { nextNeighbor(); continue; }
                var swept = sweptBounds(current.position(), pendingNext);
                if (!plan.obstacles.covers(swept)) { incomplete = true; nextNeighbor(); continue; }
                var from = current.position().equals(origin) ? plan.bounds
                        : boundsAt(current.position().getX(), current.position().getY(), current.position().getZ());
                var to = boundsAt(pendingNext.getX(), pendingNext.getY(), pendingNext.getZ());
                if (!plan.obstacles.edgeClear(from, to, swept, current.position().equals(origin) && startingSoftOverlap)) {
                    entityBlocked = true; nextNeighbor(); continue;
                }
                if (!plan.blockClear(swept, slice)) { bodyBlocked = true; nextNeighbor(); continue; }
                parents.put(pendingNext, current.position());
                if (pendingCell.dryExit()) return reconstruct(pendingNext, slice);
                if (surfaceFallback == null && pendingCell.surface()) surfaceFallback = pendingNext;
                frontier.addLast(new AirSwimNode(pendingNext, current.depth() + 1));
                nextNeighbor();
            }
            if (surfaceFallback == null) return emptyResult(slice);
            List<BlockPos> result = reconstruct(surfaceFallback, slice);
            return result.isEmpty() ? emptyResult(slice) : result;
        }

        private void nextNeighbor() { neighborIndex++; pendingNext = null; pendingCell = null; inspected = false; }

        private List<BlockPos> emptyResult(AirPlanningSlice slice) {
            slice.check();
            if (startingSoftOverlap || entityBlocked || bodyBlocked || incomplete || exhausted || !plan.obstacles.complete())
                throw new AirSwimCoverageFailure("no complete unobstructed swim route; native fallback refused");
            return List.of();
        }
        private AirSwimCell inspect(BlockPos position, AirPlanningSlice slice) {
            int x = position.getX(), y = position.getY(), z = position.getZ();
            if (!withinAirSwimBounds(position, origin) || !plan.withinDomain(position) || !airSwimInWorld(y - 1)
                    || !airSwimInWorld(y) || !airSwimInWorld(y + 1) || !airSwimInWorld(y + 2)
                    || !plan.obstacles.loadedChunks().contains(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL))) return null;
            var body = position.equals(origin) ? plan.bounds : boundsAt(x, y, z);
            if (!plan.obstacles.covers(body)) { incomplete = true; return null; }
            if (!(position.equals(origin) && startingSoftOverlap ? plan.obstacles.hardClear(body) : plan.obstacles.clear(body))) {
                entityBlocked = true;
                return null;
            }
            if (!plan.blockClear(body, slice)) {
                bodyBlocked = true;
                return null;
            }
            BlockPos feet = position, head = position.above(), support = position.below(), overhead = position.above(2);
            BlockState feetState = plan.state(feet, slice);
            BlockState headState = plan.state(head, slice);
            BlockState supportState = plan.state(support, slice);
            if (hazardousAirRecoveryState(feetState) || hazardousAirRecoveryState(headState)
                    || hazardousAirRecoveryState(supportState) || !plan.shape(feet, slice).empty()
                    || !plan.shape(head, slice).empty()
                    || plan.state(overhead, slice).getBlock() instanceof FallingBlock
                    || supportState.getBlock() instanceof FallingBlock) return null;

            boolean feetWater = feetState.getFluidState().is(FluidTags.WATER);
            boolean feetDry = feetState.getFluidState().isEmpty();
            boolean headWater = headState.getFluidState().is(FluidTags.WATER);
            boolean headDry = headState.getFluidState().isEmpty();
            if ((!feetWater && !feetDry) || (!headWater && !headDry)) return null;

            boolean supportedDry = supportState.getFluidState().isEmpty()
                    && plan.shape(support, slice).full();
            boolean aboveWater = supportState.getFluidState().is(FluidTags.WATER);
            boolean transition = feetDry && headDry && aboveWater;
            if (!feetWater && !supportedDry && !transition) return null;
            return new AirSwimCell(feetWater && headDry, feetDry && headDry && supportedDry);
        }

        private net.minecraft.world.phys.AABB boundsAt(int x, int y, int z) {
            return plan.bounds.move(x + .5 - plan.x, y - plan.y, z + .5 - plan.z);
        }

        private net.minecraft.world.phys.AABB sweptBounds(BlockPos from, BlockPos to) {
            if (from.equals(origin)) return plan.bounds.expandTowards(to.getX() + .5 - plan.x,
                    to.getY() - plan.y, to.getZ() + .5 - plan.z);
            return boundsAt(from.getX(), from.getY(), from.getZ()).expandTowards(to.getX() - from.getX(),
                    to.getY() - from.getY(), to.getZ() - from.getZ());
        }

        private List<BlockPos> reconstruct(BlockPos destination, AirPlanningSlice slice) {
            slice.check();
            List<BlockPos> route = new ArrayList<>();
            BlockPos cursor = destination;
            while (!cursor.equals(origin)) {
                if (route.size() >= AIR_SWIM_MAX_PATH) throw new AirSwimCoverageFailure("swim reconstruction exceeded path limit");
                route.add(cursor.immutable());
                cursor = parents.get(cursor);
                if (cursor == null) throw new AirSwimCoverageFailure("swim reconstruction lost its parent");
            }
            Collections.reverse(route);
            List<BlockPos> result = List.copyOf(route);
            slice.check();
            return result;
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

    void startInteraction(BlockPos target) { startInteraction(target, RouteEffects.CONFIGURED); }

    void startInteraction(BlockPos target, RouteEffects effects) {
        Objects.requireNonNull(effects);
        if (effects == RouteEffects.MOVEMENT_ONLY) checkAirRecoveryOwnership();
        prepare(); routeEffects = effects;
        routeGoal = new GoalGetToBlock(target);
        diagnosticGoal = dev.lodekeeper.nav.Goal.near16(target.getX(), target.getY() * 16, target.getZ(), 32);
        mode = Mode.MOVE; launch();
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
        startFollowing(animal, eligible, RouteEffects.CONFIGURED);
    }

    void startFollowing(Animal animal, Predicate<Animal> eligible, RouteEffects effects) {
        Objects.requireNonNull(animal);
        Objects.requireNonNull(eligible);
        Objects.requireNonNull(effects);
        if (effects == RouteEffects.MOVEMENT_ONLY) checkAirRecoveryOwnership();
        prepare();
        routeEffects = effects;
        followRadius = 1;
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
        startOwnedPickup(item, ownerSession, RouteEffects.CONFIGURED);
    }

    void startOwnedPickup(ItemEntity item, Object ownerSession, RouteEffects effects) {
        if (item == null || !item.isAlive() || ownerSession == null)
            throw new NavigationFailure("Owned pickup requires a live item and recovery session");
        Objects.requireNonNull(effects);
        if (effects == RouteEffects.MOVEMENT_ONLY) checkAirRecoveryOwnership();
        prepare();
        routeEffects = effects;
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
        routeEffects = RouteEffects.CONFIGURED;
        retreatRequest = false;
        resetRetreatPrefix();
        startedNanos = System.nanoTime(); requestTicks = failedCalculations = 0;
        shallowPreparationSamples = 0;
        motionLogAnchorInitialized = false;
        motionLogAnchorX = motionLogAnchorZ = 0;
        motionLogAnchorRequestTick = motionLogLastRequestTick = motionLogCount = 0;
        descentMotionLogLastRequestTick = descentMotionLogCount = 0;
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

    private void checkTravelEffectAuthority() {
        if (travelEffectAuthority != null && !travelEffectAuthority.getAsBoolean())
            throw new NavigationFailure("Travel authority expired; no new native effect is admitted");
    }

    private void launch() {
        checkTravelEffectAuthority();
        if (routeEffects == RouteEffects.MOVEMENT_ONLY || preparedTravelOwner != null) checkAirRecoveryOwnership();
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
        boolean movementOnly = routeEffects == RouteEffects.MOVEMENT_ONLY;
        lease.set(settings.allowBreak, !movementOnly && !airRecovery && !retreatRequest && config.allowBreaking);
        lease.set(settings.allowPlace, !movementOnly && !airRecovery && !retreatRequest && config.allowBuilding);
        lease.set(settings.allowInventory, false);
        if (retreatRequest) {
            lease.set(settings.planningTickLookahead, 0);
            lease.set(settings.splicePath, false);
        }
        lease.set(settings.allowParkour, !airRecovery && config.allowParkour);
        lease.set(settings.allowParkourPlace, !movementOnly && !airRecovery && !retreatRequest
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
        lease.set(settings.autoTool, !movementOnly);
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
        if (!movementOnly && !airRecovery && !retreatRequest) actions.prepareScaffoldHotbar(scaffoldItems);
        switch (mode) {
            case MOVE, AIR -> {
                checkTravelEffectAuthority();
                if (preparedTravelOwner != null) checkAirRecoveryOwnership();
                bot.getCustomGoalProcess().setGoalAndPath(routeGoal);
                preparedTravelGoal = null;
                preparedTravelWorld = preparedTravelPlayer = null;
                preparedTravelOwner = null; preparedTravelSession = null;
            }
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
                checkTravelEffectAuthority();
                if (preparedTravelOwner != null) checkAirRecoveryOwnership();
                lease.set(settings.followRadius, followRadius);
                lease.set(settings.followOffsetDistance, 0.0);
                lease.set(settings.followTargetMaxDistance, 64);
                checkTravelEffectAuthority();
                if (preparedTravelOwner != null) checkAirRecoveryOwnership();
                bot.getFollowProcess().follow(followFilter);
                preparedTravelGoal = null; preparedTravelWorld = preparedTravelPlayer = null;
                preparedTravelOwner = null; preparedTravelSession = null;
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
        if (routeEffects == RouteEffects.MOVEMENT_ONLY || retreatRequest || mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
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
        if (routeEffects != RouteEffects.MOVEMENT_ONLY && mode != Mode.AIR && !retreatRequest) actions.prepareScaffoldHotbar(scaffoldItems);
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
        if (routeEffects == RouteEffects.MOVEMENT_ONLY) {
            try { checkAirRecoveryOwnership(); }
            catch (NavigationFailure failure) {
                if (failure.kind != NavigationFailure.Kind.OWNERSHIP_LOST) throw failure;
                pendingOwnershipFailure = failure;
                return true;
            }
            return false;
        }
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
        if (routeEffects == RouteEffects.MOVEMENT_ONLY) checkAirRecoveryOwnership();
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
        if (routeEffects == RouteEffects.MOVEMENT_ONLY) checkAirRecoveryOwnership();
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
        if (preparedTravelOwner != null && (mode == Mode.MOVE && routeGoal == preparedTravelGoal
                || mode == Mode.FOLLOW && preparedTravelGoal == null)) {
            var pathing = activeBot.getPathingBehavior();
            if (preparedTravelWorld != client.level || preparedTravelPlayer != client.player
                    || preparedTravelOwner != dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current()
                    || preparedTravelOwner == null || !preparedTravelOwner.isCurrent(preparedTravelSession)
                    || preparedTravelSession.world() != client.level || custom.isActive()
                    || hasForeignActiveProcess(activeBot, null) || pathing.hasPath() || pathing.isPathing()
                    || pathing.getInProgress().isPresent()) throw releaseLostAirRecoveryOwnership();
            return;
        }
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
        routeEffects = RouteEffects.CONFIGURED;
        input.release();
        observation = NavigationSnapshot.EMPTY;
        miningTarget = null;
        routeGoal = airRecoveryGoal = null;
        lastAirRecoveryDestination = null;
        diagnosticGoal = null;
        pendingBreakFailure = pendingOwnershipFailure = null;
        if (lease != null) { lease.restore(); lease = null; }
        preparedTravelGoal = null; preparedTravelWorld = preparedTravelPlayer = null;
        preparedTravelOwner = null; preparedTravelSession = null;
        travelEffectAuthority = null;
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
        if (routeEffects == RouteEffects.MOVEMENT_ONLY) {
            cancellationProcess = null;
            airRecoveryCancellationPending = false;
            routeEffects = RouteEffects.CONFIGURED;
        }
        input.release();
        observation = NavigationSnapshot.EMPTY;
        miningTarget = null;
        routeGoal = null;
        diagnosticGoal = null;
        if (lease != null) { lease.restore(); lease = null; }
        preparedTravelGoal = null; preparedTravelWorld = preparedTravelPlayer = null;
        preparedTravelOwner = null; preparedTravelSession = null;
        travelEffectAuthority = null;
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
        if (routeEffects == RouteEffects.MOVEMENT_ONLY || defenseOnly || mode == Mode.AIR || mode == Mode.SUSPENDED || cancellationProcess != null
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
            if (mode == Mode.IDLE && resumeMode == Mode.IDLE && lease == null
                    && cancellationProcess == null && !followCancellationPending && !airRecoveryCancellationPending) {
                routeEffects = RouteEffects.CONFIGURED;
                travelEffectAuthority = null;
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
        preparedTravelGoal = null; preparedTravelWorld = preparedTravelPlayer = null;
        preparedTravelOwner = null; preparedTravelSession = null;
        if (mode == Mode.IDLE && resumeMode == Mode.IDLE) travelEffectAuthority = null;
        cancellationProcess = null;
        airRecoveryCancellationPending = false;
        airRecoveryGoal = null;
        if (mode == Mode.IDLE && resumeMode == Mode.IDLE) routeEffects = RouteEffects.CONFIGURED;
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
                boolean stallSample = stallTicks >= 80 && motionLogCount < 8
                        && (motionLogCount == 0 || requestTicks - motionLogLastRequestTick >= 80);
                boolean descentSample = mode == Mode.DESCEND && miningDepthPolicy != null
                        && miningDepthPolicy.bulkDiamonds() && descentMotionLogCount < 8
                        && (descentMotionLogCount == 0 || requestTicks - descentMotionLogLastRequestTick >= 20);
                if (stallSample || descentSample) {
                    if (descentSample) { descentMotionLogLastRequestTick = requestTicks; descentMotionLogCount++; }
                    try {
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
                        if (stallSample) { motionLogLastRequestTick = requestTicks; motionLogCount++; }
                        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                                "[Lodekeeper] NAV_MOTION mode={} requestTicks={} stallTicks={} pathIndex={} pathLength={} executor={} movement={} src={} dest={} nativeXYZ=({},{},{}) blockFeet={} kernelPlayerFeet={} pose={} bbox={} velocity=({},{},{}) onGround={} horizontalCollision={} verticalCollision={} srcFluid={} destFluid={} destHead={} destHead2={} yaw={} pitch={} inWater={} underWater={} inputClass={} actualSideways={} actualForward={} actualJump={} actualSneak={} progressToken={} outputCount={} jump={} forward={} back={} sneak={} attack={} clientStoredHit={} stallSample={} descentSample={} stallSampleCount={} descentSampleAttempts={} coverage=SAMPLED_ONLY descentBudgetExhausted={} allowDownward={} maxFallHeightNoWater={}",
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
                                inputOverrides.isInputForcedDown(Input.CLICK_LEFT), storedClientHit(),
                                stallSample, descentSample, motionLogCount, descentMotionLogCount,
                                descentMotionLogCount == 8,
                                OwnedKernelAPI.getSettings().allowDownward.value, OwnedKernelAPI.getSettings().maxFallHeightNoWater.value);
                    } catch (RuntimeException diagnosticFailure) {
                        if (stallSample) throw diagnosticFailure;
                    }
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
