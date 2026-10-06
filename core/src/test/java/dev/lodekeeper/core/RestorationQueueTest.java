package dev.lodekeeper.core;

import static dev.lodekeeper.core.RestorationQueue.PlacementStatus.BLOCK_MISMATCH;
import static dev.lodekeeper.core.RestorationQueue.PlacementStatus.CONFIRMED;
import static dev.lodekeeper.core.RestorationQueue.PlacementStatus.STALE_RESERVATION;
import static dev.lodekeeper.core.RestorationQueue.ReceiptStatus.CAPACITY_REACHED;
import static dev.lodekeeper.core.RestorationQueue.ReceiptStatus.RECORDED;
import static dev.lodekeeper.core.RestorationQueue.ReceiptStatus.REPLACED;
import static dev.lodekeeper.core.RestorationQueue.ReserveStatus.BUSY;
import static dev.lodekeeper.core.RestorationQueue.ReserveStatus.NO_SURPLUS;
import static dev.lodekeeper.core.RestorationQueue.ReserveStatus.RESERVED;
import static dev.lodekeeper.core.RestorationQueue.ReserveStatus.STALE_RECEIPT;
import static dev.lodekeeper.core.RestorationQueue.ReceiptStatus.STALE_SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lodekeeper.core.RestorationQueue.BlockPosition;
import dev.lodekeeper.core.RestorationQueue.ConfirmedBreakReceipt;
import dev.lodekeeper.core.RestorationQueue.PlacementReservation;
import dev.lodekeeper.core.RestorationQueue.ReservationResult;
import dev.lodekeeper.core.RestorationQueue.RestorableBlock;
import dev.lodekeeper.core.RestorationQueue.Session;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RestorationQueueTest {
    private static final WorldScope WORLD = new WorldScope("test-world", "minecraft:overworld");
    private static final Session SESSION = new Session(WORLD, 3);
    private static final ItemId STONE = ItemId.parse("minecraft:stone");
    private static final ItemId COBBLESTONE = ItemId.parse("minecraft:cobblestone");
    private static final BlockId STONE_BLOCK = BlockId.parse("minecraft:stone");
    private static final BlockId COBBLESTONE_BLOCK = BlockId.parse("minecraft:cobblestone");
    private static final BlockPosition FIRST = new BlockPosition(12, 64, -7);

    @Test
    void restorableMaterialRequiresTheMatchingBlockItem() {
        assertThrows(IllegalArgumentException.class,
                () -> new RestorableBlock(STONE_BLOCK, COBBLESTONE));
    }

    @Test
    void reservesOnlyStockAboveCombinedFloorsAndKeepsTheCellUntilServerEvidence() {
        RestorationQueue queue = new RestorationQueue(SESSION, 8);
        ConfirmedBreakReceipt receipt = receipt(1, FIRST, STONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(receipt));

        InventorySnapshot fullyReserved = stock(Map.of(STONE, 6), Map.of(STONE, 6));
        assertEquals(NO_SURPLUS, queue.tryReserve(SESSION, receipt, fullyReserved).status());
        assertEquals(1, queue.pendingCount());
        assertTrue(queue.inFlightReservationCounts().isEmpty());

        InventorySnapshot oneSurplus = stock(Map.of(STONE, 12), Map.of(STONE, 11));
        ReservationResult selected = queue.tryReserve(SESSION, receipt, oneSurplus);
        assertEquals(RESERVED, selected.status());
        PlacementReservation token = selected.reservation().orElseThrow();
        assertEquals(STONE, token.item());
        assertEquals(Map.of(STONE, 1), queue.inFlightReservationCounts());
        assertEquals(12, oneSurplus.count(STONE));
        assertEquals(11, oneSurplus.protectedCounts().get(STONE));
        assertEquals(1, queue.pendingCount());

        assertTrue(queue.releaseBeforeSend(SESSION, token));
        assertEquals(1, queue.pendingCount());
        assertTrue(queue.inFlightReservationCounts().isEmpty());
    }

    @Test
    void stoneAndCobblestoneCanSupplyEachOthersCellsFromUnreservedStock() {
        RestorationQueue queue = new RestorationQueue(SESSION, 4);
        ConfirmedBreakReceipt stoneCell = receipt(1, FIRST, STONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(stoneCell));

        PlacementReservation cobbleToken = queue.tryReserve(SESSION, stoneCell,
                stock(Map.of(COBBLESTONE, 8), Map.of(COBBLESTONE, 7)))
                .reservation().orElseThrow();
        assertEquals(COBBLESTONE, cobbleToken.item());
        assertEquals(CONFIRMED, queue.confirmServerPlacement(
                SESSION, cobbleToken, COBBLESTONE_BLOCK, 2));
        assertEquals(0, queue.pendingCount());

        BlockPosition second = new BlockPosition(13, 64, -7);
        ConfirmedBreakReceipt cobbleCell = receipt(3, second, COBBLESTONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(cobbleCell));
        PlacementReservation stoneToken = queue.tryReserve(SESSION, cobbleCell,
                stock(Map.of(STONE, 5), Map.of(STONE, 4)))
                .reservation().orElseThrow();
        assertEquals(STONE, stoneToken.item());
        assertEquals(CONFIRMED, queue.confirmServerPlacement(SESSION, stoneToken, STONE_BLOCK, 4));
        assertEquals(0, queue.pendingCount());
    }

    @Test
    void keepsOneExactPlacementTokenUntilTheServerConfirmsIt() {
        RestorationQueue queue = new RestorationQueue(SESSION, 4);
        ConfirmedBreakReceipt first = receipt(1, FIRST, STONE_BLOCK);
        ConfirmedBreakReceipt second = receipt(2, new BlockPosition(13, 64, -7), COBBLESTONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(first));
        assertEquals(RECORDED, queue.recordConfirmedBreak(second));
        PlacementReservation firstToken = queue.tryReserve(
                SESSION, first, stock(Map.of(STONE, 1), Map.of()))
                .reservation().orElseThrow();

        assertEquals(BUSY, queue.tryReserve(
                SESSION, second, stock(Map.of(COBBLESTONE, 1), Map.of())).status());
        assertEquals(Map.of(STONE, 1), queue.inFlightReservationCounts());
        assertEquals(CONFIRMED, queue.confirmServerPlacement(SESSION, firstToken, STONE_BLOCK, 3));

        PlacementReservation secondToken = queue.tryReserve(
                SESSION, second, stock(Map.of(COBBLESTONE, 1), Map.of()))
                .reservation().orElseThrow();
        assertEquals(COBBLESTONE, secondToken.item());
        assertEquals(Map.of(COBBLESTONE, 1), queue.inFlightReservationCounts());
    }

    @Test
    void aNewerReceiptReplacesTheSameCellAndInvalidatesItsOldToken() {
        RestorationQueue queue = new RestorationQueue(SESSION, 2);
        ConfirmedBreakReceipt oldReceipt = receipt(4, FIRST, STONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(oldReceipt));
        PlacementReservation oldToken = queue.tryReserve(
                SESSION, oldReceipt, stock(Map.of(STONE, 1), Map.of()))
                .reservation().orElseThrow();

        ConfirmedBreakReceipt latestReceipt = receipt(7, FIRST, COBBLESTONE_BLOCK);
        assertEquals(REPLACED, queue.recordConfirmedBreak(latestReceipt));
        assertEquals(STALE_RESERVATION, queue.confirmServerPlacement(
                SESSION, oldToken, STONE_BLOCK, 8));
        assertEquals(1, queue.pendingCount());
        assertSame(latestReceipt, queue.pendingForProbe(SESSION, 2).get(0));
        assertEquals(RestorationQueue.ReceiptStatus.STALE_REVISION, queue.recordConfirmedBreak(
                receipt(6, new BlockPosition(20, 64, -7), STONE_BLOCK)));
        assertEquals(STALE_RECEIPT, queue.tryReserve(
                SESSION, oldReceipt, stock(Map.of(COBBLESTONE, 1), Map.of())).status());
    }

    @Test
    void changingSessionClearsPendingAndHeldItemsAndRejectsOldEvidence() {
        RestorationQueue queue = new RestorationQueue(SESSION, 4);
        ConfirmedBreakReceipt receipt = receipt(9, FIRST, STONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(receipt));
        PlacementReservation token = queue.tryReserve(
                SESSION, receipt, stock(Map.of(STONE, 1), Map.of()))
                .reservation().orElseThrow();

        queue.changeSession(SESSION);
        assertEquals(1, queue.pendingCount());
        assertEquals(Map.of(STONE, 1), queue.inFlightReservationCounts());

        Session nextSession = new Session(WORLD, SESSION.generation() + 1);
        queue.changeSession(nextSession);
        assertEquals(0, queue.pendingCount());
        assertTrue(queue.inFlightReservationCounts().isEmpty());
        assertEquals(STALE_SESSION, queue.recordConfirmedBreak(receipt));
        assertEquals(RestorationQueue.PlacementStatus.STALE_SESSION,
                queue.confirmServerPlacement(SESSION, token, STONE_BLOCK, 10));

        assertEquals(RECORDED, queue.recordConfirmedBreak(new ConfirmedBreakReceipt(
                nextSession, 0, FIRST, new RestorableBlock(STONE_BLOCK, STONE))));
    }

    @Test
    void capacityAndProbeBudgetStayBoundedAndDuplicateCellsStillRefresh() {
        assertEquals(RestorationQueue.MAX_CELLS,
                new RestorationQueue(SESSION, RestorationQueue.MAX_CELLS).capacity());
        assertThrows(IllegalArgumentException.class, () -> new RestorationQueue(SESSION, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RestorationQueue(SESSION, RestorationQueue.MAX_CELLS + 1));

        RestorationQueue queue = new RestorationQueue(SESSION, 2);
        ConfirmedBreakReceipt first = receipt(1, FIRST, STONE_BLOCK);
        BlockPosition secondPosition = new BlockPosition(13, 64, -7);
        ConfirmedBreakReceipt second = receipt(2, secondPosition, COBBLESTONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(first));
        assertEquals(RECORDED, queue.recordConfirmedBreak(second));
        assertEquals(List.of(first), queue.pendingForProbe(SESSION, 1));
        assertEquals(List.of(second), queue.pendingForProbe(SESSION, 1));
        assertEquals(List.of(first), queue.pendingForProbe(SESSION, 1));
        assertThrows(IllegalArgumentException.class,
                () -> queue.pendingForProbe(SESSION, RestorationQueue.MAX_CELLS + 1));

        assertEquals(CAPACITY_REACHED, queue.recordConfirmedBreak(
                receipt(3, new BlockPosition(14, 64, -7), STONE_BLOCK)));
        assertEquals(2, queue.pendingCount());
        ConfirmedBreakReceipt replacement = receipt(4, FIRST, COBBLESTONE_BLOCK);
        assertEquals(REPLACED, queue.recordConfirmedBreak(replacement));
        assertEquals(2, queue.pendingCount());
        assertTrue(queue.pendingForProbe(SESSION, 2).stream().anyMatch(cell -> cell == replacement));
    }

    @Test
    void onlyTheExactServerTokenOrANewerSemanticChangeCanRetireACell() {
        RestorationQueue queue = new RestorationQueue(SESSION, 4);
        RestorationQueue otherQueue = new RestorationQueue(SESSION, 4);
        ConfirmedBreakReceipt receipt = receipt(10, FIRST, STONE_BLOCK);
        assertEquals(RECORDED, queue.recordConfirmedBreak(receipt));
        assertEquals(RECORDED, otherQueue.recordConfirmedBreak(receipt));
        PlacementReservation token = queue.tryReserve(
                SESSION, receipt, stock(Map.of(STONE, 1), Map.of()))
                .reservation().orElseThrow();
        PlacementReservation otherToken = otherQueue.tryReserve(
                SESSION, receipt, stock(Map.of(STONE, 1), Map.of()))
                .reservation().orElseThrow();

        assertEquals(STALE_RESERVATION, queue.confirmServerPlacement(
                SESSION, otherToken, STONE_BLOCK, 12));
        assertEquals(RestorationQueue.PlacementStatus.STALE_REVISION,
                queue.confirmServerPlacement(SESSION, token, STONE_BLOCK, 10));
        assertEquals(BLOCK_MISMATCH, queue.confirmServerPlacement(
                SESSION, token, COBBLESTONE_BLOCK, 11));
        assertEquals(1, queue.pendingCount());
        assertFalse(queue.retireAfterServerCellChanged(SESSION, receipt, 10));
        assertTrue(queue.retireAfterServerCellChanged(SESSION, receipt, 11));
        assertEquals(0, queue.pendingCount());
        assertTrue(queue.inFlightReservationCounts().isEmpty());
        assertEquals(STALE_RESERVATION, queue.confirmServerPlacement(
                SESSION, token, STONE_BLOCK, 12));
    }

    private static ConfirmedBreakReceipt receipt(long revision, BlockPosition position, BlockId block) {
        ItemId item = new ItemId(block.namespace(), block.path());
        return new ConfirmedBreakReceipt(SESSION, revision, position, new RestorableBlock(block, item));
    }

    private static InventorySnapshot stock(Map<ItemId, Integer> counts, Map<ItemId, Integer> floors) {
        return new InventorySnapshot(counts, Set.of(), Map.of(), floors);
    }
}
