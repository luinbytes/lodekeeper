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
import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import dev.lodekeeper.navigation.kernel.OwnedCoalescedJob;
import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.*;
import dev.lodekeeper.navigation.kernel.api.process.IMineProcess;
import dev.lodekeeper.navigation.kernel.api.process.OwnedMiningTargets;
import dev.lodekeeper.navigation.kernel.api.process.PathingCommand;
import dev.lodekeeper.navigation.kernel.api.process.PathingCommandType;
import dev.lodekeeper.navigation.kernel.api.utils.*;
import dev.lodekeeper.navigation.kernel.api.utils.input.Input;
import dev.lodekeeper.navigation.kernel.cache.CachedChunk;
import dev.lodekeeper.navigation.kernel.pathing.movement.CalculationContext;
import dev.lodekeeper.navigation.kernel.pathing.movement.MovementHelper;
import dev.lodekeeper.navigation.kernel.utils.BaritoneProcessHelper;
import dev.lodekeeper.navigation.kernel.utils.BlockStateInterface;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.stream.Collectors;

import static dev.lodekeeper.navigation.kernel.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Mine blocks of a certain type
 *
 * @author leijurv
 */
public final class MineProcess extends BaritoneProcessHelper implements IMineProcess, OwnedMiningTargets {

    private final OwnedCoalescedJob<ScanInput, List<BlockPos>> rescanJob;
    private volatile long processGeneration;
    private boolean initialScanComplete;
    private OwnedKernelRuntime.Session miningSession;
    private record ScanInput(CalculationContext context, BlockOptionalMetaLookup filter, int maximum,
                             List<BlockPos> known, List<BlockPos> blacklist, List<BlockPos> dropped) {}
    private BlockOptionalMetaLookup filter;
    private List<BlockPos> knownOreLocations;
    private LinkedHashSet<BlockPos> nativeBlacklist;
    private LinkedHashSet<BlockPos> ownedRejections;
    private LinkedHashSet<BlockPos> admittedTargets;
    private Map<BlockPos, Long> anticipatedDrops;
    private BlockPos branchPoint;
    private GoalRunAway branchPointRunaway;
    private int desiredQuantity;
    private int tickCount;

    public MineProcess(Baritone baritone) {
        super(baritone);
        rescanJob = baritone.getRuntime().maintenanceJob();
    }

    @Override
    public boolean isActive() {
        return filter != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        baritone.getRuntime().requireMainThread();
        var scanned = rescanJob.poll();
        if (scanned != null) {
            initialScanComplete = true;
            List<BlockPos> merged = new ArrayList<>(knownOreLocations);
            merged.addAll(scanned.valueOrThrow());
            knownOreLocations = prune(new CalculationContext(baritone), merged, filterFilter(),
                    Baritone.settings().mineMaxOreLocationsCount.value, allMiningRejections(), droppedItemsScan());
            knownOreLocations.removeIf(this::isRejected);
            admittedTargets.retainAll(knownOreLocations);
            if (knownOreLocations.isEmpty() && !Baritone.settings().exploreForBlocks.value) {
                cancel();
                return null;
            }
        }
        if (!initialScanComplete && !Baritone.settings().legitMine.value) {
            requestRescan(List.of());
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (desiredQuantity > 0) {
            int curr = ctx.player().getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> filter.has(stack))
                    .mapToInt(ItemStack::getCount).sum();
            if (curr >= desiredQuantity) {
                logDirect("Have " + curr + " valid items");
                cancel();
                return null;
            }
        }
        if (calcFailed) {
            if (!knownOreLocations.isEmpty() && Baritone.settings().blacklistClosestOnFailure.value) {
                logDirect("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", blacklisting presumably unreachable closest instance...", true);
                }
                knownOreLocations.stream().min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).ifPresent(this::rememberNativeRejection);
                knownOreLocations.removeIf(this::isRejected);
            } else {
                logDirect("Unable to find any path to " + filter + ", canceling mine");
                if (Baritone.settings().notificationOnMineFail.value) {
                    logNotification("Unable to find any path to " + filter + ", canceling mine", true);
                }
                cancel();
                return null;
            }
        }

