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

import dev.lodekeeper.navigation.kernel.Baritone;
import dev.lodekeeper.navigation.kernel.OwnedCoalescedJob;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.*;
import dev.lodekeeper.navigation.kernel.api.process.IGetToBlockProcess;
import dev.lodekeeper.navigation.kernel.api.process.PathingCommand;
import dev.lodekeeper.navigation.kernel.api.process.PathingCommandType;
import dev.lodekeeper.navigation.kernel.api.utils.BlockOptionalMeta;
import dev.lodekeeper.navigation.kernel.api.utils.BlockOptionalMetaLookup;
import dev.lodekeeper.navigation.kernel.api.utils.Rotation;
import dev.lodekeeper.navigation.kernel.api.utils.RotationUtils;
import dev.lodekeeper.navigation.kernel.api.utils.input.Input;
import dev.lodekeeper.navigation.kernel.pathing.movement.CalculationContext;
import dev.lodekeeper.navigation.kernel.pathing.movement.MovementHelper;
import dev.lodekeeper.navigation.kernel.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class GetToBlockProcess extends BaritoneProcessHelper implements IGetToBlockProcess {

    private final OwnedCoalescedJob<ScanInput, List<BlockPos>> rescanJob;
    private volatile long processGeneration;
    private record ScanInput(CalculationContext context, BlockOptionalMetaLookup target,
                             List<BlockPos> known, List<BlockPos> blacklist) {}
    private BlockOptionalMeta gettingTo;
    private List<BlockPos> knownLocations;
    private List<BlockPos> blacklist; // locations we failed to calc to
    private BlockPos start;

    private int tickCount = 0;
    private int arrivalTickCount = 0;

    public GetToBlockProcess(Baritone baritone) {
        super(baritone);
        rescanJob = baritone.getRuntime().maintenanceJob();
    }

    @Override
    public void getToBlock(BlockOptionalMeta block) {
        onLostControl();
        gettingTo = block;
        start = ctx.playerFeet();
        blacklist = new ArrayList<>();
        arrivalTickCount = 0;
        requestRescan(List.of());
    }

    @Override
    public boolean isActive() {
        return gettingTo != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        baritone.getRuntime().requireMainThread();
        var scanned = rescanJob.poll();
        if (scanned != null) { knownLocations = new ArrayList<>(scanned.valueOrThrow()); }
        if (knownLocations == null) {
            requestRescan(List.of());
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (knownLocations.isEmpty()) {
            if (Baritone.settings().exploreForBlocks.value && !calcFailed) {
                return new PathingCommand(new GoalRunAway(1, start) {
                    @Override
                    public boolean isInGoal(int x, int y, int z) {
                        return false;
                    }

                    @Override
                    public double heuristic() {
                        return Double.NEGATIVE_INFINITY;
                    }
                }, PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
            }
            logDirect("No known locations of " + gettingTo + ", canceling GetToBlock");
            if (isSafeToCancel) {
                onLostControl();
            }
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Goal goal = new GoalComposite(knownLocations.stream().map(this::createGoal).toArray(Goal[]::new));
        if (calcFailed) {
            if (Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + gettingTo + ", blacklisting presumably unreachable closest instances...");
                blacklistClosest();
                return onTick(false, isSafeToCancel); // gamer moment
            } else {
                logDirect("Unable to find any path to " + gettingTo + ", canceling GetToBlock");
                if (isSafeToCancel) {
                    onLostControl();
                }
                return new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            List<BlockPos> current = new ArrayList<>(knownLocations);
            requestRescan(current);
        }
        if (goal.isInGoal(ctx.playerFeet()) && goal.isInGoal(baritone.getPathingBehavior().pathStart()) && isSafeToCancel) {
            // we're there
            if (rightClickOnArrival(gettingTo.getBlock())) {
                if (rightClick()) {
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            } else {
                onLostControl();
                return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
            }
        }
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    // blacklist the closest block and its adjacent blocks
    public synchronized boolean blacklistClosest() {
        List<BlockPos> newBlacklist = new ArrayList<>();
        knownLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(newBlacklist::add);
        outer:
        while (true) {
            for (BlockPos known : knownLocations) {
                for (BlockPos blacklist : newBlacklist) {
                    if (areAdjacent(known, blacklist)) { // directly adjacent
                        newBlacklist.add(known);
                        knownLocations.remove(known);
                        continue outer;
                    }
                }
            }
            // i can't do break; (codacy gets mad), and i can't do if(true){break}; (codacy gets mad)
            // so i will do this
            switch (newBlacklist.size()) {
                default:
                    break outer;
            }
        }
        logDebug("Blacklisting unreachable locations " + newBlacklist);
        blacklist.addAll(newBlacklist);
        return !newBlacklist.isEmpty();
    }

    // this is to signal to MineProcess that we don't care about the allowBreak setting
    // it is NOT to be used to actually calculate a path
    public class GetToBlockCalculationContext extends CalculationContext {

        public GetToBlockCalculationContext(boolean forUseOnAnotherThread) {
            super(GetToBlockProcess.super.baritone, forUseOnAnotherThread);
        }

        @Override
        public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
            return dev.lodekeeper.navigation.kernel.OwnedMutationGuard.planBreak(policy, snapshot, new BlockPos(x, y, z), current) ? 1 : dev.lodekeeper.navigation.kernel.api.pathing.movement.ActionCosts.COST_INF;
        }
    }

    // safer than direct double comparison from distanceSq
    private boolean areAdjacent(BlockPos posA, BlockPos posB) {
        int diffX = Math.abs(posA.getX() - posB.getX());
        int diffY = Math.abs(posA.getY() - posB.getY());
        int diffZ = Math.abs(posA.getZ() - posB.getZ());
        return (diffX + diffY + diffZ) == 1;
    }

    @Override
    public void onLostControl() {
        baritone.getRuntime().requireMainThread();
        processGeneration++;
        rescanJob.cancel();
        gettingTo = null;
        knownLocations = null;
        start = null;
        blacklist = null;
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        if (knownLocations == null || knownLocations.isEmpty()) {
            return "Exploring randomly to find " + gettingTo + ", no known locations";
        }
        return "Get To " + gettingTo + ", " + knownLocations.size() + " known locations";
    }

    private void requestRescan(List<BlockPos> known) {
        baritone.getRuntime().requireMainThread();
        if (rescanJob.busy() || gettingTo == null) { return; }
        CalculationContext context = new GetToBlockCalculationContext(true);
        if (!context.snapshot.hasLiveChunk(context.playerFeet.x, context.playerFeet.z)) { return; }
        ScanInput input = new ScanInput(context, new BlockOptionalMetaLookup(gettingTo), List.copyOf(known), List.copyOf(blacklist));
        long generation = processGeneration;
        rescanJob.submit(input, () -> processGeneration == generation && baritone.getRuntime().isCurrent(context.session) && context.policy.current(), captured ->
                List.copyOf(MineProcess.searchWorld(captured.context(), captured.target(), 64, captured.known(), captured.blacklist(), new ArrayList<>())));
    }

    private Goal createGoal(BlockPos pos) {
        if (walkIntoInsteadOfAdjacent(gettingTo.getBlock())) {
            return new GoalTwoBlocks(pos);
        }
        if (blockOnTopMustBeRemoved(gettingTo.getBlock()) && MovementHelper.isBlockNormalCube(baritone.bsi.get0(pos.above()))) { // TODO this should be the check for chest openability
            return new GoalBlock(pos.above());
        }
        return new GoalGetToBlock(pos);
    }

    private boolean rightClick() {
        for (BlockPos pos : knownLocations) {
            Optional<Rotation> reachable = RotationUtils.reachable(ctx, pos, ctx.playerController().getBlockReachDistance());
            if (reachable.isPresent()) {
                baritone.getLookBehavior().updateTarget(reachable.get(), true);
                if (knownLocations.contains(ctx.getSelectedBlock().orElse(null))) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true); // TODO find some way to right click even if we're in an ESC menu
                    System.out.println(ctx.player().containerMenu);
                    if (!(ctx.player().containerMenu instanceof InventoryMenu)) {
                        return true;
                    }
                }
                if (arrivalTickCount++ > 20) {
                    logDirect("Right click timed out");
                    return true;
                }
                return false; // trying to right click, will do it next tick or so
            }
        }
        logDirect("Arrived but failed to right click open");
        return true;
    }

    private boolean walkIntoInsteadOfAdjacent(Block block) {
        if (!Baritone.settings().enterPortal.value) {
            return false;
        }
        return block == Blocks.NETHER_PORTAL;
    }

    private boolean rightClickOnArrival(Block block) {
        if (!Baritone.settings().rightClickContainerOnArrival.value) {
            return false;
        }
        return block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    private boolean blockOnTopMustBeRemoved(Block block) {
        if (!rightClickOnArrival(block)) { // only if we plan to actually open it on arrival
            return false;
        }
        // only these chests; you can open a crafting table or furnace even with a block on top
        return block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }
}
