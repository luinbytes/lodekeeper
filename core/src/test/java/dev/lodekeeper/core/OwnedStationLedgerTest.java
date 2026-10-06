package dev.lodekeeper.core;

import static dev.lodekeeper.core.OwnedStationLedger.BeginStatus.ACCEPTED;
import static dev.lodekeeper.core.OwnedStationLedger.BeginStatus.PENDING_BACKPRESSURE;
import static dev.lodekeeper.core.OwnedStationLedger.BeginStatus.RECORD_CAPACITY;
import static dev.lodekeeper.core.OwnedStationLedger.ObservationResult.CONFIRMED;
import static dev.lodekeeper.core.OwnedStationLedger.ObservationResult.IGNORED;
import static dev.lodekeeper.core.OwnedStationLedger.ObservationResult.OWNERSHIP_RETAINED;
import static dev.lodekeeper.core.OwnedStationLedger.ObservationResult.OWNERSHIP_REMOVED;
import static dev.lodekeeper.core.OwnedStationLedger.ObservationResult.PENDING;
import static dev.lodekeeper.core.OwnedStationLedger.ObservationResult.PENDING_REJECTED;
import static dev.lodekeeper.core.OwnedStationLedger.ObservationResult.STALE_SEQUENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lodekeeper.core.OwnedStationLedger.BlockPosition;
import dev.lodekeeper.core.OwnedStationLedger.PlacementTicket;
import dev.lodekeeper.core.OwnedStationLedger.Session;
import dev.lodekeeper.core.OwnedStationLedger.StationRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OwnedStationLedgerTest {
    private static final WorldScope WORLD = new WorldScope("world-a", "minecraft:overworld");
    private static final Session SESSION = new Session(WORLD, 8);
    private static final ItemId CRAFTING_TABLE = ItemId.parse("minecraft:crafting_table");
    private static final ItemId STONECUTTER = ItemId.parse("minecraft:stonecutter");
    private static final ItemId OTHER_BLOCK = ItemId.parse("minecraft:stone");
    private static final BlockPosition FIRST = new BlockPosition(4, 65, 9);

    @Test
    void discoveryAndOneReceiptNeverCreateOwnership() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);

        assertEquals(IGNORED, ledger.onServerBlockUpdate(SESSION, FIRST, CRAFTING_TABLE, 1));
        assertTrue(ledger.records().isEmpty());

        PlacementTicket ticket = begin(ledger, 14, FIRST, 1, 2);
        assertSame(SESSION, ticket.intent().session());
        assertEquals(PENDING, ledger.onServerBlockUpdate(SESSION, FIRST, CRAFTING_TABLE, 2));
        assertTrue(ledger.records().isEmpty());

        assertEquals(CONFIRMED, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 1, 3));
        StationRecord record = ledger.records().get(0);
        assertEquals(14, record.jobToken());
        assertEquals(FIRST, record.position());
        assertEquals(2, record.blockReceiptSequence());
        assertEquals(3, record.inventoryReceiptSequence());
    }

    @Test
    void inventoryThenBlockReceiptsConfirmTheSamePlacement() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        BlockPosition position = new BlockPosition(-11, 70, 20);
        begin(ledger, 22, position, 10, 3);

        assertEquals(PENDING, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 2, 11));
        assertTrue(ledger.records().isEmpty());
        assertEquals(CONFIRMED, ledger.onServerBlockUpdate(SESSION, position, CRAFTING_TABLE, 12));

        StationRecord record = ledger.records().get(0);
        assertEquals(SESSION, record.session());
        assertEquals(22, record.jobToken());
        assertEquals(position, record.position());
        assertEquals(10, record.startNetworkSequence());
        assertEquals(11, record.inventoryReceiptSequence());
        assertEquals(12, record.blockReceiptSequence());
    }

    @Test
    void refundAfterDecrementRejectsPendingBeforeBlockCanConfirmIt() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        begin(ledger, 23, FIRST, 0, 2);

        assertEquals(PENDING, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 1, 1));
        assertEquals(PENDING_REJECTED, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 2, 2));
        assertEquals(IGNORED, ledger.onServerBlockUpdate(SESSION, FIRST, CRAFTING_TABLE, 3));
        assertTrue(ledger.records().isEmpty());
        assertTrue(ledger.pendingTicket().isEmpty());
    }

    @Test
    void anUnchangedCountBeforeTheFirstDecrementDoesNotRejectPlacement() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        begin(ledger, 24, FIRST, 0, 2);

        assertEquals(PENDING, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 2, 1));
        assertEquals(PENDING, ledger.onServerBlockUpdate(SESSION, FIRST, CRAFTING_TABLE, 2));
        assertEquals(CONFIRMED, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 1, 3));
        assertEquals(1, ledger.records().size());
    }

    @Test
    void aSessionChangeClearsPendingAndConfirmedOwnershipForTheSameWorld() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        beginAndConfirm(ledger, 31, FIRST, 0, 2);
        BlockPosition second = new BlockPosition(5, 65, 9);
        begin(ledger, 32, second, 2, 2);
        assertEquals(1, ledger.records().size());
        assertTrue(ledger.pendingTicket().isPresent());

        Session next = new Session(WORLD, SESSION.generation() + 1);
        ledger.changeSession(next);

        assertEquals(List.of(), ledger.records());
        assertTrue(ledger.pendingTicket().isEmpty());
        assertEquals(OwnedStationLedger.ObservationResult.STALE_SESSION,
                ledger.onServerBlockUpdate(SESSION, second, CRAFTING_TABLE, 3));
        assertEquals(OwnedStationLedger.ObservationResult.STALE_SESSION,
                ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 1, 4));
        assertEquals(OwnedStationLedger.BeginStatus.STALE_SESSION, ledger.tryBeginPlacement(
                SESSION, 33, second, CRAFTING_TABLE, CRAFTING_TABLE, 4, 1).status());
        assertTrue(ledger.records().isEmpty());
        assertTrue(ledger.tryBeginPlacement(
                next, 34, second, CRAFTING_TABLE, CRAFTING_TABLE, 0, 1).accepted());
    }

    @Test
    void wrongSequenceItemPositionAndBlockCannotConfirmAnIntent() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        BlockPosition target = new BlockPosition(1, 64, 1);
        begin(ledger, 40, target, 5, 2);

        assertEquals(STALE_SEQUENCE, ledger.onServerBlockUpdate(SESSION, target, CRAFTING_TABLE, 5));
        assertEquals(PENDING, ledger.onServerBlockUpdate(
                SESSION, new BlockPosition(2, 64, 1), CRAFTING_TABLE, 6));
        assertEquals(PENDING, ledger.onInventoryCount(SESSION, STONECUTTER, 0, 7));
        assertEquals(PENDING, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 1, 8));
        assertEquals(PENDING_REJECTED, ledger.onServerBlockUpdate(SESSION, target, OTHER_BLOCK, 9));
        assertEquals(IGNORED, ledger.onServerBlockUpdate(SESSION, target, CRAFTING_TABLE, 10));
        assertTrue(ledger.records().isEmpty());
        assertTrue(ledger.pendingTicket().isEmpty());
    }

    @Test
    void pendingBackpressureAndReceiptSequencePreventOneDecrementCreditingTwoJobs() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        PlacementTicket first = begin(ledger, 51, FIRST, 0, 2);
        BlockPosition secondPosition = new BlockPosition(6, 65, 9);
        var blocked = ledger.tryBeginPlacement(
                SESSION, 52, secondPosition, CRAFTING_TABLE, CRAFTING_TABLE, 0, 2);
        assertEquals(PENDING_BACKPRESSURE, blocked.status());
        assertTrue(blocked.ticket().isEmpty());

        assertEquals(PENDING, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 1, 1));
        assertEquals(CONFIRMED, ledger.onServerBlockUpdate(SESSION, FIRST, CRAFTING_TABLE, 2));
        assertEquals(51, ledger.records().get(0).jobToken());

        PlacementTicket second = begin(ledger, 52, secondPosition, 2, 1);
        assertEquals(STALE_SEQUENCE, ledger.onInventoryCount(SESSION, CRAFTING_TABLE, 1, 1));
        assertEquals(PENDING, ledger.onServerBlockUpdate(SESSION, secondPosition, CRAFTING_TABLE, 3));
        assertEquals(1, ledger.records().size());
        assertSame(second, ledger.pendingTicket().orElseThrow());
        assertSame(first, ledger.records().get(0).ticket());
    }

    @Test
    void capacityAndServerReplacementBoundConfirmedOwnership() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        long sequence = 0;
        for (int index = 0; index < OwnedStationLedger.MAX_RECORDS; index++) {
            BlockPosition position = new BlockPosition(index, 64, 0);
            int inventoryBefore = OwnedStationLedger.MAX_RECORDS - index;
            begin(ledger, index + 1L, position, sequence, inventoryBefore);
            assertEquals(PENDING, ledger.onServerBlockUpdate(
                    SESSION, position, CRAFTING_TABLE, ++sequence));
            assertEquals(CONFIRMED, ledger.onInventoryCount(
                    SESSION, CRAFTING_TABLE, inventoryBefore - 1, ++sequence));
        }
        assertEquals(OwnedStationLedger.MAX_RECORDS, ledger.records().size());
        assertThrows(UnsupportedOperationException.class, () -> ledger.records().clear());

        BlockPosition extra = new BlockPosition(500, 64, 0);
        assertEquals(RECORD_CAPACITY, ledger.tryBeginPlacement(
                SESSION, 500, extra, CRAFTING_TABLE, CRAFTING_TABLE, sequence, 1).status());
        assertEquals(OWNERSHIP_REMOVED, ledger.onServerBlockUpdate(
                SESSION, new BlockPosition(0, 64, 0), OTHER_BLOCK, ++sequence));
        assertEquals(OwnedStationLedger.MAX_RECORDS - 1, ledger.records().size());
        assertTrue(ledger.tryBeginPlacement(
                SESSION, 500, extra, CRAFTING_TABLE, CRAFTING_TABLE, sequence, 1).accepted());
    }

    @Test
    void expiryAndPickupRemovalRequireTheExactTicketAndRecord() {
        OwnedStationLedger ledger = new OwnedStationLedger(SESSION);
        OwnedStationLedger otherLedger = new OwnedStationLedger(SESSION);
        StationRecord owned = beginAndConfirm(ledger, 61, FIRST, 0, 2);
        StationRecord other = beginAndConfirm(otherLedger, 61, FIRST, 0, 2);

        assertEquals(OWNERSHIP_RETAINED,
                ledger.onServerBlockUpdate(SESSION, FIRST, CRAFTING_TABLE, 3));
        assertEquals(61, ledger.records().get(0).jobToken());
        assertEquals(OwnedStationLedger.BeginStatus.POSITION_ALREADY_OWNED,
                ledger.tryBeginPlacement(SESSION, 63, FIRST, CRAFTING_TABLE,
                        CRAFTING_TABLE, 3, 1).status());
        assertFalse(ledger.removeAfterPickup(SESSION, other));
        assertSame(owned, ledger.records().get(0));
        assertTrue(ledger.removeAfterPickup(SESSION, owned));
        assertFalse(ledger.removeAfterPickup(SESSION, owned));

        BlockPosition pendingPosition = new BlockPosition(8, 65, 9);
        PlacementTicket ticket = begin(ledger, 62, pendingPosition, 3, 1);
        PlacementTicket otherTicket = begin(otherLedger, 62, pendingPosition, 2, 1);
        assertFalse(ledger.expirePending(SESSION, otherTicket));
        assertTrue(ledger.expirePending(SESSION, ticket));
        assertFalse(ledger.expirePending(SESSION, ticket));
        assertEquals(IGNORED, ledger.onServerBlockUpdate(
                SESSION, pendingPosition, CRAFTING_TABLE, 4));
        assertTrue(ledger.records().isEmpty());
    }

    private static PlacementTicket begin(
            OwnedStationLedger ledger,
            long jobToken,
            BlockPosition position,
            long startSequence,
            int inventoryCount) {
        var result = ledger.tryBeginPlacement(SESSION, jobToken, position, CRAFTING_TABLE,
                CRAFTING_TABLE, startSequence, inventoryCount);
        assertEquals(ACCEPTED, result.status());
        return result.ticket().orElseThrow();
    }

    private static StationRecord beginAndConfirm(
            OwnedStationLedger ledger,
            long jobToken,
            BlockPosition position,
            long startSequence,
            int inventoryCount) {
        begin(ledger, jobToken, position, startSequence, inventoryCount);
        long blockSequence = startSequence + 1;
        assertEquals(PENDING, ledger.onServerBlockUpdate(
                SESSION, position, CRAFTING_TABLE, blockSequence));
        assertEquals(CONFIRMED, ledger.onInventoryCount(
                SESSION, CRAFTING_TABLE, inventoryCount - 1, blockSequence + 1));
        return ledger.records().get(0);
    }
}
