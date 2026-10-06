package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.OwnedClickReceipts;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
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
@Mixin(ClickSlotC2SPacket.class)
abstract class OwnedClickPacketMixin {
    @Shadow @Final private int syncId;
    @Shadow @Final @Mutable private Int2ObjectMap<?> modifiedStacks;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodekeeper$reconcileOwnedInput(CallbackInfo callback) {
        if (OwnedClickReceipts.claimInputReconciliation(syncId)) {
            var copy = new Int2ObjectOpenHashMap<>(modifiedStacks);
            if (OwnedClickReceipts.reconcileInputSlot(syncId)) copy.remove(0);
            copy.keySet().removeIf((int slot) -> OwnedClickReceipts.reconcileStorageSlot(syncId, slot));
            modifiedStacks = Int2ObjectMaps.unmodifiable(copy);
        }
    }
}
