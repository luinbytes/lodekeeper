package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tracks stations placed by an owned bot job after both server block and inventory receipts.
 *
 * <p>The adapter must reserve an accepted placement before sending its native packet. While an
 * intent is pending, it must serialize owned station placements and prevent any other action from
 * consuming the same station item or producing an indistinguishable block update. It must use one
 * strictly increasing network receipt sequence for block and inventory updates, and deliver those
 * updates in sequence order. The starting sequence is captured before the placement packet. If the
 * adapter cannot attribute both receipts to that exclusive action, it must let the intent expire.
 * All methods must run serially on the adapter's ordered event thread.
 *
 * <p>Session changes are explicit. The adapter must call {@link #changeSession(Session)} before
 * forwarding receipts from a new world connection. This ledger does not infer ownership from
 * discovery, client prediction, persistence, or native APIs.
 */
public final class OwnedStationLedger {
    public static final int MAX_RECORDS = 128;

    private Session currentSession;
    private long latestNetworkSequence = -1;
    private PendingPlacement pending;
    private final List<StationRecord> records = new ArrayList<>();

    public OwnedStationLedger(Session initialSession) {
        currentSession = Objects.requireNonNull(initialSession, "initialSession");
    }

    public Session currentSession() {
        return currentSession;
    }

    /**
     * Starts a fresh ledger when the adapter has established a different world session.
     * Repeating the current session is idempotent and keeps its records.
     */
    public void changeSession(Session nextSession) {
        Objects.requireNonNull(nextSession, "nextSession");
        if (currentSession.equals(nextSession)) return;
        currentSession = nextSession;
        latestNetworkSequence = -1;
        pending = null;
        records.clear();
    }

    /**
     * Reserves one exact placement attempt. The adapter may send its native packet only when the
     * result is accepted.
     */
    public BeginPlacementResult tryBeginPlacement(
            Session session,
            long jobToken,
            BlockPosition position,
            ItemId expectedBlockId,
            ItemId stationItemId,
            long startNetworkSequence,
            int startingInventoryCount) {
        PlacementIntent intent = new PlacementIntent(session, jobToken, position, expectedBlockId,
                stationItemId, startNetworkSequence, startingInventoryCount);
        if (!currentSession.equals(session)) return BeginPlacementResult.rejected(BeginStatus.STALE_SESSION);
        if (pending != null) return BeginPlacementResult.rejected(BeginStatus.PENDING_BACKPRESSURE);
        if (records.size() >= MAX_RECORDS) return BeginPlacementResult.rejected(BeginStatus.RECORD_CAPACITY);
        if (records.stream().anyMatch(record -> record.position().equals(position))) {
            return BeginPlacementResult.rejected(BeginStatus.POSITION_ALREADY_OWNED);
        }
        if (startNetworkSequence < latestNetworkSequence) {
            return BeginPlacementResult.rejected(BeginStatus.STALE_SEQUENCE);
        }

        latestNetworkSequence = startNetworkSequence;
        PlacementTicket ticket = new PlacementTicket(intent);
        pending = new PendingPlacement(ticket, AwaitingBlockAndInventory.INSTANCE);
        return BeginPlacementResult.accepted(ticket);
    }

    /**
     * Reports a native server block update, never a client prediction or discovery scan. A
     * mismatching update at the intended position rejects the pending attempt. A later different
     * block at an owned position drops that ownership. A matching update keeps its current owner.
     */
    public ObservationResult onServerBlockUpdate(
            Session session, BlockPosition position, ItemId observedBlockId, long receiptSequence) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(observedBlockId, "observedBlockId");
        requireSequence(receiptSequence);
        if (!currentSession.equals(session)) return ObservationResult.STALE_SESSION;
        if (!acceptSequence(receiptSequence)) return ObservationResult.STALE_SEQUENCE;

        for (int index = 0; index < records.size(); index++) {
            StationRecord record = records.get(index);
            if (!record.position().equals(position)) continue;
            if (!record.expectedBlockId().equals(observedBlockId)) {
                records.remove(index);
                return ObservationResult.OWNERSHIP_REMOVED;
            }
            return ObservationResult.OWNERSHIP_RETAINED;
        }

        if (pending == null || !pending.ticket().intent().position().equals(position)) {
            return pending == null ? ObservationResult.IGNORED : ObservationResult.PENDING;
        }
        PlacementIntent intent = pending.ticket().intent();
        if (!intent.expectedBlockId().equals(observedBlockId)) {
            pending = null;
            return ObservationResult.PENDING_REJECTED;
        }

        if (pending.evidence() instanceof AwaitingBlock) {
            AwaitingBlock evidence = (AwaitingBlock) pending.evidence();
            return confirm(receiptSequence, evidence.inventoryReceiptSequence());
        }
        if (pending.evidence() instanceof AwaitingBlockAndInventory) {
            pending = new PendingPlacement(pending.ticket(), new AwaitingInventory(receiptSequence));
        }
        return ObservationResult.PENDING;
    }

    /**
     * Reports a native server inventory count for one item. A count below the attempt's starting
     * count is evidence only for that exact pending station item. A matching count that returns to
     * the starting value after a decrement rejects the pending attempt.
     */
    public ObservationResult onInventoryCount(
            Session session, ItemId itemId, int count, long receiptSequence) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(itemId, "itemId");
        if (count < 0) throw new IllegalArgumentException("count must be non-negative");
        requireSequence(receiptSequence);
        if (!currentSession.equals(session)) return ObservationResult.STALE_SESSION;
        if (!acceptSequence(receiptSequence)) return ObservationResult.STALE_SEQUENCE;
        if (pending == null) return ObservationResult.IGNORED;

        PlacementIntent intent = pending.ticket().intent();
        if (!intent.stationItemId().equals(itemId)) return ObservationResult.PENDING;
        if (count >= intent.startingInventoryCount()) {
            if (pending.evidence() instanceof AwaitingBlock) {
                pending = null;
                return ObservationResult.PENDING_REJECTED;
            }
            return ObservationResult.PENDING;
        }

        if (pending.evidence() instanceof AwaitingInventory) {
            AwaitingInventory evidence = (AwaitingInventory) pending.evidence();
            return confirm(evidence.blockReceiptSequence(), receiptSequence);
        }
        if (pending.evidence() instanceof AwaitingBlockAndInventory) {
            pending = new PendingPlacement(pending.ticket(), new AwaitingBlock(receiptSequence));
        }
        return ObservationResult.PENDING;
    }

    /**
     * Expires only the exact pending ticket. The adapter owns the timeout clock and must call this
     * when that attempt's bounded wait expires.
     */
    public boolean expirePending(Session session, PlacementTicket ticket) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(ticket, "ticket");
        if (!currentSession.equals(session) || pending == null || pending.ticket() != ticket) return false;
        pending = null;
        return true;
    }

    /** Returns the immutable confirmed records currently owned by this ledger. */
    public List<StationRecord> records() {
        return List.copyOf(records);
    }

    /** Returns the current ticket so the adapter can expire its exact pending attempt. */
    public Optional<PlacementTicket> pendingTicket() {
        return pending == null ? Optional.empty() : Optional.of(pending.ticket());
    }

    /**
     * Removes this exact record after the adapter receives server evidence that this station was
     * picked up. Equivalent record data from another ledger does not authorize removal.
     */
    public boolean removeAfterPickup(Session session, StationRecord record) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(record, "record");
        if (!currentSession.equals(session)) return false;
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index) == record) {
                records.remove(index);
                return true;
            }
        }
        return false;
    }

    private ObservationResult confirm(long blockReceiptSequence, long inventoryReceiptSequence) {
        PendingPlacement completed = pending;
        PlacementIntent intent = completed.ticket().intent();
        records.add(new StationRecord(intent, completed.ticket(), blockReceiptSequence,
                inventoryReceiptSequence));
        pending = null;
        return ObservationResult.CONFIRMED;
    }

    private boolean acceptSequence(long receiptSequence) {
        if (receiptSequence <= latestNetworkSequence) return false;
        latestNetworkSequence = receiptSequence;
        return true;
    }

    private static void requireSequence(long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("network sequence must be non-negative");
    }

    public record Session(WorldScope worldScope, long generation) {
        public Session {
            Objects.requireNonNull(worldScope, "worldScope");
            if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    public record BlockPosition(int x, int y, int z) { }

    public record PlacementIntent(
            Session session,
            long jobToken,
            BlockPosition position,
            ItemId expectedBlockId,
            ItemId stationItemId,
            long startNetworkSequence,
            int startingInventoryCount) {
        public PlacementIntent {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(expectedBlockId, "expectedBlockId");
            Objects.requireNonNull(stationItemId, "stationItemId");
            if (jobToken <= 0) throw new IllegalArgumentException("jobToken must be positive");
            requireSequence(startNetworkSequence);
            if (startingInventoryCount < 1) {
                throw new IllegalArgumentException("startingInventoryCount must be positive");
            }
        }
    }

    public enum BeginStatus {
        ACCEPTED,
        STALE_SESSION,
        PENDING_BACKPRESSURE,
        RECORD_CAPACITY,
        POSITION_ALREADY_OWNED,
        STALE_SEQUENCE
    }

    public static final class BeginPlacementResult {
        private final BeginStatus status;
        private final PlacementTicket ticket;

        private BeginPlacementResult(BeginStatus status, PlacementTicket ticket) {
            this.status = Objects.requireNonNull(status, "status");
            this.ticket = ticket;
            if ((status == BeginStatus.ACCEPTED) != (ticket != null)) {
                throw new IllegalArgumentException("accepted results require exactly one ticket");
            }
        }

        private static BeginPlacementResult accepted(PlacementTicket ticket) {
            return new BeginPlacementResult(BeginStatus.ACCEPTED, ticket);
        }

        private static BeginPlacementResult rejected(BeginStatus status) {
            return new BeginPlacementResult(status, null);
        }

        public BeginStatus status() {
            return status;
        }

        public boolean accepted() {
            return status == BeginStatus.ACCEPTED;
        }

        public Optional<PlacementTicket> ticket() {
            return Optional.ofNullable(ticket);
        }
    }

    /** Identity ticket used for exact cancellation and expiry of one placement attempt. */
    public static final class PlacementTicket {
        private final PlacementIntent intent;

        private PlacementTicket(PlacementIntent intent) {
            this.intent = intent;
        }

        public PlacementIntent intent() {
            return intent;
        }
    }

    /**
     * Identity record. Callers receive it from records() and pass that same object to
     * removeAfterPickup after native pickup evidence.
     */
    public static final class StationRecord {
        private final PlacementIntent intent;
        private final PlacementTicket ticket;
        private final long blockReceiptSequence;
        private final long inventoryReceiptSequence;

        private StationRecord(
                PlacementIntent intent,
                PlacementTicket ticket,
                long blockReceiptSequence,
                long inventoryReceiptSequence) {
            this.intent = intent;
            this.ticket = ticket;
            this.blockReceiptSequence = blockReceiptSequence;
            this.inventoryReceiptSequence = inventoryReceiptSequence;
        }

        public Session session() {
            return intent.session();
        }

        public long jobToken() {
            return intent.jobToken();
        }

        public BlockPosition position() {
            return intent.position();
        }

        public ItemId expectedBlockId() {
            return intent.expectedBlockId();
        }

        public ItemId stationItemId() {
            return intent.stationItemId();
        }

        public long startNetworkSequence() {
            return intent.startNetworkSequence();
        }

        public int startingInventoryCount() {
            return intent.startingInventoryCount();
        }

        public long blockReceiptSequence() {
            return blockReceiptSequence;
        }

        public long inventoryReceiptSequence() {
            return inventoryReceiptSequence;
        }

        public PlacementTicket ticket() {
            return ticket;
        }
    }

    public enum ObservationResult {
        CONFIRMED,
        PENDING,
        PENDING_REJECTED,
        OWNERSHIP_RETAINED,
        OWNERSHIP_REMOVED,
        IGNORED,
        STALE_SESSION,
        STALE_SEQUENCE
    }

    private record PendingPlacement(PlacementTicket ticket, PendingEvidence evidence) { }

    private sealed interface PendingEvidence
            permits AwaitingBlockAndInventory, AwaitingInventory, AwaitingBlock { }

    private enum AwaitingBlockAndInventory implements PendingEvidence {
        INSTANCE
    }

    private record AwaitingInventory(long blockReceiptSequence) implements PendingEvidence { }

    private record AwaitingBlock(long inventoryReceiptSequence) implements PendingEvidence { }
}
