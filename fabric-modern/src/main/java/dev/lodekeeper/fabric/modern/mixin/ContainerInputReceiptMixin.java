package dev.lodekeeper.fabric.modern.mixin;

import dev.lodekeeper.fabric.modern.OwnedClickReceipts;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Network receipt methods are distinct from local slot-click prediction. */
@Mixin(AbstractContainerMenu.class)
abstract class ContainerInputReceiptMixin implements OwnedClickReceipts.Receipt {
    @Unique private long lodekeeper$receiptSequence;
    @Unique private long lodekeeper$contentsSequence;
    @Unique private long lodekeeper$cursorSequence;
    @Unique private int lodekeeper$contentsRevision = -1;
    @Unique private ItemStack lodekeeper$receivedCursor = ItemStack.EMPTY;
    @Unique private final java.util.Map<Integer, Long> lodekeeper$slotSequences = new java.util.HashMap<>();
    @Unique private final java.util.Map<Integer, ItemStack> lodekeeper$receivedSlots = new java.util.HashMap<>();
    @Unique private long lodekeeper$networkSequence;
    @Unique private ItemStack lodekeeper$receivedInput = ItemStack.EMPTY;
    @Inject(method = "setItem", at = @At("TAIL"))
    private void lodekeeper$inputSlotReceived(int slot, int revision, ItemStack stack, CallbackInfo callback) {
        lodekeeper$slotSequences.put(slot, ++lodekeeper$networkSequence);
        lodekeeper$receivedSlots.put(slot, stack.copy());
        if (slot == 0) {
            lodekeeper$receivedInput = stack.copy();
            lodekeeper$receiptSequence++;
        }
    }
    @Inject(method = "initializeContents", at = @At("TAIL"))
    private void lodekeeper$contentsReceived(int revision, List<ItemStack> stacks, ItemStack cursor, CallbackInfo callback) {
        for (int slot = 0; slot < stacks.size(); slot++) {
            lodekeeper$slotSequences.put(slot, ++lodekeeper$networkSequence);
            lodekeeper$receivedSlots.put(slot, stacks.get(slot).copy());
        }
        lodekeeper$contentsSequence++;
        lodekeeper$cursorSequence = ++lodekeeper$networkSequence;
        lodekeeper$receivedCursor = cursor.copy();
        lodekeeper$contentsRevision = revision;
        if (!stacks.isEmpty()) {
            lodekeeper$receivedInput = stacks.get(0).copy();
            lodekeeper$receiptSequence++;
        }
    }
    @Override public long lodekeeper$slotSequence(int slot) { return lodekeeper$slotSequences.getOrDefault(slot, 0L); }
    @Override public ItemStack lodekeeper$receivedSlot(int slot) { return lodekeeper$receivedSlots.getOrDefault(slot, ItemStack.EMPTY).copy(); }
    @Override public long lodekeeper$cursorSequence() { return lodekeeper$cursorSequence; }
    @Override public ItemStack lodekeeper$receivedCursor() { return lodekeeper$receivedCursor.copy(); }
    @Override public int lodekeeper$contentsRevision() { return lodekeeper$contentsRevision; }
    @Override public long lodekeeper$contentsSequence() { return lodekeeper$contentsSequence; }
    @Override public long lodekeeper$inputSequence() { return lodekeeper$receiptSequence; }
    @Override public ItemStack lodekeeper$receivedInput() { return lodekeeper$receivedInput.copy(); }
}
