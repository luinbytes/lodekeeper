package dev.lodekeeper.fabric.modern;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

final class FullClickReceipt<T> {
    record ConsumptionCarry<T>(T consumedDestination, double beforeProgress) {
        private List<T> consumedSlots(List<T> planned) {
            List<T> consumed = new ArrayList<>(planned);
            consumed.set(1, consumedDestination);
            return consumed;
        }
    }

    record Snapshot<T>(long sequence, int revision, long cursorSequence, List<Long> slotSequences,
                       List<T> slots, T cursor, int fullSize) {
        Snapshot {
            slotSequences = List.copyOf(slotSequences);
            slots = List.copyOf(slots);
            if (slotSequences.size() != slots.size()) throw new IllegalArgumentException("Receipt slot evidence differs in size");
        }
    }

    record PreDragAcknowledgement<T>(List<T> slots, T cursor) {
        PreDragAcknowledgement {
            slots = List.copyOf(slots);
            if (slots.isEmpty() || slots.size() > 11)
                throw new IllegalArgumentException("Pre-drag acknowledgement needs between one and eleven entries");
        }
    }

    private final Snapshot<T> expected;
    private final PreDragAcknowledgement<T> preDrag;
    private final List<T> consumedSlots;
    private final double beforeConsumption;
    private final BiPredicate<T, T> same, terminalSourceIncrease;
    private Snapshot<T> accepted;
    private boolean consumptionRequired, consumptionConfirmed;
    private String failure;

    FullClickReceipt(Snapshot<T> expected, List<T> consumedSlots, double beforeConsumption, BiPredicate<T, T> same,
                     BiPredicate<T, T> terminalSourceIncrease) {
        this(expected, consumedSlots, beforeConsumption, same, terminalSourceIncrease, null);
    }

    private FullClickReceipt(Snapshot<T> expected, List<T> consumedSlots, double beforeConsumption, BiPredicate<T, T> same,
                             BiPredicate<T, T> terminalSourceIncrease, PreDragAcknowledgement<T> preDrag) {
        this.expected = expected;
        this.preDrag = preDrag;
        this.consumedSlots = consumedSlots == null ? null : List.copyOf(consumedSlots);
        this.beforeConsumption = beforeConsumption;
        this.same = same;
        this.terminalSourceIncrease = terminalSourceIncrease;
    }

    FullClickReceipt(Snapshot<T> expected, ConsumptionCarry<T> carry, BiPredicate<T, T> same,
                     BiPredicate<T, T> terminalSourceIncrease) {
        this(expected, carry == null ? null : carry.consumedSlots(expected.slots()),
                carry == null ? 0 : carry.beforeProgress(), same, terminalSourceIncrease);
    }

    static <T> FullClickReceipt<T> forDrag(Snapshot<T> expected, PreDragAcknowledgement<T> before,
                                         BiPredicate<T, T> same) {
        if (expected.slots().size() != before.slots().size())
            throw new IllegalArgumentException("Pre-drag and final receipt entries differ in size");
        FullClickReceipt<T> receipt = new FullClickReceipt<>(expected, null, 0, same, null, before);
        if (receipt.matches(expected.slots(), before.slots()) && same.test(expected.cursor(), before.cursor()))
            throw new IllegalArgumentException("Pre-drag and final receipt contents must differ");
        return receipt;
    }

