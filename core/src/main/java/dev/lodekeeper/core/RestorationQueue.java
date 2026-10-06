package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded queue of adapter-confirmed automated breaks awaiting matching block placement.
 *
 * <p>The adapter must call {@link #recordConfirmedBreak(ConfirmedBreakReceipt)} only after a
 * server block receipt confirms the automated break. A receipt is not authority by itself. The
 * adapter owns its provenance, current session, and strictly increasing per-session revision.
 * Every method must run serially on that adapter's ordered client thread.</p>
 *
 * <p>The inventory passed to {@link #tryReserve(Session, ConfirmedBreakReceipt, InventorySnapshot)}
 * must already protect every foreground, project, maintenance, recipe, and safety reservation.
 * This queue never asks an acquisition source to gather a missing placement item.</p>
 */
public final class RestorationQueue {
    public static final int MAX_CELLS = 2_048;

    private static final ItemId STONE = ItemId.parse("minecraft:stone");
    private static final ItemId COBBLESTONE = ItemId.parse("minecraft:cobblestone");

    private final int capacity;
    private final LinkedHashMap<BlockPosition, ConfirmedBreakReceipt> pending = new LinkedHashMap<>();
    private Session currentSession;
    private long latestServerRevision = -1;
    private int nextProbeIndex;
    private PlacementReservation inFlight;

    public RestorationQueue(Session initialSession, int capacity) {
        currentSession = Objects.requireNonNull(initialSession, "initialSession");
        if (capacity < 1 || capacity > MAX_CELLS) {
            throw new IllegalArgumentException("capacity must be between 1 and " + MAX_CELLS);
        }
        this.capacity = capacity;
    }

    public Session currentSession() {
        return currentSession;
    }

    public int capacity() {
        return capacity;
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Starts a fresh queue after the adapter establishes a different world session. Repeating the
     * same session is idempotent and keeps pending cells and the exact in-flight token.
     */
    public void changeSession(Session nextSession) {
        Objects.requireNonNull(nextSession, "nextSession");
        if (currentSession.equals(nextSession)) return;
        currentSession = nextSession;
        latestServerRevision = -1;
        nextProbeIndex = 0;
        inFlight = null;
        pending.clear();
    }

    /**
     * Adds one confirmed automated break. A newer receipt at the same position replaces the old
     * cell and invalidates its exact placement token. A full queue still records the latest seen
     * revision so a delayed replay cannot enter after another cell is retired.
     */
    public ReceiptStatus recordConfirmedBreak(ConfirmedBreakReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        if (!currentSession.equals(receipt.session())) return ReceiptStatus.STALE_SESSION;
        if (receipt.revision() <= latestServerRevision) return ReceiptStatus.STALE_REVISION;

        latestServerRevision = receipt.revision();
        ConfirmedBreakReceipt previous = pending.get(receipt.position());
        if (previous == null && pending.size() == capacity) return ReceiptStatus.CAPACITY_REACHED;

        if (previous != null) {
            pending.remove(receipt.position());
            if (inFlight != null && inFlight.receipt() == previous) inFlight = null;
        }
        pending.put(receipt.position(), receipt);
        return previous == null ? ReceiptStatus.RECORDED : ReceiptStatus.REPLACED;
    }

    /**
     * Returns at most {@code maximumProbes} pending receipts in round-robin order. The adapter
     * owns world reads and must retire a cell only from a newer server observation.
     */
    public List<ConfirmedBreakReceipt> pendingForProbe(Session session, int maximumProbes) {
        Objects.requireNonNull(session, "session");
        if (maximumProbes < 1 || maximumProbes > MAX_CELLS) {
            throw new IllegalArgumentException("maximumProbes must be between 1 and " + MAX_CELLS);
        }
        if (!currentSession.equals(session) || pending.isEmpty()) return List.of();

        List<ConfirmedBreakReceipt> cells = new ArrayList<>(pending.values());
        int count = Math.min(maximumProbes, cells.size());
        int start = Math.floorMod(nextProbeIndex, cells.size());
        List<ConfirmedBreakReceipt> result = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            result.add(cells.get((start + offset) % cells.size()));
        }
        nextProbeIndex = (start + count) % cells.size();
        return List.copyOf(result);
    }

    /**
     * Reserves one exact pending cell and one matching item from stock left after all supplied
     * reservation floors. The queue allows one active placement token so another cell cannot claim
     * the same unacknowledged surplus stack.
     */
    public ReservationResult tryReserve(
            Session session, ConfirmedBreakReceipt receipt, InventorySnapshot protectedStock) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(protectedStock, "protectedStock");
        if (!currentSession.equals(session) || !receipt.session().equals(session)) {
            return ReservationResult.rejected(ReserveStatus.STALE_SESSION);
        }
        if (pending.get(receipt.position()) != receipt) {
            return ReservationResult.rejected(ReserveStatus.STALE_RECEIPT);
        }
        if (inFlight != null) return ReservationResult.rejected(ReserveStatus.BUSY);

        for (ItemId item : matchingItems(receipt.material().block())) {
            if (protectedStock.spendableCount(item) <= 0) continue;
            PlacementReservation reservation = new PlacementReservation(receipt, item);
            inFlight = reservation;
            return ReservationResult.reserved(reservation);
        }
        return ReservationResult.rejected(ReserveStatus.NO_SURPLUS);
    }

    /**
     * Returns the one placement item held by the queue, if any. The adapter adds this count to
     * future protected floors until the exact token is confirmed, released before send, or cleared
     * by a newer server observation or session change.
     */
    public Map<ItemId, Integer> inFlightReservationCounts() {
        return inFlight == null ? Map.of() : Map.of(inFlight.item(), 1);
    }

    /**
     * Releases a token only if the adapter can prove that no native placement packet was sent.
     * The cell stays pending. A sent or uncertain action must wait for server evidence.
     */
    public boolean releaseBeforeSend(Session session, PlacementReservation reservation) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(reservation, "reservation");
        if (!currentSession.equals(session) || inFlight != reservation
                || pending.get(reservation.receipt().position()) != reservation.receipt()) return false;
        inFlight = null;
        return true;
    }

    /**
     * Removes a cell only after the adapter attributes a matching native server placement update
     * to this exact token. Local prediction or an accepted interaction never completes a cell.
     */
    public PlacementStatus confirmServerPlacement(
            Session session, PlacementReservation reservation, BlockId observedBlock, long serverRevision) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(observedBlock, "observedBlock");
        requireRevision(serverRevision);
        if (!currentSession.equals(session)) return PlacementStatus.STALE_SESSION;
        if (inFlight != reservation || pending.get(reservation.receipt().position()) != reservation.receipt()) {
            return PlacementStatus.STALE_RESERVATION;
        }
        if (serverRevision <= latestServerRevision) return PlacementStatus.STALE_REVISION;
        if (!reservation.blockToPlace().equals(observedBlock)) return PlacementStatus.BLOCK_MISMATCH;

        latestServerRevision = serverRevision;
        pending.remove(reservation.receipt().position());
        inFlight = null;
        return PlacementStatus.CONFIRMED;
    }

    /**
     * Retires one exact receipt after a newer server update shows that its post-break cell changed
     * semantically. The adapter must compare the full native state and never call this for a local
     * prediction, discovery scan, or chunk load.
     */
    public boolean retireAfterServerCellChanged(
            Session session, ConfirmedBreakReceipt receipt, long serverRevision) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(receipt, "receipt");
        requireRevision(serverRevision);
        if (!currentSession.equals(session) || !receipt.session().equals(session)
                || serverRevision <= latestServerRevision || pending.get(receipt.position()) != receipt) return false;
        latestServerRevision = serverRevision;
        pending.remove(receipt.position());
        if (inFlight != null && inFlight.receipt() == receipt) inFlight = null;
        return true;
    }

    private static List<ItemId> matchingItems(BlockId block) {
        ItemId exact = new ItemId(block.namespace(), block.path());
        if (exact.equals(STONE)) return List.of(STONE, COBBLESTONE);
        if (exact.equals(COBBLESTONE)) return List.of(COBBLESTONE, STONE);
        return List.of(exact);
    }

    private static void requireRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }

    public record Session(WorldScope worldScope, long generation) {
        public Session {
            Objects.requireNonNull(worldScope, "worldScope");
            if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    public record BlockPosition(int x, int y, int z) { }

    /**
     * Adapter value for one server-confirmed automated break. Its revision must be strictly
     * increasing within its session. The adapter must verify the native server receipt and dry-air
     * result before constructing it.
     */
    public record ConfirmedBreakReceipt(Session session, long revision, BlockPosition position, RestorableBlock material) {
        public ConfirmedBreakReceipt {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(material, "material");
            requireRevision(revision);
        }
    }

    /** A simple block and its same-ID item, validated at the native adapter boundary. */
    public record RestorableBlock(BlockId block, ItemId item) {
        public RestorableBlock {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(item, "item");
            if (!item.equals(new ItemId(block.namespace(), block.path()))) {
                throw new IllegalArgumentException("restoration item must have the block's identifier");
            }
        }
    }

    public enum ReceiptStatus {
        RECORDED,
        REPLACED,
        STALE_SESSION,
        STALE_REVISION,
        CAPACITY_REACHED
    }

    public enum ReserveStatus {
        RESERVED,
        STALE_SESSION,
        STALE_RECEIPT,
        BUSY,
        NO_SURPLUS
    }

    public enum PlacementStatus {
        CONFIRMED,
        STALE_SESSION,
        STALE_RESERVATION,
        STALE_REVISION,
        BLOCK_MISMATCH
    }

    public static final class ReservationResult {
        private final ReserveStatus status;
        private final PlacementReservation reservation;

        private ReservationResult(ReserveStatus status, PlacementReservation reservation) {
            this.status = Objects.requireNonNull(status, "status");
            this.reservation = reservation;
            if ((status == ReserveStatus.RESERVED) != (reservation != null)) {
                throw new IllegalArgumentException("reserved results require exactly one token");
            }
        }

        private static ReservationResult reserved(PlacementReservation reservation) {
            return new ReservationResult(ReserveStatus.RESERVED, reservation);
        }

        private static ReservationResult rejected(ReserveStatus status) {
            return new ReservationResult(status, null);
        }

        public ReserveStatus status() {
            return status;
        }

        public Optional<PlacementReservation> reservation() {
            return Optional.ofNullable(reservation);
        }
    }

    /** Identity token for one reserved cell and one selected item. */
    public static final class PlacementReservation {
        private final ConfirmedBreakReceipt receipt;
        private final ItemId item;

        private PlacementReservation(ConfirmedBreakReceipt receipt, ItemId item) {
            this.receipt = receipt;
            this.item = item;
        }

        public ConfirmedBreakReceipt receipt() {
            return receipt;
        }

        public ItemId item() {
            return item;
        }

        public BlockId blockToPlace() {
            return new BlockId(item.namespace(), item.path());
        }
    }
}
