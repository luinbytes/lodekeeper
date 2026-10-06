package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.function.DoubleSupplier;

/** Serializes ordinary menu clicks and keeps every cursor stack recoverable. */
final class SlotTransfer {
    private final Minecraft client;
    private final AbstractContainerMenu menu;
    private final int source, destination;
    private final ItemStack expected;
    private final DoubleSupplier consumptionProgress;
    private int remaining, phase, cooldown, pendingAmount, beforeCursor, beforeDestination;
    private double beforeConsumptionProgress;
    private boolean complete, failed;

    SlotTransfer(Minecraft client, AbstractContainerMenu menu, int source, int destination, int amount) {
        this(client, menu, source, destination, amount, null);
    }

    SlotTransfer(Minecraft client, AbstractContainerMenu menu, int source, int destination, int amount,
                 DoubleSupplier consumptionProgress) {
        this.client = client;
        this.menu = menu;
        this.source = source;
        this.destination = destination;
        this.consumptionProgress = consumptionProgress;
        if (amount < 1 || amount > 99 || source == destination) throw new IllegalArgumentException("invalid transfer");
        remaining = amount;
        expected = menu.getSlot(source).getItem().copy();
    }

    boolean tick() {
        if (failed) throw new IllegalStateException("Transfer failed; cancel or replan after inventory recovery");
        if (complete) return true;
        requireMenu();
        if (cooldown-- > 0) return false;
        if (phase == 0) {
            if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");
            ItemStack stack = menu.getSlot(source).getItem();
            if (!same(stack, expected) || stack.getCount() < remaining) throw new IllegalStateException("Ingredient changed during transfer");
            int half = stack.getCount() / 2 + stack.getCount() % 2;
            boolean pickupRequestedHalf = remaining < stack.getCount() && remaining == half;
            click(source, pickupRequestedHalf ? 1 : 0);
            phase = 1;
        } else if (phase == 1) {
            ItemStack cursor = menu.getCarried();
            if (cursor.isEmpty() || !same(cursor, expected)) throw new IllegalStateException("Cursor pickup rejected or changed");
            Slot slot = menu.getSlot(destination);
            ItemStack existing = slot.getItem();
            pendingAmount = cursor.getCount() == remaining ? remaining : 1;
            if (!slot.mayPlace(cursor) || !existing.isEmpty() && !same(existing, cursor)
                    || slot.getMaxStackSize(cursor) - existing.getCount() < pendingAmount) {
                throw new IllegalStateException("Destination cannot accept the planned ingredient");
            }
            beforeCursor = cursor.getCount();
            beforeDestination = existing.getCount();
            beforeConsumptionProgress = consumptionProgress == null ? 0 : consumptionProgress.getAsDouble();
            click(destination, pendingAmount == remaining && pendingAmount == beforeCursor ? 0 : 1);
            phase = 4;
        } else if (phase == 4) {
            observePlacement();
            phase = remaining == 0 ? 2 : 1;
        } else if (phase == 2) {
            returnCursor();
            phase = 3;
        } else {
            if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Could not return the inventory remainder safely");
            complete = true;
        }
        cooldown = 2;
        return complete;
    }

    private void observePlacement() {
        ItemStack placed = menu.getSlot(destination).getItem(), cursor = menu.getCarried();
        int expectedCount = beforeDestination + pendingAmount;
        boolean sameDestinationItem = placed.isEmpty() || same(placed, expected);
        boolean exactPlacement = !placed.isEmpty() && sameDestinationItem && placed.getCount() == expectedCount;
        // Cooking may consume one inserted item before its slot sync is observed. Accept that
        // one-item delta only when the caller independently proves recipe or fuel progress.
        boolean consumedOne = consumptionProgress != null && sameDestinationItem
                && placed.getCount() == expectedCount - 1
                && consumptionProgress.getAsDouble() > beforeConsumptionProgress;
        if ((!exactPlacement && !consumedOne)
                || cursor.getCount() != beforeCursor - pendingAmount || !cursor.isEmpty() && !same(cursor, expected)) {
            throw new IllegalStateException("Inventory transfer was rejected or modified");
        }
        remaining -= pendingAmount;
        pendingAmount = 0;
    }

    void recover() {
        if (client.player == null || client.player.containerMenu != menu) return;
        RuntimeException placementFailure = null;
        if (phase == 4 && !failed) {
            try { observePlacement(); }
            catch (RuntimeException exception) { failed = true; placementFailure = exception; }
        }
        try {
            returnCursor();
            if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Return the held inventory stack manually");
        } catch (RuntimeException recoveryFailure) {
            if (placementFailure == null) throw recoveryFailure;
            placementFailure.addSuppressed(recoveryFailure);
        }
        if (placementFailure != null) throw placementFailure;
        if (failed) return;
        if (remaining == 0) complete = true;
        else phase = 0;
    }

    private void returnCursor() {
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty()) return;
        ItemStack original = menu.getSlot(source).getItem();
        if (!same(cursor, expected) || !original.isEmpty() && !same(original, cursor)
                || menu.getSlot(source).getMaxStackSize(cursor) - original.getCount() < cursor.getCount()) {
            throw new IllegalStateException("Original slot changed; return the held stack manually");
        }
        click(source, 0);
    }

    private void requireMenu() {
        if (client.player == null || client.gameMode == null || client.player.containerMenu != menu) {
            throw new IllegalStateException("Container changed during transfer");
        }
    }

    private void click(int slot, int button) {
        Player player = client.player;
        OwnedClickReceipts.inventoryClick(client, menu.containerId, slot, button, ContainerInput.PICKUP, player);
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }
}
