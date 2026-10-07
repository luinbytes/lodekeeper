package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import java.util.function.DoubleSupplier;

final class SlotTransfer {
    private enum Phase { PICKUP, PLACE, DRAG, RETURN, COMPLETE }
    private static final int MAX_OBSERVATION_TICKS = 40;
    private final MinecraftClient client;
    private final ScreenHandler handler;
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

    SlotTransfer(MinecraftClient client, ScreenHandler handler, int source, int destination, int amount) {
        this(client, handler, source, destination, amount, null);
    }

    SlotTransfer(MinecraftClient client, ScreenHandler handler, int source, int destination, int amount,
                 DoubleSupplier consumptionProgress) {
        this(client, handler, source, destination, amount, consumptionProgress, null, null, null);
    }

    SlotTransfer(MinecraftClient client, ScreenHandler handler, int source, int[] destinations,
                 Runnable beforePickup, Runnable beforeDrag) {
        this(client, handler, source, destinations[0], destinations.length, null,
                destinations, beforePickup, beforeDrag);
        if (destinations.length < 2) throw new IllegalArgumentException("A crafting drag needs multiple destinations");
    }

    private SlotTransfer(MinecraftClient client, ScreenHandler handler, int source, int destination, int amount,
                         DoubleSupplier consumptionProgress, int[] dragDestinations,
                         Runnable beforePickup, Runnable beforeDrag) {
        this.client = client;
        this.handler = handler;
        this.player = client.player;
        this.world = client.world;
        this.source = source;
        this.destination = destination;
        this.consumptionProgress = consumptionProgress;
        this.dragDestinations = dragDestinations == null ? null : dragDestinations.clone();
        this.beforePickup = beforePickup;
        this.beforeDrag = beforeDrag;
        if (dragDestinations != null) {
            int gridSize;
            if (handler instanceof net.minecraft.screen.CraftingScreenHandler) gridSize = 9;
            else if (handler instanceof net.minecraft.screen.PlayerScreenHandler) gridSize = 4;
            else throw new IllegalArgumentException("A crafting drag requires a native crafting grid");
            craftingGridSlots = new int[gridSize];
            expectedCraftingGrid = new ItemStack[gridSize];
            for (int index = 0; index < gridSize; index++) {
                craftingGridSlots[index] = index + 1;
                expectedCraftingGrid[index] = handler.getSlot(index + 1).getStack().copy();
            }
            for (int index = 0; index < dragDestinations.length; index++) {
                int slot = dragDestinations[index];
                boolean gridSlot = false;
                for (int candidate : craftingGridSlots) gridSlot |= candidate == slot;
                if (!gridSlot || slot == source || !handler.getSlot(slot).getStack().isEmpty())
                    throw new IllegalArgumentException("invalid crafting destination");
                for (int previous = 0; previous < index; previous++) {
                    if (dragDestinations[previous] == slot) throw new IllegalArgumentException("duplicate crafting destination");
                }
            }
        } else {
            craftingGridSlots = null;
            expectedCraftingGrid = null;
        }
        if (amount < 1 || amount > 64 || source == destination) throw new IllegalArgumentException("invalid transfer");
        remaining = amount;
        expected = handler.getSlot(source).getStack().copy();
    }

