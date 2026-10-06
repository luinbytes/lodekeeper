package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.PlacementProvenance;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Receives only server packet payloads. Client world prediction never enters provenance. */
@Mixin(ClientPlayNetworkHandler.class)
abstract class PlacementProvenanceMixin {
    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void lodekeeper$serverBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo callback) {
        PlacementProvenance.serverBlockUpdate((ClientPlayNetworkHandler) (Object) this,
                packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void lodekeeper$serverChunkDelta(ChunkDeltaUpdateS2CPacket packet, CallbackInfo callback) {
        PlacementProvenance.serverChunkDelta((ClientPlayNetworkHandler) (Object) this, packet);
    }
}
