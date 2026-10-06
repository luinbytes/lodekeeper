package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.PlacementProvenance;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.21.2+ sends direct player-inventory slot contents through this packet. */
@Mixin(ClientPlayNetworkHandler.class)
abstract class PlacementPlayerInventoryMixin {
    @Inject(method = "onSetPlayerInventory", at = @At("TAIL"))
    private void lodekeeper$serverPlayerInventorySlot(SetPlayerInventoryS2CPacket packet, CallbackInfo callback) {
        PlacementProvenance.serverPlayerInventorySlot((ClientPlayNetworkHandler) (Object) this,
                packet.slot(), packet.contents());
    }
}
