package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import java.util.function.DoubleSupplier;

final class SlotTransfer {
    private enum Phase { PICKUP, PLACE, DRAG, RETURN, COMPLETE }
    private static final int MAX_OBSERVATION_TICKS = 40;
    private final Minecraft client;
    private final AbstractContainerMenu menu;
    private final Object player, world;
    private final int source, destination;
    private final ItemStack expected;
    private final DoubleSupplier consumptionProgress;
    private final int[] dragDestinations;
    private final Runnable beforePickup, beforeDrag;
    private final int[] craftingGridSlots;
    private final ItemStack[] expectedCraftingGrid;
    private long[] gridBefore;
    private int remaining, pendingAmount, beforeCursor, beforeDestination, sourceCount;
    private int receiptSlot, receiptSlotCount, receiptCursorCount, observations, outboundRevision;
    private long contentsBefore, cursorBefore, sourceBefore, destinationBefore;
    private Phase phase = Phase.PICKUP, pending;
    private double beforeConsumptionProgress;
    private boolean failed;

    SlotTransfer(Minecraft client, AbstractContainerMenu menu, int source, int destination, int amount) {
        this(client, menu, source, destination, amount, null);
    }

    SlotTransfer(Minecraft client, AbstractContainerMenu menu, int source, int destination, int amount,
                 DoubleSupplier consumptionProgress) {
        this(client, menu, source, destination, amount, consumptionProgress, null, null, null);
    }

    SlotTransfer(Minecraft client, AbstractContainerMenu menu, int source, int[] destinations,
                 Runnable beforePickup, Runnable beforeDrag) {
        this(client, menu, source, destinations[0], destinations.length, null,
                destinations, beforePickup, beforeDrag);
        if (destinations.length < 2) throw new IllegalArgumentException("A crafting drag needs multiple destinations");
    }

    private SlotTransfer(Minecraft client, AbstractContainerMenu menu, int source, int destination, int amount,
                         DoubleSupplier consumptionProgress, int[] dragDestinations,
                         Runnable beforePickup, Runnable beforeDrag) {
        this.client = client;
        this.menu = menu;
        this.player = client.player;
        this.world = client.level;
        this.source = source;
        this.destination = destination;
        this.consumptionProgress = consumptionProgress;
        this.dragDestinations = dragDestinations == null ? null : dragDestinations.clone();
        this.beforePickup = beforePickup;
        this.beforeDrag = beforeDrag;
        if (dragDestinations != null) {
            if (!(menu instanceof net.minecraft.world.inventory.AbstractCraftingMenu crafting))
                throw new IllegalArgumentException("A crafting drag requires a native crafting grid");
            int gridSize = crafting.getInputGridSlots().size();
            if (gridSize != 4 && gridSize != 9) throw new IllegalArgumentException("unsupported crafting grid");
            craftingGridSlots = new int[gridSize];
            expectedCraftingGrid = new ItemStack[gridSize];
            for (int index = 0; index < gridSize; index++) {
                craftingGridSlots[index] = menu.slots.indexOf(crafting.getInputGridSlots().get(index));
                expectedCraftingGrid[index] = menu.getSlot(craftingGridSlots[index]).getItem().copy();
            }
            for (int index = 0; index < dragDestinations.length; index++) {
                int slot = dragDestinations[index];
                boolean gridSlot = false;
                for (int candidate : craftingGridSlots) gridSlot |= candidate == slot;
                if (!gridSlot || slot == source || !menu.getSlot(slot).getItem().isEmpty())
                    throw new IllegalArgumentException("invalid crafting destination");
                for (int previous = 0; previous < index; previous++) {
                    if (dragDestinations[previous] == slot) throw new IllegalArgumentException("duplicate crafting destination");
                }
            }
        } else {
            craftingGridSlots = null;
            expectedCraftingGrid = null;
        }
        if (amount < 1 || amount > 99 || source == destination) throw new IllegalArgumentException("invalid transfer");
        remaining = amount;
        expected = menu.getSlot(source).getItem().copy();
    }

