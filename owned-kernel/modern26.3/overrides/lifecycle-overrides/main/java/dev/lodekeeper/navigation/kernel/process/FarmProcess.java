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
import dev.lodekeeper.navigation.kernel.OwnedMutationGuard;
import dev.lodekeeper.navigation.kernel.snapshot.ImmutableWorldView;
import java.util.Set;
import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalBlock;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalGetToBlock;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.GoalComposite;
import dev.lodekeeper.navigation.kernel.api.process.IFarmProcess;
import dev.lodekeeper.navigation.kernel.api.process.PathingCommand;
import dev.lodekeeper.navigation.kernel.api.process.PathingCommandType;
import dev.lodekeeper.navigation.kernel.api.utils.BetterBlockPos;
import dev.lodekeeper.navigation.kernel.api.utils.RayTraceUtils;
import dev.lodekeeper.navigation.kernel.api.utils.Rotation;
import dev.lodekeeper.navigation.kernel.api.utils.RotationUtils;
import dev.lodekeeper.navigation.kernel.api.utils.input.Input;
import dev.lodekeeper.navigation.kernel.pathing.movement.MovementHelper;
import dev.lodekeeper.navigation.kernel.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    private final OwnedCoalescedJob<ScanInput, List<BlockPos>> rescanJob;
    private volatile long processGeneration;
    private record ScanInput(ImmutableWorldView view, Set<Block> blocks, int maximum, BlockPos feet) {}
    private boolean active;

    private List<BlockPos> locations;
    private int tickCount;

    private int range;
    private BlockPos center;

    private static final List<Item> FARMLAND_PLANTABLE = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.WHEAT_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.POTATO,
            Items.CARROT
    );

    private static final List<Item> PICKUP_DROPPED = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.BEETROOT,
            Items.MELON_SEEDS,
            Items.MELON_SLICE,
            Blocks.MELON.asItem(),
            Items.WHEAT_SEEDS,
            Items.WHEAT,
            Items.PUMPKIN_SEEDS,
            Blocks.PUMPKIN.asItem(),
            Items.POTATO,
            Items.CARROT,
            Items.NETHER_WART,
            Items.COCOA_BEANS,
            Blocks.SUGAR_CANE.asItem(),
            Blocks.BAMBOO.asItem(),
            Blocks.CACTUS.asItem()
    );

    public FarmProcess(Baritone baritone) {
        super(baritone);
        rescanJob = baritone.getRuntime().maintenanceJob();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farm(int range, BlockPos pos) {
        baritone.getRuntime().requireMainThread();
        processGeneration++;
        rescanJob.cancel();
        tickCount = 0;
        if (pos == null) {
            center = baritone.getPlayerContext().playerFeet();
        } else {
            center = pos;
        }
        this.range = range;
        active = true;
        locations = null;
    }

    private enum Harvest {
        WHEAT((CropBlock) Blocks.WHEAT),
        CARROTS((CropBlock) Blocks.CARROTS),
        POTATOES((CropBlock) Blocks.POTATOES),
        BEETROOT((CropBlock) Blocks.BEETROOTS),
        PUMPKIN(Blocks.PUMPKIN, state -> true),
        MELON(Blocks.MELON, state -> true),
        NETHERWART(Blocks.NETHER_WART, state -> state.getValue(NetherWartBlock.AGE) >= 3),
        COCOA(Blocks.COCOA, state -> state.getValue(CocoaBlock.AGE) >= 2),
        SUGARCANE(Blocks.SUGAR_CANE, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock;
                }
                return true;
            }
        },
        BAMBOO(Blocks.BAMBOO, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof BambooStalkBlock;
                }
                return true;
            }
        },
        CACTUS(Blocks.CACTUS, null) {
            @Override
            public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.below()).getBlock() instanceof CactusBlock;
                }
                return true;
            }
        };
        public final Block block;
        public final Predicate<BlockState> readyToHarvest;

        Harvest(CropBlock blockCrops) {
            this(blockCrops, blockCrops::isMaxAge);
            // max age is 7 for wheat, carrots, and potatoes, but 3 for beetroot
        }

        Harvest(Block block, Predicate<BlockState> readyToHarvest) {
            this.block = block;
            this.readyToHarvest = readyToHarvest;
        }

        public boolean readyToHarvest(Level world, BlockPos pos, BlockState state) {
            return readyToHarvest.test(state);
        }
    }

    private boolean readyForHarvest(Level world, BlockPos pos, BlockState state) {
        for (Harvest harvest : Harvest.values()) {
            if (harvest.block == state.getBlock()) {
                return harvest.readyToHarvest(world, pos, state);
            }
        }
        return false;
    }

    private boolean isPlantable(ItemStack stack) {
        return FARMLAND_PLANTABLE.contains(stack.getItem()) && OwnedMutationGuard.stableBlockItem(stack.getItem());
    }

    private boolean isBoneMeal(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.BONE_MEAL);
    }

    private boolean isNetherWart(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.NETHER_WART) && OwnedMutationGuard.stableBlockItem(stack.getItem());
    }

    private boolean isCocoa(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().equals(Items.COCOA_BEANS) && OwnedMutationGuard.stableBlockItem(stack.getItem());
    }

    private boolean canPlant(BlockPos pos, Direction face) {
        return OwnedMutationGuard.safeEquipment(ctx.player())
                && OwnedMutationGuard.planPlace(baritone.getRuntime().capturePolicy(), pos, face);
    }

    private boolean canUseHeld(BlockHitResult hit, Predicate<ItemStack> itemFilter) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (itemFilter.test(ctx.player().getItemInHand(hand))
                    && OwnedMutationGuard.executePlace(baritone.getRuntime(), hit, hand)) { return true; }
        }
        return false;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        baritone.getRuntime().requireMainThread();
        var scanned = rescanJob.poll();
        if (scanned != null) { locations = new ArrayList<>(scanned.valueOrThrow()); }
        if (!rescanJob.busy() && (locations == null || (Baritone.settings().mineGoalUpdateInterval.value != 0 && tickCount++ % Baritone.settings().mineGoalUpdateInterval.value == 0))) {
            ArrayList<Block> scan = new ArrayList<>();
            for (Harvest harvest : Harvest.values()) {
                scan.add(harvest.block);
            }
            if (Baritone.settings().replantCrops.value) {
                scan.add(Blocks.FARMLAND);
                scan.add(Blocks.JUNGLE_LOG);
                if (Baritone.settings().replantNetherWart.value) {
                    scan.add(Blocks.SOUL_SAND);
                }
            }

            var session = baritone.getRuntime().captureSession();
            long generation = processGeneration;
            var policy = baritone.getRuntime().capturePolicy();
            ScanInput input = new ScanInput(baritone.getRuntime().worldView(), Set.copyOf(scan), Baritone.settings().farmMaxScanSize.value, ctx.playerFeet());
            if (input.view().hasLiveChunk(input.feet().getX(), input.feet().getZ())) {
                rescanJob.submit(input, () -> processGeneration == generation && baritone.getRuntime().isCurrent(session) && policy.current(), captured ->
                        captured.view().scan(state -> captured.blocks().contains(state.getBlock()), captured.maximum(), captured.feet()));
            }
        }
        if (locations == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openFarmland = new ArrayList<>();
        List<BlockPos> bonemealable = new ArrayList<>();
        List<BlockPos> openSoulsand = new ArrayList<>();
        List<BlockPos> openLog = new ArrayList<>();
        for (BlockPos pos : locations) {
            //check if the target block is out of range.
            if (range != 0 && pos.distSqr(center) > range * range) {
                continue;
            }

            BlockState state = ctx.world().getBlockState(pos);
            boolean airAbove = ctx.world().getBlockState(pos.above()).getBlock() instanceof AirBlock;
            if (state.getBlock() == Blocks.FARMLAND) {
                if (airAbove && canPlant(pos, Direction.UP)) {
                    openFarmland.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.SOUL_SAND) {
                if (airAbove && canPlant(pos, Direction.UP)) {
                    openSoulsand.add(pos);
                }
                continue;
            }
            if (state.getBlock() == Blocks.JUNGLE_LOG) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock && canPlant(pos, direction)) {
                        openLog.add(pos);
                        break;
                    }
                }
                continue;
            }
            if (readyForHarvest(ctx.world(), pos, state) && OwnedMutationGuard.executeBreak(baritone.getRuntime(), pos)) {
                toBreak.add(pos);
                continue;
            }
            for (Harvest harvest : Harvest.values()) {
                if (harvest.block == state.getBlock() && OwnedMutationGuard.executeBreak(baritone.getRuntime(), pos)) {
                    break;
                }
            }
            if (state.getBlock() instanceof CropBlock && canPlant(pos, Direction.UP)) {
                BonemealableBlock ig = (BonemealableBlock) state.getBlock();
                if (ig.isValidBonemealTarget(ctx.world(), pos, state, BonemealSource.INTERACTION)
                        && ig.isBonemealSuccess(ctx.world(), ctx.world().getRandom(), pos, state, BonemealSource.INTERACTION)) {
                    bonemealable.add(pos);
                }
            }
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        BetterBlockPos playerPos = ctx.playerFeet();
        double blockReachDistance = ctx.playerController().getBlockReachDistance();
        for (BlockPos pos : toBreak) {
            if (!OwnedMutationGuard.executeBreak(baritone.getRuntime(), pos)) { continue; }
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel) {
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                MovementHelper.switchToBestToolFor(ctx, ctx.world().getBlockState(pos));
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        ArrayList<BlockPos> both = new ArrayList<>(openFarmland);
        both.addAll(openSoulsand);
        for (BlockPos pos : both) {
            if (!canPlant(pos, Direction.UP)) { continue; }
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            boolean soulsand = openSoulsand.contains(pos);
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), blockReachDistance, false);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, soulsand ? this::isNetherWart : this::isPlantable)) {
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                if (result instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)
                        && hit.getDirection() == Direction.UP && canUseHeld(hit, soulsand ? this::isNetherWart : this::isPlantable)) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(pos)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }
        for (BlockPos pos : openLog) {
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!(ctx.world().getBlockState(pos.relative(dir)).getBlock() instanceof AirBlock) || !canPlant(pos, dir)) {
                    continue;
                }
                Vec3 faceCenter = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(dir.getStep()).scale(0.5));
                Optional<Rotation> rot = RotationUtils.reachableOffset(ctx, pos, faceCenter, blockReachDistance, false);
                if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isCocoa)) {
                    HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                    if (result instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)
                            && hit.getDirection() == dir && canUseHeld(hit, this::isCocoa)) {
                        baritone.getLookBehavior().updateTarget(rot.get(), true);
                        if (ctx.isLookingAt(pos)) {
                            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                        }
                        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                    }
                }
            }
        }
        for (BlockPos pos : bonemealable) {
            if (!(ctx.world().getBlockState(pos).getBlock() instanceof CropBlock) || !canPlant(pos, Direction.UP)) { continue; }
            if (playerPos.distSqr(pos) > blockReachDistance * blockReachDistance) {
                continue;
            }
            Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isBoneMeal)) {
                HitResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), blockReachDistance);
                if (!(result instanceof BlockHitResult hit) || !hit.getBlockPos().equals(pos) || !canUseHeld(hit, this::isBoneMeal)) { continue; }
                baritone.getLookBehavior().updateTarget(rot.get(), true);
                if (ctx.isLookingAt(pos)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }

        if (calcFailed) {
            logDirect("Farm failed");
            if (Baritone.settings().notificationOnFarmFail.value) {
                logNotification("Farm failed", true);
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<Goal> goalz = new ArrayList<>();
        for (BlockPos pos : toBreak) {
            goalz.add(new BuilderProcess.GoalBreak(pos));
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isPlantable)) {
            for (BlockPos pos : openFarmland) {
                goalz.add(new GoalBlock(pos.above()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isNetherWart)) {
            for (BlockPos pos : openSoulsand) {
                goalz.add(new GoalBlock(pos.above()));
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isCocoa)) {
            for (BlockPos pos : openLog) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (ctx.world().getBlockState(pos.relative(direction)).getBlock() instanceof AirBlock && canPlant(pos, direction)) {
                        goalz.add(new GoalGetToBlock(pos.relative(direction)));
                    }
                }
            }
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isBoneMeal)) {
            for (BlockPos pos : bonemealable) {
                goalz.add(new GoalBlock(pos));
            }
        }
        for (Entity entity : ctx.entities()) {
            if (entity instanceof ItemEntity && entity.onGround()) {
                ItemEntity ei = (ItemEntity) entity;
                if (PICKUP_DROPPED.contains(ei.getItem().getItem())) {
                    // +0.1 because of farmland's 0.9375 dummy height lol
                    goalz.add(new GoalBlock(new BetterBlockPos(entity.position().x, entity.position().y + 0.1, entity.position().z)));
                }
            }
        }
        if (goalz.isEmpty()) {
            logDirect("Farm failed");
            if (Baritone.settings().notificationOnFarmFail.value) {
                logNotification("Farm failed", true);
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalComposite(goalz.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        baritone.getRuntime().requireMainThread();
        processGeneration++;
        rescanJob.cancel();
        active = false;
    }

    @Override
    public String displayName0() {
        return "Farming";
    }
}
