package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.LodekeeperClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Validate the actual attacked block before vanilla sends any destroy packet. */
@Mixin(MultiPlayerGameMode.class)
public abstract class NavigationMiningMixin {
    @Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
    private void lodekeeper$prepareNavigationTool(BlockPos position, Direction direction,
                                                  CallbackInfoReturnable<Boolean> callback) {
        if (!LodekeeperClient.prepareAutomatedBreak(position)) callback.setReturnValue(false);
    }
}