    boolean tick() {
        if (failed) throw new IllegalStateException("Transfer failed; cancel or replan after inventory recovery");
        requireHandler();
        if (pending != null && !observeClick()) return false;
        if (phase == Phase.COMPLETE) return true;
        if (phase == Phase.PICKUP) {
            if (beforePickup != null) beforePickup.run();
            requireCraftingGrid();
            if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("Cursor is occupied; finish your inventory action first");
            ItemStack stack = handler.getSlot(source).getStack();
            if (stack.isEmpty() || !GameApi.canCombine(stack, expected) || stack.getCount() < remaining)
                throw new IllegalStateException("Ingredient changed during transfer");
            int half = (stack.getCount() + 1) / 2;
            boolean pickupHalf = remaining < stack.getCount() && remaining == half;
            int pickup = pickupHalf ? half : stack.getCount();
            sourceCount = stack.getCount() - pickup;
            click(source, pickupHalf ? 1 : 0, sourceCount, pickup);
        } else if (phase == Phase.PLACE) {
            ItemStack cursor = handler.getCursorStack();
            if (cursor.isEmpty() || !GameApi.canCombine(cursor, expected)) throw new IllegalStateException("Cursor pickup rejected or changed");
            requireSourceCount();
            Slot slot = handler.getSlot(destination);
            ItemStack existing = slot.getStack();
            pendingAmount = cursor.getCount() == remaining ? remaining : 1;
            if (!slot.canInsert(cursor) || !existing.isEmpty() && !GameApi.canCombine(existing, cursor)
                    || slot.getMaxItemCount(cursor) - existing.getCount() < pendingAmount)
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
                || !sameContents(received, handler.getSlot(receiptSlot).getStack())
                || !sameContents(cursor, handler.getCursorStack())) {
            failed = true;
            ItemStack localSlot = handler.getSlot(receiptSlot).getStack();
            ItemStack localCursor = handler.getCursorStack();
            ItemStack contentsSource = receipt.lodekeeper$receivedContentsSlot(receiptSlot);
            org.slf4j.LoggerFactory.getLogger("lodekeeper").warn(
                    "[Lodekeeper] INVENTORY_TRANSFER mismatch phase={} source={} destination={} receiptSlot={} expectedItem={} expectedSlotCount={} expectedCursorCount={} receivedItem={} receivedSlotCount={} receivedCursorItem={} receivedCursorCount={} localItem={} localSlotCount={} localCursorItem={} localCursorCount={} outboundRevision={} contentsRevision={} localRevision={} contentsBefore={} contentsSequence={} contentsSourceItem={} contentsSourceCount={} sourceSequence={} cursorSequence={}",
                    pending, source, destination, receiptSlot, expected.getItem(), receiptSlotCount, receiptCursorCount,
                    received.getItem(), received.getCount(), cursor.getItem(), cursor.getCount(),
                    localSlot.getItem(), localSlot.getCount(), localCursor.getItem(), localCursor.getCount(),
                    outboundRevision, receipt.lodekeeper$contentsRevision(), handler.getRevision(),
                    contentsBefore, receipt.lodekeeper$contentsSequence(), contentsSource.getItem(), contentsSource.getCount(),
                    receipt.lodekeeper$slotSequence(source), receipt.lodekeeper$cursorSequence());
            throw new IllegalStateException("Server rejected or modified the inventory transfer; leaving the container open");
        }
        if (craftingGridSlots != null) {
            for (int index = 0; index < craftingGridSlots.length; index++) {
                int slot = craftingGridSlots[index];
                ItemStack receivedGrid = receipt.lodekeeper$receivedSlot(slot);
                if (!sameContents(receivedGrid, expectedCraftingGrid[index])
                        || !sameContents(receivedGrid, handler.getSlot(slot).getStack())) {
                    failed = true;
                    throw new IllegalStateException("Server rejected or modified the crafting grid; leaving the container open");
                }
            }
            if (!matchesCount(receipt.lodekeeper$receivedSlot(source), pending == Phase.RETURN ? receiptSlotCount : sourceCount)
                    || !sameContents(receipt.lodekeeper$receivedSlot(source), handler.getSlot(source).getStack())) {
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
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty() || !GameApi.canCombine(cursor, expected) || cursor.getCount() < dragDestinations.length)
            throw new IllegalStateException("Crafting drag cursor changed; leaving the container open");
        requireSourceCount();
        if (beforeDrag != null) beforeDrag.run();
        requireCraftingGrid();
        for (int slot : dragDestinations) {
            Slot target = handler.getSlot(slot);
            if (!target.getStack().isEmpty() || !target.canInsert(cursor) || target.getMaxItemCount(cursor) < 1)
                throw new IllegalStateException("Crafting drag destination changed; leaving the container open");
        }
        prepareReceipt(source, sourceCount, cursor.getCount() - dragDestinations.length);
        for (int index = 0; index < craftingGridSlots.length; index++) {
            for (int slot : dragDestinations) {
                if (craftingGridSlots[index] == slot) expectedCraftingGrid[index] = expected.copyWithCount(1);
            }
        }
        OwnedClickReceipts.craftingDrag(client, handler.syncId, dragDestinations, client.player);
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
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) {
            phase = remaining == 0 ? Phase.COMPLETE : Phase.PICKUP;
            return;
        }
        requireSourceCount();
        ItemStack existing = handler.getSlot(source).getStack();
        if (!GameApi.canCombine(cursor, expected) || !existing.isEmpty() && !GameApi.canCombine(existing, cursor)
                || handler.getSlot(source).getMaxItemCount(cursor) - existing.getCount() < cursor.getCount())
            throw new IllegalStateException("Original slot changed; return the held stack manually");
        click(source, 0, existing.getCount() + cursor.getCount(), 0);
    }

    private void requireCraftingGrid() {
        if (craftingGridSlots == null) return;
        for (int index = 0; index < craftingGridSlots.length; index++) {
            if (!sameContents(expectedCraftingGrid[index], handler.getSlot(craftingGridSlots[index]).getStack()))
                throw new IllegalStateException("Crafting grid changed during transfer; leaving the container open");
        }
    }

    private void requireSourceCount() {
        if (!matchesCount(handler.getSlot(source).getStack(), sourceCount))
            throw new IllegalStateException("Original slot changed during transfer; return the held stack manually");
    }

    private boolean matchesCount(ItemStack stack, int count) {
        return count == 0 ? stack.isEmpty() : count > 0 && !stack.isEmpty()
                && stack.getCount() == count && GameApi.canCombine(stack, expected);
    }

    private static boolean sameContents(ItemStack left, ItemStack right) {
        return left.isEmpty() ? right.isEmpty() : !right.isEmpty() && left.getCount() == right.getCount()
                && GameApi.canCombine(left, right);
    }

    private OwnedClickReceipts.Receipt receipt() {
        if (!(handler instanceof OwnedClickReceipts.Receipt receipt))
            throw new IllegalStateException("Native inventory synchronization is unavailable; nothing further was clicked");
        return receipt;
    }

    private void requireHandler() {
        if (client.player == null || client.interactionManager == null || client.player != player || client.world != world
                || client.player.currentScreenHandler != handler)
            throw new IllegalStateException("Container or world changed during transfer");
    }

    private void click(int slot, int button, int expectedSlotCount, int expectedCursorCount) {
        prepareReceipt(slot, expectedSlotCount, expectedCursorCount);
        OwnedClickReceipts.cursorClick(client, handler.syncId, slot, button, client.player);
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
        outboundRevision = handler.getRevision();
        receiptSlot = slot;
        receiptSlotCount = expectedSlotCount;
        receiptCursorCount = expectedCursorCount;
        observations = 0;
        pending = phase;
    }
}
