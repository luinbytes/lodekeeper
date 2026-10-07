package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.OwnedClickReceipts;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep bot storage changes out of the prediction cache so the server echoes its actual slots. */
@Mixin(ServerboundContainerClickPacket.class)
abstract class OwnedClickPacketMixin {
    @Shadow @Final private int containerId;
    @Shadow @Final @Mutable private int stateId;
    @Shadow @Final @Mutable private Int2ObjectMap<?> changedSlots;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodekeeper$reconcileOwnedInput(CallbackInfo callback) {
        if (OwnedClickReceipts.claimInputReconciliation(containerId)) {
            // Native revisions are nonnegative. This requests a full post-click cursor receipt.
            if (OwnedClickReceipts.reconcileCursorContents(containerId)) stateId = -1;
            var copy = new Int2ObjectOpenHashMap<>(changedSlots);
            if (OwnedClickReceipts.reconcileInputSlot(containerId)) copy.remove(0);
            copy.keySet().removeIf((int slot) -> OwnedClickReceipts.reconcileStorageSlot(containerId, slot));
            changedSlots = Int2ObjectMaps.unmodifiable(copy);
        }
    }
}
