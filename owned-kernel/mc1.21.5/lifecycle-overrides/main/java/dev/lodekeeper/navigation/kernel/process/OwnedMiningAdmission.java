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

package dev.lodekeeper.navigation.kernel.process;

import dev.lodekeeper.navigation.kernel.api.process.OwnedMiningTargets;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure target-state planning shared by MineProcess and its focused admission fixture. */
final class OwnedMiningAdmission {
    record Plan(List<BlockPos> processTargets, List<BlockPos> callerTargets, boolean changed) {}

    private OwnedMiningAdmission() {}

    static Plan plan(List<BlockPos> currentProcessTargets, Collection<BlockPos> currentCallerTargets,
                     Collection<BlockPos> incomingTargets, Set<BlockPos> denied,
                     boolean rejectionStateChanged, Runnable fence) {
        LinkedHashSet<BlockPos> processTargets = new LinkedHashSet<>();
        for (BlockPos target : currentProcessTargets) {
            if (!denied.contains(target)) { processTargets.add(target.immutable()); }
        }

        LinkedHashSet<BlockPos> callerTargets = new LinkedHashSet<>();
        for (BlockPos target : currentCallerTargets) {
            if (processTargets.contains(target) && !denied.contains(target)
                    && callerTargets.size() < OwnedMiningTargets.MAX_TARGETS) {
                callerTargets.add(target.immutable());
            }
        }
        for (BlockPos target : incomingTargets) {
            if (callerTargets.size() == OwnedMiningTargets.MAX_TARGETS) { break; }
            BlockPos immutable = target.immutable();
            if (!denied.contains(immutable)) {
                processTargets.add(immutable);
                callerTargets.add(immutable);
            }
        }

        List<BlockPos> nextProcessTargets = List.copyOf(processTargets);
        List<BlockPos> nextCallerTargets = List.copyOf(callerTargets);
        boolean changed = rejectionStateChanged
                || !currentProcessTargets.equals(nextProcessTargets)
                || !List.copyOf(currentCallerTargets).equals(nextCallerTargets);
        if (changed) { fence.run(); }
        return new Plan(nextProcessTargets, nextCallerTargets, changed);
    }
}
