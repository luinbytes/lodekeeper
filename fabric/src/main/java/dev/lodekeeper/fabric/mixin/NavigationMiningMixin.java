package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.LodekeeperClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Validate the actual attacked block before vanilla sends any destroy packet. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class NavigationMiningMixin {
    @Inject(method = {"attackBlock", "updateBlockBreakingProgress"}, at = @At("HEAD"), cancellable = true)
    private void lodekeeper$prepareNavigationTool(BlockPos position, Direction direction,
                                                  CallbackInfoReturnable<Boolean> callback) {
        if (!LodekeeperClient.prepareAutomatedBreak(position)) callback.setReturnValue(false);
    }
}
