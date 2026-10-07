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
    @Unique private int lodekeeper$contentsSize;
    @Unique private java.lang.ref.WeakReference<OwnedClickReceipts.FullReceiptObserver> lodekeeper$pendingClick;

    @Unique private OwnedClickReceipts.FullReceiptObserver lodekeeper$observer() {
        return lodekeeper$pendingClick == null ? null : lodekeeper$pendingClick.get();
    }
    @Override public void lodekeeper$watchClick(OwnedClickReceipts.FullReceiptObserver observer) {
        var existing = lodekeeper$observer();
        if (existing != null && existing != observer && existing.contextCurrent())
            throw new IllegalStateException("Another owned inventory click is awaiting its receipt");
        lodekeeper$pendingClick = new java.lang.ref.WeakReference<>(observer);
    }
    @Override public void lodekeeper$unwatchClick(OwnedClickReceipts.FullReceiptObserver observer) {
        if (lodekeeper$observer() == observer) lodekeeper$pendingClick = null;
    }
    @Unique private ItemStack lodekeeper$receivedCursor = ItemStack.EMPTY;
    @Unique private final java.util.Map<Integer, Long> lodekeeper$slotSequences = new java.util.HashMap<>();
    @Unique private final java.util.Map<Integer, ItemStack> lodekeeper$receivedSlots = new java.util.HashMap<>();
    @Unique private final java.util.Map<Integer, ItemStack> lodekeeper$receivedContentsSlots = new java.util.HashMap<>();
    @Unique private long lodekeeper$networkSequence;
    @Unique private ItemStack lodekeeper$receivedInput = ItemStack.EMPTY;
    @Inject(method = "clicked", at = @At("HEAD"))
    private void lodekeeper$clickStarted(int slot, int button, net.minecraft.world.inventory.ContainerInput type,
                                        net.minecraft.world.entity.player.Player player, CallbackInfo callback) {
        var observer = lodekeeper$observer();
        if (observer != null) observer.clickStarted(OwnedClickReceipts.isOwnedClick(this));
    }
    @Inject(method = "setItem", at = @At("TAIL"))
    private void lodekeeper$inputSlotReceived(int slot, int revision, ItemStack stack, CallbackInfo callback) {
        lodekeeper$slotSequences.put(slot, ++lodekeeper$networkSequence);
        lodekeeper$receivedSlots.put(slot, stack.copy());
        if (slot == 0) {
            lodekeeper$receivedInput = stack.copy();
            lodekeeper$receiptSequence++;
        }
        var observer = lodekeeper$observer();
        if (observer != null) observer.slotUpdated(slot, revision, lodekeeper$networkSequence);
    }
    @Inject(method = "initializeContents", at = @At("TAIL"))
    private void lodekeeper$contentsReceived(int revision, List<ItemStack> stacks, ItemStack cursor, CallbackInfo callback) {
        lodekeeper$receivedContentsSlots.clear();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack received = stacks.get(slot).copy();
            lodekeeper$slotSequences.put(slot, ++lodekeeper$networkSequence);
            lodekeeper$receivedSlots.put(slot, received);
            lodekeeper$receivedContentsSlots.put(slot, received);
        }
        lodekeeper$contentsSize = stacks.size();
        lodekeeper$contentsSequence++;
        lodekeeper$cursorSequence = ++lodekeeper$networkSequence;
        lodekeeper$receivedCursor = cursor.copy();
        lodekeeper$contentsRevision = revision;
        if (!stacks.isEmpty()) {
            lodekeeper$receivedInput = stacks.get(0).copy();
            lodekeeper$receiptSequence++;
        }
        var observer = lodekeeper$observer();
        if (observer != null) observer.fullContentsApplied(this);
    }
    @Override public int lodekeeper$contentsSize() { return lodekeeper$contentsSize; }
    @Override public long lodekeeper$slotSequence(int slot) { return lodekeeper$slotSequences.getOrDefault(slot, 0L); }
    @Override public ItemStack lodekeeper$receivedSlot(int slot) { return lodekeeper$receivedSlots.getOrDefault(slot, ItemStack.EMPTY).copy(); }
    @Override public ItemStack lodekeeper$receivedContentsSlot(int slot) { return lodekeeper$receivedContentsSlots.getOrDefault(slot, ItemStack.EMPTY).copy(); }
    @Override public long lodekeeper$cursorSequence() { return lodekeeper$cursorSequence; }
    @Override public ItemStack lodekeeper$receivedCursor() { return lodekeeper$receivedCursor.copy(); }
    @Override public int lodekeeper$contentsRevision() { return lodekeeper$contentsRevision; }
    @Override public long lodekeeper$contentsSequence() { return lodekeeper$contentsSequence; }
    @Override public long lodekeeper$inputSequence() { return lodekeeper$receiptSequence; }
    @Override public ItemStack lodekeeper$receivedInput() { return lodekeeper$receivedInput.copy(); }
}
