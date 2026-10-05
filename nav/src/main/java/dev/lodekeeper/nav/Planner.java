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

    public Planner(Terrain terrain, int startX, int startY, int startZ, Goal goal, Options options) {
        if (terrain == null || goal == null) throw new NullPointerException("terrain and goal are required");
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
        probe(startX, startY, startZ, sourceProbe);
        if (!sourceProbe.loaded || !sourceProbe.bodyClear || sourceProbe.hazard) {
            status = NavStatus.NO_PATH;
            return;
        }
        int index = addNode(start, startX, startY, startZ, 0, false);
        if (index < 0) {
            status = NavStatus.PARTIAL_LIMIT;
            return;
        }
        costs[index] = 0;
        heuristics[index] = goal.heuristic(startX, startY, startZ);
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
            if (goal.matches(x, y, z)) {
                buildPath(current);
                status = NavStatus.FOUND;
                return status;
            }

            probe(x, y, z, sourceProbe);
            if (!prepareSourceAfterBreak(current)) continue;
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

    public NavStatus getStatus() { return status; }
    public Path getPath() { return path; }
    public long getExpandedNodes() { return expandedNodes; }
    public int getDiscoveredNodes() { return nodeCount; }
    public int getOpenNodes() { return heapSize; }

    private void expandLocal(int current, int x, int y, int z) {
        boolean hasSupport = sourceProbe.fullSupport || builtSupport[current] != 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diagonal = dx != 0 && dz != 0;
                tryHorizontal(current, x, y, z, dx, dz, diagonal, hasSupport);
                if (nodeLimitHit) return;
            }
        }

        // One-block ledge climbs are cardinal and require a supported landing.
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

        if (allowParkour && hasSupport) {
            for (int[] direction : CARDINALS) {
                tryParkour(current, x, y, z, direction[0], direction[1], 2);
                if (nodeLimitHit) return;
                tryParkour(current, x, y, z, direction[0], direction[1], 3);
                if (nodeLimitHit) return;
            }
        }

        if (allowSwimming || allowClimbing) {
            tryVerticalMedium(current, x, y, z, 1);
            if (nodeLimitHit) return;
            tryVerticalMedium(current, x, y, z, -1);
        }
    }

    private void tryHorizontal(int current, int x, int y, int z, int dx, int dz,
                               boolean diagonal, boolean sourceHasSupport) {
        int tx = x + dx;
        int tz = z + dz;
        if (!validPosition(tx, y, tz)) return;
        if (diagonal && !cornersClear(x, y, z, dx, dz)) return;
        probe(tx, y, tz, targetProbe);
        if (!targetProbe.loaded || targetProbe.hazard) return;

        Path.Movement movement;
        long baseCost;
        boolean supported = targetProbe.fullSupport;
        if (supported) {
            movement = Path.Movement.WALK;
            baseCost = diagonal ? DIAGONAL_COST : WALK_COST;
        } else if (allowSwimming && (sourceProbe.water || targetProbe.water)) {
            movement = Path.Movement.SWIM;
            baseCost = MEDIUM_COST;
        } else if (allowClimbing && (sourceProbe.climbable || targetProbe.climbable)) {
            movement = Path.Movement.CLIMB;
            baseCost = MEDIUM_COST;
        } else if (!diagonal && allowBuilding && targetProbe.bodyClear && sourceHasSupport
                && placementsUsed[current] < maxPlacements) {
            tryBridge(current, x, y, z, tx, y, tz, targetProbe);
            return;
        } else {
            return;
        }
        tryTransitionWithProbe(current, x, y, z, tx, y, tz, movement, baseCost, 0.0,
                supported, true, false, targetProbe);
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
        if (!destination.loaded || !destination.bodyClear || destination.hazard
                || destination.fullSupport || destination.water || destination.climbable) return;
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
        if (sourceProbe.bodyClear) return true;

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
        int newPlacements = placementsUsed[current] + addedPlacements;
        if (newPlacements > maxPlacements) return;
        long packed = Position.pack(x, y, z);
        long candidateCost = costs[current] + edgeCost;
        int slot = locateSlot(packed, newPlacements, supportBuilt);
        int node = hashSlots[slot] - 1;
        if (node < 0) {
            if (nodeCount >= maxNodes) {
                nodeLimitHit = true;
                return;
            }
            node = nodeCount++;
            hashSlots[slot] = node + 1;
            positions[node] = packed;
            placementsUsed[node] = newPlacements;
            builtSupport[node] = (byte) (supportBuilt ? 1 : 0);
            costs[node] = Long.MAX_VALUE;
            heuristics[node] = goal.heuristic(x, y, z);
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

    private int addNode(long packed, int x, int y, int z, int placementCount, boolean supportBuilt) {
        int slot = locateSlot(packed, placementCount, supportBuilt);
        if (hashSlots[slot] != 0) return -1;
        int node = nodeCount++;
        hashSlots[slot] = node + 1;
        positions[node] = packed;
        placementsUsed[node] = placementCount;
        builtSupport[node] = (byte) (supportBuilt ? 1 : 0);
        costs[node] = Long.MAX_VALUE;
        heuristics[node] = goal.heuristic(x, y, z);
        return node;
    }

    private int locateSlot(long packed, int placements, boolean supportBuilt) {
        long hash = mix64(packed ^ (PLACEMENT_HASH * (placements + 1L))
                ^ (supportBuilt ? SUPPORT_HASH : 0L));
        int mask = hashSlots.length - 1;
        int slot = (int) hash & mask;
        while (hashSlots[slot] != 0) {
            int existing = hashSlots[slot] - 1;
            if (positions[existing] == packed && placementsUsed[existing] == placements
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
            int y = Position.y(positions[node]);
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
            steps[i] = new Path.Step(x, y, z, movement, actions);
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
        if (!validPosition(x, y, z)) {
            out.clear();
            return false;
        }
        long key = Position.pack(x, y, z);
        return probeCache.getOrProbe(key, x, y, z, out, terrain);
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
            flags = new byte[capacity];
            breakCounts = new byte[capacity];
            x1 = new int[capacity]; y1 = new int[capacity]; z1 = new int[capacity];
            state1 = new int[capacity]; cost1 = new int[capacity];
            x2 = new int[capacity]; y2 = new int[capacity]; z2 = new int[capacity];
            state2 = new int[capacity]; cost2 = new int[capacity];
            mask = capacity - 1;
        }

        boolean getOrProbe(long key, int x, int y, int z, StanceProbe out, Terrain terrain) {
            int slot = (int) mix64(key) & mask;
            int start = slot;
            while (occupied[slot] != 0) {
                if (keys[slot] == key) {
                    restore(slot, out);
                    return out.loaded;
                }
                slot = (slot + 1) & mask;
                if (slot == start) {
                    out.clear();
                    terrain.probeStance(x, y, z, out);
                    return out.loaded;
                }
            }

            out.clear();
            terrain.probeStance(x, y, z, out);
            if (size < keys.length * 7 / 10) {
                occupied[slot] = 1;
                keys[slot] = key;
                store(slot, out);
                size++;
            }
            return out.loaded;
        }

        private void store(int slot, StanceProbe probe) {
            flags[slot] = (byte) ((probe.loaded ? 1 : 0) | (probe.bodyClear ? 2 : 0)
                    | (probe.fullSupport ? 4 : 0) | (probe.hazard ? 8 : 0)
                    | (probe.water ? 16 : 0) | (probe.climbable ? 32 : 0));
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
