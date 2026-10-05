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

/** Ask native reconciliation to send input-slot state after an owned stonecutter click. */
@Mixin(ServerboundContainerClickPacket.class)
abstract class OwnedClickPacketMixin {
    @Shadow @Final private int containerId;
    @Shadow @Final @Mutable private Int2ObjectMap<?> changedSlots;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodekeeper$reconcileOwnedInput(CallbackInfo callback) {
        if (changedSlots.containsKey(0) && OwnedClickReceipts.claimInputReconciliation(containerId)) {
            var copy = new Int2ObjectOpenHashMap<>(changedSlots);
            copy.remove(0);
            changedSlots = Int2ObjectMaps.unmodifiable(copy);
        }
    }
}
