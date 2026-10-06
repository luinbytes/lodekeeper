package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import java.util.function.DoubleSupplier;

/** Serialized cursor transaction. Never drops stacks; verifies observed capacity and movement. */
final class SlotTransfer {
    private final MinecraftClient client;
    private final ScreenHandler handler;
    private final int source, destination;
    private final ItemStack expected;
    private int remaining, phase, cooldown, pendingAmount, beforeCursor, beforeDestination;
    private boolean complete, failed;
    private final DoubleSupplier consumptionProgress;
    private double beforeConsumptionProgress;
    SlotTransfer(MinecraftClient client, ScreenHandler handler, int source, int destination, int amount) {
        this(client, handler, source, destination, amount, null);
    }
    SlotTransfer(MinecraftClient client, ScreenHandler handler, int source, int destination, int amount, DoubleSupplier consumptionProgress) {
        this.client = client; this.handler = handler; this.source = source; this.destination = destination;
        this.consumptionProgress = consumptionProgress;
        if (amount < 1 || amount > 64 || source == destination) throw new IllegalArgumentException("invalid transfer");
        remaining = amount; expected = handler.getSlot(source).getStack().copy();
    }
    boolean tick() {
        if (failed) throw new IllegalStateException("Transfer failed; cancel or replan after inventory recovery");
        if (complete) return true;
        requireHandler();
        if (cooldown-- > 0) return false;
        if (phase == 0) {
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");
            ItemStack stack = handler.getSlot(source).getStack();
            if (!GameApi.canCombine(stack, expected) || stack.getCount() < remaining) throw new IllegalStateException("Ingredient changed during transfer");
            int half = stack.getCount() / 2 + stack.getCount() % 2;
            boolean pickupRequestedHalf = remaining < stack.getCount() && remaining == half;
            click(source, pickupRequestedHalf ? 1 : 0); phase = 1;
        } else if (phase == 1) {
            ItemStack cursor = handler.getCursorStack();
            if (cursor.isEmpty() || !GameApi.canCombine(cursor, expected)) throw new IllegalStateException("Cursor pickup rejected or changed");
            Slot slot = handler.getSlot(destination);
            ItemStack existing = slot.getStack();
            pendingAmount = cursor.getCount() == remaining ? remaining : 1;
            if (!slot.canInsert(cursor) || !existing.isEmpty() && !GameApi.canCombine(existing, cursor) || slot.getMaxItemCount(cursor) - existing.getCount() < pendingAmount) throw new IllegalStateException("Destination cannot accept the planned ingredient");
            beforeCursor = cursor.getCount(); beforeDestination = existing.getCount();
            beforeConsumptionProgress = consumptionProgress == null ? 0 : consumptionProgress.getAsDouble();
            click(destination, pendingAmount == remaining && pendingAmount == beforeCursor ? 0 : 1);
            phase = 4;
        } else if (phase == 4) {
            observePlacement(); phase = remaining == 0 ? 2 : 1;
        } else if (phase == 2) {
            returnCursor(); phase = 3;
        } else {
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Could not return inventory remainder safely");
            complete = true;
        }
        cooldown = 2;
        return complete;
    }
    private void observePlacement() {
        ItemStack destinationStack = handler.getSlot(destination).getStack(), cursor = handler.getCursorStack();
        int expectedCount = beforeDestination + pendingAmount;
        boolean identicalItem = destinationStack.isEmpty() || GameApi.canCombine(destinationStack, expected);
        boolean exactPlacement = !destinationStack.isEmpty() && identicalItem && destinationStack.getCount() == expectedCount;
        // Cooking may consume one inserted item before slot synchronization arrives. The caller
        // must supply independent progress proving that consumption, rather than accept missing items.
        boolean consumedOne = consumptionProgress != null && identicalItem && destinationStack.getCount() == expectedCount - 1
            && consumptionProgress.getAsDouble() > beforeConsumptionProgress;
        if ((!exactPlacement && !consumedOne) || cursor.getCount() != beforeCursor - pendingAmount || !cursor.isEmpty() && !GameApi.canCombine(cursor, expected)) throw new IllegalStateException("Inventory transfer was rejected or modified");
        remaining -= pendingAmount; pendingAmount = 0;
    }
    void recover() {
        if (client.player == null || client.interactionManager == null || client.player.currentScreenHandler != handler) return;
        RuntimeException placementFailure = null;
        if (phase == 4 && !failed) {
            try { observePlacement(); }
            catch (RuntimeException exception) { failed = true; placementFailure = exception; }
        }
        try {
            returnCursor();
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Return the held inventory stack manually");
        } catch (RuntimeException recoveryFailure) {
            if (placementFailure == null) throw recoveryFailure;
            placementFailure.addSuppressed(recoveryFailure);
        }
        if (placementFailure != null) throw placementFailure;
        if (failed) return;
        if (remaining == 0) complete = true; else phase = 0;
    }
    private void returnCursor() {
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) return;
        ItemStack existing = handler.getSlot(source).getStack();
        if (!GameApi.canCombine(cursor, expected) || !existing.isEmpty() && !GameApi.canCombine(existing, cursor) || handler.getSlot(source).getMaxItemCount(cursor) - existing.getCount() < cursor.getCount()) throw new IllegalStateException("Original slot changed; return the held stack manually");
        click(source, 0);
    }
    private void requireHandler() {
        if (client.player == null || client.interactionManager == null || client.player.currentScreenHandler != handler) throw new IllegalStateException("Container changed during transfer");
    }
    private void click(int slot, int button) { OwnedClickReceipts.inventoryClick(client, handler.syncId, slot, button, SlotActionType.PICKUP, client.player); }
}
