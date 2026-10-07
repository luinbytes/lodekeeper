package dev.lodekeeper.navigation.kernel;

import dev.lodekeeper.navigation.kernel.snapshot.ImmutableWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Predicate;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OwnedMutationGuard {
    private OwnedMutationGuard() {}

    private static final int MAX_GRAVITY_CELLS = 16;

    public static boolean planBreak(BoundWorldEditPolicy policy, ImmutableWorldView view, BlockPos pos, BlockState state) {
        return breakClaims(policy, view, cell -> view.hasLiveChunk(cell.getX(), cell.getZ()), pos, state);
    }

    private static boolean breakClaims(BoundWorldEditPolicy policy, BlockGetter world, Predicate<BlockPos> loaded,
                                       BlockPos pos, BlockState state) {
        if (!loaded.test(pos)) { return false; }
        Set<BlockPos> removed = new LinkedHashSet<>();
        removed.add(pos);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            BlockPos companion = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            if (!loaded.test(companion)) { return false; }
            BlockState other = world.getBlockState(companion);
            if (other.getBlock() != state.getBlock() || !other.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    || other.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == half) { return false; }
            removed.add(companion);
        }
        if (state.getBlock() instanceof BedBlock) {
            if (!state.hasProperty(BlockStateProperties.BED_PART)
                    || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) { return false; }
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            BlockPos companion = pos.relative(part == BedPart.FOOT ? facing : facing.getOpposite());
            if (!loaded.test(companion)) { return false; }
            BlockState other = world.getBlockState(companion);
            if (other.getBlock() != state.getBlock() || !other.hasProperty(BlockStateProperties.BED_PART)
                    || !other.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    || other.getValue(BlockStateProperties.BED_PART) == part
                    || other.getValue(BlockStateProperties.HORIZONTAL_FACING) != facing) { return false; }
            removed.add(companion);
        }
        int minY = world.getMinY();
        int maxYExclusive = minY + world.getHeight();
        for (BlockPos cell : removed) {
            if (cell.getY() < minY || cell.getY() >= maxYExclusive
                    || !policy.mayBreak(cell)) { return false; }
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = cell.relative(direction);
                if (neighbor.getY() >= minY && neighbor.getY() < maxYExclusive
                        && !policy.mayBreak(neighbor)) { return false; }
            }
        }
        for (BlockPos cell : removed) {
            if (!gravityClaims(policy, world, loaded, cell, removed)) { return false; }
        }
        return true;
    }

    private static boolean gravityClaims(BoundWorldEditPolicy policy, BlockGetter world, Predicate<BlockPos> loaded,
                                         BlockPos pos, Set<BlockPos> removed) {
        int maxY = world.getMinY() + world.getHeight();
        int falling = 0;
        for (int dy = 1; pos.getY() + dy < maxY; dy++) {
            BlockPos above = pos.above(dy);
            if (!loaded.test(above)) { return false; }
            if (removed.contains(above)) { continue; }
            BlockState aboveState = world.getBlockState(above);
            if (!(aboveState.getBlock() instanceof FallingBlock)) { break; }
            if (++falling > MAX_GRAVITY_CELLS || !policy.mayBreak(above) || !policy.mayPlace(above)) { return false; }
        }
        if (falling == 0) { return true; }
        if (!policy.mayPlace(pos)) { return false; }
        for (int dy = 1; dy <= MAX_GRAVITY_CELLS; dy++) {
            BlockPos below = pos.below(dy);
            if (below.getY() < world.getMinY() || !loaded.test(below)) { return false; }
            if (removed.contains(below)) {
                if (!policy.mayPlace(below)) { return false; }
                continue;
            }
            BlockState belowState = world.getBlockState(below);
            // Fluids and partial collision shapes cannot establish this bounded landing contract.
            if (!belowState.getFluidState().isEmpty()) { return false; }
            if (!FallingBlock.isFree(belowState)) {
                return !(belowState.getBlock() instanceof FallingBlock)
                        && belowState.isCollisionShapeFullBlock(world, below);
            }
            if (!belowState.isAir() || !policy.mayBreak(below) || !policy.mayPlace(below)) { return false; }
        }
        return false;
    }

    public static boolean stableBlockItem(Item item) {
        return item instanceof BlockItem blockItem && !(blockItem.getBlock() instanceof FallingBlock);
    }

    public static boolean supportedItem(Item item, BlockState clicked) {
        return stableBlockItem(item) || item instanceof AxeItem || item instanceof HoeItem
                || item instanceof ShovelItem || item instanceof BoneMealItem && clicked.getBlock() instanceof CropBlock;
    }

    public static boolean planPlace(BoundWorldEditPolicy policy, BlockPos clicked, Direction face) {
        return placeClaims(policy, clicked) && placeClaims(policy, clicked.relative(face));
    }

    public static boolean placeClaims(BoundWorldEditPolicy policy, BlockPos pos) {
        if (!policy.mayPlace(pos) || !policy.mayPlace(pos.above()) || !policy.mayPlace(pos.below())) { return false; }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!policy.mayPlace(pos.relative(direction))) { return false; }
        }
        return true;
    }

    public static boolean safeEquipment(LocalPlayer player) {
        if (player == null) { return false; }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            for (var enchantment : player.getItemBySlot(slot).getEnchantments().keySet()) {
                if (enchantment.is(Enchantments.FROST_WALKER)) { return false; }
            }
        }
        return true;
    }

    public static boolean executeBreak(OwnedKernelRuntime owner, BlockPos pos) {
        owner.requireMainThread();
        var session = owner.captureSession();
        if (!owner.isCurrent(session)) { return false; }
        if (owner.getPrimaryBaritone().getPlayerContext().minecraft().screen
                instanceof dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier) { return false; }
        return breakClaims(owner.capturePolicy(), session.world(), session.world()::hasChunkAt, pos,
                session.world().getBlockState(pos));
    }

    public static boolean executePlace(OwnedKernelRuntime owner, BlockHitResult hit, InteractionHand hand) {
        owner.requireMainThread();
        var session = owner.captureSession();
        if (!owner.isCurrent(session)) { return false; }
        if (owner.getPrimaryBaritone().getPlayerContext().minecraft().screen
                instanceof dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier) { return false; }
        var player = owner.getPrimaryBaritone().getPlayerContext().player();
        if (!safeEquipment(player)) { return false; }
        var stack = player.getItemInHand(hand);
        var item = stack.getItem();
        BlockPos clicked = hit.getBlockPos();
        if (!stack.isEmpty() && !supportedItem(item, session.world().getBlockState(clicked))) {
            return false;
        }
        var policy = owner.capturePolicy();
        return planPlace(policy, clicked, hit.getDirection());
    }
}