        updateLoucaSystem();
        int mineGoalUpdateInterval = Baritone.settings().mineGoalUpdateInterval.value;
        List<BlockPos> curr = new ArrayList<>(knownOreLocations);
        if (mineGoalUpdateInterval != 0 && tickCount++ % mineGoalUpdateInterval == 0) { // big brain
            requestRescan(curr);
        }
        if (Baritone.settings().legitMine.value) {
            if (!addNearby()) {
                cancel();
                return null;
            }
        }
        Optional<BlockPos> shaft = curr.stream()
                .filter(pos -> pos.getX() == ctx.playerFeet().getX() && pos.getZ() == ctx.playerFeet().getZ())
                .filter(pos -> pos.getY() >= ctx.playerFeet().getY())
                .filter(pos -> !(BlockStateInterface.get(ctx, pos).getBlock() instanceof AirBlock)) // after breaking a block, it takes mineGoalUpdateInterval ticks for it to actually update this list =(
                .min(Comparator.comparingDouble(ctx.playerFeet().above()::distSqr));
        baritone.getInputOverrideHandler().clearAllKeys();
        if (shaft.isPresent() && ctx.player().onGround()) {
            BlockPos pos = shaft.get();
            BlockState state = baritone.bsi.get0(pos);
            if (!MovementHelper.avoidBreaking(baritone.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
                Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
                if (rot.isPresent() && isSafeToCancel) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                    if (ctx.isLookingAt(pos) || ctx.playerRotations().isReallyCloseTo(rot.get())) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        PathingCommand command = updateGoal();
        if (command == null) {
            // none in range
            // maybe say something in chat? (ahem impact)
            cancel();
            return null;
        }
        return command;
    }


    private void updateLoucaSystem() {
        Map<BlockPos, Long> copy = new HashMap<>(anticipatedDrops);
        ctx.getSelectedBlock().ifPresent(pos -> {
            if (knownOreLocations.contains(pos)) {
                copy.put(pos, System.currentTimeMillis() + Baritone.settings().mineDropLoiterDurationMSThanksLouca.value);
            }
        });
        // elaborate dance to avoid concurrentmodificationexcepption since rescan thread reads this
        // don't want to slow everything down with a gross lock do we now
        for (BlockPos pos : anticipatedDrops.keySet()) {
            if (copy.get(pos) < System.currentTimeMillis()) {
                copy.remove(pos);
            }
        }
        anticipatedDrops = copy;
    }

    @Override
    public void onLostControl() {
        mine(0, (BlockOptionalMetaLookup) null);
    }

    @Override
    public String displayName0() {
        return "Mine " + filter;
    }

    private PathingCommand updateGoal() {
        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return null;
        }

        boolean legit = Baritone.settings().legitMine.value;
        List<BlockPos> locs = knownOreLocations;
        if (!locs.isEmpty()) {
            CalculationContext context = new CalculationContext(baritone);
            List<BlockPos> locs2 = prune(context, new ArrayList<>(locs), filter, Baritone.settings().mineMaxOreLocationsCount.value, allMiningRejections(), droppedItemsScan());
            if (!locs2.isEmpty() && locs2.stream().allMatch(pos -> nearbySnapshotPending(context, pos))) {
                knownOreLocations = locs2;
                admittedTargets.retainAll(knownOreLocations);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // can't reassign locs, gotta make a new var locs2, because we use it in a lambda right here, and variables you use in a lambda must be effectively final
            Goal goal = new GoalComposite(locs2.stream().map(loc -> coalesce(loc, locs2, context)).toArray(Goal[]::new));
            knownOreLocations = locs2;
            admittedTargets.retainAll(knownOreLocations);
            return new PathingCommand(goal, legit ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH : PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        // we don't know any ore locations at the moment
        if (!legit && !Baritone.settings().exploreForBlocks.value) {
            return null;
        }
        // only when we should explore for blocks or are in legit mode we do this
        int y = Baritone.settings().legitMineYLevel.value;
        if (branchPoint == null) {
            /*if (!baritone.getPathingBehavior().isPathing() && playerFeet().y == y) {
                // cool, path is over and we are at desired y
                branchPoint = playerFeet();
                branchPointRunaway = null;
            } else {
                return new GoalYLevel(y);
            }*/
            branchPoint = ctx.playerFeet();
        }
        // TODO shaft mode, mine 1x1 shafts to either side
        // TODO also, see if the GoalRunAway with maintain Y at 11 works even from the surface
        if (branchPointRunaway == null) {
            branchPointRunaway = new GoalRunAway(1, y, branchPoint) {
                @Override
                public boolean isInGoal(int x, int y, int z) {
                    return false;
                }

                @Override
                public double heuristic() {
                    return Double.NEGATIVE_INFINITY;
                }
            };
        }
        return new PathingCommand(branchPointRunaway, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private void requestRescan(List<BlockPos> already) {
        baritone.getRuntime().requireMainThread();
        if (rescanJob.busy() || Baritone.settings().legitMine.value) { return; }
        BlockOptionalMetaLookup target = filterFilter();
        if (target == null) { return; }
        CalculationContext context = new CalculationContext(baritone, true);
        if (!context.snapshot.hasLiveChunk(context.playerFeet.x, context.playerFeet.z)) { return; }
        ScanInput input = new ScanInput(context, target, Baritone.settings().mineMaxOreLocationsCount.value,
                List.copyOf(already), allMiningRejections(), List.copyOf(droppedItemsScan()));
        OwnedKernelRuntime.Session expectedSession = miningSession;
        long generation = processGeneration;
        rescanJob.submit(input, () -> processGeneration == generation && context.session == expectedSession
                && baritone.getRuntime().isCurrent(context.session) && context.policy.current(), captured -> {
            List<BlockPos> dropped = new ArrayList<>(captured.dropped());
            List<BlockPos> found = searchWorld(captured.context(), captured.filter(), captured.maximum(), captured.known(), captured.blacklist(), dropped);
            found.addAll(dropped);
            return List.copyOf(found);
        });
    }

    private boolean internalMiningGoal(BlockPos pos, CalculationContext context, List<BlockPos> locs) {
        // Here, BlockStateInterface is used because the position may be in a cached chunk (the targeted block is one that is kept track of)
        if (locs.contains(pos)) {
            return true;
        }
        BlockState state = context.bsi.get0(pos);
        if (Baritone.settings().internalMiningAirException.value && state.getBlock() instanceof AirBlock) {
            return true;
        }
        return filter.has(state) && plausibleToBreak(context, pos);
    }

    private Goal coalesce(BlockPos loc, List<BlockPos> locs, CalculationContext context) {
        boolean assumeVerticalShaftMine = !(baritone.bsi.get0(loc.above()).getBlock() instanceof FallingBlock);
        if (!Baritone.settings().forceInternalMining.value) {
            if (assumeVerticalShaftMine) {
                // we can get directly below the block
                return new GoalThreeBlocks(loc);
            } else {
                // we need to get feet or head into the block
                return new GoalTwoBlocks(loc);
            }
        }
        boolean upwardGoal = internalMiningGoal(loc.above(), context, locs);
        boolean downwardGoal = internalMiningGoal(loc.below(), context, locs);
        boolean doubleDownwardGoal = internalMiningGoal(loc.below(2), context, locs);
        if (upwardGoal == downwardGoal) { // symmetric
            if (doubleDownwardGoal && assumeVerticalShaftMine) {
                // we have a checkerboard like pattern
                // this one, and the one two below it
                // therefore it's fine to path to immediately below this one, since your feet will be in the doubleDownwardGoal
                // but only if assumeVerticalShaftMine
                return new GoalThreeBlocks(loc);
            } else {
                // this block has nothing interesting two below, but is symmetric vertically so we can get either feet or head into it
                return new GoalTwoBlocks(loc);
            }
        }
        if (upwardGoal) {
            // downwardGoal known to be false
            // ignore the gap then potential doubleDownward, because we want to path feet into this one and head into upwardGoal
            return new GoalBlock(loc);
        }
        // upwardGoal known to be false, downwardGoal known to be true
        if (doubleDownwardGoal && assumeVerticalShaftMine) {
            // this block and two below it are goals
            // path into the center of the one below, because that includes directly below this one
            return new GoalTwoBlocks(loc.below());
        }
        // upwardGoal false, downwardGoal true, doubleDownwardGoal false
        // just this block and the one immediately below, no others
        return new GoalBlock(loc.below());
    }

    private static class GoalThreeBlocks extends GoalTwoBlocks {

        public GoalThreeBlocks(BlockPos pos) {
            super(pos);
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return x == this.x && (y == this.y || y == this.y - 1 || y == this.y - 2) && z == this.z;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            return GoalBlock.calculate(xDiff, yDiff < -1 ? yDiff + 2 : yDiff == -1 ? 0 : yDiff, zDiff);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * 393857768;
        }

        @Override
        public String toString() {
            return String.format(
                    "GoalThreeBlocks{x=%s,y=%s,z=%s}",
                    KernelSettingsUtil.maybeCensor(x),
                    KernelSettingsUtil.maybeCensor(y),
                    KernelSettingsUtil.maybeCensor(z)
            );
        }
    }

    public List<BlockPos> droppedItemsScan() {
        baritone.getRuntime().requireMainThread();
        if (!Baritone.settings().mineScanDroppedItems.value) {
            return Collections.emptyList();
        }
        List<BlockPos> ret = new ArrayList<>();
        for (Entity entity : ((ClientLevel) ctx.world()).entitiesForRendering()) {
            if (entity instanceof ItemEntity) {
                ItemEntity ei = (ItemEntity) entity;
                if (filter.has(ei.getItem())) {
                    ret.add(entity.blockPosition());
                }
            }
        }
        ret.addAll(anticipatedDrops.keySet());
        return ret;
    }

    public static List<BlockPos> searchWorld(CalculationContext ctx, BlockOptionalMetaLookup filter, int max, List<BlockPos> alreadyKnown, List<BlockPos> blacklist, List<BlockPos> dropped) {
        checkScanInterrupted();
        max = Math.max(0, Math.min(max, 65_536));
        List<BlockPos> locs = new ArrayList<>();
        List<Block> untracked = new ArrayList<>();
        for (BlockOptionalMeta bom : filter.blocks()) {
            checkScanInterrupted();
            Block block = bom.getBlock();
            if (CachedChunk.BLOCKS_TO_KEEP_TRACK_OF.contains(block)) {
                locs.addAll(ctx.snapshot.cachedLocations(BlockUtils.blockToString(block), Math.min(max - locs.size(), ctx.scanOptions.cachedMaximum())));
            } else {
                untracked.add(block);
            }
        }

        locs = prune(ctx, locs, filter, max, blacklist, dropped, Set.of());

        if (!untracked.isEmpty() || (ctx.scanOptions.extendCache() && locs.size() < max)) {
            locs.addAll(ctx.snapshot.scan(filter::has, max, ctx.playerFeet));
        }

        checkScanInterrupted();
        for (BlockPos pos : alreadyKnown) { checkScanInterrupted(); locs.add(pos); }

        return prune(ctx, locs, filter, max, blacklist, dropped, new HashSet<>(alreadyKnown));
    }

    private boolean addNearby() {
        List<BlockPos> dropped = droppedItemsScan();
        knownOreLocations.addAll(dropped);
        BlockPos playerFeet = ctx.playerFeet();
        BlockStateInterface bsi = new BlockStateInterface(ctx);


        BlockOptionalMetaLookup filter = filterFilter();
        if (filter == null) {
            return false;
        }

        int searchDist = 10;
        double fakedBlockReachDistance = 20; // at least 10 * sqrt(3) with some extra space to account for positioning within the block
        for (int x = playerFeet.getX() - searchDist; x <= playerFeet.getX() + searchDist; x++) {
            for (int y = playerFeet.getY() - searchDist; y <= playerFeet.getY() + searchDist; y++) {
                for (int z = playerFeet.getZ() - searchDist; z <= playerFeet.getZ() + searchDist; z++) {
                    // crucial to only add blocks we can see because otherwise this
                    // is an x-ray and it'll get caught
                    if (filter.has(bsi.get0(x, y, z))) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if ((Baritone.settings().legitMineIncludeDiagonals.value && knownOreLocations.stream().anyMatch(ore -> ore.distSqr(pos) <= 2 /* sq means this is pytha dist <= sqrt(2) */)) || RotationUtils.reachable(ctx, pos, fakedBlockReachDistance).isPresent()) {
                            knownOreLocations.add(pos);
                        }
                    }
                }
            }
        }
        knownOreLocations = prune(new CalculationContext(baritone), knownOreLocations, filter, Baritone.settings().mineMaxOreLocationsCount.value, allMiningRejections(), dropped);
        admittedTargets.retainAll(knownOreLocations);
        return true;
    }

    private static void checkScanInterrupted() {
        if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException("Owned mine scan interrupted"); }
    }

    private static boolean snapshotComplete(CalculationContext ctx, BlockPos pos) {
        if (!ctx.snapshot.hasLiveChunk(pos.getX(), pos.getZ())) { return false; }
        BlockState state = ctx.get(pos.getX(), pos.getY(), pos.getZ());
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            BlockPos companion = pos.relative(state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                    ? facing : facing.getOpposite());
            if (!ctx.snapshot.hasLiveChunk(companion.getX(), companion.getZ())) { return false; }
        }
        if (ctx.scanOptions.onlyExposed()) {
            int radius = ctx.scanOptions.exposedRadius();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= radius
                            && !ctx.snapshot.hasLiveChunk(pos.getX() + dx, pos.getZ() + dz)) { return false; }
                }
            }
        }
        return true;
    }

