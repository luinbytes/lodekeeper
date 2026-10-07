package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.BlockId;
import dev.lodekeeper.core.InventorySnapshot;
import dev.lodekeeper.core.ItemId;
import dev.lodekeeper.core.RestorationQueue;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Keeps owned break intents separate from received server cell state. */
final class BackfillController {
    private static final Set<Block> SIMPLE_BLOCKS = Set.of(Blocks.STONE, Blocks.COBBLESTONE,
            Blocks.DIRT, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE, Blocks.NETHERRACK, Blocks.END_STONE);
    private record BreakIntent(RestorationQueue.Session session, long job, long policyEpoch,
                               long afterSequence, long expires,
                               dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.Session nativeSession,
                               long nativePolicyGeneration, BlockState original,
                               RestorationQueue.RestorableBlock material) { }
    private final Minecraft client;
    private final LodekeeperConfig config;
    private final WorldProtection protection;
    private final PlayerActions actions;
    private final PlacementProvenance provenance;
    private final LongSupplier currentJob;
    private final Map<BlockPos, BreakIntent> intents = new LinkedHashMap<>();
    private final Map<BlockPos, RestorationQueue.ConfirmedBreakReceipt> cells = new LinkedHashMap<>();
    private RestorationQueue queue;
    private RestorationQueue.PlacementReservation sent;
    private long sentAfterSequence;
    private long ticks;

    BackfillController(Minecraft client, LodekeeperConfig config, WorldProtection protection,
                       PlayerActions actions, PlacementProvenance provenance, LongSupplier currentJob) {
        this.client = client;
        this.config = config;
        this.protection = protection;
        this.actions = actions;
        this.provenance = provenance;
        this.currentJob = currentJob;
        actions.attachBackfill(this);
        provenance.observeServerBlocks(this::serverBlock);
    }

    void tick() {
        ticks = Math.addExact(ticks, 1);
        refreshSession();
        long job = currentJob.getAsLong();
        long epoch = protection.capture().epoch();
        intents.values().removeIf(intent -> intent.job() != job || intent.policyEpoch() != epoch
                || ticks >= intent.expires());
        if (!config.backfill || GameApi.screen(client) != null) intents.clear();
    }

    private boolean refreshSession() {
        var binding = provenance.session().orElse(null);
        if (binding == null) {
            queue = null;
            sent = null;
            intents.clear();
            cells.clear();
            return false;
        }
        var session = new RestorationQueue.Session(binding.worldScope(), binding.generation());
        if (queue == null || !queue.currentSession().equals(session)) {
            queue = new RestorationQueue(session, config.backfillPendingLimit);
            sent = null;
            intents.clear();
            cells.clear();
        }
        return true;
    }

    void beforeOwnedBreak(BlockPos position) {
        long job = currentJob.getAsLong();
        if (!config.backfill || job <= 0 || GameApi.screen(client) != null || !refreshSession()
                || !protection.mayBreak(position) || !protection.mayPlace(position)) return;
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        var nativeSession = owner == null ? null : owner.captureSession();
        if (owner == null || !owner.isCurrent(nativeSession) || nativeSession.world() != client.level
                || !dev.lodekeeper.navigation.kernel.OwnedMutationGuard.executeBreak(owner, position)) return;
        BlockState original = client.level.getBlockState(position);
        if (!safeMaterial(position, original)) return;
        BreakIntent previous = intents.get(position);
        if (previous != null && previous.job() == job && previous.original().equals(original)) return;
        if (intents.size() >= queue.capacity()) return;
        BlockId block = BlockId.parse(BuiltInRegistries.BLOCK.getKey(original.getBlock()).toString());
        ItemId item = ItemId.parse(BuiltInRegistries.ITEM.getKey(original.getBlock().asItem()).toString());
        intents.put(position.immutable(), new BreakIntent(queue.currentSession(), job,
                protection.capture().epoch(), provenance.serverReceiptSequence(), ticks + 40,
                nativeSession, owner.policyGeneration(), original, new RestorationQueue.RestorableBlock(block, item)));
    }

    private boolean safeMaterial(BlockPos position, BlockState state) {
        Block block = state.getBlock();
        return SIMPLE_BLOCKS.contains(block) && state.getProperties().isEmpty()
                && state.getFluidState().isEmpty() && !state.hasBlockEntity()
                && state.equals(block.defaultBlockState()) && block.asItem() instanceof BlockItem item
                && item.getBlock() == block
                && BuiltInRegistries.BLOCK.getKey(block).equals(BuiltInRegistries.ITEM.getKey(item));
    }

