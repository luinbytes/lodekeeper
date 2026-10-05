package dev.lodekeeper.fabric.mixin;

import dev.lodekeeper.fabric.OwnedClickReceipts;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.item.ItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Network receipt methods are distinct from local slot-click prediction. */
@Mixin(ScreenHandler.class)
abstract class ContainerInputReceiptMixin implements OwnedClickReceipts.Receipt {
    @Unique private long lodekeeper$receiptSequence;
    @Unique private ItemStack lodekeeper$receivedInput = ItemStack.EMPTY;
    @Inject(method = "setStackInSlot", at = @At("TAIL"))
    private void lodekeeper$inputSlotReceived(int slot, int revision, ItemStack stack, CallbackInfo callback) {
        if (slot == 0) {
            lodekeeper$receivedInput = stack.copy();
            lodekeeper$receiptSequence++;
        }
    }
    @Inject(method = "updateSlotStacks", at = @At("TAIL"))
    private void lodekeeper$contentsReceived(int revision, List<ItemStack> stacks, ItemStack cursor, CallbackInfo callback) {
        if (!stacks.isEmpty()) {
            lodekeeper$receivedInput = stacks.get(0).copy();
            lodekeeper$receiptSequence++;
        }
    }
    @Override public long lodekeeper$inputSequence() { return lodekeeper$receiptSequence; }
    @Override public ItemStack lodekeeper$receivedInput() { return lodekeeper$receivedInput.copy(); }
}
