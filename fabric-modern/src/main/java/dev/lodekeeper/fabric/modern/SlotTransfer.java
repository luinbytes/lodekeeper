package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

final class SlotTransfer implements OwnedClickReceipts.FullReceiptObserver {
    private enum Phase { PICKUP, PLACE, DRAG, RETURN, COMPLETE }
    private static final int MAX_OBSERVATION_TICKS = 40;
    private final Minecraft client;
    private final AbstractContainerMenu menu;
    private final Object player, world, networkHandler, connection, inventory;
    private final int source, destination, menuSize;
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
    private PickupObservation pickupObservation;

    private static final class PickupObservation {
        int button, records;
        long omitted, partialSourceSequence, partialSourceTime;
        int partialSourceRevision = -1;
        boolean incomplete, closed;
        String terminalOutcome, partialSource = "UNOBSERVED";
        int[] slots;
        ItemStack sourceBefore;
    }

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
        this.menuSize = menu.slots.size();
        this.player = client.player;
        this.world = client.level;
        this.networkHandler = client.getConnection();
        this.connection = client.getConnection() == null ? null : client.getConnection().getConnection();
        this.inventory = client.player == null ? null : client.player.getInventory();
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
        receiptSlots = new int[2 + (craftingGridSlots == null ? 0 : craftingGridSlots.length)];
        receiptSlots[0] = source;
        receiptSlots[1] = destination;
        if (craftingGridSlots != null) System.arraycopy(craftingGridSlots, 0, receiptSlots, 2, craftingGridSlots.length);
        remaining = amount;
        expected = menu.getSlot(source).getItem().copy();
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
        if (phase == Phase.COMPLETE) receipt().lodekeeper$unwatchClick(this);
        return phase == Phase.COMPLETE;
    }

    @Override public void fullContentsApplied(OwnedClickReceipts.Receipt receipt) {
        if (failed || fullReceipt == null) return;
        observePickup("FULL_BEFORE_VALIDATION", false, receipt, -1, -1, 0);
        try {
            requireHandler();
            boolean alreadyAccepted = fullReceipt.accepted() != null;
            var snapshot = fullSnapshot(receipt);
            double progress = consumptionProgress == null ? 0 : consumptionProgress.getAsDouble();
            fullReceipt.applyFull(snapshot, liveSlots(), menu.getCarried(), menu.getStateId(),
                    receipt == receipt() && contextCurrent(), !sending && (pending == null || pendingSent), progress);
            pickupValidationOutcome(receipt);
            if (fullReceipt.failure() != null) {
                org.slf4j.LoggerFactory.getLogger("lodekeeper").warn(
                        "[Lodekeeper] INVENTORY_TRANSFER full_rejected phase={} menu={} source={} destination={} contentsSequence={} contentsRevision={} localRevision={} expectedItem={} expectedSlotCount={} expectedCursorCount={} fullSlots={} fullCursor={} reason={}",
                        pending, menu.containerId, source, destination, snapshot.sequence(), snapshot.revision(), menu.getStateId(),
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
        for (int slot : receiptSlots) slots.add(menu.getSlot(slot).getItem());
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
                "[Lodekeeper] INVENTORY_TRANSFER {} phase={} menu={} source={} destination={} expectedItem={} expectedSlotCount={} expectedCursorCount={} fullSlots={} fullCursor={} contentsBefore={} contentsSequence={} outboundRevision={} contentsRevision={} localRevision={} sourceSequence={} cursorSequence={} consumptionRequired={} consumptionConfirmed={} itemComponentsAndLiveMatched=true",
                event, pending, menu.containerId, source, destination, expected.getItem(), receiptSlotCount, receiptCursorCount,
                acknowledgement.slots(), acknowledgement.cursor(), contentsBefore, acknowledgement.sequence(),
                outboundRevision, acknowledgement.revision(), menu.getStateId(), acknowledgement.slotSequences().get(0),
                acknowledgement.cursorSequence(), fullReceipt.consumptionRequired(), fullReceipt.consumptionConfirmed());
    }

    @Override public void slotUpdated(int slot, int revision, long sequence) {
        observePickup("PARTIAL_APPLIED", false, null, slot, revision, sequence);
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
                "[Lodekeeper] INVENTORY_TRANSFER slot_after_full phase={} menu={} source={} destination={} ackSequence={} ackRevision={} ackSourceSequence={} ackCursorSequence={} ackSlots={} ackCursor={} updatedSlot={} updatedSequence={} updatedRevision={} liveSlot={} consumptionConfirmed={} itemComponentsAndLiveMatchedAtFull=true",
                pending, menu.containerId, source, destination, acknowledgement.sequence(), acknowledgement.revision(),
                acknowledgement.slotSequences().get(0), acknowledgement.cursorSequence(), acknowledgement.slots(),
                acknowledgement.cursor(), slot, sequence, revision, menu.getSlot(slot).getItem(), fullReceipt.consumptionConfirmed());
    }

    @Override public void clickStarted(boolean owned) {
        if (!failed && (!owned || !sending || !contextCurrent())) {
            reject("Another inventory action took over the owned transfer; leaving the container open");
            if (owned) throw new IllegalStateException(failure);
        }
    }

    private void reject(String reason) {
        if (failed) return;
        observePickup("REJECTED", true, null, -1, -1, 0);
        failed = true;
        failure = reason;
        if (fullReceipt != null) fullReceipt.reject(reason);
        if (menu instanceof OwnedClickReceipts.Receipt receipt) receipt.lodekeeper$unwatchClick(this);
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
            if (++observations >= MAX_OBSERVATION_TICKS) {
                observePickup("OBSERVATION_TIMEOUT_PAUSE", true, null, -1, -1, 0);
                throw new IllegalStateException("Server has not confirmed the issued inventory click; resume to observe it without clicking again");
            }
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
            closePickupObservation();
        } else {
            phase = remaining == 0 ? Phase.COMPLETE : Phase.PICKUP;
        }
        pending = null;
        observations = 0;
        return true;
    }

    private void drag() {
        requireAcknowledgedState(false);
        requireDragCursor();
        requireSourceCount();
        if (beforeDrag != null) beforeDrag.run();
        requireAcknowledgedState(false);
        ItemStack cursor = requireDragCursor();
        requireSourceCount();
        requireCraftingGrid();
        for (int slot : dragDestinations) {
            Slot target = menu.getSlot(slot);
            if (!target.getItem().isEmpty() || !target.mayPlace(cursor) || target.getMaxStackSize(cursor) < 1)
                throw new IllegalStateException("Crafting drag destination changed; leaving the container open");
        }
        for (int index = 0; index < craftingGridSlots.length; index++) {
            for (int slot : dragDestinations) {
                if (craftingGridSlots[index] == slot) expectedCraftingGrid[index] = expected.copyWithCount(1);
            }
        }
        prepareReceipt(sourceCount, cursor.getCount() - dragDestinations.length);
        sendClick(() -> OwnedClickReceipts.craftingDrag(client, menu.containerId, dragDestinations, client.player));
    }

    private ItemStack requireDragCursor() {
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty() || !ItemStack.isSameItemSameComponents(cursor, expected) || cursor.getCount() < dragDestinations.length)
            throw new IllegalStateException("Crafting drag cursor changed; leaving the container open");
        return cursor;
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
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty()) {
            requireAcknowledgedState(remaining == 0);
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

    private static boolean sameSourceIncrease(ItemStack left, ItemStack right) {
        return !left.isEmpty() && !right.isEmpty() && right.getCount() > left.getCount() && ItemStack.isSameItemSameComponents(left, right);
    }

    private OwnedClickReceipts.Receipt receipt() {
        if (!(menu instanceof OwnedClickReceipts.Receipt receipt))
            throw new IllegalStateException("Native inventory synchronization is unavailable; nothing further was clicked");
        return receipt;
    }

    @Override public boolean contextCurrent() {
        return !failed && client.isSameThread() && client.player != null && client.gameMode != null
                && client.player == player && client.level == world && world != null
                && client.getConnection() == networkHandler && networkHandler != null
                && client.getConnection().getConnection() == connection && connection != null
                && client.player.getInventory() == inventory && client.player.containerMenu == menu
                && menu.slots.size() == menuSize;
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
            fullReceipt.requireLive(liveSlots(), menu.getCarried(), contextCurrent(), progress, completing);
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
            observePickup("SEND_FAILED", true, null, -1, -1, 0);
            reject("Owned inventory click send failed or was interrupted; leaving the container open: " + exception.getMessage());
            throw exception;
        } finally { sending = false; }
    }

    private void click(int slot, int button, int expectedSlotCount, int expectedCursorCount) {
        prepareReceipt(expectedSlotCount, expectedCursorCount);
        sendClick(() -> {
            startPickupObservation(button);
            OwnedClickReceipts.cursorClick(client, menu.containerId, slot, button, client.player);
            observePickup("LOCAL_RETURN", false, null, -1, -1, 0);
        });
    }

    private void startPickupObservation(int button) {
        try {
            if (pickupObservation != null || pending != Phase.PICKUP || !debugLogging()
                    || !(menu instanceof net.minecraft.world.inventory.CraftingMenu crafting) || menuSize != 46) return;
            pickupObservation = new PickupObservation();
            pickupObservation.button = button;
            if (crafting.getInputGridSlots().size() != 9) {
                pickupObservation.records++;
                incompletePickupObservation("BEFORE_NATIVE_CALL", false);
                return;
            }
            pickupObservation.slots = new int[11];
            pickupObservation.slots[0] = source;
            pickupObservation.slots[1] = destination;
            for (int index = 2; index < pickupObservation.slots.length; index++)
                pickupObservation.slots[index] = menu.slots.indexOf(crafting.getInputGridSlots().get(index - 2));
            pickupObservation.sourceBefore = menu.getSlot(source).getItem().copy();
        } catch (Throwable diagnosticFailure) {
            if (pickupObservation != null) {
                pickupObservation.records++;
                incompletePickupObservation("BEFORE_NATIVE_CALL", false);
            }
            return;
        }
        observePickup("BEFORE_NATIVE_CALL", false, null, -1, -1, 0);
    }

    private void pickupValidationOutcome(OwnedClickReceipts.Receipt receipt) {
        if (pickupObservation == null || pickupObservation.closed || pending != Phase.PICKUP) return;
        try {
            observePickup(fullReceipt.failure() != null ? "FULL_REJECTED"
                    : fullReceipt.accepted() != null ? "FULL_ACCEPTED" : "FULL_WAITING",
                    fullReceipt.failure() != null || fullReceipt.accepted() != null, receipt, -1, -1, 0);
        } catch (Throwable diagnosticFailure) {
            if (pickupObservation != null && !pickupObservation.closed)
                incompletePickupObservation("VALIDATION_OUTCOME", true);
        }
    }

    private void closePickupObservation() {
        if (pickupObservation == null) return;
        pickupObservation.closed = true;
        pickupObservation.sourceBefore = null;
    }

    private void incompletePickupObservation(String event, boolean terminal) {
        PickupObservation observation = pickupObservation;
        observation.incomplete = true;
        try {
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] PICKUP_OBSERVATION event={} timeNanos={} transferTag={} terminal={} capture=INCOMPLETE intermediateRecords={} omitted={}",
                    event, System.nanoTime(), pickupTag(this), terminal, observation.records, observation.omitted);
        } catch (Throwable ignoredDiagnosticFailure) {}
        finally { closePickupObservation(); }
    }

    private void observePickup(String event, boolean terminal, OwnedClickReceipts.Receipt applied,
                               int updatedSlot, int updatedRevision, long updatedSequence) {
        PickupObservation observation = pickupObservation;
        if (observation == null || observation.closed || pending != Phase.PICKUP) return;
        boolean emitRecord = false, omitted = false;
        try {
            if (updatedSlot >= 0 && observation.slots != null) {
                boolean watched = false;
                for (int slot : observation.slots) watched |= slot == updatedSlot;
                if (!watched) return;
            }
            boolean terminalUpdate = observation.terminalOutcome != null;
            if (terminal) {
                if (event.equals(observation.terminalOutcome)) return;
                observation.terminalOutcome = event;
                emitRecord = true;
            } else {
                if (observation.records >= 12) {
                    observation.omitted++;
                    observation.incomplete = true;
                    omitted = true;
                } else {
                    observation.records++;
                    emitRecord = true;
                }
            }
            long time = System.nanoTime();
            OwnedClickReceipts.Receipt receipt = applied == null ? receipt() : applied;
            if (updatedSlot == source) {
                observation.partialSource = pickupStack(receipt.lodekeeper$receivedSlot(source));
                observation.partialSourceSequence = updatedSequence;
                observation.partialSourceRevision = updatedRevision;
                observation.partialSourceTime = time;
            }
            if (!emitRecord) return;
            long fullSequence = receipt.lodekeeper$contentsSequence();
            boolean fullPresent = fullSequence > 0 && receipt.lodekeeper$contentsSize() == menuSize;
            List<String> slots = new ArrayList<>();
            for (int index = 0; index < observation.slots.length; index++) {
                int slot = observation.slots[index];
                Slot nativeSlot = menu.getSlot(slot);
                ItemStack live = nativeSlot.getItem();
                ItemStack full = fullPresent ? receipt.lodekeeper$receivedContentsSlot(slot) : null;
                slots.add("role=" + (index == 0 ? "source" : index == 1 ? "destination" : "grid")
                        + ",menuSlot=" + slot + ",playerInventorySlot="
                        + (nativeSlot.container == inventory ? nativeSlot.getContainerSlot() : "NONE")
                        + ",latestUpdateSequence=" + receipt.lodekeeper$slotSequence(slot)
                        + ",live=" + pickupStack(live) + ",latestFull=" + (full == null ? "ABSENT" : pickupStack(full))
                        + ",liveSameFullComponents=" + (full == null ? "ABSENT" : ItemStack.isSameItemSameComponents(live, full)));
            }
            ItemStack cursor = menu.getCarried();
            ItemStack fullCursor = fullPresent ? receipt.lodekeeper$receivedCursor() : null;
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] PICKUP_OBSERVATION event={} timeNanos={} terminal={} terminalUpdate={} intermediateRecords={} omitted={} incomplete={} transferTag={} menuId={} menuClass={} menuTag={} playerUuid={} playerTag={} worldTag={} networkTag={} connectionTag={} inventoryTag={} contextCurrent={} appliedReceiptMatchesMenu={} phase={} pending={} sending={} pendingSent={} button={} remaining={} expectedConstructorCount={} predictedSourceCount={} predictedCursorCount={} localRevision={} localRevisionBeforePrediction={} baselineFullSequence={} latestFullSequence={} latestFullRevision={} latestFullSize={} latestFullStatus={} latestCursorSequence={} slots={} liveCursor={} latestFullCursor={} liveCursorSameFullComponents={} liveCursorSamePrePickupSourceComponents={} fullCursorSamePrePickupSourceComponents={} latestReceivedSource={} latestPartialSource={} latestPartialSourceSequence={} latestPartialSourceRevision={} latestPartialSourceTimeNanos={} updatedSlot={} updatedRevision={} updatedSequence={} updatedValue={}",
                    event, time, terminal, terminalUpdate, observation.records, observation.omitted, observation.incomplete,
                    pickupTag(this), menu.containerId, menu.getClass().getName(), pickupTag(menu),
                    ((net.minecraft.world.entity.player.Player) player).getUUID(), pickupTag(player), pickupTag(world),
                    pickupTag(networkHandler), pickupTag(connection), pickupTag(inventory), contextCurrent(), receipt == receipt(),
                    phase, pending, sending, pendingSent, observation.button, remaining, expected.getCount(), sourceCount,
                    receiptCursorCount, menu.getStateId(), outboundRevision, contentsBefore, fullSequence,
                    receipt.lodekeeper$contentsRevision(), receipt.lodekeeper$contentsSize(), fullPresent ? "PRESENT" : "ABSENT",
                    receipt.lodekeeper$cursorSequence(), slots, pickupStack(cursor), fullCursor == null ? "ABSENT" : pickupStack(fullCursor),
                    fullCursor == null ? "ABSENT" : ItemStack.isSameItemSameComponents(cursor, fullCursor),
                    observation.sourceBefore == null ? "INCOMPLETE" : ItemStack.isSameItemSameComponents(cursor, observation.sourceBefore),
                    fullCursor == null ? "ABSENT" : observation.sourceBefore == null ? "INCOMPLETE"
                            : ItemStack.isSameItemSameComponents(fullCursor, observation.sourceBefore),
                    pickupStack(receipt.lodekeeper$receivedSlot(source)), observation.partialSource, observation.partialSourceSequence,
                    observation.partialSourceRevision, observation.partialSourceTime, updatedSlot, updatedRevision, updatedSequence,
                    updatedSlot < 0 ? "NONE" : pickupStack(receipt.lodekeeper$receivedSlot(updatedSlot)));
        } catch (Throwable diagnosticFailure) {
            if (!omitted) observation.omitted++;
            incompletePickupObservation(event, terminal || !emitRecord);
        } finally {
            try {
                if (terminal && (event.equals("FULL_REJECTED") || event.equals("REJECTED") || event.equals("SEND_FAILED")))
                    closePickupObservation();
            } catch (Throwable diagnosticFailure) { observation.incomplete = true; }
        }
    }

    private String pickupStack(ItemStack stack) {
        return "{empty=" + stack.isEmpty() + ",item=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                + ",count=" + stack.getCount() + ",sameExpectedComponents=" + ItemStack.isSameItemSameComponents(stack, expected) + "}";
    }

    private static String pickupTag(Object value) {
        return value == null ? "null" : Integer.toHexString(System.identityHashCode(value));
    }

    private void prepareReceipt(int expectedSlotCount, int expectedCursorCount) {
        if (pending != null) throw new IllegalStateException("An inventory click is already awaiting its receipt");
        requireAcknowledgedState(false);
        FullClickReceipt.PreDragAcknowledgement<ItemStack> preDrag = null;
        if (phase == Phase.DRAG) {
            if (fullReceipt == null || fullReceipt.accepted() == null)
                throw new IllegalStateException("Crafting drag requires a confirmed pickup receipt");
            var pickup = fullReceipt.accepted();
            preDrag = new FullClickReceipt.PreDragAcknowledgement<>(
                    pickup.slots().stream().map(ItemStack::copy).toList(), pickup.cursor().copy());
        }
        OwnedClickReceipts.Receipt receipt = receipt();
        receipt.lodekeeper$watchClick(this);
        pendingSent = false;
        contentsBefore = receipt.lodekeeper$contentsSequence();
        List<Long> sequences = new ArrayList<>();
        for (int watched : receiptSlots) sequences.add(receipt.lodekeeper$slotSequence(watched));
        List<ItemStack> planned = new ArrayList<>();
        planned.add(expected.copyWithCount(phase == Phase.RETURN ? expectedSlotCount : sourceCount));
        planned.add(phase == Phase.PLACE ? expected.copyWithCount(expectedSlotCount)
                : phase == Phase.DRAG ? expected.copyWithCount(1) : menu.getSlot(destination).getItem().copy());
        if (craftingGridSlots != null) {
            for (ItemStack stack : expectedCraftingGrid) planned.add(stack.copy());
        }
        List<ItemStack> consumed = null;
        if (phase == Phase.PLACE && consumptionProgress != null && expectedSlotCount > 0) {
            consumed = new ArrayList<>(planned);
            consumed.set(1, expected.copyWithCount(expectedSlotCount - 1));
        }
        var expectation = new FullClickReceipt.Snapshot<>(contentsBefore, menu.getStateId(),
                receipt.lodekeeper$cursorSequence(), sequences, planned, expected.copyWithCount(expectedCursorCount), menuSize);
        fullReceipt = phase == Phase.DRAG
                ? FullClickReceipt.forDrag(expectation, preDrag, SlotTransfer::sameContents)
                : phase == Phase.RETURN && fullReceipt != null
                ? new FullClickReceipt<>(expectation, fullReceipt.carryConsumption(), SlotTransfer::sameContents,
                        remaining == 0 && expectedCursorCount == 0 ? SlotTransfer::sameSourceIncrease : null)
                : new FullClickReceipt<>(expectation, consumed, beforeConsumptionProgress, SlotTransfer::sameContents, null);
        outboundRevision = menu.getStateId();
        receiptSlotCount = expectedSlotCount;
        receiptCursorCount = expectedCursorCount;
        observations = 0;
        pending = phase;
    }
}