    private void serverBlock(PlacementProvenance.ServerBlockReceipt received) {
        if (!refreshSession()) return;
        var session = new RestorationQueue.Session(received.session().worldScope(), received.session().generation());
        if (!session.equals(queue.currentSession())) return;
        BlockPos position = received.position();
        BlockState state = received.state();
        var cell = cells.get(position);
        if (cell != null && received.sequence() > cell.revision() && !dryAir(state)) {
            boolean confirmed = sent != null && sent.receipt() == cell
                    && received.sequence() > sentAfterSequence
                    && state.equals(BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(sent.blockToPlace().toString())).defaultBlockState())
                    && queue.confirmServerPlacement(session, sent,
                    BlockId.parse(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()), received.sequence())
                    == RestorationQueue.PlacementStatus.CONFIRMED;
            if (confirmed || queue.retireAfterServerCellChanged(session, cell, received.sequence())) {
                cells.remove(position);
                if (sent != null && sent.receipt() == cell) sent = null;
            }
        }
        BreakIntent intent = intents.get(position);
        var owner = dev.lodekeeper.navigation.kernel.OwnedKernelRuntime.current();
        if (intent == null || owner == null || !owner.isCurrent(intent.nativeSession())
                || owner.policyGeneration() != intent.nativePolicyGeneration() || !intent.session().equals(session) || intent.job() != currentJob.getAsLong()
                || intent.policyEpoch() != protection.capture().epoch() || ticks >= intent.expires()
                || received.sequence() <= intent.afterSequence()) return;
        if (!dryAir(state)) {
            if (!state.equals(intent.original())) intents.remove(position);
            return;
        }
        intents.remove(position);
        var receipt = new RestorationQueue.ConfirmedBreakReceipt(session, received.sequence(),
                new RestorationQueue.BlockPosition(position.getX(), position.getY(), position.getZ()), intent.material());
        var result = queue.recordConfirmedBreak(receipt);
        if (result == RestorationQueue.ReceiptStatus.RECORDED || result == RestorationQueue.ReceiptStatus.REPLACED) {
            cells.put(position, receipt);
            if (sent != null && sent.receipt().position().equals(receipt.position())) sent = null;
        }
    }

    dev.lodekeeper.nav.NavigationSceneSnapshot.Marker[] visualizationMarkers() {
        if (!config.backfill || !refreshSession() || client.player == null)
            return new dev.lodekeeper.nav.NavigationSceneSnapshot.Marker[0];
        var player = client.player;
        return cells.values().stream()
                .sorted(java.util.Comparator.comparingDouble(receipt -> {
                    var p = receipt.position();
                    double dx = p.x() + 0.5 - player.getX();
                    double dy = p.y() + 0.5 - player.getY();
                    double dz = p.z() + 0.5 - player.getZ();
                    return dx * dx + dy * dy + dz * dz;
                }))
                .limit(dev.lodekeeper.nav.NavigationSceneSnapshot.MAX_MARKERS)
                .map(receipt -> {
                    var p = receipt.position();
                    return sent != null && sent.receipt() == receipt
                            ? dev.lodekeeper.nav.NavigationSceneSnapshot.Marker.backfillMatched(p.x(), p.y(), p.z())
                            : dev.lodekeeper.nav.NavigationSceneSnapshot.Marker.backfillPendingMatch(p.x(), p.y(), p.z());
                }).toArray(dev.lodekeeper.nav.NavigationSceneSnapshot.Marker[]::new);
    }

    Map<ItemId, Integer> inFlightReservationCounts() {
        return refreshSession() ? queue.inFlightReservationCounts() : Map.of();
    }

    void restoreIdle(InventorySnapshot combinedProtectedStock) {
        if (!config.backfill || !config.allowBuilding || !refreshSession() || sent != null
                || client.player == null || GameApi.screen(client) != null || !client.player.isAlive()
                || client.player.containerMenu != client.player.inventoryMenu
                || !client.player.containerMenu.getCarried().isEmpty()
                || client.player.getAbilities().instabuild || client.player.isSpectator() || client.player.isPassenger() || client.player.isUsingItem()
                || !provenance.confirmedInventoryReady()) return;
        for (var receipt : queue.pendingForProbe(queue.currentSession(), 8)) {
            var cell = receipt.position();
            BlockPos destination = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!protection.mayPlace(destination) || !dryAir(client.level.getBlockState(destination))) continue;
            Vec3 offset = Vec3.atCenterOf(destination).subtract(client.player.position());
            Vec3 forward = client.player.getViewVector(1.0f);
            if (offset.x * forward.x + offset.z * forward.z >= -0.5
                    || destination.distSqr(client.player.blockPosition()) > 16
                    || !actions.canPlaceAt(destination)) continue;
            InventorySnapshot stock = config.backfillEquivalentStone ? combinedProtectedStock
                    : exactStoneStock(combinedProtectedStock, receipt.material().block());
            var reserved = queue.tryReserve(queue.currentSession(), receipt, stock);
            if (reserved.status() != RestorationQueue.ReserveStatus.RESERVED) continue;
            var token = reserved.reservation().orElseThrow();
            Block block = BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(token.blockToPlace().toString()));
            if (!safeMaterial(destination, block.defaultBlockState()) || !GameCatalog.id(block.asItem()).equals(token.item())) {
                queue.releaseBeforeSend(queue.currentSession(), token);
                continue;
            }
            sent = token;
            sentAfterSequence = provenance.serverReceiptSequence();
            var result = actions.placeBackfill(destination, block);
            if (result == PlayerActions.BackfillAttempt.NOT_SENT) {
                queue.releaseBeforeSend(queue.currentSession(), token);
                sent = null;
            }
            return;
        }
    }

    private static boolean dryAir(BlockState state) { return state.isAir() && state.getFluidState().isEmpty(); }

    private static InventorySnapshot exactStoneStock(InventorySnapshot stock, BlockId source) {
        String id = source.toString();
        if (!id.equals("minecraft:stone") && !id.equals("minecraft:cobblestone")) return stock;
        ItemId other = ItemId.parse(id.equals("minecraft:stone") ? "minecraft:cobblestone" : "minecraft:stone");
        Map<ItemId, Integer> floors = new LinkedHashMap<>(stock.protectedCounts());
        floors.put(other, stock.count(other));
        return new InventorySnapshot(stock.counts(), stock.availableStations(), stock.remainingDurability(),
                floors, stock.durabilityLots(), stock.toolLots());
    }
}
