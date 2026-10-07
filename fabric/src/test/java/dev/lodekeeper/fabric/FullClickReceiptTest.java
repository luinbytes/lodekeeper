package dev.lodekeeper.fabric;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class FullClickReceiptTest {
    private record Stack(String item, String components, int count) {}
    private static final Stack EMPTY = new Stack("air", "", 0);
    private static final Stack COBBLE = new Stack("cobblestone", "ordinary", 1);
    private static final List<Stack> RETURNED = List.of(COBBLE, COBBLE, COBBLE, EMPTY);
    private static final List<Long> BEFORE = List.of(140L, 142L, 142L, 143L);
    private static final List<Long> FRESH = List.of(190L, 151L, 151L, 152L);

    @Test void fullReturnRemainsConfirmedAfterALaterPartialSourceUpdate() {
        FullClickReceipt<Stack> receipt = returning();
        List<Stack> packetSlots = new ArrayList<>(RETURNED);
        List<Stack> liveSlots = new ArrayList<>(RETURNED);
        receipt.applyFull(reply(4, 7, 191, FRESH, packetSlots, EMPTY, 46), liveSlots, EMPTY, 7, true, true, 0);

        Stack laterSource = new Stack("cobblestone", "ordinary", 2);
        packetSlots.set(0, laterSource);
        liveSlots.set(0, laterSource);

        var confirmed = receipt.observe(0);
        assertEquals(4, confirmed.sequence());
        assertEquals(7, confirmed.revision());
        assertEquals(1, confirmed.slots().get(0).count());
        assertEquals(0, confirmed.cursor().count());
        assertDoesNotThrow(() -> receipt.requireLive(liveSlots, EMPTY, true, 0, true));
        assertSame(confirmed, receipt.observe(0));
        assertThrows(IllegalStateException.class, () -> receipt.requireLive(liveSlots, EMPTY, true, 0, false));
        assertThrows(IllegalStateException.class, () -> receipt.observe(0));
    }

    @Test void aLaterFullSourceIncreasePreservesTheTerminalReturnAck() {
        FullClickReceipt<Stack> receipt = returning();
        applyReturn(receipt, RETURNED, EMPTY);
        var firstAck = receipt.observe(0);
        List<Stack> refreshed = List.of(new Stack("cobblestone", "ordinary", 2), COBBLE, COBBLE, EMPTY);
        receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), refreshed, EMPTY, 46),
                refreshed, EMPTY, 8, true, true, 0);

        assertSame(firstAck, receipt.observe(0));
        assertEquals(RETURNED, receipt.accepted().slots());
        assertDoesNotThrow(() -> receipt.requireLive(refreshed, EMPTY, true, 0, true));
        List<Stack> laterPartial = List.of(new Stack("cobblestone", "ordinary", 3), COBBLE, COBBLE, EMPTY);
        assertDoesNotThrow(() -> receipt.requireLive(laterPartial, EMPTY, true, 0, true));
        assertSame(firstAck, receipt.observe(0));
        assertThrows(IllegalStateException.class, () -> receipt.requireLive(refreshed, EMPTY, true, 0, false));
        assertThrows(IllegalStateException.class, () -> receipt.observe(0));
    }

    @Test void ongoingReturnsAndPlacementsCannotUseARefreshedSourceForAnotherClick() {
        List<Stack> increased = List.of(new Stack("cobblestone", "ordinary", 2), COBBLE, COBBLE, EMPTY);
        for (Stack cursor : List.of(EMPTY, new Stack("cobblestone", "ordinary", 5))) {
            for (boolean full : new boolean[]{false, true}) {
                var expectation = reply(3, 6, 150, BEFORE, RETURNED, cursor, 46);
                FullClickReceipt<Stack> ongoing = new FullClickReceipt<>(expectation, null, 0, Stack::equals, null);
                applyReturn(ongoing, RETURNED, cursor);
                if (full) {
                    ongoing.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), increased, cursor, 46),
                            increased, cursor, 8, true, true, 0);
                    assertThrows(IllegalStateException.class, () -> ongoing.observe(0));
                } else {
                    assertThrows(IllegalStateException.class, () -> ongoing.requireLive(increased, cursor, true, 0, false));
                }
            }
        }
    }

    @Test void terminalReturnStillRequiresSourceIdentityAndEveryOtherLiveCellAndCursor() {
        List<List<Stack>> invalid = new ArrayList<>();
        invalid.add(List.of(EMPTY, COBBLE, COBBLE, EMPTY));
        invalid.add(List.of(new Stack("stone", "ordinary", 2), COBBLE, COBBLE, EMPTY));
        invalid.add(List.of(new Stack("cobblestone", "custom", 2), COBBLE, COBBLE, EMPTY));
        for (int index = 1; index < RETURNED.size(); index++) {
            List<Stack> changed = new ArrayList<>(RETURNED);
            changed.set(0, new Stack("cobblestone", "ordinary", 2));
            changed.set(index, new Stack("cobblestone", "ordinary", 2));
            invalid.add(changed);
        }
        for (List<Stack> slots : invalid) {
            for (boolean full : new boolean[]{false, true}) {
                FullClickReceipt<Stack> receipt = returning();
                applyReturn(receipt, RETURNED, EMPTY);
                if (full) receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), slots, EMPTY, 46),
                        slots, EMPTY, 8, true, true, 0);
                assertThrows(IllegalStateException.class, () -> receipt.requireLive(slots, EMPTY, true, 0, true));
                assertThrows(IllegalStateException.class, () -> receipt.observe(0));
            }
        }
        FullClickReceipt<Stack> cursor = returning();
        applyReturn(cursor, RETURNED, EMPTY);
        assertThrows(IllegalStateException.class, () -> cursor.requireLive(RETURNED, COBBLE, true, 0, true));
        FullClickReceipt<Stack> context = returning();
        applyReturn(context, RETURNED, EMPTY);
        assertThrows(IllegalStateException.class, () -> context.requireLive(RETURNED, EMPTY, false, 0, true));
    }

    @Test void staleFullRepliesCannotConfirmButAFreshCoherentReplyCan() {
        List<FullClickReceipt.Snapshot<Stack>> stale = List.of(
                reply(3, 7, 191, FRESH, RETURNED, EMPTY, 46),
                reply(4, 6, 191, FRESH, RETURNED, EMPTY, 46),
                reply(4, 7, 150, FRESH, RETURNED, EMPTY, 46),
                reply(4, 7, 191, List.of(140L, 151L, 151L, 152L), RETURNED, EMPTY, 46),
                reply(4, 7, 191, List.of(190L, 142L, 151L, 152L), RETURNED, EMPTY, 46),
                reply(4, 7, 191, List.of(190L, 151L, 151L, 143L), RETURNED, EMPTY, 46));
        for (var staleReply : stale) {
            FullClickReceipt<Stack> receipt = returning();
            receipt.applyFull(staleReply, RETURNED, EMPTY, staleReply.revision(), true, true, 0);
            assertNull(receipt.observe(0));
            applyReturn(receipt, RETURNED, EMPTY);
            assertEquals(RETURNED, receipt.observe(0).slots());
        }
    }

    @Test void wrongCountsItemsComponentsGridOrCursorRemainRejectedAfterAValidReply() {
        List<List<Stack>> rejectedSlots = new ArrayList<>();
        for (int slot = 0; slot < RETURNED.size(); slot++) {
            List<Stack> changed = new ArrayList<>(RETURNED);
            changed.set(slot, new Stack("cobblestone", "ordinary", 2));
            rejectedSlots.add(changed);
        }
        rejectedSlots.add(List.of(new Stack("stone", "ordinary", 1), COBBLE, COBBLE, EMPTY));
        for (int slot = 0; slot < 3; slot++) {
            List<Stack> changed = new ArrayList<>(RETURNED);
            changed.set(slot, new Stack("cobblestone", "custom", 1));
            rejectedSlots.add(changed);
        }
        for (List<Stack> slots : rejectedSlots) assertStickyRejection(slots, EMPTY);
        assertStickyRejection(RETURNED, COBBLE);
    }

    @Test void packetToLiveEqualityAndRevisionAreRequiredAtTheFullReceiptEvent() {
        for (int changed = 0; changed < RETURNED.size(); changed++) {
            FullClickReceipt<Stack> receipt = returning();
            List<Stack> live = new ArrayList<>(RETURNED);
            live.set(changed, new Stack("cobblestone", "ordinary", 2));
            receipt.applyFull(reply(4, 7, 191, FRESH, RETURNED, EMPTY, 46), live, EMPTY, 7, true, true, 0);
            assertThrows(IllegalStateException.class, () -> receipt.observe(0));
        }
        FullClickReceipt<Stack> cursorMismatch = returning();
        cursorMismatch.applyFull(reply(4, 7, 191, FRESH, RETURNED, EMPTY, 46), RETURNED, COBBLE, 7, true, true, 0);
        assertThrows(IllegalStateException.class, () -> cursorMismatch.observe(0));
        FullClickReceipt<Stack> revisionMismatch = returning();
        revisionMismatch.applyFull(reply(4, 7, 191, FRESH, RETURNED, EMPTY, 46), RETURNED, EMPTY, 8, true, true, 0);
        assertThrows(IllegalStateException.class, () -> revisionMismatch.observe(0));
        FullClickReceipt<Stack> sizeMismatch = returning();
        sizeMismatch.applyFull(reply(4, 7, 191, FRESH, RETURNED, EMPTY, 45), RETURNED, EMPTY, 7, true, true, 0);
        assertThrows(IllegalStateException.class, () -> sizeMismatch.observe(0));
    }

    @Test void incompatibleLaterFullReplyPreservesTheOriginalSnapshotAndDisablesObservation() {
        FullClickReceipt<Stack> receipt = returning();
        applyReturn(receipt, RETURNED, EMPTY);
        List<Stack> changed = List.of(COBBLE, new Stack("cobblestone", "ordinary", 2), COBBLE, EMPTY);
        receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), changed, EMPTY, 46),
                changed, EMPTY, 8, true, true, 0);
        String rejection = receipt.failure();
        applyReturn(receipt, RETURNED, EMPTY);
        assertEquals(rejection, receipt.failure());
        assertEquals(4, receipt.accepted().sequence());
        assertEquals(RETURNED, receipt.accepted().slots());
        assertThrows(IllegalStateException.class, () -> receipt.observe(0));
        assertThrows(IllegalStateException.class, () -> receipt.requireLive(RETURNED, EMPTY, true, 0, false));
    }

    @Test void fullContentsCanPrecedeTheFurnacePropertyThatProvesOneConsumedItem() {
        List<Stack> planned = List.of(EMPTY, new Stack("coal", "ordinary", 2));
        List<Stack> consumed = List.of(EMPTY, new Stack("coal", "ordinary", 1));
        var expectation = reply(3, 6, 150, BEFORE.subList(0, 2), planned, EMPTY, 39);
        FullClickReceipt<Stack> receipt = new FullClickReceipt<>(expectation, consumed, 3, Stack::equals, null);
        receipt.applyFull(reply(4, 7, 191, FRESH.subList(0, 2), consumed, EMPTY, 39), consumed, EMPTY, 7, true, true, 3);

        assertNull(receipt.failure());
        assertFalse(receipt.consumptionConfirmed());
        assertEquals(consumed, receipt.observe(4).slots());
        assertDoesNotThrow(() -> receipt.requireLive(consumed, EMPTY, true, 4, false));
    }

    @Test void anUnprovedMissingItemRejectionCannotBeRevivedByALaterReply() {
        List<Stack> planned = List.of(EMPTY, new Stack("coal", "ordinary", 2));
        List<Stack> consumed = List.of(EMPTY, new Stack("coal", "ordinary", 1));
        var expectation = reply(3, 6, 150, BEFORE.subList(0, 2), planned, EMPTY, 39);
        for (double unproved : new double[]{3, Double.NaN}) {
            FullClickReceipt<Stack> receipt = new FullClickReceipt<>(expectation, consumed, 3, Stack::equals, null);
            receipt.applyFull(reply(4, 7, 191, FRESH.subList(0, 2), consumed, EMPTY, 39), consumed, EMPTY, 7, true, true, unproved);
            assertThrows(IllegalStateException.class, () -> receipt.observe(unproved));
            receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L), planned, EMPTY, 39), planned, EMPTY, 8, true, true, 4);
            assertThrows(IllegalStateException.class, () -> receipt.observe(4));
        }
        FullClickReceipt<Stack> tooManyConsumed = new FullClickReceipt<>(expectation, consumed, 3, Stack::equals, null);
        List<Stack> emptyFuel = List.of(EMPTY, EMPTY);
        tooManyConsumed.applyFull(reply(4, 7, 191, FRESH.subList(0, 2), emptyFuel, EMPTY, 39), emptyFuel, EMPTY, 7, true, true, 100);
        assertThrows(IllegalStateException.class, () -> tooManyConsumed.observe(100));
    }

    @Test void exactPlacementThenProvedPartialConsumptionAllowsAnotherPlacementOrRemainderReturn() {
        FullClickReceipt<Stack> receipt = placing(1);
        List<Stack> planned = placementSlots(1);
        List<Stack> consumed = placementSlots(0);
        Stack held = new Stack("coal", "ordinary", 9);
        receipt.applyFull(reply(4, 7, 191, FRESH, planned, held, 39), planned, held, 7, true, true, 3);
        var ack = receipt.observe(3);

        assertDoesNotThrow(() -> receipt.requireLive(consumed, held, true, 4, false));
        assertDoesNotThrow(() -> receipt.requireLive(consumed, held, true, 4, true));
        assertSame(ack, receipt.observe(4));
        assertEquals(planned, receipt.accepted().slots());
    }

    @Test void exactPlacementThenALaterFullConsumptionUsesTheOriginalOneItemAllowance() {
        for (boolean propertyAlreadyArrived : new boolean[]{false, true}) {
            FullClickReceipt<Stack> receipt = placing(1);
            List<Stack> planned = placementSlots(1);
            List<Stack> consumed = placementSlots(0);
            Stack held = new Stack("coal", "ordinary", 9);
            receipt.applyFull(reply(4, 7, 191, FRESH, planned, held, 39), planned, held, 7, true, true, 3);
            var ack = receipt.observe(3);
            receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), consumed, held, 39),
                    consumed, held, 8, true, true, propertyAlreadyArrived ? 4 : 3);
            assertTrue(receipt.consumptionRequired());
            assertEquals(propertyAlreadyArrived, receipt.consumptionConfirmed());
            assertSame(ack, receipt.observe(4));
            assertDoesNotThrow(() -> receipt.requireLive(consumed, held, true, 4, false));
            assertEquals(planned, receipt.accepted().slots());
        }
    }

    @Test void consumptionAfterAnExactAckStillRequiresIndependentProgress() {
        for (double unproved : new double[]{3, Double.NaN}) {
            for (boolean full : new boolean[]{false, true}) {
                FullClickReceipt<Stack> receipt = placing(1);
                List<Stack> planned = placementSlots(1);
                List<Stack> consumed = placementSlots(0);
                Stack held = new Stack("coal", "ordinary", 9);
                receipt.applyFull(reply(4, 7, 191, FRESH, planned, held, 39), planned, held, 7, true, true, 3);
                receipt.observe(3);
                if (full) {
                    receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), consumed, held, 39),
                            consumed, held, 8, true, true, unproved);
                    assertThrows(IllegalStateException.class, () -> receipt.observe(unproved));
                } else {
                    assertThrows(IllegalStateException.class, () -> receipt.requireLive(consumed, held, true, unproved, false));
                }
                assertThrows(IllegalStateException.class, () -> receipt.observe(100));
            }
        }
    }

    @Test void anExactOrAlreadyConsumedFullAckCannotGainASecondMissingItem() {
        for (boolean consumedAtAck : new boolean[]{false, true}) {
            for (boolean full : new boolean[]{false, true}) {
                FullClickReceipt<Stack> receipt = placing(2);
                List<Stack> acknowledged = placementSlots(consumedAtAck ? 1 : 2);
                List<Stack> twoMissing = placementSlots(0);
                Stack held = new Stack("coal", "ordinary", 9);
                receipt.applyFull(reply(4, 7, 191, FRESH, acknowledged, held, 39), acknowledged, held, 7, true, true, 4);
                var ack = receipt.observe(4);
                if (full) receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), twoMissing, held, 39),
                        twoMissing, held, 8, true, true, 100);
                assertThrows(IllegalStateException.class, () -> receipt.requireLive(twoMissing, held, true, 100, false));
                assertSame(ack, receipt.accepted());
                assertThrows(IllegalStateException.class, () -> receipt.observe(100));
            }
        }
    }

    @Test void provedConsumptionPreservesExactSourceCursorComponentsAndAllOtherGridCells() {
        List<List<Stack>> invalid = new ArrayList<>();
        for (int index : new int[]{0, 2, 3}) {
            List<Stack> changed = new ArrayList<>(placementSlots(1));
            changed.set(index, new Stack("coal", "ordinary", 1));
            invalid.add(changed);
        }
        invalid.add(List.of(EMPTY, new Stack("coal", "custom", 1), COBBLE, EMPTY));
        invalid.add(placementSlots(3));
        for (List<Stack> slots : invalid) {
            for (boolean full : new boolean[]{false, true}) {
                FullClickReceipt<Stack> receipt = placing(2);
                List<Stack> planned = placementSlots(2);
                Stack held = new Stack("coal", "ordinary", 9);
                receipt.applyFull(reply(4, 7, 191, FRESH, planned, held, 39), planned, held, 7, true, true, 3);
                if (full) receipt.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), slots, held, 39),
                        slots, held, 8, true, true, 100);
                assertThrows(IllegalStateException.class, () -> receipt.requireLive(slots, held, true, 100, false));
            }
        }
        for (Stack changedCursor : List.of(new Stack("coal", "ordinary", 8), new Stack("coal", "custom", 9))) {
            FullClickReceipt<Stack> receipt = placing(1);
            List<Stack> planned = placementSlots(1);
            Stack held = new Stack("coal", "ordinary", 9);
            receipt.applyFull(reply(4, 7, 191, FRESH, planned, held, 39), planned, held, 7, true, true, 3);
            assertThrows(IllegalStateException.class,
                    () -> receipt.requireLive(placementSlots(0), changedCursor, true, 4, false));
        }
    }

    @Test void replacedContextEarlyReplyAndManualTakeoverCannotBeRevived() {
        for (boolean[] state : new boolean[][]{{false, true}, {true, false}}) {
            FullClickReceipt<Stack> receipt = returning();
            receipt.applyFull(reply(4, 7, 191, FRESH, RETURNED, EMPTY, 46), RETURNED, EMPTY, 7, state[0], state[1], 0);
            applyReturn(receipt, RETURNED, EMPTY);
            assertThrows(IllegalStateException.class, () -> receipt.observe(0));
        }
        FullClickReceipt<Stack> takeover = returning();
        applyReturn(takeover, RETURNED, EMPTY);
        takeover.reject("manual takeover");
        applyReturn(takeover, RETURNED, EMPTY);
        assertThrows(IllegalStateException.class, () -> takeover.observe(0));
        assertThrows(IllegalStateException.class, () -> takeover.requireLive(RETURNED, EMPTY, true, 0, true));
    }

    @Test void aReadyPickupCanBeObservedByRecoveryBeforeReturningTheHeldCursor() {
        List<Stack> pickedUp = List.of(EMPTY, COBBLE);
        Stack held = new Stack("cobblestone", "ordinary", 5);
        var expectation = reply(3, 6, 150, BEFORE.subList(0, 2), pickedUp, held, 46);
        FullClickReceipt<Stack> receipt = new FullClickReceipt<>(expectation, null, 0, Stack::equals, null);
        assertNull(receipt.observe(0));
        receipt.applyFull(reply(4, 7, 191, FRESH.subList(0, 2), pickedUp, held, 46), pickedUp, held, 7, true, true, 0);
        assertEquals(held, receipt.observe(0).cursor());
        assertDoesNotThrow(() -> receipt.requireLive(pickedUp, held, true, 0, false));
    }

    @Test void unusedPlacementConsumptionCarriesThroughAnInFlightReturnWithItsOriginalProgressBaseline() {
        for (boolean progressBeforeSend : new boolean[]{false, true}) {
            FullClickReceipt<Stack> placed = placing(1);
            Stack held = new Stack("coal", "ordinary", 9);
            List<Stack> planned = placementSlots(1);
            placed.applyFull(reply(4, 7, 191, FRESH, planned, held, 39), planned, held, 7, true, true, 3);
            placed.observe(3);
            double progress = progressBeforeSend ? 4 : 3;
            placed.requireLive(planned, held, true, progress, false);
            var carry = placed.carryConsumption();
            assertEquals(3, carry.beforeProgress());
            FullClickReceipt<Stack> returned = new FullClickReceipt<>(
                    reply(4, 7, 191, FRESH, fuelReturnSlots(1), EMPTY, 39), carry,
                    Stack::equals, FullClickReceiptTest::sameSourceIncrease);
            List<Stack> consumedReturn = fuelReturnSlots(0);
            returned.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), consumedReturn, EMPTY, 39),
                    consumedReturn, EMPTY, 8, true, true, progress);

            assertEquals(progressBeforeSend, returned.consumptionConfirmed());
            assertTrue(returned.consumptionRequired());
            var ack = returned.observe(4);
            assertEquals(consumedReturn, ack.slots());
            assertEquals(EMPTY, ack.cursor());
            returned.requireLive(consumedReturn, EMPTY, true, 4, true);
            assertNull(returned.carryConsumption());
            List<Stack> increasedSource = new ArrayList<>(consumedReturn);
            increasedSource.set(0, new Stack("coal", "ordinary", 10));
            returned.requireLive(increasedSource, EMPTY, true, 4, true);
            assertSame(ack, returned.observe(4));
        }
    }

    @Test void consumptionBeforeReturnSendExhaustsTheAllowanceForFullAndPartialOrderings() {
        for (int consumptionOrdering = 0; consumptionOrdering < 3; consumptionOrdering++) {
            for (boolean laterFull : new boolean[]{false, true}) {
                FullClickReceipt<Stack> placed = placing(2);
                Stack held = new Stack("coal", "ordinary", 9);
                List<Stack> acknowledged = placementSlots(consumptionOrdering == 0 ? 1 : 2);
                placed.applyFull(reply(4, 7, 191, FRESH, acknowledged, held, 39),
                        acknowledged, held, 7, true, true, 4);
                placed.observe(4);
                if (consumptionOrdering == 1) placed.applyFull(
                        reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), placementSlots(1), held, 39),
                        placementSlots(1), held, 8, true, true, 4);
                placed.requireLive(placementSlots(1), held, true, 4, false);
                assertTrue(placed.consumptionRequired());
                assertNull(placed.carryConsumption());
                FullClickReceipt<Stack> missingAtReturn = new FullClickReceipt<>(
                        reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), fuelReturnSlots(1), EMPTY, 39),
                        placed.carryConsumption(), Stack::equals, FullClickReceiptTest::sameSourceIncrease);
                missingAtReturn.applyFull(
                        reply(6, 9, 300, List.of(299L, 260L, 260L, 261L), fuelReturnSlots(0), EMPTY, 39),
                        fuelReturnSlots(0), EMPTY, 9, true, true, 100);
                assertThrows(IllegalStateException.class, () -> missingAtReturn.observe(100));
                FullClickReceipt<Stack> returned = new FullClickReceipt<>(
                        reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), fuelReturnSlots(1), EMPTY, 39),
                        placed.carryConsumption(), Stack::equals, FullClickReceiptTest::sameSourceIncrease);
                returned.applyFull(reply(6, 9, 300, List.of(299L, 260L, 260L, 261L), fuelReturnSlots(1), EMPTY, 39),
                        fuelReturnSlots(1), EMPTY, 9, true, true, 4);
                var ack = returned.observe(4);
                assertEquals(fuelReturnSlots(1), ack.slots());
                returned.requireLive(fuelReturnSlots(1), EMPTY, true, 4, true);
                if (laterFull) returned.applyFull(
                        reply(7, 10, 350, List.of(349L, 310L, 310L, 311L), fuelReturnSlots(0), EMPTY, 39),
                        fuelReturnSlots(0), EMPTY, 10, true, true, 100);
                assertThrows(IllegalStateException.class,
                        () -> returned.requireLive(fuelReturnSlots(0), EMPTY, true, 100, true));
                assertSame(ack, returned.accepted());
                assertThrows(IllegalStateException.class, () -> returned.observe(100));
            }
        }
    }

    @Test void exactReturnAckCanSpendItsCarriedAllowanceOnceInALaterFullOrPartialUpdate() {
        for (boolean sourceGrowth : new boolean[]{false, true}) for (boolean laterFull : new boolean[]{false, true}) {
            FullClickReceipt<Stack> placed = placing(2);
            Stack held = new Stack("coal", "ordinary", 9);
            placed.applyFull(reply(4, 7, 191, FRESH, placementSlots(2), held, 39),
                    placementSlots(2), held, 7, true, true, 3);
            placed.observe(3);
            placed.requireLive(placementSlots(2), held, true, 3, false);
            FullClickReceipt<Stack> returned = new FullClickReceipt<>(
                    reply(4, 7, 191, FRESH, fuelReturnSlots(2), EMPTY, 39), placed.carryConsumption(),
                    Stack::equals, FullClickReceiptTest::sameSourceIncrease);
            returned.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), fuelReturnSlots(2), EMPTY, 39),
                    fuelReturnSlots(2), EMPTY, 8, true, true, 3);
            var ack = returned.observe(3);
            List<Stack> consumed = new ArrayList<>(fuelReturnSlots(1));
            if (sourceGrowth) consumed.set(0, new Stack("coal", "ordinary", 10));
            if (laterFull) returned.applyFull(
                    reply(6, 9, 300, List.of(299L, 260L, 260L, 261L), consumed, EMPTY, 39),
                    consumed, EMPTY, 9, true, true, 3);
            returned.observe(4);
            returned.requireLive(consumed, EMPTY, true, 4, true);

            assertTrue(returned.consumptionRequired());
            assertNull(returned.carryConsumption());
            assertSame(ack, returned.observe(4));
            assertEquals(fuelReturnSlots(2), ack.slots());
            assertThrows(IllegalStateException.class,
                    () -> returned.requireLive(fuelReturnSlots(0), EMPTY, true, 100, true));
        }
    }

    @Test void combinedTerminalSourceGrowthAndConsumptionKeepEverySafetyCheck() {
        for (boolean laterFull : new boolean[]{false, true}) for (int invalid = 0; invalid < 7; invalid++) {
            FullClickReceipt<Stack> placed = placing(2);
            Stack held = new Stack("coal", "ordinary", 9);
            placed.applyFull(reply(4, 7, 191, FRESH, placementSlots(2), held, 39),
                    placementSlots(2), held, 7, true, true, 3);
            placed.observe(3);
            FullClickReceipt<Stack> returned = new FullClickReceipt<>(
                    reply(4, 7, 191, FRESH, fuelReturnSlots(2), EMPTY, 39), placed.carryConsumption(),
                    Stack::equals, FullClickReceiptTest::sameSourceIncrease);
            returned.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), fuelReturnSlots(2), EMPTY, 39),
                    fuelReturnSlots(2), EMPTY, 8, true, true, 3);
            var ack = returned.observe(3);
            List<Stack> changed = new ArrayList<>(fuelReturnSlots(1));
            changed.set(0, new Stack("coal", "ordinary", 10));
            if (invalid == 1) changed.set(1, EMPTY);
            if (invalid == 2) changed.set(0, new Stack("coal", "changed", 10));
            if (invalid == 3) changed.set(1, new Stack("coal", "changed", 1));
            if (invalid == 4) changed.set(2, EMPTY);
            Stack cursor = invalid == 5 ? held : EMPTY;
            double progress = invalid == 0 ? 3 : 100;
            if (laterFull) returned.applyFull(
                    reply(6, 9, 300, List.of(299L, 260L, 260L, 261L), changed, cursor, 39),
                    changed, cursor, 9, true, true, progress);
            boolean completing = invalid != 6;
            assertThrows(IllegalStateException.class,
                    () -> returned.requireLive(changed, cursor, true, progress, completing));
            assertSame(ack, returned.accepted());
            assertThrows(IllegalStateException.class, () -> returned.observe(100));
        }
    }

    @Test void carriedReturnLossWithoutIndependentProgressFailsPermanently() {
        for (double unproved : new double[]{3, 2, Double.NaN}) {
            FullClickReceipt<Stack> placed = placing(1);
            Stack held = new Stack("coal", "ordinary", 9);
            placed.applyFull(reply(4, 7, 191, FRESH, placementSlots(1), held, 39),
                    placementSlots(1), held, 7, true, true, 3);
            placed.observe(3);
            placed.requireLive(placementSlots(1), held, true, 3, false);
            FullClickReceipt<Stack> returned = new FullClickReceipt<>(
                    reply(4, 7, 191, FRESH, fuelReturnSlots(1), EMPTY, 39), placed.carryConsumption(),
                    Stack::equals, FullClickReceiptTest::sameSourceIncrease);
            returned.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), fuelReturnSlots(0), EMPTY, 39),
                    fuelReturnSlots(0), EMPTY, 8, true, true, unproved);

            assertFalse(returned.consumptionConfirmed());
            assertThrows(IllegalStateException.class, () -> returned.observe(unproved));
            returned.applyFull(reply(6, 9, 300, List.of(299L, 260L, 260L, 261L), fuelReturnSlots(1), EMPTY, 39),
                    fuelReturnSlots(1), EMPTY, 9, true, true, 100);
            assertThrows(IllegalStateException.class, () -> returned.observe(100));
            assertThrows(IllegalStateException.class, returned::carryConsumption);
        }
    }

    @Test void carriedReturnConsumptionKeepsExactCountsComponentsSourceCursorAndGrid() {
        List<List<Stack>> invalid = new ArrayList<>();
        invalid.add(fuelReturnSlots(0));
        invalid.add(fuelReturnSlots(3));
        invalid.add(List.of(new Stack("coal", "ordinary", 10), new Stack("coal", "ordinary", 1), COBBLE, EMPTY));
        for (int index = 0; index < 4; index++) {
            List<Stack> changed = new ArrayList<>(fuelReturnSlots(1));
            changed.set(index, new Stack("coal", "ordinary", index == 1 ? 4 : 2));
            invalid.add(changed);
            changed = new ArrayList<>(fuelReturnSlots(1));
            changed.set(index, new Stack("coal", "custom", index == 0 ? 9 : 1));
            invalid.add(changed);
        }
        List<Stack> wrongItem = new ArrayList<>(fuelReturnSlots(1));
        wrongItem.set(1, COBBLE);
        invalid.add(wrongItem);
        for (List<Stack> slots : invalid) {
            for (boolean changedAfterAck : new boolean[]{false, true}) {
                FullClickReceipt<Stack> placed = placing(2);
                Stack held = new Stack("coal", "ordinary", 9);
                placed.applyFull(reply(4, 7, 191, FRESH, placementSlots(2), held, 39),
                        placementSlots(2), held, 7, true, true, 3);
                placed.observe(3);
                placed.requireLive(placementSlots(2), held, true, 3, false);
                FullClickReceipt<Stack> returned = new FullClickReceipt<>(
                        reply(4, 7, 191, FRESH, fuelReturnSlots(2), EMPTY, 39), placed.carryConsumption(),
                        Stack::equals, FullClickReceiptTest::sameSourceIncrease);
                List<Stack> initial = changedAfterAck ? fuelReturnSlots(2) : slots;
                returned.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), initial, EMPTY, 39),
                        initial, EMPTY, 8, true, true, 100);
                if (changedAfterAck) {
                    assertEquals(fuelReturnSlots(2), returned.observe(100).slots());
                    assertThrows(IllegalStateException.class,
                            () -> returned.requireLive(slots, EMPTY, true, 100, false));
                }
                assertThrows(IllegalStateException.class, () -> returned.observe(100));
            }
        }
        for (Stack cursor : List.of(new Stack("coal", "ordinary", 1), new Stack("coal", "custom", 9))) {
            FullClickReceipt<Stack> placed = placing(2);
            Stack held = new Stack("coal", "ordinary", 9);
            placed.applyFull(reply(4, 7, 191, FRESH, placementSlots(2), held, 39),
                    placementSlots(2), held, 7, true, true, 3);
            placed.observe(3);
            FullClickReceipt<Stack> returned = new FullClickReceipt<>(
                    reply(4, 7, 191, FRESH, fuelReturnSlots(2), EMPTY, 39), placed.carryConsumption(),
                    Stack::equals, FullClickReceiptTest::sameSourceIncrease);
            returned.applyFull(reply(5, 8, 240, List.of(239L, 200L, 200L, 201L), fuelReturnSlots(1), cursor, 39),
                    fuelReturnSlots(1), cursor, 8, true, true, 100);
            assertThrows(IllegalStateException.class, () -> returned.observe(100));
        }
    }

    @Test void carriedConsumptionCannotBypassReturnFreshnessLiveEqualityOrSendContext() {
        FullClickReceipt<Stack> placed = placing(1);
        Stack held = new Stack("coal", "ordinary", 9);
        placed.applyFull(reply(4, 7, 191, FRESH, placementSlots(1), held, 39),
                placementSlots(1), held, 7, true, true, 3);
        placed.observe(3);
        placed.requireLive(placementSlots(1), held, true, 3, false);
        var carry = placed.carryConsumption();
        var expectation = reply(4, 7, 191, FRESH, fuelReturnSlots(1), EMPTY, 39);
        List<Long> returnFresh = List.of(239L, 200L, 200L, 201L);
        var valid = reply(5, 8, 240, returnFresh, fuelReturnSlots(0), EMPTY, 39);
        List<FullClickReceipt.Snapshot<Stack>> stale = List.of(
                reply(4, 8, 240, returnFresh, fuelReturnSlots(0), EMPTY, 39),
                reply(5, 7, 240, returnFresh, fuelReturnSlots(0), EMPTY, 39),
                reply(5, 8, 191, returnFresh, fuelReturnSlots(0), EMPTY, 39),
                reply(5, 8, 240, List.of(190L, 200L, 200L, 201L), fuelReturnSlots(0), EMPTY, 39),
                reply(5, 8, 240, List.of(239L, 151L, 200L, 201L), fuelReturnSlots(0), EMPTY, 39));
        for (var staleReply : stale) {
            FullClickReceipt<Stack> returned = new FullClickReceipt<>(expectation, carry,
                    Stack::equals, FullClickReceiptTest::sameSourceIncrease);
            returned.applyFull(staleReply, fuelReturnSlots(0), EMPTY, staleReply.revision(), true, true, 4);
            assertNull(returned.observe(4));
            returned.applyFull(valid, fuelReturnSlots(0), EMPTY, 8, true, true, 4);
            assertEquals(fuelReturnSlots(0), returned.observe(4).slots());
        }
        for (int invalid = 0; invalid < 4; invalid++) {
            FullClickReceipt<Stack> returned = new FullClickReceipt<>(expectation, carry,
                    Stack::equals, FullClickReceiptTest::sameSourceIncrease);
            returned.applyFull(valid, invalid == 0 ? fuelReturnSlots(1) : fuelReturnSlots(0), EMPTY,
                    invalid == 1 ? 9 : 8, invalid != 2, invalid != 3, 4);
            assertThrows(IllegalStateException.class, () -> returned.observe(4));
            returned.applyFull(valid, fuelReturnSlots(0), EMPTY, 8, true, true, 4);
            assertThrows(IllegalStateException.class, () -> returned.observe(4));
        }
    }

    private static List<Stack> fuelReturnSlots(int destinationCount) {
        return List.of(new Stack("coal", "ordinary", 9),
                destinationCount == 0 ? EMPTY : new Stack("coal", "ordinary", destinationCount), COBBLE, EMPTY);
    }

    private static List<Stack> placementSlots(int destinationCount) {
        return List.of(EMPTY, destinationCount == 0 ? EMPTY : new Stack("coal", "ordinary", destinationCount), COBBLE, EMPTY);
    }

    private static FullClickReceipt<Stack> placing(int destinationCount) {
        Stack held = new Stack("coal", "ordinary", 9);
        return new FullClickReceipt<>(reply(3, 6, 150, BEFORE, placementSlots(destinationCount), held, 39),
                placementSlots(destinationCount - 1), 3, Stack::equals, null);
    }

    private static FullClickReceipt<Stack> returning() {
        return new FullClickReceipt<>(reply(3, 6, 150, BEFORE, RETURNED, EMPTY, 46), null, 0, Stack::equals, FullClickReceiptTest::sameSourceIncrease);
    }

    private static boolean sameSourceIncrease(Stack before, Stack after) {
        return before.count() > 0 && after.count() > before.count()
                && before.item().equals(after.item()) && before.components().equals(after.components());
    }

    private static void applyReturn(FullClickReceipt<Stack> receipt, List<Stack> slots, Stack cursor) {
        receipt.applyFull(reply(4, 7, 191, FRESH, slots, cursor, 46), slots, cursor, 7, true, true, 0);
    }

    private static void assertStickyRejection(List<Stack> slots, Stack cursor) {
        FullClickReceipt<Stack> receipt = returning();
        applyReturn(receipt, slots, cursor);
        String rejection = receipt.failure();
        assertNotNull(rejection);
        applyReturn(receipt, RETURNED, EMPTY);
        assertEquals(rejection, receipt.failure());
        assertThrows(IllegalStateException.class, () -> receipt.observe(0));
    }

    private static FullClickReceipt.Snapshot<Stack> reply(long sequence, int revision, long cursorSequence,
                                                         List<Long> slotSequences, List<Stack> slots, Stack cursor, int fullSize) {
        return new FullClickReceipt.Snapshot<>(sequence, revision, cursorSequence, slotSequences, slots, cursor, fullSize);
    }
}
