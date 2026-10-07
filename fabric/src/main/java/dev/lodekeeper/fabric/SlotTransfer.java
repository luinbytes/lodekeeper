package dev.lodekeeper.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

final class SlotTransfer implements OwnedClickReceipts.FullReceiptObserver {
    private enum Phase { PICKUP, PLACE, DRAG, RETURN, COMPLETE }
    private static final int MAX_OBSERVATION_TICKS = 40;
    private final MinecraftClient client;
    private final ScreenHandler handler;
    private final Object player, world, networkHandler, connection, inventory;
    private final int source, destination;
    private final int[] receiptSlots;
    private final ItemStack expected;
    private final DoubleSupplier consumptionProgress;
    private final int[] dragDestinations;
    private final Runnable beforePickup, beforeDrag;
    private final int[] craftingGridSlots;
    private final ItemStack[] expectedCraftingGrid;
    private int remaining, pendingAmount, beforeCursor, beforeDestination, sourceCount;
    private int receiptSlotCount, receiptCursorCount, observations, outboundRevision;
    private long contentsBefore;
    private Phase phase = Phase.PICKUP, pending;
    private double beforeConsumptionProgress;
    private boolean failed, sending, pendingSent, operating, laterSlotLogged, returnProofLogged;
    private int consumptionProofLogs;
    private String failure;
    private FullClickReceipt<ItemStack> fullReceipt;

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
        this.networkHandler = client.getNetworkHandler();
        this.connection = client.getNetworkHandler() == null ? null : client.getNetworkHandler().getConnection();
        this.inventory = client.player == null ? null : client.player.getInventory();
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
        receiptSlots = new int[2 + (craftingGridSlots == null ? 0 : craftingGridSlots.length)];
        receiptSlots[0] = source;
        receiptSlots[1] = destination;
        if (craftingGridSlots != null) System.arraycopy(craftingGridSlots, 0, receiptSlots, 2, craftingGridSlots.length);
        remaining = amount;
        expected = handler.getSlot(source).getStack().copy();
    }

    boolean tick() {
        if (operating) {
            reject("Reentrant inventory transfer; leaving the container open");
            throw new IllegalStateException(failure);
        }
        operating = true;
        try { return tickTransfer(); } finally { operating = false; }
    }

    private boolean tickTransfer() {
        if (failed) throw new IllegalStateException(failure);
        requireHandler();
        if (pending != null && !observeClick()) return false;
        if (phase == Phase.COMPLETE) {
            requireAcknowledgedState(true);
            receipt().lodekeeper$unwatchClick(this);
            return true;
        }
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
        if (phase == Phase.COMPLETE) receipt().lodekeeper$unwatchClick(this);
        return phase == Phase.COMPLETE;
    }

    @Override public void fullContentsApplied(OwnedClickReceipts.Receipt receipt) {
        if (failed || fullReceipt == null) return;
        try {
            requireHandler();
            boolean alreadyAccepted = fullReceipt.accepted() != null;
            var snapshot = fullSnapshot(receipt);
            double progress = consumptionProgress == null ? 0 : consumptionProgress.getAsDouble();
            fullReceipt.applyFull(snapshot, liveSlots(), handler.getCursorStack(), handler.getRevision(),
                    receipt == receipt() && contextCurrent(), !sending && (pending == null || pendingSent), progress);
            if (fullReceipt.failure() != null) {
                org.slf4j.LoggerFactory.getLogger("lodekeeper").warn(
                        "[Lodekeeper] INVENTORY_TRANSFER full_rejected phase={} handler={} source={} destination={} contentsSequence={} contentsRevision={} localRevision={} expectedItem={} expectedSlotCount={} expectedCursorCount={} fullSlots={} fullCursor={} reason={}",
                        pending, handler.syncId, source, destination, snapshot.sequence(), snapshot.revision(), handler.getRevision(),
                        expected.getItem(), receiptSlotCount, receiptCursorCount, snapshot.slots(), snapshot.cursor(), fullReceipt.failure());
                reject(fullReceipt.failure() + "; leaving the container open");
            } else if (!alreadyAccepted && fullReceipt.accepted() != null) {
                logAcknowledgement(fullReceipt.consumptionConfirmed() ? "full_ack" : "full_consumption_pending");
            }
        } catch (RuntimeException exception) {
            reject("Could not validate the owned inventory receipt; leaving the container open: " + exception.getMessage());
        }
    }

    private FullClickReceipt.Snapshot<ItemStack> fullSnapshot(OwnedClickReceipts.Receipt receipt) {
        List<ItemStack> slots = new ArrayList<>();
        List<Long> sequences = new ArrayList<>();
        for (int slot : receiptSlots) {
            slots.add(receipt.lodekeeper$receivedContentsSlot(slot));
            sequences.add(receipt.lodekeeper$slotSequence(slot));
        }
        return new FullClickReceipt.Snapshot<>(receipt.lodekeeper$contentsSequence(), receipt.lodekeeper$contentsRevision(),
                receipt.lodekeeper$cursorSequence(), sequences, slots, receipt.lodekeeper$receivedCursor(), receipt.lodekeeper$contentsSize());
    }

    private List<ItemStack> liveSlots() {
        List<ItemStack> slots = new ArrayList<>();
        for (int slot : receiptSlots) slots.add(handler.getSlot(slot).getStack());
        return slots;
    }

    private boolean debugLogging() {
        return LodekeeperClient.engine != null && LodekeeperClient.engine.config.debugLogging;
    }

    private void logAcknowledgement(String event) {
        if (!debugLogging()) return;
        if (pending == Phase.RETURN) {
            if (returnProofLogged) return;
            returnProofLogged = true;
        } else {
            if (!fullReceipt.consumptionRequired() || consumptionProofLogs >= 2) return;
            consumptionProofLogs++;
        }
        var acknowledgement = fullReceipt.accepted();
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] INVENTORY_TRANSFER {} phase={} handler={} source={} destination={} expectedItem={} expectedSlotCount={} expectedCursorCount={} fullSlots={} fullCursor={} contentsBefore={} contentsSequence={} outboundRevision={} contentsRevision={} localRevision={} sourceSequence={} cursorSequence={} consumptionRequired={} consumptionConfirmed={} itemComponentsAndLiveMatched=true",
                event, pending, handler.syncId, source, destination, expected.getItem(), receiptSlotCount, receiptCursorCount,
                acknowledgement.slots(), acknowledgement.cursor(), contentsBefore, acknowledgement.sequence(),
                outboundRevision, acknowledgement.revision(), handler.getRevision(), acknowledgement.slotSequences().get(0),
                acknowledgement.cursorSequence(), fullReceipt.consumptionRequired(), fullReceipt.consumptionConfirmed());
    }

    @Override public void slotUpdated(int slot, int revision, long sequence) {
        if (failed || fullReceipt == null || fullReceipt.accepted() == null) return;
        if (!contextCurrent()) {
            reject("Container or session changed after inventory acknowledgement; leaving the container open");
            return;
        }
        if (!debugLogging() || laterSlotLogged || pending != Phase.RETURN) return;
        var acknowledgement = fullReceipt.accepted();
        int[] watched = receiptSlots;
        int index = -1;
        for (int candidate = 0; candidate < watched.length; candidate++) {
            if (watched[candidate] == slot) { index = candidate; break; }
        }
        if (index < 0 || sameContents(acknowledgement.slots().get(index), receipt().lodekeeper$receivedSlot(slot))) return;
        laterSlotLogged = true;
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] INVENTORY_TRANSFER slot_after_full phase={} handler={} source={} destination={} ackSequence={} ackRevision={} ackSourceSequence={} ackCursorSequence={} ackSlots={} ackCursor={} updatedSlot={} updatedSequence={} updatedRevision={} liveSlot={} consumptionConfirmed={} itemComponentsAndLiveMatchedAtFull=true",
                pending, handler.syncId, source, destination, acknowledgement.sequence(), acknowledgement.revision(),
                acknowledgement.slotSequences().get(0), acknowledgement.cursorSequence(), acknowledgement.slots(),
                acknowledgement.cursor(), slot, sequence, revision, handler.getSlot(slot).getStack(), fullReceipt.consumptionConfirmed());
    }

    @Override public void clickStarted(boolean owned) {
        if (!failed && (!owned || !sending || !contextCurrent())) {
            reject("Another inventory action took over the owned transfer; leaving the container open");
            if (owned) throw new IllegalStateException(failure);
        }
    }

    private void reject(String reason) {
        if (failed) return;
        failed = true;
        failure = reason;
        if (fullReceipt != null) fullReceipt.reject(reason);
        if (handler instanceof OwnedClickReceipts.Receipt receipt) receipt.lodekeeper$unwatchClick(this);
        var acknowledgement = fullReceipt == null ? null : fullReceipt.accepted();
        org.slf4j.LoggerFactory.getLogger("lodekeeper").warn(
                "[Lodekeeper] INVENTORY_TRANSFER invalidated phase={} source={} destination={} ackSequence={} reason={}",
                pending, source, destination, acknowledgement == null ? 0 : acknowledgement.sequence(), reason);
    }

    private boolean observeClick() {
        if (failed) throw new IllegalStateException(failure);
        boolean confirmedBefore = fullReceipt.consumptionConfirmed();
        FullClickReceipt.Snapshot<ItemStack> acknowledgement;
        try {
            double progress = fullReceipt.consumptionRequired() && !confirmedBefore ? consumptionProgress.getAsDouble() : 0;
            acknowledgement = fullReceipt.observe(progress);
        } catch (RuntimeException exception) {
            reject("Could not confirm the owned inventory receipt; leaving the container open: " + exception.getMessage());
            throw exception;
        }
        if (acknowledgement == null) {
            if (++observations >= MAX_OBSERVATION_TICKS)
                throw new IllegalStateException("Server has not confirmed the issued inventory click; resume to observe it without clicking again");
            return false;
        }
        if (!confirmedBefore && fullReceipt.consumptionConfirmed()) logAcknowledgement("full_ack_consumption_confirmed");
        if (pending == Phase.DRAG) {
            remaining = 0;
            phase = Phase.RETURN;
        } else if (pending == Phase.PLACE) {
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
        for (int index = 0; index < craftingGridSlots.length; index++) {
            for (int slot : dragDestinations) {
                if (craftingGridSlots[index] == slot) expectedCraftingGrid[index] = expected.copyWithCount(1);
            }
        }
        prepareReceipt(sourceCount, cursor.getCount() - dragDestinations.length);
        sendClick(() -> OwnedClickReceipts.craftingDrag(client, handler.syncId, dragDestinations, client.player));
    }

    void recover() {
        if (operating) {
            reject("Reentrant inventory recovery; leaving the container open");
            throw new IllegalStateException(failure);
        }
        operating = true;
        try { recoverTransfer(); } finally { operating = false; }
    }

    private void recoverTransfer() {
        requireHandler();
        if (pending != null && !observeClick())
            throw new IllegalStateException("Inventory click is still awaiting its server receipt; resume to observe it before returning the cursor");
        if (failed) throw new IllegalStateException(failure);
        phase = Phase.RETURN;
        returnCursor();
        if (pending != null)
            throw new IllegalStateException("Inventory remainder return was issued; resume to observe its server receipt");
        receipt().lodekeeper$unwatchClick(this);
    }

    private void returnCursor() {
        requireCraftingGrid();
        ItemStack cursor = handler.getCursorStack();
        if (cursor.isEmpty()) {
            requireAcknowledgedState(remaining == 0);
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

    private static boolean sameSourceIncrease(ItemStack left, ItemStack right) {
        return !left.isEmpty() && !right.isEmpty() && right.getCount() > left.getCount() && GameApi.canCombine(left, right);
    }

    private OwnedClickReceipts.Receipt receipt() {
        if (!(handler instanceof OwnedClickReceipts.Receipt receipt))
            throw new IllegalStateException("Native inventory synchronization is unavailable; nothing further was clicked");
        return receipt;
    }

    @Override public boolean contextCurrent() {
        return !failed && client.isOnThread() && client.player != null && client.interactionManager != null
                && client.player == player && client.world == world && world != null
                && client.getNetworkHandler() == networkHandler && networkHandler != null
                && client.getNetworkHandler().getConnection() == connection && connection != null
                && client.player.getInventory() == inventory && client.player.currentScreenHandler == handler;
    }

    private void requireHandler() {
        if (!contextCurrent()) {
            reject("Container, player or world session changed during transfer; leaving the container open");
            throw new IllegalStateException(failure);
        }
    }

    private void requireAcknowledgedState(boolean completing) {
        requireHandler();
        if (fullReceipt == null) return;
        try {
            double progress = consumptionProgress == null ? 0 : consumptionProgress.getAsDouble();
            fullReceipt.requireLive(liveSlots(), handler.getCursorStack(), contextCurrent(), progress, completing);
        } catch (RuntimeException exception) {
            reject("Acknowledged inventory changed outside the permitted transfer state; leaving the container open");
            throw exception;
        }
    }

    private void sendClick(Runnable operation) {
        sending = true;
        try {
            operation.run();
            requireHandler();
            pendingSent = true;
        } catch (RuntimeException exception) {
            reject("Owned inventory click send failed or was interrupted; leaving the container open: " + exception.getMessage());
            throw exception;
        } finally { sending = false; }
    }

    private void click(int slot, int button, int expectedSlotCount, int expectedCursorCount) {
        prepareReceipt(expectedSlotCount, expectedCursorCount);
        sendClick(() -> OwnedClickReceipts.cursorClick(client, handler.syncId, slot, button, client.player));
    }

    private void prepareReceipt(int expectedSlotCount, int expectedCursorCount) {
        if (pending != null) throw new IllegalStateException("An inventory click is already awaiting its receipt");
        requireAcknowledgedState(false);
        OwnedClickReceipts.Receipt receipt = receipt();
        receipt.lodekeeper$watchClick(this);
        pendingSent = false;
        contentsBefore = receipt.lodekeeper$contentsSequence();
        List<Long> sequences = new ArrayList<>();
        for (int watched : receiptSlots) sequences.add(receipt.lodekeeper$slotSequence(watched));
        List<ItemStack> planned = new ArrayList<>();
        planned.add(expected.copyWithCount(phase == Phase.RETURN ? expectedSlotCount : sourceCount));
        planned.add(phase == Phase.PLACE ? expected.copyWithCount(expectedSlotCount)
                : phase == Phase.DRAG ? expected.copyWithCount(1) : handler.getSlot(destination).getStack().copy());
        if (craftingGridSlots != null) {
            for (ItemStack stack : expectedCraftingGrid) planned.add(stack.copy());
        }
        List<ItemStack> consumed = null;
        if (phase == Phase.PLACE && consumptionProgress != null && expectedSlotCount > 0) {
            consumed = new ArrayList<>(planned);
            consumed.set(1, expected.copyWithCount(expectedSlotCount - 1));
        }
        var expectation = new FullClickReceipt.Snapshot<>(contentsBefore, handler.getRevision(),
                receipt.lodekeeper$cursorSequence(), sequences, planned, expected.copyWithCount(expectedCursorCount), handler.slots.size());
        fullReceipt = phase == Phase.RETURN && fullReceipt != null
                ? new FullClickReceipt<>(expectation, fullReceipt.carryConsumption(), SlotTransfer::sameContents,
                        remaining == 0 && expectedCursorCount == 0 ? SlotTransfer::sameSourceIncrease : null)
                : new FullClickReceipt<>(expectation, consumed, beforeConsumptionProgress, SlotTransfer::sameContents, null);
        outboundRevision = handler.getRevision();
        receiptSlotCount = expectedSlotCount;
        receiptCursorCount = expectedCursorCount;
        observations = 0;
        pending = phase;
    }
}
