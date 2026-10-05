package dev.lodekeeper.nav;

import java.util.Arrays;

/** Bounded incremental A* over conservative player stances in loaded terrain. */
public final class Planner {
    private static final int DEFAULT_NODE_LIMIT = 16_384;
    private static final int CACHE_ENTRY_LIMIT = 65_536;
    private static final int WALK_COST = 10;
    private static final int DIAGONAL_COST = 14;
    private static final int JUMP_COST = 18;
    private static final int DROP_COST = 10;
    private static final int PARKOUR_TWO_COST = 25;
    private static final int PARKOUR_THREE_COST = 34;
    private static final int MEDIUM_COST = 17;
    private static final int BRIDGE_COST = 55;
    private static final int GROUNDED_VALIDATION_LIMIT = 64;

    private static final int BREAK_ACTION = 1;
    private static final int PLACE_ACTION = 2;
    private static final long PLACEMENT_HASH = 0x9e3779b97f4a7c15L;
    private static final long SUPPORT_HASH = 0xd1b54a32d192ed03L;

    /** Mutable per-search policy. The planner copies and validates these values on construction. */
    public static final class Options {
        public int maxNodes = DEFAULT_NODE_LIMIT;
        /** Conservative maximum fall distance, from zero through three blocks. */
        public int maxDrop = 3;
        public boolean allowBreaking;
        public boolean allowBuilding;
        public boolean allowParkour;
        public boolean allowSwimming = true;
        public boolean allowClimbing = true;
        /** Reserved placeable blocks available to this search; this caps placements at every path prefix. */
        public int maxPlacements;
        /** Adapter-owned token for one approved full-block inventory item, or -1 when unavailable. */
        public int placementItemToken = -1;

        public Options maxNodes(int value) { maxNodes = value; return this; }
        public Options maxDrop(int value) { maxDrop = value; return this; }
        public Options allowBreaking(boolean value) { allowBreaking = value; return this; }
        public Options allowBuilding(boolean value) { allowBuilding = value; return this; }
        public Options allowParkour(boolean value) { allowParkour = value; return this; }
        public Options allowSwimming(boolean value) { allowSwimming = value; return this; }
        public Options allowClimbing(boolean value) { allowClimbing = value; return this; }
        public Options placements(int count, int itemToken) {
            maxPlacements = count;
            placementItemToken = itemToken;
            return this;
        }
    }

    private final Terrain terrain;
    private final Goal goal;
    private final int maxNodes;
    private final int maxDrop;
    private final boolean allowBreaking;
    private final boolean allowBuilding;
    private final boolean allowParkour;
    private final boolean allowSwimming;
    private final boolean allowClimbing;
    private final int maxPlacements;
    private final int placementItemToken;
    private final long terrainRevision;

    private final long[] positions;
    private final byte[] feetFractions;
    private final long[] costs;
    private final long[] heuristics;
    private final int[] parents;
    private final int[] placementsUsed;
    private final byte[] builtSupport;
    private final byte[] movements;
    private final byte[] actionCounts;
    private final byte[] actionType1;
    private final byte[] actionType2;
    private final long[] actionPosition1;
    private final long[] actionPosition2;
    private final int[] actionToken1;
    private final int[] actionToken2;
    private final int[] heapPosition;
    private final byte[] closed;
    private final int[] hashSlots;
    private final int[] heap;
    private final ProbeCache probeCache;
    private final GroundedStanceBuffer groundedStances = new GroundedStanceBuffer();

    private final StanceProbe sourceProbe = new StanceProbe();
    private final StanceProbe sourceAfterBreak = new StanceProbe().clear();
    private final StanceProbe targetProbe = new StanceProbe();
    private final StanceProbe sideProbe = new StanceProbe();
    private final StanceProbe emptyProbe = new StanceProbe().clear();

    private int nodeCount;
    private int heapSize;
    private long expandedNodes;
    private int placementsInFoundPath;
    private Path path;
    private NavStatus status = NavStatus.IN_PROGRESS;
    private boolean nodeLimitHit;
    private int groundedValidations;

    public Planner(Terrain terrain, int startX, int startY, int startZ, Goal goal, Options options) {
        this(terrain, startX, integerFeetY16(startY), startZ, goal, options, true);
    }

    /** Construct a planner whose starting feet height is expressed in sixteenths of a block. */
    public static Planner fromFeetY16(Terrain terrain, int startX, int startFeetY16, int startZ,
                                      Goal goal, Options options) {
        return new Planner(terrain, startX, startFeetY16, startZ, goal, options, true);
    }

