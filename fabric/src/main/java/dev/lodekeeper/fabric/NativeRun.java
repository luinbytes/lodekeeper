package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.OwnedStationLedger;

interface NativeRun {
    enum DrainReason { STOP, REPLAN, PREEMPT, SCREEN, FAILURE }
    sealed interface Outcome {
        record Pending(String status) implements Outcome { }
        record Delivered(ObservedStock stock, java.util.UUID target, java.util.UUID drop) implements Outcome { }
        record Yielded(ObservedStock stock, String reason) implements Outcome { }
        record TravelFinished(TravelReceipt receipt) implements Outcome { }
        record Drained() implements Outcome { }
        record Blocked(String reason) implements Outcome { }
    }
    enum TravelResult { ARRIVED, EXPLORED, FOLLOW_EXPIRED, CANCELLED, REFUSED }
    record TravelReceipt(OwnedStationLedger.Session session, long jobToken, TravelResult result,
                         int arrivedSegments, String reason) { }
    record ObservedStock(OwnedStationLedger.Session session, long jobToken, long offerGeneration,
                         ItemId item, int count, long increaseSequence) { }
    Outcome tick();
    void requestDrain(DrainReason reason);
    void pause();
    void abandonSession();
    boolean safeToRelease();
}