    private static boolean nearbySnapshotPending(CalculationContext ctx, BlockPos pos) {
        return Math.abs((pos.getX() >> 4) - (ctx.playerFeet.getX() >> 4)) <= 3
                && Math.abs((pos.getZ() >> 4) - (ctx.playerFeet.getZ() >> 4)) <= 3
                && !snapshotComplete(ctx, pos);
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped) {
        return prune(ctx, locs2, filter, max, blacklist, dropped, new HashSet<>(locs2));
    }

    private static List<BlockPos> prune(CalculationContext ctx, List<BlockPos> locs2, BlockOptionalMetaLookup filter, int max, List<BlockPos> blacklist, List<BlockPos> dropped, Set<BlockPos> preserved) {
        checkScanInterrupted();
        Set<BlockPos> remainingDrops = new HashSet<>();
        for (BlockPos drop : dropped) {
            checkScanInterrupted();
            boolean nearOre = false;
            for (BlockPos pos : locs2) {
                checkScanInterrupted();
                if (pos.distSqr(drop) <= 9 && filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && MineProcess.plausibleToBreak(ctx, pos)) {
                    nearOre = true;
                    break;
                }
            }
            if (!nearOre) { remainingDrops.add(drop); }
        }
        Set<BlockPos> denied = new HashSet<>();
        for (BlockPos pos : blacklist) { checkScanInterrupted(); denied.add(pos); }
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> locs = new ArrayList<>();
        for (BlockPos pos : locs2) {
            checkScanInterrupted();
            if (!seen.add(pos) || denied.contains(pos)
                    || pos.getY() < ctx.scanOptions.minimumY() + ctx.minY || pos.getY() > ctx.scanOptions.maximumY()) { continue; }
            if (ctx.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())
                    && !filter.has(ctx.get(pos.getX(), pos.getY(), pos.getZ())) && !remainingDrops.contains(pos)) { continue; }
            if (remainingDrops.contains(pos)) { locs.add(pos); continue; }
            if (!ctx.policy.mayBreak(pos)) { continue; }
            if (!snapshotComplete(ctx, pos)) {
                if (preserved.contains(pos)) { locs.add(pos); }
                continue;
            }
            if (!MineProcess.plausibleToBreak(ctx, pos)) { continue; }
            if (ctx.scanOptions.onlyExposed() && !isNextToAir(ctx, pos)) { continue; }
            locs.add(pos);
        }
        locs.sort((first, second) -> {
            checkScanInterrupted();
            return Double.compare(ctx.playerFeet.distSqr(first), ctx.playerFeet.distSqr(second));
        });
        checkScanInterrupted();
        return locs.size() > max ? locs.subList(0, max) : locs;
    }

    public static boolean isNextToAir(CalculationContext ctx, BlockPos pos) {
        checkScanInterrupted();
        int radius = ctx.scanOptions.exposedRadius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    checkScanInterrupted();
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= radius
                            && MovementHelper.isTransparent(ctx.getBlock(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos) {
        checkScanInterrupted();
        BlockState state = ctx.bsi.get0(pos);
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(), state, true) >= COST_INF) {
            return false;
        }
        checkScanInterrupted();
        if (MovementHelper.avoidBreaking(ctx.bsi, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }

        checkScanInterrupted();
        // bedrock above and below makes it implausible, otherwise we're good
        return !(ctx.bsi.get0(pos.above()).getBlock() == Blocks.BEDROCK && ctx.bsi.get0(pos.below()).getBlock() == Blocks.BEDROCK);
    }

    @Override
    public void mineByName(int quantity, String... blocks) {
        mine(quantity, new BlockOptionalMetaLookup(blocks));
    }

    @Override
    public void mine(int quantity, BlockOptionalMetaLookup filter) {
        baritone.getRuntime().requireMainThread();
        processGeneration++;
        initialScanComplete = false;
        rescanJob.cancel();
        this.filter = filter;
        if (this.filterFilter() == null) {
            this.filter = null;
        }
        this.miningSession = this.filter == null ? null : baritone.getRuntime().captureSession();
        this.desiredQuantity = quantity;
        this.knownOreLocations = new ArrayList<>();
        this.nativeBlacklist = new LinkedHashSet<>();
        this.ownedRejections = new LinkedHashSet<>();
        this.admittedTargets = new LinkedHashSet<>();
        this.branchPoint = null;
        this.branchPointRunaway = null;
        this.anticipatedDrops = new HashMap<>();
        if (this.filter != null) {
            requestRescan(List.of());
        }
    }

    @Override
    public Optional<OwnedMiningTargets.Snapshot> ownedMiningTargetsSnapshot() {
        baritone.getRuntime().requireMainThread();
        return hasCurrentMiningSession() ? Optional.of(ownedMiningSnapshot()) : Optional.empty();
    }

    @Override
    public Optional<OwnedMiningTargets.Snapshot> replaceOwnedMiningTargets(Collection<BlockPos> targets, Collection<BlockPos> rejections) {
        baritone.getRuntime().requireMainThread();
        OwnedMiningTargets.Snapshot incoming = OwnedMiningTargets.Snapshot.copyOf(targets, rejections);
        if (!hasCurrentMiningSession()) { return Optional.empty(); }

        processGeneration++;
        rescanJob.cancel();
        replaceOwnedRejections(incoming.rejections());
        admittedTargets = new LinkedHashSet<>();
        knownOreLocations = incoming.targets().stream()
                .filter(position -> !isRejected(position))
                .collect(Collectors.toCollection(ArrayList::new));
        admittedTargets.addAll(knownOreLocations);
        initialScanComplete = !knownOreLocations.isEmpty();
        if (!initialScanComplete) {
            requestRescan(List.of());
        }
        return Optional.of(ownedMiningSnapshot());
    }

    @Override
    public Optional<OwnedMiningTargets.Snapshot> admitOwnedMiningTargets(Collection<BlockPos> targets, Collection<BlockPos> rejections) {
        baritone.getRuntime().requireMainThread();
        OwnedMiningTargets.Snapshot incoming = OwnedMiningTargets.Snapshot.copyOf(targets, rejections);
        if (!hasCurrentMiningSession()) { return Optional.empty(); }

        List<BlockPos> nextRejections = new ArrayList<>(ownedRejections);
        for (BlockPos rejection : incoming.rejections()) {
            if (!nativeBlacklist.contains(rejection)) { nextRejections.add(rejection); }
        }
        nextRejections = OwnedMiningTargets.Snapshot.copyOf(List.of(), nextRejections).rejections();
        Set<BlockPos> denied = new HashSet<>(allMiningRejections());
        denied.addAll(incoming.rejections());
        boolean rejectionStateChanged = !List.copyOf(ownedRejections).equals(nextRejections);
        OwnedMiningAdmission.Plan plan = OwnedMiningAdmission.plan(knownOreLocations, admittedTargets,
                incoming.targets(), denied, rejectionStateChanged, () -> {
                    processGeneration++;
                    rescanJob.cancel();
                });
        if (!plan.changed()) { return Optional.of(ownedMiningSnapshot()); }

        replaceOwnedRejections(nextRejections);
        knownOreLocations = new ArrayList<>(plan.processTargets());
        admittedTargets = new LinkedHashSet<>(plan.callerTargets());
        initialScanComplete = !knownOreLocations.isEmpty();
        if (!initialScanComplete) {
            requestRescan(List.of());
        }
        return Optional.of(ownedMiningSnapshot());
    }

    private OwnedMiningTargets.Snapshot ownedMiningSnapshot() {
        LinkedHashSet<BlockPos> targets = new LinkedHashSet<>();
        for (BlockPos position : admittedTargets) {
            if (knownOreLocations.contains(position) && !isRejected(position)) { targets.add(position); }
        }
        for (BlockPos position : knownOreLocations) {
            if (targets.size() == OwnedMiningTargets.MAX_TARGETS) { break; }
            if (!isRejected(position)) { targets.add(position); }
        }
        List<BlockPos> rejections = new ArrayList<>(ownedRejections);
        rejections.addAll(nativeBlacklist);
        return OwnedMiningTargets.Snapshot.copyOf(targets, rejections);
    }

    private boolean hasCurrentMiningSession() {
        return filterFilter() != null && miningSession != null
                && baritone.getRuntime().isCurrent(miningSession)
                && baritone.getPlayerContext().world() == miningSession.world();
    }

    private void rememberNativeRejection(BlockPos position) {
        BlockPos immutable = position.immutable();
        nativeBlacklist.remove(immutable);
        nativeBlacklist.add(immutable);
        ownedRejections.remove(immutable);
        admittedTargets.remove(immutable);
    }

    private void replaceOwnedRejections(Collection<BlockPos> rejections) {
        ownedRejections = new LinkedHashSet<>();
        for (BlockPos rejection : rejections) {
            if (!nativeBlacklist.contains(rejection)) { ownedRejections.add(rejection); }
        }
    }

    private boolean isRejected(BlockPos position) {
        return nativeBlacklist.contains(position) || ownedRejections.contains(position);
    }

    private List<BlockPos> allMiningRejections() {
        LinkedHashSet<BlockPos> rejections = new LinkedHashSet<>(ownedRejections);
        rejections.addAll(nativeBlacklist);
        return List.copyOf(rejections);
    }

    private BlockOptionalMetaLookup filterFilter() {
        if (this.filter == null) {
            return null;
        }
        if (!Baritone.settings().allowBreak.value) {
            BlockOptionalMetaLookup f = new BlockOptionalMetaLookup(this.filter.blocks()
                    .stream()
                    .filter(e -> Baritone.settings().allowBreakAnyway.value.contains(e.getBlock()))
                    .toArray(BlockOptionalMeta[]::new));
            if (f.blocks().isEmpty()) {
                logDirect("Unable to mine when allowBreak is false and target block is not in allowBreakAnyway!");
                return null;
            }
            return f;
        }
        return filter;
    }
}
