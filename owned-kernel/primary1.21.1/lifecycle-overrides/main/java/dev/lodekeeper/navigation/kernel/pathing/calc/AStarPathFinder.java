/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.lodekeeper.navigation.kernel.pathing.calc;

import dev.lodekeeper.navigation.kernel.Baritone;
import dev.lodekeeper.navigation.kernel.api.pathing.calc.IPath;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal;
import dev.lodekeeper.navigation.kernel.api.pathing.movement.ActionCosts;
import dev.lodekeeper.navigation.kernel.api.utils.BetterBlockPos;
import dev.lodekeeper.navigation.kernel.api.utils.KernelSettingsUtil;
import dev.lodekeeper.navigation.kernel.pathing.calc.openset.BinaryHeapOpenSet;
import dev.lodekeeper.navigation.kernel.pathing.movement.CalculationContext;
import dev.lodekeeper.navigation.kernel.pathing.movement.Moves;
import dev.lodekeeper.navigation.kernel.utils.pathing.BetterWorldBorder;
import dev.lodekeeper.navigation.kernel.utils.pathing.Favoring;
import dev.lodekeeper.navigation.kernel.utils.pathing.MutableMoveResult;

import java.util.Optional;

/**
 * The actual A* pathfinding
 *
 * @author leijurv
 */
public final class AStarPathFinder extends AbstractNodeCostSearch {

    private final Favoring favoring;
    private final CalculationContext calcContext;

    public AStarPathFinder(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, Favoring favoring, CalculationContext context) {
        super(realStart, startX, startY, startZ, goal, context);
        this.favoring = favoring;
        this.calcContext = context;
    }