    boolean tick() {
        if (failed) throw new IllegalStateException("Transfer failed; cancel or replan after inventory recovery");
        requireHandler();
        if (pending != null && !observeClick()) return false;
        if (phase == Phase.COMPLETE) return true;
        if (phase == Phase.PICKUP) {
            if (beforePickup != null) beforePickup.run();
            requireCraftingGrid();
            if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");
            ItemStack stack = menu.getSlot(source).getItem();
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, expected) || stack.getCount() < remaining)
                throw new IllegalStateException("Ingredient changed during transfer");
            int half = (stack.getCount() + 1) / 2;
            boolean pickupHalf = remaining < stack.getCount() && remaining == half;
            int pickup = pickupHalf ? half : stack.getCount();
            sourceCount = stack.getCount() - pickup;
            click(source, pickupHalf ? 1 : 0, sourceCount, pickup);
        } else if (phase == Phase.PLACE) {
            ItemStack cursor = menu.getCarried();
            if (cursor.isEmpty() || !ItemStack.isSameItemSameComponents(cursor, expected)) throw new IllegalStateException("Cursor pickup rejected or changed");
            requireSourceCount();
            Slot slot = menu.getSlot(destination);
            ItemStack existing = slot.getItem();
            pendingAmount = cursor.getCount() == remaining ? remaining : 1;
            if (!slot.mayPlace(cursor) || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, cursor)
                    || slot.getMaxStackSize(cursor) - existing.getCount() < pendingAmount)
                throw new IllegalStateException("Destination cannot accept the planned ingredient");
            beforeCursor = cursor.getCount();
            beforeDestination = existing.getCount();
            beforeConsumptionProgress = consumptionProgress == null ? 0 : consumptionProgress.getAsDouble();
            click(destination, pendingAmount == remaining && pendingAmount == beforeCursor ? 0 : 1,
                    beforeDestination + pendingAmount, beforeCursor - pendingAmount);
        } else if (phase == Phase.DRAG) {
            drag();
        } else {
            returnCursor();
        }
        return phase == Phase.COMPLETE;
    }

    private boolean observeClick() {
        OwnedClickReceipts.Receipt receipt = receipt();
        boolean gridFresh = true;
        if (craftingGridSlots != null) {
            for (int index = 0; index < craftingGridSlots.length; index++) {
                gridFresh &= receipt.lodekeeper$slotSequence(craftingGridSlots[index]) > gridBefore[index];
            }
        }
        if (!gridFresh || receipt.lodekeeper$contentsSequence() <= contentsBefore || receipt.lodekeeper$cursorSequence() <= cursorBefore
                || receipt.lodekeeper$slotSequence(source) <= sourceBefore
                || receipt.lodekeeper$slotSequence(destination) <= destinationBefore
                || receipt.lodekeeper$contentsRevision() == outboundRevision) {
            if (++observations >= MAX_OBSERVATION_TICKS)
                throw new IllegalStateException("Server has not confirmed the issued inventory click; resume to observe it without clicking again");
            return false;
        }
        ItemStack received = receipt.lodekeeper$receivedSlot(receiptSlot);
        ItemStack cursor = receipt.lodekeeper$receivedCursor();
        boolean exactSlot = matchesCount(received, receiptSlotCount);
        boolean consumedOne = pending == Phase.PLACE && consumptionProgress != null
                && matchesCount(received, receiptSlotCount - 1)
                && consumptionProgress.getAsDouble() > beforeConsumptionProgress;
        if ((!exactSlot && !consumedOne) || !matchesCount(cursor, receiptCursorCount)
                || !sameContents(received, menu.getSlot(receiptSlot).getItem())
                || !sameContents(cursor, menu.getCarried())) {
            failed = true;
            ItemStack localSlot = menu.getSlot(receiptSlot).getItem();
            ItemStack localCursor = menu.getCarried();
            org.slf4j.LoggerFactory.getLogger("lodekeeper").warn(
                    "[Lodekeeper] INVENTORY_TRANSFER mismatch phase={} source={} destination={} receiptSlot={} expectedItem={} expectedSlotCount={} expectedCursorCount={} receivedItem={} receivedSlotCount={} receivedCursorItem={} receivedCursorCount={} localItem={} localSlotCount={} localCursorItem={} localCursorCount={} outboundRevision={} contentsRevision={} localRevision={} contentsBefore={} contentsSequence={}",
                    pending, source, destination, receiptSlot, expected.getItem(), receiptSlotCount, receiptCursorCount,
                    received.getItem(), received.getCount(), cursor.getItem(), cursor.getCount(),
                    localSlot.getItem(), localSlot.getCount(), localCursor.getItem(), localCursor.getCount(),
                    outboundRevision, receipt.lodekeeper$contentsRevision(), menu.getStateId(),
                    contentsBefore, receipt.lodekeeper$contentsSequence());
            throw new IllegalStateException("Server rejected or modified the inventory transfer; leaving the container open");
        }
        if (craftingGridSlots != null) {
            for (int index = 0; index < craftingGridSlots.length; index++) {
                int slot = craftingGridSlots[index];
                ItemStack receivedGrid = receipt.lodekeeper$receivedSlot(slot);
                if (!sameContents(receivedGrid, expectedCraftingGrid[index])
                        || !sameContents(receivedGrid, menu.getSlot(slot).getItem())) {
                    failed = true;
                    throw new IllegalStateException("Server rejected or modified the crafting grid; leaving the container open");
                }
            }
            if (!matchesCount(receipt.lodekeeper$receivedSlot(source), pending == Phase.RETURN ? receiptSlotCount : sourceCount)
                    || !sameContents(receipt.lodekeeper$receivedSlot(source), menu.getSlot(source).getItem())) {
                failed = true;
                throw new IllegalStateException("Original inventory slot changed during crafting drag; leaving the container open");
            }
        }
        if (pending == Phase.DRAG) {
            remaining = 0;
            phase = Phase.RETURN;
        } else if (pending == Phase.PLACE) {
            if (!matchesCount(receipt.lodekeeper$receivedSlot(source), sourceCount)) {
                failed = true;
                throw new IllegalStateException("Original inventory slot changed during transfer; leaving the container open");
            }
            remaining -= pendingAmount;
            pendingAmount = 0;
            phase = remaining == 0 ? Phase.RETURN : Phase.PLACE;
        } else if (pending == Phase.PICKUP) {
            phase = dragDestinations == null ? Phase.PLACE : Phase.DRAG;
        } else {
            phase = remaining == 0 ? Phase.COMPLETE : Phase.PICKUP;
        }
        pending = null;
        observations = 0;
        return true;
    }

    private void drag() {
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty() || !ItemStack.isSameItemSameComponents(cursor, expected) || cursor.getCount() < dragDestinations.length)
            throw new IllegalStateException("Crafting drag cursor changed; leaving the container open");
        requireSourceCount();
        if (beforeDrag != null) beforeDrag.run();
        requireCraftingGrid();
        for (int slot : dragDestinations) {
            Slot target = menu.getSlot(slot);
            if (!target.getItem().isEmpty() || !target.mayPlace(cursor) || target.getMaxStackSize(cursor) < 1)
                throw new IllegalStateException("Crafting drag destination changed; leaving the container open");
        }
        prepareReceipt(source, sourceCount, cursor.getCount() - dragDestinations.length);
        for (int index = 0; index < craftingGridSlots.length; index++) {
            for (int slot : dragDestinations) {
                if (craftingGridSlots[index] == slot) expectedCraftingGrid[index] = expected.copyWithCount(1);
            }
        }
        OwnedClickReceipts.craftingDrag(client, menu.containerId, dragDestinations, client.player);
    }

    void recover() {
        requireHandler();
        if (pending != null && !observeClick())
            throw new IllegalStateException("Inventory click is still awaiting its server receipt; resume to observe it before returning the cursor");
        if (failed) throw new IllegalStateException("Inventory transfer changed; inspect the held stack before recovery");
        phase = Phase.RETURN;
        returnCursor();
        if (pending != null)
            throw new IllegalStateException("Inventory remainder return was issued; resume to observe its server receipt");
    }

    private void returnCursor() {
        requireCraftingGrid();
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty()) {
            phase = remaining == 0 ? Phase.COMPLETE : Phase.PICKUP;
            return;
        }
        requireSourceCount();
        ItemStack existing = menu.getSlot(source).getItem();
        if (!ItemStack.isSameItemSameComponents(cursor, expected) || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, cursor)
                || menu.getSlot(source).getMaxStackSize(cursor) - existing.getCount() < cursor.getCount())
            throw new IllegalStateException("Original slot changed; return the held stack manually");
        click(source, 0, existing.getCount() + cursor.getCount(), 0);
    }

    private void requireCraftingGrid() {
        if (craftingGridSlots == null) return;
        for (int index = 0; index < craftingGridSlots.length; index++) {
            if (!sameContents(expectedCraftingGrid[index], menu.getSlot(craftingGridSlots[index]).getItem()))
                throw new IllegalStateException("Crafting grid changed during transfer; leaving the container open");
        }
    }

    private void requireSourceCount() {
        if (!matchesCount(menu.getSlot(source).getItem(), sourceCount))
            throw new IllegalStateException("Original slot changed during transfer; return the held stack manually");
    }

    private boolean matchesCount(ItemStack stack, int count) {
        return count == 0 ? stack.isEmpty() : count > 0 && !stack.isEmpty()
                && stack.getCount() == count && ItemStack.isSameItemSameComponents(stack, expected);
    }

    private static boolean sameContents(ItemStack left, ItemStack right) {
        return left.isEmpty() ? right.isEmpty() : !right.isEmpty() && left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    private OwnedClickReceipts.Receipt receipt() {
        if (!(menu instanceof OwnedClickReceipts.Receipt receipt))
            throw new IllegalStateException("Native inventory synchronization is unavailable; nothing further was clicked");
        return receipt;
    }

    private void requireHandler() {
        if (client.player == null || client.gameMode == null || client.player != player || client.level != world
                || client.player.containerMenu != menu)
            throw new IllegalStateException("Container or world changed during transfer");
    }

    private void click(int slot, int button, int expectedSlotCount, int expectedCursorCount) {
        prepareReceipt(slot, expectedSlotCount, expectedCursorCount);
        OwnedClickReceipts.cursorClick(client, menu.containerId, slot, button, client.player);
    }

    private void prepareReceipt(int slot, int expectedSlotCount, int expectedCursorCount) {
        if (pending != null) throw new IllegalStateException("An inventory click is already awaiting its receipt");
        OwnedClickReceipts.Receipt receipt = receipt();
        contentsBefore = receipt.lodekeeper$contentsSequence();
        cursorBefore = receipt.lodekeeper$cursorSequence();
        sourceBefore = receipt.lodekeeper$slotSequence(source);
        destinationBefore = receipt.lodekeeper$slotSequence(destination);
        if (craftingGridSlots != null) {
            gridBefore = new long[craftingGridSlots.length];
            for (int index = 0; index < craftingGridSlots.length; index++)
                gridBefore[index] = receipt.lodekeeper$slotSequence(craftingGridSlots[index]);
        }
        outboundRevision = menu.getStateId();
        receiptSlot = slot;
        receiptSlotCount = expectedSlotCount;
        receiptCursorCount = expectedCursorCount;
        observations = 0;
        pending = phase;
    }
}
