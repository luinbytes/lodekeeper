package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.PlacementProvenance;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Server inventory receipts for this classic packet accessor family. */
@Mixin(ClientPlayNetworkHandler.class)
abstract class PlacementInventoryReceiptMixin {
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void lodekeeper$serverInventorySlot(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo callback) {
        PlacementProvenance.serverInventorySlot((ClientPlayNetworkHandler) (Object) this,
                packet.getSyncId(), packet.getSlot(), packet.getStack());
    }

    @Inject(method = "onInventory", at = @At("TAIL"))
    private void lodekeeper$serverInventoryContents(InventoryS2CPacket packet, CallbackInfo callback) {
        List<net.minecraft.item.ItemStack> contents = packet.contents();
        PlacementProvenance.serverInventoryContents((ClientPlayNetworkHandler) (Object) this,
                packet.syncId(), contents);
    }

}
