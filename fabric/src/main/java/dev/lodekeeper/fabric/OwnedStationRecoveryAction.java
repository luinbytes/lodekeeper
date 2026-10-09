package dev.lodekeeper.fabric;

import dev.lodekeeper.core.OwnedStationLedger;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Recovers only the exact bot-owned station after its native contents are server-verified empty. */
final class OwnedStationRecoveryAction {
    private static final double DROP_RADIUS = 4.0;
    private static final long MAX_DURATION_NANOS = 20_000_000_000L;

    private enum Phase {
        IDLE, APPROACH, APPROACH_STOPPING, WAITING_FOR_HANDLER, WAITING_FOR_CONTENTS,
        CLOSING_HANDLER, MINING, PICKUP, PICKUP_REPLANNING, PICKUP_INCOMPLETE,
        RETURN, FINISHING, COMPLETE, STOPPED
    }

    private enum StationKind {
        CRAFTING_TABLE(Blocks.CRAFTING_TABLE, "crafting table", 10),
        FURNACE(Blocks.FURNACE, "furnace", 3),
        SMOKER(Blocks.SMOKER, "smoker", 3),
        BLAST_FURNACE(Blocks.BLAST_FURNACE, "blast furnace", 3),
        STONECUTTER(Blocks.STONECUTTER, "stonecutter", 2);

        final Block block;
        final String name;
        final int nativeSlotCount;

        StationKind(Block block, String name, int nativeSlotCount) {
            this.block = block;
            this.name = name;
            this.nativeSlotCount = nativeSlotCount;
        }

        static StationKind forBlock(Block block) {
            for (StationKind kind : values()) if (kind.block == block) return kind;
            return null;
        }

        boolean matches(ScreenHandler handler) {
            if (handler == null) return false;
            return switch (this) {
                case CRAFTING_TABLE -> handler.getClass() == CraftingScreenHandler.class;
                case FURNACE -> handler.getClass() == net.minecraft.screen.FurnaceScreenHandler.class;
                case SMOKER -> handler.getClass() == net.minecraft.screen.SmokerScreenHandler.class;
                case BLAST_FURNACE -> handler.getClass() == net.minecraft.screen.BlastFurnaceScreenHandler.class;
                case STONECUTTER -> handler.getClass() == StonecutterScreenHandler.class;
            };
        }

        boolean contentsEmpty(ScreenHandler handler) {
            if (!matches(handler) || handler.slots.size() < nativeSlotCount) return false;
            for (int slot = 0; slot < nativeSlotCount; slot++) {
                if (!handler.getSlot(slot).getStack().isEmpty()) return false;
            }
            return true;
        }
    }

    private static final class PickupDiagnostics {
        private Object connection;
        private OwnedStationLedger.Session observedSession;
        private PlacementProvenance.ServerInventoryReceipt inventoryReceipt;
        private long inventoryObservedNanos;
        private OptionalLong removalSequence = OptionalLong.empty();
        private long removalObservedNanos;
        private long stallAnchorNanos, lastSampleNanos;
        private double anchorX, anchorY, anchorZ;
        private int samples;
        private boolean terminalLogged;
    }

    private final MinecraftClient client;
    private final LodekeeperConfig config;
    private final PlayerActions actions;
    private final MovementController movement;
    private final Predicate<BlockPos> mayBreak;
    private final Function<BlockPos, OptionalLong> serverRemovalSequence;
    private final Function<Item, Optional<PlacementProvenance.ServerInventoryReceipt>> inventoryReceipt;
    private final Supplier<Optional<OwnedStationLedger.Session>> currentSession;

    private Phase phase = Phase.IDLE;
    private String status = "owned station recovery idle";
    private StationKind stationKind;
    private Block expectedBlock;
    private Item blockItem;
    private BlockPos ownedPosition;
    private Object ownerPlayer, ownerWorld;
    private ScreenHandler originalHandler, stationHandler;
    private Object originalScreen, stationScreen;
    private ItemEntity drop;
    private Map<ItemEntity, Integer> startingNearbyDrops = Map.of();
    private int dropObservedCount;
    private PlacementProvenance.ServerInventoryReceipt startingInventoryReceipt;
    private long exactStationRemovalSequence;
    private long dropObservedIncreaseSequence = -1;
    private long startedAtNanos;
    private OwnedStationLedger.Session recoverySession;
    private boolean restorationRevoked, unknownHandlerReturn;
    private int pickupAttempts;
    private PickupDiagnostics pickupDiagnostics = new PickupDiagnostics();
    private boolean miningStarted, contentsVerifiedEmpty, collectedBeforeDropObserved;
    private static final int MAX_PICKUP_ATTEMPTS = 3;