    private Planner(Terrain terrain, int startX, int startFeetY16, int startZ,
                    Goal goal, Options options, boolean exactHeight) {
        if (terrain == null || goal == null) throw new NullPointerException("terrain and goal are required");
        int startY = Math.floorDiv(startFeetY16, 16);
        Position.pack(startX, startY, startZ);
        if (options == null) options = new Options();
        if (options.maxNodes < 64 || options.maxNodes > 1_000_000)
            throw new IllegalArgumentException("maxNodes must be between 64 and 1000000");
        if (options.maxDrop < 0 || options.maxDrop > 3)
            throw new IllegalArgumentException("maxDrop must be between zero and three");
        if (options.maxPlacements < 0)
            throw new IllegalArgumentException("maxPlacements must be non-negative");
        if (options.maxPlacements > 0 && options.placementItemToken < 0)
            throw new IllegalArgumentException("a placement item token is required when placements are reserved");

        this.terrain = terrain;
        this.goal = goal;
        this.maxNodes = options.maxNodes;
        this.maxDrop = options.maxDrop;
        this.allowBreaking = options.allowBreaking;
        this.allowBuilding = options.allowBuilding;
        this.allowParkour = options.allowParkour;
        this.allowSwimming = options.allowSwimming;
        this.allowClimbing = options.allowClimbing;
        this.maxPlacements = options.maxPlacements;
        this.placementItemToken = options.placementItemToken;
        this.terrainRevision = terrain.revision();

        int hashCapacity = tableCapacity(maxNodes * 2);
        positions = new long[maxNodes];
        feetFractions = new byte[maxNodes];
        costs = new long[maxNodes];
        heuristics = new long[maxNodes];
        parents = new int[maxNodes];
        placementsUsed = new int[maxNodes];
        builtSupport = new byte[maxNodes];
        movements = new byte[maxNodes];
        actionCounts = new byte[maxNodes];
        actionType1 = new byte[maxNodes];
        actionType2 = new byte[maxNodes];
        actionPosition1 = new long[maxNodes];
        actionPosition2 = new long[maxNodes];
        actionToken1 = new int[maxNodes];
        actionToken2 = new int[maxNodes];
        heapPosition = new int[maxNodes];
        closed = new byte[maxNodes];
        hashSlots = new int[hashCapacity];
        heap = new int[maxNodes];
        probeCache = new ProbeCache(Math.min(CACHE_ENTRY_LIMIT, maxNodes * 4));
        Arrays.fill(parents, -1);
        Arrays.fill(heapPosition, -1);

        long start = Position.pack(startX, startY, startZ);
        probeAtFeetY16(startX, startFeetY16, startZ, sourceProbe);
        boolean integralStart = Math.floorMod(startFeetY16, 16) == 0;
        boolean mediumStart = integralStart
                && ((allowSwimming && sourceProbe.water) || (allowClimbing && sourceProbe.climbable));
        if (!sourceProbe.loaded || !sourceProbe.bodyClear || sourceProbe.hazard
                || (!sourceProbe.hasGroundSupport() && !mediumStart)) {
            status = NavStatus.NO_PATH;
            return;
        }
        int index = addNode(start, Math.floorMod(startFeetY16, 16), startX, startY, startZ, 0, false);
        if (index < 0) {
            status = NavStatus.PARTIAL_LIMIT;
            return;
        }
        costs[index] = 0;
        heuristics[index] = goal.heuristic16(startX, startFeetY16, startZ);
        movements[index] = (byte) Path.Movement.START.ordinal();
        pushHeap(index);
    }

    /**
     * Expand at most {@code maxExpansions} nodes and stop after approximately
     * {@code nanosBudget} nanoseconds. A single node expansion is not interrupted mid-flight.
     */
    public NavStatus advance(int maxExpansions, long nanosBudget) {
        if (status != NavStatus.IN_PROGRESS) return status;
        if (maxExpansions <= 0 || nanosBudget <= 0L) return status;
        if (terrain.revision() != terrainRevision) return status = NavStatus.STALE;

        long started = System.nanoTime();
        int expandedThisCall = 0;
        while (heapSize > 0 && expandedThisCall < maxExpansions) {
            if (terrain.revision() != terrainRevision) return status = NavStatus.STALE;
            if (System.nanoTime() - started >= nanosBudget) break;

            int current = popHeap();
            if (closed[current] != 0) continue;
            closed[current] = 1;
            expandedNodes++;
            expandedThisCall++;

            int x = Position.x(positions[current]);
            int y = Position.y(positions[current]);
            int z = Position.z(positions[current]);
            int feetY16 = feetY16(current);
            if (goal.matches16(x, feetY16, z)) {
                buildPath(current);
                status = NavStatus.FOUND;
                return status;
            }

            probeAtFeetY16(x, feetY16, z, sourceProbe);
            if (!prepareSourceAfterBreak(current)) continue;
            groundedValidations = 0;
            expandLocal(current, x, y, z);
            if (nodeLimitHit) {
                buildPartialPath();
                return status = NavStatus.PARTIAL_LIMIT;
            }
        }

        if (heapSize == 0) status = NavStatus.NO_PATH;
        return status;
    }

    public void cancel() {
        if (status == NavStatus.IN_PROGRESS) status = NavStatus.CANCELLED;
    }

    /** Yield a validated forward prefix, or preserve the search when a detour needs more work. */
    public NavStatus finishPartial(int minimumProgressBlocks) {
        if (minimumProgressBlocks < 1 || minimumProgressBlocks > 64)
            throw new IllegalArgumentException("Progress threshold must be between 1 and 64 blocks");
        if (status != NavStatus.IN_PROGRESS) return status;
        if (terrain.revision() != terrainRevision) return status = NavStatus.STALE;
        int best = -1;
        long minimumSquared16 = (long) minimumProgressBlocks * minimumProgressBlocks * 256L;
        for (int i = 1; i < nodeCount; i++) {
            if (parents[i] < 0 || heuristics[i] >= heuristics[0]) continue;
            long dx16 = ((long) Position.x(positions[i]) - Position.x(positions[0])) * 16L;
            long dy16 = (long) feetY16(i) - feetY16(0);
            long dz16 = ((long) Position.z(positions[i]) - Position.z(positions[0])) * 16L;
            if (dx16 * dx16 + dy16 * dy16 + dz16 * dz16 < minimumSquared16) continue;
            if (best < 0 || heuristics[i] < heuristics[best]
                    || heuristics[i] == heuristics[best] && costs[i] < costs[best]) best = i;
        }
        if (best < 0) return status;
        buildPath(best);
        return status = NavStatus.PARTIAL_LIMIT;
    }