    void applyFull(Snapshot<T> reply, List<T> liveSlots, T liveCursor, int liveRevision,
                   boolean contextCurrent, boolean sendFinished, double consumptionProgress) {
        if (failure != null) return;
        if (!contextCurrent || !sendFinished) {
            reject("Inventory receipt lost its context or arrived before the click finished sending");
            return;
        }
        if (accepted == null) {
            boolean fresh = reply.sequence() > expected.sequence() && reply.cursorSequence() > expected.cursorSequence()
                    && reply.slotSequences().size() == expected.slotSequences().size();
            if (fresh) {
                for (int index = 0; index < reply.slotSequences().size(); index++)
                    fresh &= reply.slotSequences().get(index) > expected.slotSequences().get(index);
            }
            if (!fresh || reply.revision() == expected.revision()) return;
        }
        if (reply.fullSize() != expected.fullSize() || reply.revision() < 0 || reply.revision() != liveRevision
                || !matches(reply.slots(), liveSlots) || !same.test(reply.cursor(), liveCursor)) {
            reject("Full inventory receipt differs from the live handler or its revision");
            return;
        }
        if (accepted != null) {
            if (!matchesAcknowledged(reply.slots(), true) || !same.test(reply.cursor(), accepted.cursor())) {
                reject("A later full inventory receipt changed the acknowledged transfer");
            } else if (!matchesState(accepted.slots(), reply.slots(), true) && consumedSlots != null
                    && matchesState(consumedSlots, reply.slots(), true) && !consumptionRequired) {
                consumptionRequired = true;
                consumptionConfirmed = consumptionProgress > beforeConsumption;
            }
            return;
        }
        if (preDrag != null && matches(reply.slots(), preDrag.slots()) && same.test(reply.cursor(), preDrag.cursor())) return;
        boolean exact = matches(reply.slots(), expected.slots());
        boolean consumed = !exact && consumedSlots != null && matches(reply.slots(), consumedSlots);
        if ((!exact && !consumed) || !same.test(reply.cursor(), expected.cursor())) {
            reject("Server rejected or modified the full inventory transfer");
            return;
        }
        accepted = reply;
        consumptionRequired = consumed;
        consumptionConfirmed = !consumed || consumptionProgress > beforeConsumption;
    }

    Snapshot<T> observe(double consumptionProgress) {
        requireValid();
        if (accepted == null) return null;
        if (!consumptionConfirmed) {
            if (!(consumptionProgress > beforeConsumption)) {
                reject("Server receipt did not prove the planned item was consumed");
                requireValid();
            }
            consumptionConfirmed = true;
        }
        return accepted;
    }

    void requireLive(List<T> liveSlots, T liveCursor, boolean contextCurrent,
                     double consumptionProgress, boolean completing) {
        requireValid();
        boolean consumedAfterAck = accepted != null && consumedSlots != null
                && !matchesState(accepted.slots(), liveSlots, completing)
                && matchesState(consumedSlots, liveSlots, completing);
        if (!contextCurrent || accepted != null
                && (!matchesAcknowledged(liveSlots, completing) || !same.test(accepted.cursor(), liveCursor)
                || consumedAfterAck && !(consumptionProgress > beforeConsumption))) {
            reject("Acknowledged inventory changed outside the permitted transfer state");
            requireValid();
        }
        if (consumedAfterAck) {
            consumptionRequired = true;
            consumptionConfirmed = true;
        }
    }

    ConsumptionCarry<T> carryConsumption() {
        requireValid();
        if (accepted == null || !consumptionConfirmed)
            throw new IllegalStateException("Inventory consumption cannot carry before its receipt is confirmed");
        return consumedSlots == null || consumptionRequired ? null
                : new ConsumptionCarry<>(consumedSlots.get(1), beforeConsumption);
    }

    void reject(String reason) { if (failure == null) failure = reason; }
    String failure() { return failure; }
    Snapshot<T> accepted() { return accepted; }
    boolean consumptionRequired() { return consumptionRequired; }
    boolean consumptionConfirmed() { return consumptionConfirmed; }

    private void requireValid() {
        if (failure != null) throw new IllegalStateException(failure);
    }

    private boolean matchesAcknowledged(List<T> slots, boolean allowTerminalSourceIncrease) {
        return matchesState(accepted.slots(), slots, allowTerminalSourceIncrease)
                || consumedSlots != null && matchesState(consumedSlots, slots, allowTerminalSourceIncrease);
    }

    private boolean matchesState(List<T> reference, List<T> slots, boolean allowTerminalSourceIncrease) {
        if (matches(reference, slots)) return true;
        if (!allowTerminalSourceIncrease || terminalSourceIncrease == null || slots.size() != reference.size()
                || !terminalSourceIncrease.test(reference.get(0), slots.get(0))) return false;
        return matches(reference.subList(1, reference.size()), slots.subList(1, slots.size()));
    }

    private boolean matches(List<T> left, List<T> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!same.test(left.get(index), right.get(index))) return false;
        }
        return true;
    }
}
