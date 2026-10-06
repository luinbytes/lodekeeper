package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.PlacementProvenance;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Receives server block and inventory packets without observing local prediction. */
@Mixin(ClientPacketListener.class)
abstract class PlacementProvenanceMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void lodekeeper$serverBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo callback) {
        PlacementProvenance.serverBlockUpdate((ClientPacketListener) (Object) this,
                packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void lodekeeper$serverChunkDelta(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo callback) {
        PlacementProvenance.serverChunkDelta((ClientPacketListener) (Object) this, packet);
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void lodekeeper$serverInventorySlot(ClientboundContainerSetSlotPacket packet, CallbackInfo callback) {
        PlacementProvenance.serverInventorySlot((ClientPacketListener) (Object) this,
                packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void lodekeeper$serverInventoryContents(ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        PlacementProvenance.serverInventoryContents((ClientPacketListener) (Object) this,
                packet.containerId(), packet.items());
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
    private void lodekeeper$serverPlayerInventorySlot(ClientboundSetPlayerInventoryPacket packet, CallbackInfo callback) {
        PlacementProvenance.serverPlayerInventorySlot((ClientPacketListener) (Object) this,
                packet.slot(), packet.contents());
    }

}