    public NavStatus getStatus() { return status; }
    public Path getPath() { return path; }
    public long getExpandedNodes() { return expandedNodes; }
    public int getDiscoveredNodes() { return nodeCount; }
    public int getOpenNodes() { return heapSize; }

    /** Sample at most 256 graph entries with O(limit) work, without exposing mutable search arrays. */
    public NavigationSnapshot snapshot(int nextStep, long searchNanos, int searchTicks, int retries,
                                       boolean includeNodes) {
        int count = includeNodes && status != NavStatus.CANCELLED && status != NavStatus.STALE
                ? Math.min(256, nodeCount) : 0;
        long[] points = new long[count];
        byte[] fractions = new byte[count];
        boolean[] expanded = new boolean[count];
        for (int i = 0; i < count; i++) {
            int index = (int) ((long) i * nodeCount / count);
            points[i] = positions[index];
            fractions[i] = feetFractions[index];
            expanded[i] = closed[index] != 0;
        }
        return new NavigationSnapshot(path, nextStep, expandedNodes, nodeCount, heapSize,
                searchNanos, searchTicks, retries, status == NavStatus.IN_PROGRESS, points, fractions, expanded);
    }

    private void expandLocal(int current, int x, int y, int z) {
        int sourceFeetY16 = feetY16(current);
        boolean integral = Math.floorMod(sourceFeetY16, 16) == 0;
        boolean hasGroundSupport = sourceProbe.hasGroundSupport() || builtSupport[current] != 0;
        boolean hasFullSupport = sourceProbe.fullSupport || builtSupport[current] != 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diagonal = dx != 0 && dz != 0;
                tryHorizontal(current, x, y, z, dx, dz, diagonal, hasGroundSupport, hasFullSupport, integral);
                if (nodeLimitHit) return;
            }
        }

        // Fractional landings are grounded stair contacts. Other movement models remain integral.
        if (!integral) return;

        if (hasFullSupport) {
            // One-block ledge climbs are cardinal and require a fully supported landing.
            for (int[] direction : CARDINALS) {
                tryTransition(current, x, y, z, x + direction[0], y + 1, z + direction[1],
                        Path.Movement.JUMP, JUMP_COST, 0.85, true, false, false);
                if (nodeLimitHit) return;
            }

            // Falls are cardinal to keep their swept path and landing cost predictable.
            if (maxDrop > 0) {
                for (int[] direction : CARDINALS) {
                    for (int drop = 1; drop <= maxDrop; drop++) {
                        tryTransition(current, x, y, z, x + direction[0], y - drop, z + direction[1],
                                Path.Movement.DROP, DROP_COST + drop * 4, 0.0, true, true, false);
                        if (nodeLimitHit) return;
                    }
                }
            }

            if (allowParkour) {
                for (int[] direction : CARDINALS) {
                    tryParkour(current, x, y, z, direction[0], direction[1], 2);
                    if (nodeLimitHit) return;
                    tryParkour(current, x, y, z, direction[0], direction[1], 3);
                    if (nodeLimitHit) return;
                }
            }
        }

        if (allowSwimming || allowClimbing) {
            tryVerticalMedium(current, x, y, z, 1);
            if (nodeLimitHit) return;
            tryVerticalMedium(current, x, y, z, -1);
        }
    }

    private void tryHorizontal(int current, int x, int y, int z, int dx, int dz,
                               boolean diagonal, boolean sourceHasGroundSupport,
                               boolean sourceHasFullSupport, boolean integral) {
        int tx = x + dx;
        int tz = z + dz;
        int sourceFeetY16 = feetY16(current);
        if (!validPosition(tx, y, tz)) return;

        if (diagonal) {
            if (sourceHasGroundSupport && cornersClearAtFeet16(x, sourceFeetY16, z, dx, dz)) {
                probeAtFeetY16(tx, sourceFeetY16, tz, targetProbe);
                if (targetProbe.loaded && !targetProbe.hazard && targetProbe.hasGroundSupport()) {
                    tryGroundedWalk(current, x, z, tx, tz, sourceFeetY16, targetProbe, DIAGONAL_COST, true);
                    return;
                }
            }
            if (!integral || !cornersClear(x, y, z, dx, dz)) return;
            probe(tx, y, tz, targetProbe);
            if (!targetProbe.loaded || targetProbe.hazard) return;
            tryHorizontalMediumOrBridge(current, x, y, z, tx, tz, true,
                    sourceHasFullSupport, targetProbe);
            return;
        }

        if (sourceHasGroundSupport) {
            groundedStances.clear();
            boolean complete = terrain.collectGroundedStances(tx, sourceFeetY16, tz, groundedStances);
            if (!complete || !groundedStances.isComplete()) {
                nodeLimitHit = true;
                return;
            }
            for (int i = 0; i < groundedStances.size(); i++) {
                int candidateFeetY16 = groundedStances.get(i);
                if (Math.abs((long) candidateFeetY16 - sourceFeetY16) > 16L) continue;
                if (groundedValidations >= GROUNDED_VALIDATION_LIMIT) {
                    nodeLimitHit = true;
                    return;
                }
                groundedValidations++;
                probeAtFeetY16(tx, candidateFeetY16, tz, targetProbe);
                if (!targetProbe.loaded || targetProbe.hazard || !targetProbe.hasGroundSupport()) continue;
                long baseCost = WALK_COST + positiveRiseCost(candidateFeetY16 - sourceFeetY16);
                tryGroundedWalk(current, x, z, tx, tz, candidateFeetY16, targetProbe, baseCost, false);
                if (nodeLimitHit) return;
            }
        }

        if (!integral) return;
        probe(tx, y, tz, targetProbe);
        if (!targetProbe.loaded || targetProbe.hazard) return;
        tryHorizontalMediumOrBridge(current, x, y, z, tx, tz, false,
                sourceHasFullSupport, targetProbe);
    }

    private void tryHorizontalMediumOrBridge(int current, int x, int y, int z, int tx, int tz,
                                              boolean diagonal, boolean sourceHasFullSupport,
                                              StanceProbe destination) {
        if (destination.fullSupport && !sourceProbe.water && !sourceProbe.climbable) return;
        Path.Movement movement;
        if (allowSwimming && (sourceProbe.water || destination.water)) movement = Path.Movement.SWIM;
        else if (allowClimbing && (sourceProbe.climbable || destination.climbable)) movement = Path.Movement.CLIMB;
        else if (!diagonal && allowBuilding && destination.bodyClear && sourceHasFullSupport
                && placementsUsed[current] < maxPlacements) {
            tryBridge(current, x, y, z, tx, y, tz, destination);
            return;
        } else return;
        tryTransitionWithProbe(current, x, y, z, tx, y, tz, movement, MEDIUM_COST, 0.0,
                false, true, false, destination);
    }

    private void tryGroundedWalk(int current, int fromX, int fromZ, int toX, int toZ,
                                 int toFeetY16, StanceProbe destination, long baseCost,
                                 boolean diagonal) {
        int fromFeetY16 = feetY16(current);
        int rise16 = toFeetY16 - fromFeetY16;
        if (diagonal && rise16 != 0) return;
        if (rise16 > 16 || rise16 < -16 || !destination.hasGroundSupport()) return;
        if (!destination.bodyClear && (Math.floorMod(fromFeetY16, 16) != 0
                || Math.floorMod(toFeetY16, 16) != 0)) return;
        boolean groundedSweepClear;
        if (builtSupport[current] != 0 && rise16 == 0
                && Math.floorMod(fromFeetY16, 16) == 0
                && Math.floorMod(toFeetY16, 16) == 0 && destination.fullSupport) {
            // The bridge tile is an execution-time support promise. Until placement is observed,
            // use the legacy collision sweep to leave it for a real bank rather than asking the
            // adapter to prove support for a block that is not present in the planning snapshot.
            groundedSweepClear = motionClear(fromX, Math.floorDiv(fromFeetY16, 16), fromZ,
                    toX, Math.floorDiv(toFeetY16, 16), toZ, 0.0, destination);
        } else {
            groundedSweepClear = terrain.isGroundedWalkClear(fromX + 0.5, fromFeetY16, fromZ + 0.5,
                    toX + 0.5, toFeetY16, toZ + 0.5, sourceAfterBreak, destination);
        }
        if (!groundedSweepClear) return;

        int actionType1 = 0;
        int actionType2 = 0;
        long actionPos1 = 0L;
        long actionPos2 = 0L;
        int actionToken1 = -1;
        int actionToken2 = -1;
        long actionCost = baseCost;
        if (!destination.bodyClear) {
            int count = destination.breakCount;
            if (!allowBreaking || count < 1 || count > StanceProbe.MAX_BREAK_TARGETS) return;
            for (int i = 0; i < count; i++) {
                BreakTarget target = destination.breakTargets[i];
                int sourceY = Math.floorDiv(fromFeetY16, 16);
                if (target.cost < 1 || !terrain.canBreakFrom(fromX, sourceY, fromZ, destination, i)) return;
                actionCost += target.cost;
                if (i == 0) {
                    actionType1 = BREAK_ACTION;
                    actionPos1 = Position.pack(target.x, target.y, target.z);
                    actionToken1 = target.stateToken;
                } else {
                    actionType2 = BREAK_ACTION;
                    actionPos2 = Position.pack(target.x, target.y, target.z);
                    actionToken2 = target.stateToken;
                }
            }
        } else if (destination.breakCount != 0) {
            return;
        }
        relaxAtFeetY16(current, toX, toFeetY16, toZ, actionCost, Path.Movement.WALK,
                0, false, destination, actionType1, actionPos1, actionToken1,
                actionType2, actionPos2, actionToken2);
    }

    private static long positiveRiseCost(int rise16) {
        if (rise16 <= 0) return 0L;
        return (7L * rise16 + 15L) / 16L;
    }

    private void tryVerticalMedium(int current, int x, int y, int z, int dy) {
        int ty = y + dy;
        if (!validPosition(x, ty, z)) return;
        probe(x, ty, z, targetProbe);
        if (!targetProbe.loaded || targetProbe.hazard) return;
        Path.Movement movement;
        if (allowSwimming && (sourceProbe.water || targetProbe.water)) movement = Path.Movement.SWIM;
        else if (allowClimbing && (sourceProbe.climbable || targetProbe.climbable)) movement = Path.Movement.CLIMB;
        else return;
        tryTransitionWithProbe(current, x, y, z, x, ty, z, movement, MEDIUM_COST, 0.0,
                false, true, false, targetProbe);
    }

    private void tryParkour(int current, int x, int y, int z, int dx, int dz, int distance) {
        int tx = x + dx * distance;
        int tz = z + dz * distance;
        if (!validPosition(tx, y, tz)) return;
        probe(tx, y, tz, targetProbe);
        if (!targetProbe.loaded || !targetProbe.bodyClear || !targetProbe.fullSupport
                || targetProbe.hazard || targetProbe.breakCount != 0) return;
        long baseCost = distance == 2 ? PARKOUR_TWO_COST : PARKOUR_THREE_COST;
        if (!motionClear(x, y, z, tx, y, tz, 1.35, targetProbe)) return;
        relax(current, tx, y, tz, baseCost, Path.Movement.PARKOUR, 0,
                false, targetProbe);
    }

    private void tryBridge(int current, int x, int y, int z, int tx, int ty, int tz,
                           StanceProbe destination) {
        if (Math.floorMod(feetY16(current), 16) != 0
                || !(sourceProbe.fullSupport || builtSupport[current] != 0)) return;
        if (!destination.loaded || !destination.bodyClear || destination.hazard
                || destination.fullSupport || destination.surfaceSupport
                || destination.water || destination.climbable) return;
        if (!terrain.canPlaceBridgeFrom(x, y, z, tx, ty - 1, tz,
                placementItemToken, builtSupport[current] != 0)) return;
        if (!motionClear(x, y, z, tx, ty, tz, 0.0, destination)) return;
        relax(current, tx, ty, tz, BRIDGE_COST, Path.Movement.BRIDGE, 1,
                true, destination, PLACE_ACTION, Position.pack(tx, ty - 1, tz), placementItemToken,
                0, 0L, -1);
    }

    private void tryTransition(int current, int x, int y, int z, int tx, int ty, int tz,
                               Path.Movement movement, long baseCost, double arcHeight,
                               boolean requireSupport, boolean allowMedium, boolean diagonal) {
        if (!validPosition(tx, ty, tz)) return;
        if (diagonal && !cornersClear(x, Math.min(y, ty), z, tx - x, tz - z)) return;
        probe(tx, ty, tz, targetProbe);
        if (!targetProbe.loaded || targetProbe.hazard) return;
        tryTransitionWithProbe(current, x, y, z, tx, ty, tz, movement, baseCost, arcHeight,
                requireSupport, allowMedium, diagonal, targetProbe);
    }

    private void tryTransitionWithProbe(int current, int x, int y, int z, int tx, int ty, int tz,
                                        Path.Movement movement, long baseCost, double arcHeight,
                                        boolean requireSupport, boolean allowMedium, boolean diagonal,
                                        StanceProbe destination) {
        boolean willBreak = !destination.bodyClear;
        if (willBreak && (!allowBreaking || destination.breakCount < 1
                || destination.breakCount > StanceProbe.MAX_BREAK_TARGETS)) return;
        if (requireSupport && !destination.fullSupport) return;
        if (!destination.fullSupport && allowMedium) {
            boolean medium = (allowSwimming && destination.water && (sourceProbe.water || destination.water))
                    || (allowClimbing && destination.climbable && (sourceProbe.climbable || destination.climbable));
            if (!medium) return;
        }
        if (!destination.fullSupport && !allowMedium && requireSupport) return;
        if (diagonal && !cornersClear(x, Math.min(y, ty), z, tx - x, tz - z)) return;
        if (!motionClear(x, y, z, tx, ty, tz, arcHeight, destination)) return;

        int actionType1 = 0;
        int actionType2 = 0;
        long actionPos1 = 0L;
        long actionPos2 = 0L;
        int actionToken1 = -1;
        int actionToken2 = -1;
        long actionCost = baseCost;
        int count = 0;
        if (willBreak) {
            count = destination.breakCount;
            if (count < 1 || count > StanceProbe.MAX_BREAK_TARGETS) return;
            for (int i = 0; i < count; i++) {
                BreakTarget target = destination.breakTargets[i];
                if (target.cost < 1 || !terrain.canBreakFrom(x, y, z, destination, i)) return;
                actionCost += target.cost;
                if (i == 0) {
                    actionType1 = BREAK_ACTION;
                    actionPos1 = Position.pack(target.x, target.y, target.z);
                    actionToken1 = target.stateToken;
                } else {
                    actionType2 = BREAK_ACTION;
                    actionPos2 = Position.pack(target.x, target.y, target.z);
                    actionToken2 = target.stateToken;
                }
            }
        } else if (destination.breakCount != 0) {
            // A clear stance with stale obstruction metadata is an adapter contract violation.
            return;
        }
        relax(current, tx, ty, tz, actionCost, movement, 0, false, destination,
                actionType1, actionPos1, actionToken1, actionType2, actionPos2, actionToken2);
    }

    private boolean cornersClear(int x, int y, int z, int dx, int dz) {
        int ax = x + dx;
        int bz = z + dz;
        if (!validPosition(ax, y, z) || !validPosition(x, y, bz)) return false;
        probe(ax, y, z, sideProbe);
        if (!cornerStanceClear(sideProbe)) return false;
        probe(x, y, bz, sideProbe);
        return cornerStanceClear(sideProbe);
    }

    private boolean cornersClearAtFeet16(int x, int feetY16, int z, int dx, int dz) {
        int ax = x + dx;
        int bz = z + dz;
        int y = Math.floorDiv(feetY16, 16);
        if (!validPosition(ax, y, z) || !validPosition(x, y, bz)) return false;
        probeAtFeetY16(ax, feetY16, z, sideProbe);
        if (!groundCornerStanceClear(sideProbe)) return false;
        probeAtFeetY16(x, feetY16, bz, sideProbe);
        return groundCornerStanceClear(sideProbe);
    }

    private static boolean groundCornerStanceClear(StanceProbe stance) {
        return stance.loaded && stance.bodyClear && stance.hasGroundSupport()
                && !stance.hazard && stance.breakCount == 0;
    }

    private boolean cornerStanceClear(StanceProbe stance) {
        if (!stance.loaded || !stance.bodyClear || stance.hazard || stance.breakCount != 0) return false;
        return stance.fullSupport
                || (allowSwimming && stance.water)
                || (allowClimbing && stance.climbable);
    }

    private boolean motionClear(int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
                                double arcHeight, StanceProbe destination) {
        return terrain.isMotionClear(fromX + 0.5, fromY, fromZ + 0.5,
                toX + 0.5, toY, toZ + 0.5, arcHeight, sourceAfterBreak, destination);
    }

    /** A break action on the incoming edge makes this node's cached blockers virtual removals. */
    private boolean prepareSourceAfterBreak(int current) {
        sourceAfterBreak.clear();
        if (!sourceProbe.loaded || sourceProbe.hazard) return false;
        if (sourceProbe.bodyClear) {
            if (sourceProbe.breakCount != 0) return false;
            sourceAfterBreak.copyFrom(sourceProbe);
            return true;
        }

        int count = actionCounts[current];
        if (count < 1 || count > StanceProbe.MAX_BREAK_TARGETS || count != sourceProbe.breakCount) return false;
        for (int i = 0; i < count; i++) {
            int type = i == 0 ? actionType1[current] : actionType2[current];
            long packed = i == 0 ? actionPosition1[current] : actionPosition2[current];
            int token = i == 0 ? actionToken1[current] : actionToken2[current];
            BreakTarget observed = sourceProbe.breakTargets[i];
            if (type != BREAK_ACTION || Position.x(packed) != observed.x
                    || Position.y(packed) != observed.y || Position.z(packed) != observed.z
                    || token != observed.stateToken) return false;
            BreakTarget removed = sourceAfterBreak.breakTargets[i];
            removed.x = observed.x;
            removed.y = observed.y;
            removed.z = observed.z;
            removed.stateToken = observed.stateToken;
            removed.cost = observed.cost;
        }
        sourceAfterBreak.loaded = true;
        sourceAfterBreak.bodyClear = true;
        sourceAfterBreak.fullSupport = sourceProbe.fullSupport;
        sourceAfterBreak.surfaceSupport = sourceProbe.surfaceSupport;
        sourceAfterBreak.hazard = sourceProbe.hazard;
        sourceAfterBreak.water = sourceProbe.water;
        sourceAfterBreak.climbable = sourceProbe.climbable;
        sourceAfterBreak.breakCount = count;
        return true;
    }

    private void relax(int current, int x, int y, int z, long edgeCost, Path.Movement movement,
                       int addedPlacements, boolean supportBuilt, StanceProbe destination) {
        relax(current, x, y, z, edgeCost, movement, addedPlacements, supportBuilt, destination,
                0, 0L, -1, 0, 0L, -1);
    }

    private void relax(int current, int x, int y, int z, long edgeCost, Path.Movement movement,
                       int addedPlacements, boolean supportBuilt, StanceProbe destination,
                       int type1, long pos1, int token1, int type2, long pos2, int token2) {
        relaxAtFeetY16(current, x, y * 16, z, edgeCost, movement, addedPlacements,
                supportBuilt, destination, type1, pos1, token1, type2, pos2, token2);
    }

    private void relaxAtFeetY16(int current, int x, int feetY16, int z, long edgeCost,
                                Path.Movement movement, int addedPlacements, boolean supportBuilt,
                                StanceProbe destination, int type1, long pos1, int token1,
                                int type2, long pos2, int token2) {
        int newPlacements = placementsUsed[current] + addedPlacements;
        if (newPlacements > maxPlacements) return;
        int y = Math.floorDiv(feetY16, 16);
        int fraction = Math.floorMod(feetY16, 16);
        long packed = Position.pack(x, y, z);
        long candidateCost = costs[current] + edgeCost;
        int slot = locateSlot(packed, fraction, newPlacements, supportBuilt);
        int node = hashSlots[slot] - 1;
        if (node < 0) {
            if (nodeCount >= maxNodes) {
                nodeLimitHit = true;
                return;
            }
            node = nodeCount++;
            hashSlots[slot] = node + 1;
            positions[node] = packed;
            feetFractions[node] = (byte) fraction;
            placementsUsed[node] = newPlacements;
            builtSupport[node] = (byte) (supportBuilt ? 1 : 0);
            costs[node] = Long.MAX_VALUE;
            heuristics[node] = goal.heuristic16(x, feetY16, z);
            parents[node] = -1;
            heapPosition[node] = -1;
        }
        if (candidateCost >= costs[node]) return;

        costs[node] = candidateCost;
        parents[node] = current;
        movements[node] = (byte) movement.ordinal();
        int count = 0;
        if (type1 != 0) count++;
        if (type2 != 0) count++;
        actionCounts[node] = (byte) count;
        actionType1[node] = (byte) type1;
        actionType2[node] = (byte) type2;
        actionPosition1[node] = pos1;
        actionPosition2[node] = pos2;
        actionToken1[node] = token1;
        actionToken2[node] = token2;

        if (closed[node] != 0) closed[node] = 0;
        if (heapPosition[node] < 0) pushHeap(node);
        else siftUp(heapPosition[node]);
    }

    private int addNode(long packed, int fraction, int x, int y, int z,
                        int placementCount, boolean supportBuilt) {
        int slot = locateSlot(packed, fraction, placementCount, supportBuilt);
        if (hashSlots[slot] != 0) return -1;
        int node = nodeCount++;
        hashSlots[slot] = node + 1;
        positions[node] = packed;
        feetFractions[node] = (byte) fraction;
        placementsUsed[node] = placementCount;
        builtSupport[node] = (byte) (supportBuilt ? 1 : 0);
        costs[node] = Long.MAX_VALUE;
        heuristics[node] = goal.heuristic16(x, y * 16 + fraction, z);
        return node;
    }

    private int locateSlot(long packed, int fraction, int placements, boolean supportBuilt) {
        long hash = mix64(packed ^ (0x94d049bb133111ebL * fraction)
                ^ (PLACEMENT_HASH * (placements + 1L))
                ^ (supportBuilt ? SUPPORT_HASH : 0L));
        int mask = hashSlots.length - 1;
        int slot = (int) hash & mask;
        while (hashSlots[slot] != 0) {
            int existing = hashSlots[slot] - 1;
            if (positions[existing] == packed && (feetFractions[existing] & 0xff) == fraction
                    && placementsUsed[existing] == placements
                    && (builtSupport[existing] != 0) == supportBuilt) return slot;
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private void pushHeap(int node) {
        int at = heapSize++;
        heap[at] = node;
        heapPosition[node] = at;
        siftUp(at);
    }

    private int popHeap() {
        int result = heap[0];
        heapPosition[result] = -1;
        int replacement = heap[--heapSize];
        if (heapSize > 0) {
            heap[0] = replacement;
            heapPosition[replacement] = 0;
            siftDown(0);
        }
        return result;
    }

    private void siftUp(int at) {
        int node = heap[at];
        while (at > 0) {
            int parent = (at - 1) >>> 1;
            int parentNode = heap[parent];
            if (compare(parentNode, node) <= 0) break;
            heap[at] = parentNode;
            heapPosition[parentNode] = at;
            at = parent;
        }
        heap[at] = node;
        heapPosition[node] = at;
    }

    private void siftDown(int at) {
        int node = heap[at];
        int half = heapSize >>> 1;
        while (at < half) {
            int child = (at << 1) + 1;
            int right = child + 1;
            if (right < heapSize && compare(heap[right], heap[child]) < 0) child = right;
            if (compare(node, heap[child]) <= 0) break;
            int childNode = heap[child];
            heap[at] = childNode;
            heapPosition[childNode] = at;
            at = child;
        }
        heap[at] = node;
        heapPosition[node] = at;
    }

    private int compare(int a, int b) {
        long fa = costs[a] + heuristics[a];
        long fb = costs[b] + heuristics[b];
        if (fa != fb) return fa < fb ? -1 : 1;
        if (heuristics[a] != heuristics[b]) return heuristics[a] < heuristics[b] ? -1 : 1;
        return Integer.compare(a, b);
    }

    private void buildPath(int goalNode) {
        int length = 1;
        for (int n = goalNode; parents[n] >= 0; n = parents[n]) length++;
        Path.Step[] steps = new Path.Step[length];
        int node = goalNode;
        for (int i = length - 1; i >= 0; i--) {
            int x = Position.x(positions[node]);
            int feetY16 = feetY16(node);
            int z = Position.z(positions[node]);
            Path.Movement movement = Path.Movement.values()[movements[node]];
            Action[] actions;
            int count = actionCounts[node];
            if (count == 0) {
                actions = new Action[0];
            } else if (count == 1) {
                actions = new Action[] { makeAction(actionType1[node], actionPosition1[node], actionToken1[node]) };
            } else {
                actions = new Action[] {
                        makeAction(actionType1[node], actionPosition1[node], actionToken1[node]),
                        makeAction(actionType2[node], actionPosition2[node], actionToken2[node])
                };
            }
            steps[i] = Path.Step.atFeetY16(x, feetY16, z, movement, actions);
            node = parents[node];
            if (node < 0 && i != 0) throw new IllegalStateException("broken A* parent chain");
        }
        placementsInFoundPath = placementsUsed[goalNode];
        path = new Path(steps, costs[goalNode], placementsInFoundPath, terrainRevision);
    }

    private void buildPartialPath() {
        // Prefer any validated route edge over a zero-length result, even when the first safe
        // move temporarily increases distance to the goal (for example, walking around a wall).
        int best = nodeCount > 1 ? 1 : 0;
        for (int candidate = best + 1; candidate < nodeCount; candidate++) {
            if (heuristics[candidate] < heuristics[best]
                    || (heuristics[candidate] == heuristics[best] && costs[candidate] < costs[best])) {
                best = candidate;
            }
        }
        buildPath(best);
    }

    private static Action makeAction(byte type, long packed, int token) {
        Action.Type actionType = type == BREAK_ACTION ? Action.Type.BREAK_BLOCK : Action.Type.PLACE_BLOCK;
        return new Action(actionType, Position.x(packed), Position.y(packed), Position.z(packed), token);
    }

    private boolean probe(int x, int y, int z, StanceProbe out) {
        return probeAtFeetY16(x, y * 16, z, out);
    }

    private boolean probeAtFeetY16(int x, int feetY16, int z, StanceProbe out) {
        int y = Math.floorDiv(feetY16, 16);
        if (!validPosition(x, y, z)) {
            out.clear();
            return false;
        }
        long key = Position.pack(x, y, z);
        return probeCache.getOrProbe(key, Math.floorMod(feetY16, 16), x, feetY16, z, out, terrain);
    }

    private int feetY16(int node) {
        return Position.y(positions[node]) * 16 + (feetFractions[node] & 0xff);
    }

    private static int integerFeetY16(int y) {
        Position.pack(0, y, 0);
        return y * 16;
    }

    private static boolean validPosition(int x, int y, int z) {
        return x >= -33_554_432 && x <= 33_554_431
                && z >= -33_554_432 && z <= 33_554_431
                && y >= -2_048 && y <= 2_047;
    }

    private static int tableCapacity(int requested) {
        int cap = 1;
        while (cap < requested) {
            if (cap >= (1 << 30)) throw new IllegalArgumentException("search table too large");
            cap <<= 1;
        }
        return cap;
    }

    private static final int[][] CARDINALS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    private static final class ProbeCache {
        private final long[] keys;
        private final byte[] occupied;
        private final byte[] fractions;
        private final byte[] flags;
        private final byte[] breakCounts;
        private final int[] x1, y1, z1, state1, cost1;
        private final int[] x2, y2, z2, state2, cost2;
        private final int mask;
        private int size;

        ProbeCache(int desiredEntries) {
            int capacity = tableCapacity(Math.max(16, desiredEntries));
            keys = new long[capacity];
            occupied = new byte[capacity];
            fractions = new byte[capacity];
            flags = new byte[capacity];
            breakCounts = new byte[capacity];
            x1 = new int[capacity]; y1 = new int[capacity]; z1 = new int[capacity];
            state1 = new int[capacity]; cost1 = new int[capacity];
            x2 = new int[capacity]; y2 = new int[capacity]; z2 = new int[capacity];
            state2 = new int[capacity]; cost2 = new int[capacity];
            mask = capacity - 1;
        }

        boolean getOrProbe(long key, int fraction, int x, int feetY16, int z,
                           StanceProbe out, Terrain terrain) {
            int slot = (int) mix64(key ^ (0x94d049bb133111ebL * fraction)) & mask;
            int start = slot;
            while (occupied[slot] != 0) {
                if (keys[slot] == key && (fractions[slot] & 0xff) == fraction) {
                    restore(slot, out);
                    return out.loaded;
                }
                slot = (slot + 1) & mask;
                if (slot == start) {
                    out.clear();
                    terrain.probeStance16(x, feetY16, z, out);
                    return out.loaded;
                }
            }

            out.clear();
            terrain.probeStance16(x, feetY16, z, out);
            if (size < keys.length * 7 / 10) {
                occupied[slot] = 1;
                keys[slot] = key;
                fractions[slot] = (byte) fraction;
                store(slot, out);
                size++;
            }
            return out.loaded;
        }

        private void store(int slot, StanceProbe probe) {
            flags[slot] = (byte) ((probe.loaded ? 1 : 0) | (probe.bodyClear ? 2 : 0)
                    | (probe.fullSupport ? 4 : 0) | (probe.hazard ? 8 : 0)
                    | (probe.water ? 16 : 0) | (probe.climbable ? 32 : 0)
                    | (probe.surfaceSupport ? 64 : 0));
            int count = Math.max(0, Math.min(StanceProbe.MAX_BREAK_TARGETS + 1, probe.breakCount));
            breakCounts[slot] = (byte) count;
            if (count > 0) {
                BreakTarget target = probe.breakTargets[0];
                x1[slot] = target.x; y1[slot] = target.y; z1[slot] = target.z;
                state1[slot] = target.stateToken; cost1[slot] = target.cost;
            }
            if (count > 1) {
                BreakTarget target = probe.breakTargets[1];
                x2[slot] = target.x; y2[slot] = target.y; z2[slot] = target.z;
                state2[slot] = target.stateToken; cost2[slot] = target.cost;
            }
        }

        private void restore(int slot, StanceProbe out) {
            int value = flags[slot];
            out.loaded = (value & 1) != 0;
            out.bodyClear = (value & 2) != 0;
            out.fullSupport = (value & 4) != 0;
            out.hazard = (value & 8) != 0;
            out.water = (value & 16) != 0;
            out.climbable = (value & 32) != 0;
            out.surfaceSupport = (value & 64) != 0;
            out.breakCount = breakCounts[slot];
            if (out.breakCount > 0) {
                BreakTarget target = out.breakTargets[0];
                target.x = x1[slot]; target.y = y1[slot]; target.z = z1[slot];
                target.stateToken = state1[slot]; target.cost = cost1[slot];
            }
            if (out.breakCount > 1) {
                BreakTarget target = out.breakTargets[1];
                target.x = x2[slot]; target.y = y2[slot]; target.z = z2[slot];
                target.stateToken = state2[slot]; target.cost = cost2[slot];
            }
        }
    }
}
