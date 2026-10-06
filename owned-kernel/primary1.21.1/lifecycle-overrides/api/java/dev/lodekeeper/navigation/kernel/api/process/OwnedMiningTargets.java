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

package dev.lodekeeper.navigation.kernel.api.process;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owned, main-thread access to the current mine process's bounded target state.
 * Empty optionals indicate an inactive process or a stale world session.
 */
public interface OwnedMiningTargets {
    int MAX_TARGETS = 64;
    int MAX_REJECTIONS = 512;

    record Snapshot(List<BlockPos> targets, List<BlockPos> rejections) {
        public Snapshot {
            rejections = immutableRejections(rejections);
            targets = immutableTargets(targets, new HashSet<>(rejections));
        }

        public static Snapshot copyOf(Collection<BlockPos> targets, Collection<BlockPos> rejections) {
            List<BlockPos> copiedRejections = immutableRejections(rejections);
            return new Snapshot(immutableTargets(targets, new HashSet<>(copiedRejections)), copiedRejections);
        }

        private static List<BlockPos> immutableRejections(Collection<BlockPos> positions) {
            Objects.requireNonNull(positions, "rejections");
            LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
            for (BlockPos position : positions) {
                BlockPos immutable = Objects.requireNonNull(position, "rejection").immutable();
                copy.remove(immutable);
                copy.add(immutable);
                if (copy.size() > MAX_REJECTIONS) {
                    copy.remove(copy.iterator().next());
                }
            }
            return List.copyOf(copy);
        }

        private static List<BlockPos> immutableTargets(Collection<BlockPos> positions, Set<BlockPos> denied) {
            Objects.requireNonNull(positions, "targets");
            LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
            for (BlockPos position : positions) {
                BlockPos immutable = Objects.requireNonNull(position, "target").immutable();
                if (!denied.contains(immutable) && copy.size() < MAX_TARGETS) {
                    copy.add(immutable);
                }
            }
            return List.copyOf(copy);
        }
    }

    /** Returns a defensive snapshot, or empty when mining is inactive or its world session is stale. */
    Optional<Snapshot> ownedMiningTargetsSnapshot();

    /**
     * Replaces the caller-owned seed and rejections. Native path-failure rejections remain in effect.
     * An empty seed requests normal process scanning with the merged rejection set.
     */
    Optional<Snapshot> replaceOwnedMiningTargets(Collection<BlockPos> targets, Collection<BlockPos> rejections);

    /** Adds caller-owned targets and rejections, preserving existing and native process rejections. */
    Optional<Snapshot> admitOwnedMiningTargets(Collection<BlockPos> targets, Collection<BlockPos> rejections);
}
