package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class RegionRevisionTrackerTest {
    @Test
    void onlyChangesToSampledChunksInvalidateTheSearch() {
        RegionRevisionTracker tracker = new RegionRevisionTracker(8);
        tracker.begin();
        tracker.watch(-2, 3);
        long sampledRevision = tracker.currentRevision();

        tracker.changed(5, 3);
        assertEquals(sampledRevision, tracker.currentRevision());

        tracker.changed(-2, 3);
        assertNotEquals(sampledRevision, tracker.currentRevision());
    }

    @Test
    void unloadOfSampledChunkInvalidatesTheSearch() {
        RegionRevisionTracker tracker = new RegionRevisionTracker(8);
        tracker.begin();
        tracker.watch(-4, -7);
        long sampledRevision = tracker.currentRevision();

        tracker.unloaded(-4, -7);

        assertNotEquals(sampledRevision, tracker.currentRevision());
    }

    @Test
    void changesBeforeSamplingDoNotMakeAStableSnapshotStale() {
        RegionRevisionTracker tracker = new RegionRevisionTracker(8);
        tracker.begin();
        tracker.changed(9, -11);
        long revisionBeforeSample = tracker.currentRevision();

        tracker.watch(9, -11);

        assertEquals(revisionBeforeSample, tracker.currentRevision());
        tracker.changed(9, -11);
        assertNotEquals(revisionBeforeSample, tracker.currentRevision());
    }

    @Test
    void resetInvalidatesOldSearchAndStartsASeparateWatchSet() {
        RegionRevisionTracker tracker = new RegionRevisionTracker(8);
        tracker.begin();
        tracker.watch(1, 2);
        long oldSearchRevision = tracker.currentRevision();

        tracker.reset();
        long newSearchRevision = tracker.currentRevision();
        assertNotEquals(oldSearchRevision, newSearchRevision);

        tracker.changed(1, 2);
        assertEquals(newSearchRevision, tracker.currentRevision());
        tracker.watch(3, 4);
        tracker.changed(1, 2);
        assertEquals(newSearchRevision, tracker.currentRevision());
        tracker.changed(3, 4);
        assertNotEquals(newSearchRevision, tracker.currentRevision());
    }

    @Test
    void watchCapacityOverflowInvalidatesAndMakesLaterChangesConservative() {
        RegionRevisionTracker tracker = new RegionRevisionTracker(1);
        tracker.begin();
        long initialRevision = tracker.currentRevision();
        tracker.watch(0, 0);
        tracker.watch(0, 1);
        long overflowRevision = tracker.currentRevision();
        assertNotEquals(initialRevision, overflowRevision);

        tracker.changed(50, -80);

        assertNotEquals(overflowRevision, tracker.currentRevision());
    }

    @Test
    void trackerRejectsUseFromAnotherThread() throws InterruptedException {
        RegionRevisionTracker tracker = new RegionRevisionTracker(8);
        tracker.begin();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread otherThread = new Thread(() -> {
            try {
                tracker.currentRevision();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        otherThread.start();
        otherThread.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
    }
}