    @Override
    protected Optional<IPath> calculate0(long primaryTimeout, long failureTimeout) {
        int minY = calcContext.minY;
        int height = calcContext.maxY - calcContext.minY;
        startNode = getNodeAtPosition(startX, startY, startZ, BetterBlockPos.longHash(startX, startY, startZ));
        startNode.cost = 0;
        startNode.combinedCost = startNode.estimatedCostToGoal;
        BinaryHeapOpenSet openSet = new BinaryHeapOpenSet();
        openSet.insert(startNode);
        double[] bestHeuristicSoFar = new double[COEFFICIENTS.length];//keep track of the best node by the metric of (estimatedCostToGoal + cost / COEFFICIENTS[i])
        for (int i = 0; i < bestHeuristicSoFar.length; i++) {
            bestHeuristicSoFar[i] = startNode.estimatedCostToGoal;
            bestSoFar[i] = startNode;
        }
        MutableMoveResult res = new MutableMoveResult();
        BetterWorldBorder worldBorder = calcContext.worldBorder;
        long startTime = System.currentTimeMillis();
        boolean slowPath = Baritone.settings().slowPath.value;
        if (slowPath) {
            System.out.println("slowPath is on, path timeout will be " + Baritone.settings().slowPathTimeoutMS.value + "ms instead of " + primaryTimeout + "ms");
        }
        long primaryTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : primaryTimeout);
        long failureTimeoutTime = startTime + (slowPath ? Baritone.settings().slowPathTimeoutMS.value : failureTimeout);
        boolean failing = true;
        int numNodes = 0;
        int numMovementsConsidered = 0;
        int numEmptyChunk = 0;
        boolean isFavoring = !favoring.isEmpty();
        int timeCheckInterval = 1 << 6;
        int lastPreviewNodeCount = 0;
        long lastPreviewMillis = System.currentTimeMillis();
        int pathingMaxChunkBorderFetch = Baritone.settings().pathingMaxChunkBorderFetch.value; // grab all settings beforehand so that changing settings during pathing doesn't cause a crash or unpredictable behavior
        double minimumImprovement = Baritone.settings().minimumImprovementRepropagation.value ? MIN_IMPROVEMENT : 0;
        Moves[] allMoves = Moves.values();
        boolean debugSearch = Baritone.settings().chatDebug.value;
        StringBuilder firstExpansion = debugSearch ? new StringBuilder() : null;
        String stopReason = "exception";
        try {
            while (!openSet.isEmpty() && numEmptyChunk < pathingMaxChunkBorderFetch && !cancelRequested && !Thread.currentThread().isInterrupted()) {
                if ((numNodes & (timeCheckInterval - 1)) == 0) { // only call this once every 64 nodes (about half a millisecond)
                    long previewNow = System.currentTimeMillis();
                    if (numNodes - lastPreviewNodeCount >= 256 || previewNow - lastPreviewMillis >= 50) {
                        publishSearchPreview(numNodes, openSet.size());
                        lastPreviewNodeCount = numNodes;
                        lastPreviewMillis = previewNow;
                    }
                    long now = System.currentTimeMillis(); // since nanoTime is slow on windows (takes many microseconds)
                    if (now - failureTimeoutTime >= 0 || (!failing && now - primaryTimeoutTime >= 0)) {
                        if (debugSearch) {
                            stopReason = now - failureTimeoutTime >= 0 ? "failure_timeout" : "primary_timeout";
                        }
                        break;
                    }
                }
                if (slowPath) {
                    try {
                        Thread.sleep(Baritone.settings().slowPathTimeDelayMS.value);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cancel();
                        publishSearchPreview(numNodes, openSet.size());
                        if (debugSearch) {
                            stopReason = "slow_path_interrupted";
                        }
                        return Optional.empty();
                    }
                }
                PathNode currentNode = openSet.removeLowest();
                mostRecentConsidered = currentNode;
                numNodes++;
                boolean recordFirstExpansion = debugSearch && numNodes == 1;
                if (goal.isInGoal(currentNode.x, currentNode.y, currentNode.z)) {
                    publishSearchPreview(numNodes, openSet.size());
                    System.out.println("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
                    if (debugSearch) {
                        stopReason = "goal_reached";
                    }
                    return Optional.of(new Path(realStart, startNode, currentNode, numNodes, goal, calcContext));
                }
                for (Moves moves : allMoves) {
                    int newX = currentNode.x + moves.xOffset;
                    int newZ = currentNode.z + moves.zOffset;
                    if (recordFirstExpansion) {
                        firstExpansion.append(" move=").append(moves)
                                .append(" expectedDestination=").append(newX).append(',')
                                .append(currentNode.y + moves.yOffset).append(',').append(newZ);
                    }
                    if ((newX >> 4 != currentNode.x >> 4 || newZ >> 4 != currentNode.z >> 4) && !calcContext.isLoaded(newX, newZ)) {
                        // only need to check if the destination is a loaded chunk if it's in a different chunk than the start of the movement
                        if (!moves.dynamicXZ) { // only increment the counter if the movement would have gone out of bounds guaranteed
                            numEmptyChunk++;
                        }
                        if (recordFirstExpansion) {
                            firstExpansion.append(" skip=unloaded_chunk cost=not_evaluated counter=").append(numEmptyChunk);
                        }
                        continue;
                    }
                    if (!moves.dynamicXZ && !worldBorder.entirelyContains(newX, newZ)) {
                        if (recordFirstExpansion) {
                            firstExpansion.append(" skip=world_border cost=not_evaluated");
                        }
                        continue;
                    }
                    if (currentNode.y + moves.yOffset > height || currentNode.y + moves.yOffset < minY) {
                        if (recordFirstExpansion) {
                            firstExpansion.append(" skip=height_bounds cost=not_evaluated");
                        }
                        continue;
                    }
                    res.reset();
                    moves.apply(calcContext, currentNode.x, currentNode.y, currentNode.z, res);
                    numMovementsConsidered++;
                    double actionCost = res.cost;
                    if (recordFirstExpansion) {
                        firstExpansion.append(" resultDestination=").append(res.x).append(',').append(res.y).append(',').append(res.z)
                                .append(" cost=").append(actionCost);
                    }
                    if (actionCost >= ActionCosts.COST_INF) {
                        if (recordFirstExpansion) {
                            firstExpansion.append(" skip=cost_inf destinationMayBeUnset=true");
                        }
                        continue;
                    }
                    if (actionCost <= 0 || Double.isNaN(actionCost)) {
                        if (recordFirstExpansion) {
                            firstExpansion.append(" skip=invalid_cost");
                        }
                        throw new IllegalStateException(String.format(
                                "%s from %s %s %s calculated implausible cost %s",
                                moves,
                                KernelSettingsUtil.maybeCensor(currentNode.x),
                                KernelSettingsUtil.maybeCensor(currentNode.y),
                                KernelSettingsUtil.maybeCensor(currentNode.z),
                                actionCost));
                    }
                    // check destination after verifying it's not COST_INF -- some movements return COST_INF without adjusting the destination
                    if (moves.dynamicXZ && !worldBorder.entirelyContains(res.x, res.z)) { // see issue #218
                        if (recordFirstExpansion) {
                            firstExpansion.append(" skip=dynamic_world_border");
                        }
                        continue;
                    }
                    if (!moves.dynamicXZ && (res.x != newX || res.z != newZ)) {
                        throw new IllegalStateException(String.format(
                                "%s from %s %s %s ended at x z %s %s instead of %s %s",
                                moves,
                                KernelSettingsUtil.maybeCensor(currentNode.x),
                                KernelSettingsUtil.maybeCensor(currentNode.y),
                                KernelSettingsUtil.maybeCensor(currentNode.z),
                                KernelSettingsUtil.maybeCensor(res.x),
                                KernelSettingsUtil.maybeCensor(res.z),
                                KernelSettingsUtil.maybeCensor(newX),
                                KernelSettingsUtil.maybeCensor(newZ)));
                    }
                    if (!moves.dynamicY && res.y != currentNode.y + moves.yOffset) {
                        throw new IllegalStateException(String.format(
                                "%s from %s %s %s ended at y %s instead of %s",
                                moves,
                                KernelSettingsUtil.maybeCensor(currentNode.x),
                                KernelSettingsUtil.maybeCensor(currentNode.y),
                                KernelSettingsUtil.maybeCensor(currentNode.z),
                                KernelSettingsUtil.maybeCensor(res.y),
                                KernelSettingsUtil.maybeCensor(currentNode.y + moves.yOffset)));
                    }
                    long hashCode = BetterBlockPos.longHash(res.x, res.y, res.z);
                    if (isFavoring) {
                        // see issue #18
                        actionCost *= favoring.calculate(hashCode);
                    }
                    if (recordFirstExpansion) {
                        firstExpansion.append(" effectiveCost=").append(actionCost);
                    }
                    PathNode neighbor = getNodeAtPosition(res.x, res.y, res.z, hashCode);
                    double tentativeCost = currentNode.cost + actionCost;
                    if (neighbor.cost - tentativeCost > minimumImprovement) {
                        neighbor.previous = currentNode;
                        neighbor.cost = tentativeCost;
                        neighbor.combinedCost = tentativeCost + neighbor.estimatedCostToGoal;
                        if (neighbor.isOpen()) {
                            openSet.update(neighbor);
                        } else {
                            openSet.insert(neighbor);//dont double count, dont insert into open set if it's already there
                        }
                        for (int i = 0; i < COEFFICIENTS.length; i++) {
                            double heuristic = neighbor.estimatedCostToGoal + neighbor.cost / COEFFICIENTS[i];
                            if (bestHeuristicSoFar[i] - heuristic > minimumImprovement) {
                                bestHeuristicSoFar[i] = heuristic;
                                bestSoFar[i] = neighbor;
                                if (failing && getDistFromStartSq(neighbor) > MIN_DIST_PATH * MIN_DIST_PATH) {
                                    failing = false;
                                }
                            }
                        }
                    }
                }
            }
            if (debugSearch && stopReason.equals("exception")) {
                stopReason = openSet.isEmpty() ? "open_set_exhausted"
                        : numEmptyChunk >= pathingMaxChunkBorderFetch ? "chunk_border_limit"
                        : cancelRequested ? "cancel_requested" : "thread_interrupted";
            }
            publishSearchPreview(numNodes, openSet.size());
            if (cancelRequested) {
                return Optional.empty();
            }
            System.out.println(numMovementsConsidered + " movements considered");
            System.out.println("Open set size: " + openSet.size());
            System.out.println("PathNode map size: " + mapSize());
            System.out.println((int) (numNodes * 1.0 / ((System.currentTimeMillis() - startTime) / 1000F)) + " nodes per second");
            Optional<IPath> result = bestSoFar(true, numNodes);
            if (result.isPresent()) {
                System.out.println("Took " + (System.currentTimeMillis() - startTime) + "ms, " + numMovementsConsidered + " movements considered");
            }
            return result;
        } catch (RuntimeException | Error failure) {
            if (debugSearch) {
                stopReason = "exception_" + failure.getClass().getSimpleName();
            }
            throw failure;
        } finally {
            if (debugSearch) {
                StringBuilder diagnostic = new StringBuilder("A* stop reason=" + stopReason
                        + " start=" + startX + "," + startY + "," + startZ + " goal=" + goal
                        + " elapsedMs=" + (System.currentTimeMillis() - startTime)
                        + " primaryTimeoutMs=" + (primaryTimeoutTime - startTime)
                        + " failureTimeoutMs=" + (failureTimeoutTime - startTime)
                        + " failing=" + failing + " emptyChunk=" + numEmptyChunk
                        + " chunkBorderLimit=" + pathingMaxChunkBorderFetch
                        + " open=" + openSet.size() + " map=" + mapSize()
                        + " nodes=" + numNodes + " moves=" + numMovementsConsidered);
                if (firstExpansion.length() > 0) {
                    diagnostic.append(" firstExpansion=[").append(firstExpansion).append(']');
                }
                for (int i = 0; i < COEFFICIENTS.length; i++) {
                    PathNode retained = bestSoFar[i];
                    diagnostic.append(" fallback coefficient=" + COEFFICIENTS[i]
                            + " endpoint=" + retained.x + "," + retained.y + "," + retained.z
                            + " heuristic=" + retained.estimatedCostToGoal + " cost=" + retained.cost
                            + " score=" + bestHeuristicSoFar[i] + " distanceSq=" + getDistFromStartSq(retained));
                }
                logDebug(diagnostic.toString());
            }
        }
    }
}
