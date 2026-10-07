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

package dev.lodekeeper.navigation.kernel.api.pathing.calc;

import dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal;
import dev.lodekeeper.navigation.kernel.api.utils.PathCalculationResult;

import java.util.Optional;

/**
 * Generic path finder interface
 *
 * @author leijurv
 */
public interface IPathFinder {

    /** Returns an immutable, bounded view of the active search. */
    default SearchPreview searchPreview() {
        return SearchPreview.EMPTY;
    }

    Goal getGoal();

    /**
     * Calculate the path in full. Will take several seconds.
     *
     * @param primaryTimeout If a path is found, the path finder will stop after this amount of time
     * @param failureTimeout If a path isn't found, the path finder will continue for this amount of time
     * @return The final path
     */
    PathCalculationResult calculate(long primaryTimeout, long failureTimeout);

    /**
     * Intended to be called concurrently with calculatePath from a different thread to tell if it's finished yet
     *
     * @return Whether or not this finder is finished
     */
    boolean isFinished();

    /**
     * Called for path rendering. Returns a path to the most recent node popped from the open set and considered.
     *
     * @return The temporary path
     */
    Optional<IPath> pathToMostRecentNodeConsidered();

    /**
     * The best path so far, according to the most forgiving coefficient heuristic (the reason being that that path is
     * most likely to represent the true shape of the path to the goal, assuming it's within a possible cost heuristic.
     * That's almost always a safe assumption, but in the case of a nearly impossible path, it still works by providing
     * a theoretically plausible but practically unlikely path)
     *
     * @return The temporary path
     */
    Optional<IPath> bestPathSoFar();


    /** Immutable progress and a bounded predecessor-chain segment copied by the finder. */
    final class SearchPreview {
        public static final int MAX_NODES = 128;
        public static final SearchPreview EMPTY = new SearchPreview(0, 0, 0, new int[0], new boolean[0]);

        private final long expandedNodes;
        private final int discoveredNodes;
        private final int frontierSize;
        private final int[] coordinates;
        private final boolean[] nodeOpen;

        public SearchPreview(long expandedNodes, int discoveredNodes, int frontierSize,
                             int[] coordinates, boolean[] nodeOpen) {
            if (expandedNodes < 0 || discoveredNodes < 0 || frontierSize < 0
                    || coordinates == null || nodeOpen == null || coordinates.length % 3 != 0
                    || coordinates.length / 3 != nodeOpen.length || nodeOpen.length > MAX_NODES) {
                throw new IllegalArgumentException("Invalid bounded search preview");
            }
            this.expandedNodes = expandedNodes;
            this.discoveredNodes = discoveredNodes;
            this.frontierSize = frontierSize;
            this.coordinates = coordinates.clone();
            this.nodeOpen = nodeOpen.clone();
        }

        public long expandedNodes() { return expandedNodes; }
        public int discoveredNodes() { return discoveredNodes; }
        public int frontierSize() { return frontierSize; }
        public int nodeCount() { return nodeOpen.length; }
        public int nodeX(int index) { return coordinates[index * 3]; }
        public int nodeY(int index) { return coordinates[index * 3 + 1]; }
        public int nodeZ(int index) { return coordinates[index * 3 + 2]; }
        public boolean nodeIsOpen(int index) { return nodeOpen[index]; }
    }
}
