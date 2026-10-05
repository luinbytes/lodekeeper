package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.WorldRevision;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "setBlock", at = @At("RETURN"))
    private void lodekeeper$markTerrainChanged(BlockPos position, BlockState state, int flags, int recursionLeft,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (Boolean.TRUE.equals(callback.getReturnValue())) WorldRevision.changed(position.getX(), position.getZ());
    }
}
