package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.LodekeeperClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Validate the actual attacked block before vanilla sends any destroy packet. */
@Mixin(MultiPlayerGameMode.class)
public abstract class NavigationMiningMixin {
    @Shadow private BlockPos destroyBlockPos;
    @Shadow private float destroyProgress;
    @Shadow private int destroyDelay;
    @Shadow private boolean isDestroying;

    @Inject(method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void lodekeeper$startHead(BlockPos position, Direction direction, CallbackInfoReturnable<Boolean> callback) {
        lodekeeper$prepareNavigationTool(true, position, direction, callback);
    }

    @Inject(method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void lodekeeper$continueHead(BlockPos position, Direction direction, CallbackInfoReturnable<Boolean> callback) {
        lodekeeper$prepareNavigationTool(false, position, direction, callback);
    }

    @Unique
    private void lodekeeper$prepareNavigationTool(boolean start, BlockPos position, Direction direction,
                                                  CallbackInfoReturnable<Boolean> callback) {
        lodekeeper$observe(0, start, false, position, direction);
        boolean allowed = LodekeeperClient.prepareAutomatedBreak(position);
        lodekeeper$observe(1, start, allowed, position, direction);
        if (!allowed) {
            callback.setReturnValue(false);
            lodekeeper$observe(3, start, false, position, direction);
        }
    }

    @Inject(method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("RETURN"))
    private void lodekeeper$startReturn(BlockPos position, Direction direction, CallbackInfoReturnable<Boolean> callback) {
        lodekeeper$observe(2, true, callback.getReturnValue(), position, direction);
    }

    @Inject(method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z", at = @At("RETURN"))
    private void lodekeeper$continueReturn(BlockPos position, Direction direction, CallbackInfoReturnable<Boolean> callback) {
        lodekeeper$observe(2, false, callback.getReturnValue(), position, direction);
    }

    @Unique
    private void lodekeeper$observe(int stage, boolean start, boolean value, BlockPos position, Direction direction) {
        LodekeeperClient.observeNativeBreak(this, stage, start, value, position, direction,
                destroyBlockPos, destroyProgress, destroyDelay, isDestroying);
    }
}
