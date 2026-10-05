package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.WorldRevision;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
abstract class ClientWorldMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void lodekeeper$blockChanged(BlockPos position, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> result) {
        if (Boolean.TRUE.equals(result.getReturnValue())) WorldRevision.changed(position.getX(), position.getZ());
    }
}