    OwnedStationRecoveryAction(MinecraftClient client, LodekeeperConfig config, PlayerActions actions,
                               MovementController movement, Predicate<BlockPos> mayBreak,
                               Function<BlockPos, OptionalLong> serverRemovalSequence,
                               Function<Item, Optional<PlacementProvenance.ServerInventoryReceipt>> inventoryReceipt,
                               Supplier<Optional<OwnedStationLedger.Session>> currentSession) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.movement = Objects.requireNonNull(movement, "movement");
        this.mayBreak = Objects.requireNonNull(mayBreak, "mayBreak");
        this.serverRemovalSequence = Objects.requireNonNull(serverRemovalSequence, "serverRemovalSequence");
        this.inventoryReceipt = Objects.requireNonNull(inventoryReceipt, "inventoryReceipt");
        this.currentSession = Objects.requireNonNull(currentSession, "currentSession");
    }

    boolean begin(BlockPos position, Block block) {
        if (active()) {
            status = "owned station recovery is already active";
            return false;
        }
        if (!returnPending()) pickupDiagnostics = new PickupDiagnostics();
        OwnedStationLedger.Session session = observeSession().orElse(null);
        if (!returnPending()) pickupDiagnostics.connection = diagnosticConnection();
        boolean resumePickup = phase == Phase.PICKUP_INCOMPLETE && session != null
                && session.equals(recoverySession) && position != null && ownedPosition != null
                && ownedPosition.equals(position) && expectedBlock == block
                && ownerWorld == client.world && ownerPlayer == client.player && !restorationRevoked
                && diagnosticConnection() == pickupDiagnostics.connection
                && drop != null && drop.isAlive() && blockItem != null && drop.getStack().isOf(blockItem);
        if (returnPending() && !resumePickup) return refuse("retained owned station RETURN debt belongs to its original actor");
        if (!resumePickup) {
            clearOwnership();
            phase = Phase.IDLE;
        }
        String reason = unsafeContextReason();
        if (reason != null) return refuse(reason);
        if (position == null || block == null) return refuse("no owned station position and block were supplied");
        stationKind = StationKind.forBlock(block);
        if (stationKind == null) return refuse("unsupported owned station block");
        blockItem = block.asItem();
        if (blockItem == Items.AIR) return refuse("owned station has no recoverable block item");
        if (session == null) return refuse("owned station recovery has no current server session");
        if (!hasRoomForStation()) return refuse("inventory has no room for the recovered station");
        if (config.stationRecoveryRange < 1) return refuse("station recovery range is not configured");

        BlockPos candidate = new BlockPos(position.getX(), position.getY(), position.getZ());
        if (resumePickup) {
            if (!isClearedTarget(candidate)) return refuse("pending owned station drop no longer matches its server removal receipt");
            if (!withinRecoveryRange(candidate)) return refuse("pending owned station drop is farther than the configured recovery range");
            try {
                if (!mayBreak.test(candidate)) return refuse("pending owned station drop is inside a protected claim");
            } catch (RuntimeException failure) {
                return refuse("pending owned station claim check failed: " + diagnostic(failure));
            }
            if (!originalContextSafe()) return refuse("inventory screen or cursor is not safe");
            recoverySession = session;
            ownerPlayer = client.player;
            ownerWorld = client.world;
            originalHandler = client.player.currentScreenHandler;
            originalScreen = currentScreen();
            if (System.nanoTime() - startedAtNanos >= MAX_DURATION_NANOS || pickupAttempts >= MAX_PICKUP_ATTEMPTS)
                return refuse("retained station pickup has no remaining original route budget");
            phase = Phase.PICKUP_REPLANNING;
            status = "retrying the exact owned " + stationKind.name + " drop from the same server session";
            return true;
        }
        if (!isExpectedBlock(candidate, block)) return refuse("recorded owned position is not a loaded matching station");
        if (!withinRecoveryRange(candidate)) {
            return refuse("owned station is farther than the configured recovery range");
        }
        try {
            if (!mayBreak.test(candidate)) return refuse("owned station is inside a protected claim");
        } catch (RuntimeException failure) {
            return refuse("owned station claim check failed: " + diagnostic(failure));
        }
        if (!originalContextSafe()) return refuse("inventory screen or cursor is not safe");

        ownedPosition = candidate;
        expectedBlock = block;
        ownerPlayer = client.player;
        ownerWorld = client.world;
        recoverySession = session;
        originalHandler = client.player.currentScreenHandler;
        originalScreen = currentScreen();
        startingNearbyDrops = Map.of();
        dropObservedCount = 0;
        startingInventoryReceipt = null;
        exactStationRemovalSequence = 0;
        dropObservedIncreaseSequence = -1;
        drop = null;
        miningStarted = contentsVerifiedEmpty = collectedBeforeDropObserved = false;
        startedAtNanos = System.nanoTime();

        try {
            movement.checkAirRecoveryOwnership();
            if (actions.hit(candidate) == null) {
                phase = Phase.APPROACH;
                status = "approaching the recorded owned " + stationKind.name;
                movement.startInteraction(candidate);
                return true;
            }
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "stopping movement before opening the owned " + stationKind.name;
            return true;
        } catch (RuntimeException failure) {
            throw abort("could not start owned station recovery: " + diagnostic(failure), failure);
        }
    }

    boolean active() {
        return phase == Phase.APPROACH || phase == Phase.APPROACH_STOPPING
                || phase == Phase.WAITING_FOR_HANDLER || phase == Phase.WAITING_FOR_CONTENTS
                || phase == Phase.CLOSING_HANDLER || phase == Phase.MINING
                || phase == Phase.PICKUP || phase == Phase.PICKUP_REPLANNING || phase == Phase.FINISHING;
    }

    boolean pickupIncomplete() { return phase == Phase.PICKUP_INCOMPLETE; }
    boolean pickupRetained() { return pickupIncomplete() && drop != null && drop.isAlive(); }

    boolean returnPending() {
        return ownedPosition != null && phase != Phase.COMPLETE && phase != Phase.IDLE
                && (phase == Phase.RETURN || phase == Phase.PICKUP_INCOMPLETE
                    || miningStarted || exactStationRemovalSequence > 0);
    }
    boolean observingReturn() { return phase == Phase.RETURN || phase == Phase.PICKUP_INCOMPLETE || phase == Phase.FINISHING; }
    void revokeRestoration() {
        restorationRevoked = true;
        unknownHandlerReturn |= phase == Phase.WAITING_FOR_HANDLER;
        if (returnPending() || stationHandler != null && phase != Phase.COMPLETE || unknownHandlerReturn)
            phase = exactStationRemovalSequence > 0 ? Phase.PICKUP_INCOMPLETE : Phase.RETURN;
    }
    boolean tickRetainedReturn(boolean restorationAllowed, boolean pickupAllowed) {
        if (!observingReturn() || !returnPending()) return false;
        if (client.player != ownerPlayer || client.world != ownerWorld || recoverySession == null
                || !observeSession().filter(recoverySession::equals).isPresent()
                || diagnosticConnection() != pickupDiagnostics.connection) return false;
        var network = client.getNetworkHandler();
        if (network == null || network.getConnection() == null || !network.getConnection().isOpen()) return false;
        if (miningStarted && exactStationRemovalSequence <= 0 && client.world != null
                && client.world.isChunkLoaded(ownedPosition) && client.world.getBlockState(ownedPosition).isAir()) {
            OptionalLong removal = observeRemovalSequence(ownedPosition);
            if (removal.isPresent() && removal.getAsLong() > 0) {
                exactStationRemovalSequence = removal.getAsLong();
                phase = Phase.PICKUP_INCOMPLETE;
            }
        }
        if (exactStationRemovalSequence > 0 && isClearedTarget(ownedPosition) && drop == null) {
            ItemEntity candidate = nearestBlockDrop();
            if (candidate != null) {
                Optional<PlacementProvenance.ServerInventoryReceipt> receipt = observeInventoryReceipt();
                if (receipt.isPresent()) {
                    drop = candidate;
                    dropObservedIncreaseSequence = receipt.get().increaseSequence();
                    dropObservedCount = drop.getStack().getCount() - startingNearbyDrops.getOrDefault(drop, 0);
                }
            } else if (hasInventoryGainAfterStationRemoval()) collectedBeforeDropObserved = true;
        }
        observeRetainedHandlerReceipt();
        boolean safe = originalContextSafe() && client.player.currentScreenHandler == originalHandler
                && currentScreen() == originalScreen;
        if (restorationAllowed && !restorationRevoked && !manualInput()) {
            movement.checkAirRecoveryOwnership();
            movement.stop();
            if (!movement.finishCancellation()) return false;
            if (phase == Phase.RETURN && ownsStationContext() && contentsVerifiedEmpty)
                closeOwnedEmptyHandler();
        }
        if (phase == Phase.RETURN && exactStationRemovalSequence <= 0 && !miningStarted) {
            if (safe && !unknownHandlerReturn && movement.travelReleased()) {
                phase = Phase.STOPPED;
                status = "owned station menu returned; station recovery remains unproved";
            }
            return false;
        }
        if (!safe || !isClearedTarget(ownedPosition)) return false;
        if (hasVerifiedInventoryGain() && dropWasCollected() && movement.travelReleased()) {
            ownerPlayer = ownerWorld = null;
            phase = Phase.COMPLETE;
            status = "recovered the owned " + stationKind.name + " after retained receipt settlement";
            return true;
        }
        if (pickupAllowed && restorationAllowed && !restorationRevoked && !manualInput()
                && System.nanoTime() - startedAtNanos < MAX_DURATION_NANOS
                && pickupAttempts < MAX_PICKUP_ATTEMPTS && drop != null && drop.isAlive() && !drop.isRemoved()
                && drop.getStack().isOf(blockItem) && hasRoomForStation() && withinRecoveryRange(ownedPosition)
                && unsafeContextReason() == null && mayBreak.test(ownedPosition)) {
            phase = Phase.PICKUP_REPLANNING;
            status = "continuing the exact station pickup with its original budget";
        } else status = "owned station RETURN barrier awaits exact receipt settlement or context abandonment";
        return false;
    }

    private void observeRetainedHandlerReceipt() {
        if (phase != Phase.RETURN || contentsVerifiedEmpty || miningStarted || stationKind == null || manualInput()) return;
        if (stationHandler == null) {
            ScreenHandler current = client.player.currentScreenHandler;
            if (current == originalHandler || currentScreen() == originalScreen
                    || currentScreen() == null || !stationKind.matches(current)
                    || !isExpectedTarget(ownedPosition)) return;
            stationHandler = current;
            stationScreen = currentScreen();
        }
        if (!ownsStationContext() || !stationHandler.getCursorStack().isEmpty()
                || !(stationHandler instanceof OwnedClickReceipts.Receipt receipt)
                || receipt.lodekeeper$contentsSequence() <= 0
                || receipt.lodekeeper$contentsSize() != stationHandler.slots.size()
                || receipt.lodekeeper$contentsRevision() != stationHandler.getRevision()
                || !receipt.lodekeeper$receivedCursor().isEmpty()
                || !stationKind.contentsEmpty(stationHandler)) return;
        for (int slot = 0; slot < stationKind.nativeSlotCount; slot++)
            if (!receipt.lodekeeper$receivedContentsSlot(slot).isEmpty() || !stationHandler.getSlot(slot).getStack().isEmpty()) return;
        contentsVerifiedEmpty = true;
        unknownHandlerReturn = false;
        status = "issued station open received its exact empty native contents; retaining menu RETURN";
    }

    boolean tick() {
        if (phase == Phase.COMPLETE) return true;
        if (!active()) return false;
        try {
            if (System.nanoTime() - startedAtNanos >= MAX_DURATION_NANOS && pickupInProgress()) {
                markPickupIncomplete("owned station pickup reached its 20 second limit");
                return false;
            }
            if (System.nanoTime() - startedAtNanos >= MAX_DURATION_NANOS) {
                throw new IllegalStateException("owned station recovery exceeded 20 seconds");
            }
            if (active() && !observeSession().filter(recoverySession::equals).isPresent()) {
                throw new MovementController.NavigationFailure(
                        MovementController.NavigationFailure.Kind.OWNERSHIP_LOST,
                        "Owned station recovery lost its server session");
            }
            String reason = unsafeContextReason();
            if (reason != null) throw new IllegalStateException(reason);
            movement.checkAirRecoveryOwnership();
            return switch (phase) {
                case APPROACH -> tickApproach();
                case APPROACH_STOPPING -> tickApproachStopping();
                case WAITING_FOR_HANDLER -> tickWaitingForHandler();
                case WAITING_FOR_CONTENTS -> tickWaitingForContents();
                case CLOSING_HANDLER -> tickClosingHandler();
                case MINING -> tickMining();
                case PICKUP -> tickPickup();
                case PICKUP_REPLANNING -> tickPickupReplanning();
                case FINISHING -> tickFinishing();
                default -> false;
            };
        } catch (RuntimeException failure) {
            if (phase == Phase.STOPPED) throw failure;
            if (failure instanceof MovementController.NavigationFailure lost
                    && lost.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) {
                revokeRestoration();
                status = diagnostic(failure);
                throw failure;
            }
            if (pickupInProgress() && drop != null && drop.isAlive()) {
                markPickupIncomplete(diagnostic(failure));
                return false;
            }
            throw abort("owned station recovery failed: " + diagnostic(failure), failure);
        }
    }

    void abandonSession() {
        clearOwnership();
        phase = Phase.STOPPED;
        status = "owned station cleanup abandoned after context replacement; recovery unproved";
    }

    void stop() {
        unknownHandlerReturn |= phase == Phase.WAITING_FOR_HANDLER;
        if (restorationRevoked && phase != Phase.COMPLETE) {
            if (returnPending() || stationHandler != null || unknownHandlerReturn)
                phase = exactStationRemovalSequence > 0 ? Phase.PICKUP_INCOMPLETE : Phase.RETURN;
            else { clearOwnership(); phase = Phase.STOPPED; }
            return;
        }
        if (unknownHandlerReturn) {
            cancelMovement();
            phase = Phase.RETURN;
            status = "issued station open has UNKNOWN return evidence; original actor retained";
            return;
        }
        if (returnPending()) {
            if (!restorationRevoked && !observingReturn()) { actions.cancel(); cancelMovement(); }
            phase = exactStationRemovalSequence > 0 ? Phase.PICKUP_INCOMPLETE : Phase.RETURN;
            return;
        }
        if (phase == Phase.PICKUP_INCOMPLETE) return;
        if (pickupInProgress() && drop != null && drop.isAlive()) {
            markPickupIncomplete("cleanup stopped before the exact station drop was collected");
            return;
        }
        if (!active()) {
            clearOwnership();
            return;
        }
        actions.cancel();
        if (phase != Phase.CLOSING_HANDLER && !manualInput()) closeOwnedEmptyHandler();
        boolean handlerLeftOpen = ownsStationContext();
        boolean cancelled = cancelMovement();
        if (handlerLeftOpen) {
            phase = Phase.RETURN;
            status = "owned station menu RETURN debt retained for inspection";
            return;
        }
        clearOwnership();
        phase = Phase.STOPPED;
        status = cancelled ? "owned station recovery stopped"
                : "owned station recovery stopped; movement cancellation remains pending";
        if (handlerLeftOpen) status += "; owned station handler left open for inspection";
    }

    String status() { return status; }
    BlockPos position() { return ownedPosition; }
    Block block() { return expectedBlock; }

    private boolean tickApproach() {
        requireExpectedTarget();
        requireOriginalContext();
        if (!withinRecoveryRange(ownedPosition)) {
            throw new IllegalStateException("owned station approach moved beyond the configured recovery range");
        }
        if (actions.hit(ownedPosition) != null) {
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "stopping movement before opening the owned " + stationKind.name;
            return false;
        }
        if (movement.tick()) {
            movement.stop();
            phase = Phase.APPROACH_STOPPING;
            status = "checking reach after approaching the owned " + stationKind.name;
        }
        return false;
    }

    private boolean tickApproachStopping() {
        requireExpectedTarget();
        requireOriginalContext();
        if (!withinRecoveryRange(ownedPosition)) {
            throw new IllegalStateException("owned station approach moved beyond the configured recovery range");
        }
        if (!movement.finishCancellation()) return false;
        if (actions.hit(ownedPosition) == null) {
            throw new IllegalStateException("movement stopped without reach to the owned station");
        }
        openOwnedHandler();
        return false;
    }

    private void openOwnedHandler() {
        requireExpectedTarget();
        requireOriginalContext();
        if (!actions.use(ownedPosition)) throw new IllegalStateException("native station open was refused");
        phase = Phase.WAITING_FOR_HANDLER;
        status = "waiting for the owned " + stationKind.name + " handler";
    }

    private boolean tickWaitingForHandler() {
        requireExpectedTarget();
        if (client.player.currentScreenHandler == originalHandler && currentScreen() == originalScreen) {
            status = "waiting for the server to open the owned " + stationKind.name;
            return false;
        }
        ScreenHandler current = client.player.currentScreenHandler;
        if (!stationKind.matches(current) || currentScreen() == null) {
            throw new IllegalStateException("station open changed the player's handler unexpectedly; leaving it open");
        }
        stationHandler = current;
        stationScreen = currentScreen();
        phase = Phase.WAITING_FOR_CONTENTS;
        return tickWaitingForContents();
    }

    private boolean tickWaitingForContents() {
        requireExpectedTarget();
        requireStationContext();
        if (!stationHandler.getCursorStack().isEmpty()) {
            throw new IllegalStateException("station cursor is occupied; leaving the owned handler open");
        }
        if (!(stationHandler instanceof OwnedClickReceipts.Receipt receipt)) {
            throw new IllegalStateException("owned station handler has no server contents receipt; leaving it open");
        }
        if (receipt.lodekeeper$contentsSequence() <= 0) {
            status = "waiting for the server contents receipt from the owned " + stationKind.name;
            return false;
        }
        if (!stationKind.contentsEmpty(stationHandler)) {
            throw new IllegalStateException("owned " + stationKind.name + " contains items; leaving it open without moving them");
        }
        contentsVerifiedEmpty = true;
        if (!closeOwnedEmptyHandler()) {
            throw new IllegalStateException("could not safely close the verified empty owned station handler");
        }
        phase = Phase.CLOSING_HANDLER;
        status = "closing the verified empty owned " + stationKind.name + " handler";
        return false;
    }

    private boolean tickClosingHandler() {
        requireExpectedTarget();
        if (client.player.currentScreenHandler == originalHandler && currentScreen() == originalScreen) {
            phase = Phase.MINING;
            status = "mining the verified empty owned " + stationKind.name;
            return tickMining();
        }
        if (ownsStationContext()) {
            requireStationContentsStillEmpty();
            status = "waiting for the owned station handler to close";
            return false;
        }
        throw new IllegalStateException("station handler changed during close; leaving the new handler open");
    }

    private boolean tickMining() {
        requireOriginalContext();
        if (miningStarted && client.world.isChunkLoaded(ownedPosition)
                && client.world.getBlockState(ownedPosition).isAir()) {
            actions.cancel();
            OptionalLong removalSequence = observeRemovalSequence(ownedPosition);
            if (removalSequence.isEmpty()) {
                status = "waiting for the server station removal receipt";
                return false;
            }
            exactStationRemovalSequence = removalSequence.getAsLong();
            phase = Phase.PICKUP;
            status = "checking for the owned " + stationKind.name + " drop";
            return false;
        }
        requireExpectedTarget();
        if (!withinRecoveryRange(ownedPosition)) {
            throw new IllegalStateException("owned station is beyond the configured recovery range");
        }
        if (actions.hit(ownedPosition) == null) {
            throw new IllegalStateException("owned station is no longer reachable");
        }
        boolean allowed;
        try {
            allowed = mayBreak.test(ownedPosition);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("owned station claim check failed: " + diagnostic(failure), failure);
        }
        if (!allowed) throw new IllegalStateException("owned station is inside a protected claim");
        if (!hasRoomForStation()) throw new IllegalStateException("inventory has no room for the recovered station");
        if (!miningStarted) {
            startingNearbyDrops = snapshotNearbyDrops(ownedPosition);
            Optional<PlacementProvenance.ServerInventoryReceipt> baseline = observeInventoryReceipt();
            if (baseline.isEmpty()) {
                status = "waiting for the server inventory baseline before mining the owned " + stationKind.name;
                return false;
            }
            startingInventoryReceipt = baseline.get();
            miningStarted = true;
        }
        if (!actions.mine(ownedPosition, null)) {
            throw new IllegalStateException("native mining refused: " + actions.mineFailure());
        }
        return false;
    }

    private boolean tickPickup() {
        requireClearedTarget();
        requireOriginalContext();
        if (!miningStarted) throw new IllegalStateException("owned station disappeared before recovery started mining it");
        if (drop == null) {
            ItemEntity candidate = nearestBlockDrop();
            if (candidate == null) {
                if (hasInventoryGainAfterStationRemoval()) {
                    collectedBeforeDropObserved = true;
                    return startFinishing();
                }
                if (hasInventoryGain()) {
                    status = "server inventory increase predates the owned station removal; waiting for the exact drop";
                    return false;
                }
                status = "waiting for the owned " + stationKind.name + " drop";
                return false;
            }
            Optional<PlacementProvenance.ServerInventoryReceipt> receipt = observeInventoryReceipt();
            if (receipt.isEmpty()) {
                status = "waiting for the server inventory receipt before collecting the exact drop";
                return false;
            }
            drop = candidate;
            dropObservedIncreaseSequence = receipt.get().increaseSequence();
            dropObservedCount = drop.getStack().getCount() - startingNearbyDrops.getOrDefault(drop, 0);
            status = "collecting the owned " + stationKind.name + " drop";
            pickupAttempts = 1;
            startOwnedPickup();
            return false;
        }
        boolean collectedDrop = dropWasCollected();
        if (hasInventoryGainAfterDropObservation() && collectedDrop) {
            return startFinishing();
        }
        if (collectedDrop) {
            status = "waiting for the server inventory receipt for the exact owned station drop";
            return false;
        }
        sampleStalledPickup();
        try {
            if (!movement.tick()) return false;
        } catch (MovementController.NavigationFailure failure) {
            if (failure.kind == MovementController.NavigationFailure.Kind.OWNERSHIP_LOST) {
                revokeRestoration();
                status = diagnostic(failure);
                throw failure;
            }
            if (failure.kind == MovementController.NavigationFailure.Kind.PROCESS_ENDED
                    && pickupAttempts < MAX_PICKUP_ATTEMPTS) {
                logPickupDiagnostic("replan", diagnostic(failure), true);
                movement.stop();
                phase = Phase.PICKUP_REPLANNING;
                status = "replanning the exact owned " + stationKind.name + " drop after a failed native route";
                return false;
            }
            markPickupIncomplete(diagnostic(failure));
            return false;
        }
        collectedDrop = dropWasCollected();
        if (hasInventoryGainAfterDropObservation() && collectedDrop) return startFinishing();
        if (collectedDrop) {
            status = "waiting for the server inventory receipt for the exact owned station drop";
            return false;
        }
        if (pickupAttempts < MAX_PICKUP_ATTEMPTS) {
            phase = Phase.PICKUP_REPLANNING;
            status = "continuing pickup of the remaining exact owned " + stationKind.name + " drop";
        } else {
            markPickupIncomplete("owned station drop was only partially collected after bounded retries");
        }
        return false;
    }

    private boolean tickPickupReplanning() {
        requireClearedTarget();
        requireOriginalContext();
        if (drop == null) {
            markPickupIncomplete("exact owned station drop is no longer available");
            return false;
        }
        if (dropWasCollected()) {
            phase = Phase.PICKUP;
            status = "waiting for the server inventory receipt for the exact owned station drop";
            return false;
        }
        if (!hasRoomForStation()) {
            markPickupIncomplete("inventory has no room for the recovered station");
            return false;
        }
        if (!movement.finishCancellation()) {
            status = "finishing the failed pickup route before replanning";
            return false;
        }
        if (pickupAttempts >= MAX_PICKUP_ATTEMPTS) {
            markPickupIncomplete("owned station pickup exhausted its bounded route retries");
            return false;
        }
        pickupAttempts++;
        startOwnedPickup();
        phase = Phase.PICKUP;
        status = "retrying the exact owned " + stationKind.name + " drop";
        return false;
    }

    private Optional<OwnedStationLedger.Session> observeSession() {
        Optional<OwnedStationLedger.Session> session = currentSession.get();
        pickupDiagnostics.observedSession = session.orElse(null);
        return session;
    }

    private Optional<PlacementProvenance.ServerInventoryReceipt> observeInventoryReceipt() {
        Optional<PlacementProvenance.ServerInventoryReceipt> receipt = inventoryReceipt.apply(blockItem);
        pickupDiagnostics.inventoryReceipt = receipt.orElse(null);
        pickupDiagnostics.inventoryObservedNanos = System.nanoTime();
        return receipt;
    }

    private OptionalLong observeRemovalSequence(BlockPos position) {
        OptionalLong sequence = serverRemovalSequence.apply(position);
        pickupDiagnostics.removalSequence = sequence;
        pickupDiagnostics.removalObservedNanos = System.nanoTime();
        return sequence;
    }

    private Object diagnosticConnection() {
        var handler = client.getNetworkHandler();
        return handler == null ? null : handler.getConnection();
    }

    private boolean diagnosticContextMatches() {
        return client.player != null && client.player == ownerPlayer && client.world != null
                && client.world == ownerWorld && recoverySession != null
                && recoverySession.equals(pickupDiagnostics.observedSession)
                && pickupDiagnostics.connection != null
                && pickupDiagnostics.connection == diagnosticConnection();
    }

    private void startOwnedPickup() {
        pickupDiagnostics.samples = 0;
        pickupDiagnostics.terminalLogged = false;
        pickupDiagnostics.stallAnchorNanos = pickupDiagnostics.lastSampleNanos = System.nanoTime();
        pickupDiagnostics.anchorX = client.player.getX();
        pickupDiagnostics.anchorY = client.player.getY();
        pickupDiagnostics.anchorZ = client.player.getZ();
        movement.startOwnedPickup(drop, recoverySession, MovementController.RouteEffects.MOVEMENT_ONLY);
        logPickupDiagnostic("start", "exact drop pinned", false);
    }

    private void sampleStalledPickup() {
        if (!config.debugLogging || pickupDiagnostics.samples >= 8 || !diagnosticContextMatches()) return;
        long now = System.nanoTime();
        double dx = client.player.getX() - pickupDiagnostics.anchorX;
        double dy = client.player.getY() - pickupDiagnostics.anchorY;
        double dz = client.player.getZ() - pickupDiagnostics.anchorZ;
        if (dx * dx + dy * dy + dz * dz >= 0.0025) {
            pickupDiagnostics.anchorX = client.player.getX();
            pickupDiagnostics.anchorY = client.player.getY();
            pickupDiagnostics.anchorZ = client.player.getZ();
            pickupDiagnostics.stallAnchorNanos = now;
        }
        if (now - pickupDiagnostics.stallAnchorNanos < 1_000_000_000L
                || now - pickupDiagnostics.lastSampleNanos < 1_000_000_000L) return;
        pickupDiagnostics.lastSampleNanos = now;
        pickupDiagnostics.samples++;
        logPickupDiagnostic("stalled", "player displacement below 0.05 blocks", false);
    }

    private String diagnosticStationBlock() {
        if (!diagnosticContextMatches()) return "unavailable-context";
        if (ownedPosition == null || !client.world.isChunkLoaded(ownedPosition)) return "unloaded";
        return client.world.getBlockState(ownedPosition).getBlock().toString();
    }

    private String diagnosticDropAndPlayer() {
        if (!diagnosticContextMatches()) return "contextMatches=false entity=unavailable localHeldCount=unavailable";
        var player = client.player;
        return "contextMatches=true entityUUID=" + (drop == null ? "none" : drop.getUuid())
                + " entityItem=" + (drop == null ? "none" : drop.getStack().getItem())
                + " entityCount=" + (drop == null ? 0 : drop.getStack().getCount())
                + " entityAlive=" + (drop != null && drop.isAlive())
                + " entityRemoved=" + (drop != null && drop.isRemoved())
                + " entityXYZ=" + (drop == null ? "none" : drop.getX() + "," + drop.getY() + "," + drop.getZ())
                + " entityBounds=" + (drop == null ? "none" : drop.getBoundingBox())
                + " playerXYZ=" + player.getX() + "," + player.getY() + "," + player.getZ()
                + " playerBounds=" + player.getBoundingBox()
                + " distance=" + (drop == null ? "unavailable" : Math.sqrt(drop.squaredDistanceTo(player)))
                + " localHeldCount=" + (blockItem == null ? "unavailable" : actions.count(blockItem));
    }

    private void logPickupDiagnostic(String event, String reason, boolean terminal) {
        if (!config.debugLogging || terminal && pickupDiagnostics.terminalLogged) return;
        if (terminal) pickupDiagnostics.terminalLogged = true;
        long now = System.nanoTime();
        boolean sameContext = diagnosticContextMatches();
        var receipt = sameContext ? pickupDiagnostics.inventoryReceipt : null;
        String flatReason = reason.replace('\n', ' ').replace('\r', ' ');
        if (flatReason.length() > 180) flatReason = flatReason.substring(0, 180);
        org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                "[Lodekeeper] OWNED_PICKUP event={} phase={} attempt={} sample={} reason={} position={} currentBlock={} expectedItem={} baselineCount={} baselineIncreaseSequence={} expectedCount={} observedDropIncreaseCount={} dropObservedIncreaseSequence={} completeMatchingServerReceiptObserved={} observedServerCount={} observedServerIncreaseSequence={} inventoryObservationAgeMs={} expectedRemovalSequence={} currentRemovalSequenceObserved={} removalObservationAgeMs={} {} {}",
                event, phase, pickupAttempts, pickupDiagnostics.samples, flatReason, ownedPosition,
                diagnosticStationBlock(), blockItem,
                startingInventoryReceipt == null ? "unavailable" : startingInventoryReceipt.count(),
                startingInventoryReceipt == null ? "unavailable" : startingInventoryReceipt.increaseSequence(),
                startingInventoryReceipt == null ? "unavailable" : startingInventoryReceipt.count() + Math.max(1, dropObservedCount),
                dropObservedCount, dropObservedIncreaseSequence, receipt != null,
                receipt == null ? "unavailable" : receipt.count(),
                receipt == null ? "unavailable" : receipt.increaseSequence(),
                pickupDiagnostics.inventoryObservedNanos == 0 ? -1 : (now - pickupDiagnostics.inventoryObservedNanos) / 1_000_000L,
                exactStationRemovalSequence,
                sameContext && pickupDiagnostics.removalSequence.isPresent()
                        ? pickupDiagnostics.removalSequence.getAsLong() : "unavailable",
                pickupDiagnostics.removalObservedNanos == 0 ? -1 : (now - pickupDiagnostics.removalObservedNanos) / 1_000_000L,
                diagnosticDropAndPlayer(), sameContext ? movement.ownedPickupDiagnostic(drop, recoverySession)
                        : "nativeContextMatches=false");
    }

    private boolean pickupInProgress() {
        return phase == Phase.PICKUP || phase == Phase.PICKUP_REPLANNING;
    }

    private void markPickupIncomplete(String reason) {
        logPickupDiagnostic("incomplete", reason, true);
        actions.cancel();
        cancelMovement();
        phase = Phase.PICKUP_INCOMPLETE;
        status = drop != null && drop.isAlive()
                ? "station cleanup incomplete; exact " + stationKind.name
                        + " drop retained for a later cleanup run: " + reason
                : "station cleanup incomplete; exact " + stationKind.name
                        + " drop has no matching inventory and entity receipts: " + reason;
    }

    private boolean tickFinishing() {
        requireClearedTarget();
        requireOriginalContext();
        actions.cancel();
        movement.stop();
        if (!movement.finishCancellation()) {
            status = "station item gained; finishing movement cancellation";
            return false;
        }
        if (!hasVerifiedInventoryGain() || !dropWasCollected()) {
            throw new IllegalStateException("owned station recovery lost its inventory or entity receipt");
        }
        ownerPlayer = ownerWorld = null;
        phase = Phase.COMPLETE;
        status = "recovered the owned " + stationKind.name;
        return true;
    }

    private boolean startFinishing() {
        actions.cancel();
        movement.stop();
        phase = Phase.FINISHING;
        status = "station item gained; confirming safe movement cancellation";
        return tickFinishing();
    }

    private ItemEntity nearestBlockDrop() {
        double x = ownedPosition.getX() + 0.5;
        double y = ownedPosition.getY() + 0.5;
        double z = ownedPosition.getZ() + 0.5;
        Box area = new Box(x - DROP_RADIUS, y - DROP_RADIUS, z - DROP_RADIUS,
                x + DROP_RADIUS, y + DROP_RADIUS, z + DROP_RADIUS);
        return client.world.getEntitiesByClass(ItemEntity.class, area, item ->
                        item.isAlive() && item.getStack().isOf(blockItem)
                                && item.getStack().getCount() > startingNearbyDrops.getOrDefault(item, 0))
                .stream()
                .min(Comparator.comparingDouble(item -> item.squaredDistanceTo(x, y, z)))
                .orElse(null);
    }

    private Map<ItemEntity, Integer> snapshotNearbyDrops(BlockPos position) {
        double x = position.getX() + 0.5;
        double y = position.getY() + 0.5;
        double z = position.getZ() + 0.5;
        Box area = new Box(x - DROP_RADIUS, y - DROP_RADIUS, z - DROP_RADIUS,
                x + DROP_RADIUS, y + DROP_RADIUS, z + DROP_RADIUS);
        Map<ItemEntity, Integer> counts = new IdentityHashMap<>();
        for (ItemEntity item : client.world.getEntitiesByClass(ItemEntity.class, area, candidate ->
                candidate.isAlive() && candidate.getStack().isOf(blockItem))) {
            counts.put(item, item.getStack().getCount());
        }
        return counts;
    }

    private boolean dropWasCollected() {
        return drop == null ? collectedBeforeDropObserved : !drop.isAlive()
                || dropObservedCount > 0 && drop.getStack().isOf(blockItem)
                && drop.getStack().getCount() <= startingNearbyDrops.getOrDefault(drop, 0);
    }

    private boolean hasRoomForStation() {
        if (client.player == null || blockItem == null) return false;
        for (int slot = 0; slot < 36; slot++) {
            var stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty() || stack.isOf(blockItem) && stack.getCount() < stack.getMaxCount()) return true;
        }
        return false;
    }

    private Optional<PlacementProvenance.ServerInventoryReceipt> inventoryGain() {
        if (startingInventoryReceipt == null) return Optional.empty();
        return observeInventoryReceipt().filter(receipt ->
                receipt.count() > startingInventoryReceipt.count()
                        && receipt.increaseSequence() > startingInventoryReceipt.increaseSequence());
    }

    private boolean hasInventoryGain() { return inventoryGain().isPresent(); }

    private boolean hasInventoryGainAfterStationRemoval() {
        Optional<PlacementProvenance.ServerInventoryReceipt> receipt = inventoryGain();
        return exactStationRemovalSequence > 0 && receipt.isPresent()
                && receipt.get().increaseSequence() > exactStationRemovalSequence;
    }

    private boolean hasInventoryGainAfterDropObservation() {
        Optional<PlacementProvenance.ServerInventoryReceipt> receipt = inventoryGain();
        return dropObservedCount > 0 && dropObservedIncreaseSequence >= 0 && receipt.isPresent()
                && receipt.get().count() >= startingInventoryReceipt.count() + dropObservedCount
                && receipt.get().increaseSequence() > dropObservedIncreaseSequence;
    }

    private boolean hasVerifiedInventoryGain() {
        return dropObservedIncreaseSequence >= 0
                ? hasInventoryGainAfterDropObservation() : hasInventoryGainAfterStationRemoval();
    }

    private boolean isExpectedBlock(BlockPos position, Block block) {
        return client.world != null && client.world.isChunkLoaded(position)
                && client.world.getBlockState(position).isOf(block);
    }

    private boolean isExpectedTarget(BlockPos position) {
        return isExpectedBlock(position, expectedBlock);
    }

    private void requireExpectedTarget() {
        if (!isExpectedTarget(ownedPosition)) {
            throw new IllegalStateException("recorded owned station changed or unloaded before it was mined");
        }
    }

    private void requireClearedTarget() {
        OptionalLong removalSequence = observeRemovalSequence(ownedPosition);
        if (exactStationRemovalSequence <= 0 || removalSequence.isEmpty()
                || removalSequence.getAsLong() != exactStationRemovalSequence) {
            throw new IllegalStateException("owned station removal receipt changed during recovery"
                    + " expectedSequence=" + exactStationRemovalSequence
                    + " currentSequence=" + (removalSequence.isPresent() ? removalSequence.getAsLong() : "unavailable")
                    + " position=" + ownedPosition + " currentBlock=" + diagnosticStationBlock()
                    + " " + diagnosticDropAndPlayer());
        }
        if (client.world == null || !client.world.isChunkLoaded(ownedPosition)
                || !client.world.getBlockState(ownedPosition).isAir()) {
            throw new IllegalStateException("owned station position changed or unloaded after mining");
        }
    }

    private boolean isClearedTarget(BlockPos position) {
        OptionalLong removalSequence = observeRemovalSequence(position);
        return exactStationRemovalSequence > 0 && removalSequence.isPresent()
                && removalSequence.getAsLong() == exactStationRemovalSequence
                && client.world != null && client.world.isChunkLoaded(position)
                && client.world.getBlockState(position).isAir();
    }

    private boolean withinRecoveryRange(BlockPos position) {
        int range = config.stationRecoveryRange;
        if (range < 1 || client.player == null) return false;
        double dx = position.getX() + 0.5 - client.player.getX();
        double dy = position.getY() + 0.5 - client.player.getY();
        double dz = position.getZ() + 0.5 - client.player.getZ();
        return dx * dx + dy * dy + dz * dz <= (double) range * range;
    }

    private String unsafeContextReason() {
        if (client.player == null || client.world == null || client.interactionManager == null) return "world unavailable";
        if (active() && (ownerPlayer != client.player || ownerWorld != client.world)) {
            return "player or world changed during owned station recovery";
        }
        if (manualInput()) return "manual player input has priority";
        var player = client.player;
        if (!player.isAlive() || player.getAbilities().creativeMode || player.isSpectator()) {
            return "owned station recovery requires survival play";
        }
        if (player.isSubmergedInWater()) return "owned station recovery waits for breathable air";
        if (!config.allowBreaking) return "block breaking is disabled";
        return null;
    }

    private boolean originalContextSafe() {
        return client.currentScreen == null && client.player.currentScreenHandler == client.player.playerScreenHandler
                && client.player.currentScreenHandler.getCursorStack().isEmpty();
    }

    private void requireOriginalContext() {
        if (client.player.currentScreenHandler != originalHandler || currentScreen() != originalScreen
                || !client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            throw new IllegalStateException("player handler or cursor changed; leaving the current screen open");
        }
    }

    private boolean ownsStationContext() {
        return stationHandler != null && client.player.currentScreenHandler == stationHandler
                && currentScreen() == stationScreen;
    }

    private void requireStationContext() {
        if (!ownsStationContext()) {
            throw new IllegalStateException("owned station handler or screen changed; leaving the current screen open");
        }
    }

    private void requireStationContentsStillEmpty() {
        requireStationContext();
        if (!contentsVerifiedEmpty || !stationHandler.getCursorStack().isEmpty()
                || !stationKind.contentsEmpty(stationHandler)) {
            throw new IllegalStateException("owned station contents changed; leaving the handler open");
        }
    }

    private boolean closeOwnedEmptyHandler() {
        if (!ownsStationContext() || !contentsVerifiedEmpty) return false;
        try {
            requireStationContentsStillEmpty();
            client.player.closeHandledScreen();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Object currentScreen() { return client.currentScreen; }

    private boolean manualInput() {
        var options = client.options;
        return options.attackKey.isPressed() || options.useKey.isPressed()
                || options.forwardKey.isPressed() || options.backKey.isPressed()
                || options.leftKey.isPressed() || options.rightKey.isPressed()
                || options.jumpKey.isPressed() || options.sneakKey.isPressed() || options.sprintKey.isPressed();
    }

    private boolean refuse(String reason) {
        logPickupDiagnostic("refusal", reason, true);
        if (!returnPending()) phase = Phase.STOPPED;
        status = reason.length() > 180 ? reason.substring(0, 180) : reason;
        return false;
    }

    private IllegalStateException abort(String reason, RuntimeException cause) {
        logPickupDiagnostic("abort", reason, true);
        actions.cancel();
        boolean cancelled = cancelMovement();
        unknownHandlerReturn |= phase == Phase.WAITING_FOR_HANDLER;
        phase = exactStationRemovalSequence > 0 ? Phase.PICKUP_INCOMPLETE
                : miningStarted || ownsStationContext() || unknownHandlerReturn ? Phase.RETURN : Phase.STOPPED;
        if (!cancelled) reason += "; movement cancellation remains pending";
        status = reason.length() > 180 ? reason.substring(0, 180) : reason;
        return new IllegalStateException(status, cause);
    }

    private void clearOwnership() {
        stationKind = null;
        expectedBlock = null;
        blockItem = null;
        ownedPosition = null;
        ownerPlayer = ownerWorld = null;
        originalHandler = stationHandler = null;
        originalScreen = stationScreen = null;
        drop = null;
        startingNearbyDrops = Map.of();
        dropObservedCount = 0;
        startingInventoryReceipt = null;
        exactStationRemovalSequence = 0;
        dropObservedIncreaseSequence = -1;
        recoverySession = null;
        restorationRevoked = unknownHandlerReturn = false;
        pickupAttempts = 0;
        miningStarted = contentsVerifiedEmpty = collectedBeforeDropObserved = false;
    }

    private boolean cancelMovement() {
        try {
            movement.checkAirRecoveryOwnership();
            movement.stop();
            return movement.finishCancellation();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String diagnostic(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
