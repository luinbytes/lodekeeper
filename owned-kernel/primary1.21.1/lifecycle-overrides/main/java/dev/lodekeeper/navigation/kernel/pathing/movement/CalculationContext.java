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

package dev.lodekeeper.navigation.kernel.pathing.movement;

import dev.lodekeeper.navigation.kernel.Baritone;
import dev.lodekeeper.navigation.kernel.api.IBaritone;
import dev.lodekeeper.navigation.kernel.api.pathing.movement.ActionCosts;
import dev.lodekeeper.navigation.kernel.cache.WorldData;
import dev.lodekeeper.navigation.kernel.pathing.precompute.PrecomputedData;
import dev.lodekeeper.navigation.kernel.utils.BlockStateInterface;
import dev.lodekeeper.navigation.kernel.utils.ToolSet;
import dev.lodekeeper.navigation.kernel.utils.pathing.BetterWorldBorder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static dev.lodekeeper.navigation.kernel.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    public record ScanOptions(int cachedMaximum, boolean extendCache, boolean onlyExposed,
                              int minimumY, int maximumY, int exposedRadius) {}
    public final ScanOptions scanOptions;
    public final dev.lodekeeper.navigation.kernel.BoundWorldEditPolicy policy;
    public final boolean safeForThreadedUse;
    public final IBaritone baritone;
    public final int minY;
    public final int maxY;
    public final dev.lodekeeper.navigation.kernel.api.utils.BetterBlockPos playerFeet;
    public final dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session session;
    public final dev.lodekeeper.navigation.kernel.snapshot.ImmutableWorldView snapshot;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean safeAutomation;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;

    public final PrecomputedData precomputedData;

    public CalculationContext(IBaritone baritone) {
        this(baritone, false);
    }

    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
        ((Baritone) baritone).getRuntime().requireMainThread();
        this.scanOptions = new ScanOptions(Math.max(0, Math.min(Baritone.settings().maxCachedWorldScanCount.value, 65_536)),
                Baritone.settings().extendCacheOnThreshold.value, Baritone.settings().allowOnlyExposedOres.value,
                Baritone.settings().minYLevelWhileMining.value, Baritone.settings().maxYLevelWhileMining.value,
                Math.max(0, Math.min(Baritone.settings().allowOnlyExposedOresDistance.value, 16)));
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        LocalPlayer player = baritone.getPlayerContext().player();
        Level world = baritone.getPlayerContext().world();
        this.minY = world.getMinBuildHeight();
        this.maxY = world.getMaxBuildHeight();
        this.playerFeet = baritone.getPlayerContext().playerFeet();
        this.session = ((Baritone) baritone).getRuntime().captureSession();
        this.policy = ((Baritone) baritone).getRuntime().capturePolicy();
        this.bsi = new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread);
        this.snapshot = forUseOnAnotherThread ? bsi.snapshot() : ((Baritone) baritone).getRuntime().worldView();
        this.toolSet = new ToolSet(player);
        this.hasThrowaway = Baritone.settings().allowPlace.value && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway();
        this.hasWaterBucket = false;
        this.canSprint = Baritone.settings().allowSprint.value && player.getFoodData().getFoodLevel() > 6;
        this.placeBlockCost = Baritone.settings().blockPlacementPenalty.value;
        this.allowBreak = Baritone.settings().allowBreak.value;
        this.allowBreakAnyway = List.copyOf(Baritone.settings().allowBreakAnyway.value);
        this.allowParkour = Baritone.settings().allowParkour.value;
        this.allowParkourPlace = Baritone.settings().allowParkourPlace.value;
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = Baritone.settings().allowParkourAscend.value;
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        this.safeAutomation = dev.lodekeeper.navigation.kernel.OwnedMutationGuard.safeEquipment(player);
        this.frostWalker = 0;
        this.allowDiagonalDescend = Baritone.settings().allowDiagonalDescend.value;
        this.allowDiagonalAscend = Baritone.settings().allowDiagonalAscend.value;
        this.allowDownward = Baritone.settings().allowDownward.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = Baritone.settings().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = Baritone.settings().maxFallHeightBucket.value;
        float waterSpeedMultiplier = 1.0f;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value()
                    .getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        waterSpeedMultiplier = effect.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    }

    public final IBaritone getBaritone() {
        return baritone;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (!dev.lodekeeper.navigation.kernel.OwnedMutationGuard.placeClaims(policy, new BlockPos(x, y, z))) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (!dev.lodekeeper.navigation.kernel.OwnedMutationGuard.planBreak(policy, snapshot, new BlockPos(x, y, z), current)) {
            return COST_INF;
        }
        return 1;
    }

    public double placeBucketCost() {
        return COST_INF;
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !policy.mayBreak(pos) || !policy.mayPlace(pos);
    }
}
