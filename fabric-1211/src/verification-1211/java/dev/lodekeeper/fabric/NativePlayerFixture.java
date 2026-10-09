package dev.lodekeeper.fabric;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.play.AcknowledgeChunksC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkSentS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

final class NativePlayerFixture {
    private static final int EVENT_CAP = 256, RESPONSE_CAP = 256, ENTITY_CAP = 256;
    private static final long LIFETIME = 120_000_000_000L;
    private static final List<EquipmentSlot> EQUIPMENT = List.of(EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);
    private final MinecraftServer server;
    private final ServerWorld world;
    private final ServerPlayerEntity host, peer;
    private final UUID id, hostId;
    private final int entityId, startedTick;
    private final long startedAt = System.nanoTime();
    private final double originX, originZ;
    private final Thread ownerThread = Thread.currentThread();
    private final ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
    private final ArrayDeque<Packet<? super ServerPlayPacketListener>> responses = new ArrayDeque<>();
    private final ArrayList<String> events = new ArrayList<>();
    private final List<ItemStack> hostInitial, peerInitial;
    private final EmbeddedChannel channel;
    private NetworkState<ServerPlayPacketListener> inbound;
    private ByteBuf inboundFrame;
    private boolean inboundFrameAccepted;
    private List<ItemStack> hostPrevious, peerPrevious;
    private Map<String, String> latest = Map.of();
    private int lastTick = -1, moves;
    private long outboundPackets, discardedBytes;
    private String failure;
    private boolean closed, hostOtherStockUnchanged = true, peerOtherStockUnchanged = true;
    private int initialCombinedBread, maxObservedCombinedBread;
    private boolean breadConservationViolated;
    private record DropObservation(List<String> rows, int ordinaryBread) {}

    static Object join(ServerPlayerEntity host, ServerWorld world, boolean fullRecipient) {
        if (!host.server.isOnThread() || world != host.server.getOverworld()
                || host.getServerWorld() != world || host.server.getPlayerManager().getPlayer(host.getUuid()) != host)
            throw new IllegalStateException("fixture peer requires the current overworld server thread");
        NativePlayerFixture fixture = new NativePlayerFixture(host, world, fullRecipient);
        return fixture;
    }

    private NativePlayerFixture(ServerPlayerEntity host, ServerWorld world, boolean fullRecipient) {
        this.host = host; this.server = host.server; this.world = world;
        this.hostId = host.getUuid();
        this.startedTick = server.getTicks(); this.originX = host.getX(); this.originZ = host.getZ();
        id = UUID.randomUUID();
        if (id.equals(host.getUuid()) || server.getPlayerManager().getPlayer(id) != null)
            throw new IllegalStateException("fixture profile collision");
        GameProfile profile = new GameProfile(id, "LKPeer_" + id.toString().substring(0, 8));
        SyncedClientOptions defaults = SyncedClientOptions.createDefault();
        SyncedClientOptions options = new SyncedClientOptions(defaults.language(), 2,
                defaults.chatVisibility(), defaults.chatColorsEnabled(), defaults.playerModelParts(),
                defaults.mainArm(), defaults.filtersText(), defaults.allowsServerListing());
        peer = new ServerPlayerEntity(server, world, profile, options);
        entityId = peer.getId();
        double x = originX + 8, z = originZ;
        checkTarget(x, 64, z);
        peer.refreshPositionAndAngles(x, 64, z, 0, 0);
        if (host.currentScreenHandler != host.playerScreenHandler || !host.currentScreenHandler.getCursorStack().isEmpty())
            throw new IllegalStateException("fixture setup requires the host player menu and empty cursor");
        hostInitial = stock(host);
        channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel created) {
                created.pipeline().addLast("fixture-discard", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                        if (message instanceof ByteBuf bytes) discardedBytes += bytes.readableBytes();
                        else failure = "unencoded fixture outbound value";
                        ReferenceCountUtil.release(message);
                        promise.trySuccess();
                    }
                });
                created.pipeline().addLast("fixture-frame-admission", new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext context, Object message) {
                        if (message == inboundFrame) inboundFrameAccepted = true;
                        context.fireChannelRead(message);
                    }
                });
                ClientConnection.addLocalValidator(created.pipeline(), NetworkSide.SERVERBOUND);
                created.pipeline().addLast("fixture-observer", new ChannelDuplexHandler() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        observePacket(message);
                        context.write(message, promise);
                    }
                    @Override public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
                        failure = "fixture transport failure: " + error.getClass().getSimpleName();
                        context.fireExceptionCaught(error);
                    }
                });
                connection.addFlowControlHandler(created.pipeline());
            }
        });
        try {
            if (!channel.isActive() || !connection.isOpen()) throw new IllegalStateException("fixture channel is inactive");
            connection.transitionOutbound(PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(server.getRegistryManager())));
            server.getPlayerManager().onPlayerConnect(connection, peer, new ConnectedClientData(profile, 0, options, false));
            inbound = PlayStateFactories.C2S.bind(RegistryByteBuf.makeFactory(server.getRegistryManager()));
            if (server.getPlayerManager().getPlayer(id) != peer || connection.getPacketListener() != peer.networkHandler
                    || peer.getServerWorld() != world) throw new IllegalStateException("native fixture registration failed");
            peer.changeGameMode(GameMode.SURVIVAL);
            peer.getInventory().clear();
            if (fullRecipient) {
                for (int slot = 0; slot < 36; slot++) peer.getInventory().setStack(slot, new ItemStack(Items.STONE, 64));
                peer.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE, 64));
            }
            peer.getInventory().markDirty();
            peerInitial = stock(peer);
            hostPrevious = copies(hostInitial); peerPrevious = copies(peerInitial);
            initialCombinedBread = Math.addExact(Math.addExact(bread(hostInitial), bread(peerInitial)), drops().ordinaryBread());
            maxObservedCombinedBread = initialCombinedBread;
            drainResponses();
            checkFailure();
            event("joined profile=" + id + " entity=" + entityId + " fullRecipient=" + fullRecipient);
            checkFailure();
        } catch (RuntimeException error) {
            closeOwned();
            throw error;
        }
    }

    private void observePacket(Object message) {
        if (Thread.currentThread() != ownerThread) { failure = "fixture outbound left server thread"; return; }
        if (!(message instanceof Packet<?>)) return;
        outboundPackets++;
        if (message instanceof PlayerPositionLookS2CPacket teleport) {
            if (!teleport.getFlags().isEmpty()) { failure = "relative fixture teleport is unsupported"; return; }
            queue(new TeleportConfirmC2SPacket(teleport.getTeleportId()));
            event("peer teleport packet=" + teleport.getTeleportId());
        } else if (message instanceof KeepAliveS2CPacket keepAlive) {
            queue(new KeepAliveC2SPacket(keepAlive.getId()));
            event("peer keepalive packet=" + keepAlive.getId());
        } else if (message instanceof ChunkSentS2CPacket batch) {
            queue(new AcknowledgeChunksC2SPacket(1));
            event("peer chunk batch=" + batch.batchSize());
        }
    }

    private void queue(Packet<? super ServerPlayPacketListener> packet) {
        if (closed || responses.size() >= RESPONSE_CAP) { failure = "fixture response limit"; return; }
        responses.addLast(packet);
    }

    private void drainResponses() {
        for (int count = 0; count < 32 && !responses.isEmpty(); count++) {
            requireResponseTransport();
            if (inboundFrame != null) throw new IllegalStateException("reentrant fixture response drain");
            Packet<? super ServerPlayPacketListener> packet = responses.removeFirst();
            ByteBuf bytes = Unpooled.buffer(64, 2048);
            inboundFrame = bytes;
            inboundFrameAccepted = false;
            try {
                inbound.codec().encode(bytes, packet);
                requireResponseTransport();
                channel.writeInbound(bytes);
            } finally {
                boolean accepted = inboundFrameAccepted;
                inboundFrame = null;
                inboundFrameAccepted = false;
                if (!accepted) bytes.release();
            }
            channel.checkException();
            if (!channel.inboundMessages().isEmpty() || !channel.outboundMessages().isEmpty())
                throw new IllegalStateException("fixture retained unconsumed channel data");
        }
    }

    private void requireResponseTransport() {
        ensureOpen();
        if (!connection.isOpen() || !channel.isOpen() || !channel.isActive())
            throw new IllegalStateException("fixture response transport is closed");
    }

    static Map<String, String> observe(Object handle, ServerPlayerEntity host, int tick) {
        return ((NativePlayerFixture) handle).observeOwned(host, tick);
    }

    private Map<String, String> observeOwned(ServerPlayerEntity currentHost, int tick) {
        ensureOpen();
        try {
            if (currentHost != host || host.getServerWorld() != world || server.getPlayerManager().getPlayer(host.getUuid()) != host
                    || server.getPlayerManager().getPlayer(id) != peer || peer.getServerWorld() != world)
                throw new IllegalStateException("fixture player/world identity changed");
            if (tick < lastTick) throw new IllegalStateException("fixture server clock regressed");
            if (tick == lastTick) return latest;
            lastTick = tick;
            drainResponses();
            connection.tick();
            drainResponses();
            checkFailure();
            if (!connection.isOpen() || !peer.isAlive() || peer.isSpectator() || peer.isCreative())
                throw new IllegalStateException("fixture peer is no longer a live survival player");
            List<ItemStack> hostNow = stock(host), peerNow = stock(peer);
            if (!equalStock(hostPrevious, hostNow) || !equalStock(peerPrevious, peerNow)) event("stock tick=" + tick);
            checkFailure();
            hostPrevious = hostNow; peerPrevious = peerNow;
            hostOtherStockUnchanged &= otherStockEqual(hostInitial, hostNow);
            peerOtherStockUnchanged &= otherStockEqual(peerInitial, peerNow);
            DropObservation dropped = drops();
            int combinedBread = Math.addExact(Math.addExact(bread(hostNow), bread(peerNow)), dropped.ordinaryBread());
            maxObservedCombinedBread = Math.max(maxObservedCombinedBread, combinedBread);
            breadConservationViolated |= combinedBread != initialCombinedBread;
            Map<String, String> result = new LinkedHashMap<>();
            result.put("serverTick", Integer.toString(tick)); result.put("peerUuid", id.toString());
            result.put("peerEntityId", Integer.toString(entityId)); result.put("peerRegistered", "true");
            result.put("peerX", Double.toString(peer.getX())); result.put("peerY", Double.toString(peer.getY()));
            result.put("peerZ", Double.toString(peer.getZ())); result.put("peerSelected", Integer.toString(peer.getInventory().selectedSlot));
            result.put("hostSelected", Integer.toString(host.getInventory().selectedSlot));
            result.put("peerOrdinaryBread", Integer.toString(bread(peerNow))); result.put("hostOrdinaryBread", Integer.toString(bread(hostNow)));
            result.put("peerOtherStockUnchanged", Boolean.toString(peerOtherStockUnchanged));
            result.put("hostOtherStockUnchanged", Boolean.toString(hostOtherStockUnchanged));
            result.put("hostInitialStock", rows(hostInitial).toString()); result.put("hostStock", rows(hostNow).toString());
            result.put("peerInitialStock", rows(peerInitial).toString()); result.put("peerStock", rows(peerNow).toString());
            result.put("hostPlayerMenu", Boolean.toString(host.currentScreenHandler == host.playerScreenHandler));
            result.put("peerPlayerMenu", Boolean.toString(peer.currentScreenHandler == peer.playerScreenHandler));
            result.put("hostCursorEmpty", Boolean.toString(host.currentScreenHandler.getCursorStack().isEmpty()));
            result.put("peerCursorEmpty", Boolean.toString(peer.currentScreenHandler.getCursorStack().isEmpty()));
            result.put("drops", dropped.rows().toString());
            result.put("ordinaryDropBread", Integer.toString(dropped.ordinaryBread()));
            result.put("initialCombinedOrdinaryBread", Integer.toString(initialCombinedBread));
            result.put("combinedOrdinaryBread", Integer.toString(combinedBread));
            result.put("maxObservedCombinedOrdinaryBread", Integer.toString(maxObservedCombinedBread));
            result.put("ordinaryBreadConservationViolated", Boolean.toString(breadConservationViolated));
            result.put("outboundPackets", Long.toString(outboundPackets));
            result.put("discardedBytes", Long.toString(discardedBytes)); result.put("responsesPending", Integer.toString(responses.size()));
            result.put("events", List.copyOf(events).toString()); result.put("fixtureAuthority", "offline supplied ServerPlayer; no certified chat");
            latest = Map.copyOf(result);
            return latest;
        } catch (RuntimeException error) { closeOwned(); throw error; }
    }

    static Map<String, String> tracking(Object handle, MinecraftClient client) {
        NativePlayerFixture fixture = (NativePlayerFixture) handle;
        var handler = client.getNetworkHandler();
        var profile = handler == null ? null : handler.getPlayerListEntry(fixture.id);
        var entity = client.world == null ? null : client.world.getEntityById(fixture.entityId);
        boolean listed = profile != null && fixture.id.equals(profile.getProfile().getId());
        boolean tracked = entity instanceof net.minecraft.client.network.AbstractClientPlayerEntity
                && fixture.id.equals(entity.getUuid());
        boolean localContext = client.getServer() == fixture.server && client.player != null
                && client.player.getUuid().equals(fixture.hostId) && client.world != null
                && client.world.getRegistryKey().equals(World.OVERWORLD);
        return Map.of("peerListed", Boolean.toString(listed), "peerTracked", Boolean.toString(tracked),
                "contextMatches", Boolean.toString(localContext), "peerUuid", fixture.id.toString());
    }

    static void move(Object handle, double x, double y, double z) {
        NativePlayerFixture fixture = (NativePlayerFixture) handle;
        fixture.ensureOpen(); fixture.checkTarget(x, y, z);
        if (++fixture.moves > 64) throw new IllegalStateException("fixture movement limit");
        fixture.event("issued server peer move=" + fixture.moves + " x=" + x + " y=" + y + " z=" + z);
        fixture.checkFailure();
        fixture.peer.networkHandler.requestTeleport(x, y, z, 0, 0);
        fixture.checkFailure();
    }

    private void checkTarget(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || y != 64
                || Math.abs(x - originX) > 64 || Math.abs(z - originZ) > 64)
            throw new IllegalStateException("fixture target outside finite flat-pad domain");
        BlockPos feet = BlockPos.ofFloored(x, y, z);
        if (!world.isChunkLoaded(feet) || !world.getBlockState(feet.down()).isOf(Blocks.BEDROCK)
                || !world.getBlockState(feet).isAir() || !world.getBlockState(feet.up()).isAir())
            throw new IllegalStateException("fixture target requires loaded bedrock support and clear feet/head");
    }

    private void ensureOpen() {
        if (!server.isOnThread() || Thread.currentThread() != ownerThread) throw new IllegalStateException("fixture server-thread violation");
        if (closed) throw new IllegalStateException("fixture peer is closed");
        if (failure != null) { closeOwned(); throw new IllegalStateException(failure); }
        if (host.getServerWorld() != world || peer.getServerWorld() != world
                || server.getPlayerManager().getPlayer(hostId) != host || server.getPlayerManager().getPlayer(id) != peer
                || connection.getPacketListener() != peer.networkHandler) {
            closeOwned(); throw new IllegalStateException("fixture player/world/connection identity changed");
        }
        if (System.nanoTime() - startedAt >= LIFETIME || server.getTicks() - startedTick >= 2400) {
            closeOwned(); throw new IllegalStateException("fixture peer lifetime expired");
        }
    }
    private void checkFailure() { if (failure != null) throw new IllegalStateException(failure); }
    private void event(String value) {
        if (events.size() >= EVENT_CAP) { failure = "fixture event limit"; return; }
        events.add(value);
    }

    static void close(Object handle) { if (handle != null) ((NativePlayerFixture) handle).closeOwned(); }
    private void closeOwned() {
        if (!server.isOnThread() || Thread.currentThread() != ownerThread) throw new IllegalStateException("fixture teardown requires server thread");
        if (closed) return;
        closed = true; responses.clear();
        boolean stillOwned = server.getPlayerManager().getPlayer(id) == peer && connection.getPacketListener() == peer.networkHandler;
        try {
            connection.disconnect(Text.literal("bounded native verifier peer removed"));
            if (stillOwned) connection.handleDisconnection();
        } finally { channel.finishAndReleaseAll(); }
        if (stillOwned && server.getPlayerManager().getPlayer(id) == peer)
            throw new IllegalStateException("normal fixture disconnect failed to remove peer");
    }

    private static List<ItemStack> stock(ServerPlayerEntity player) {
        ArrayList<ItemStack> stacks = new ArrayList<>(42);
        for (int slot = 0; slot < 36; slot++) stacks.add(player.getInventory().getStack(slot).copy());
        for (EquipmentSlot slot : EQUIPMENT) stacks.add(player.getEquippedStack(slot).copy());
        stacks.add(player.currentScreenHandler.getCursorStack().copy());
        return List.copyOf(stacks);
    }
    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
    private static boolean ordinaryBread(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, new ItemStack(Items.BREAD));
    }
    private static int bread(List<ItemStack> stacks) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) if (ordinaryBread(stacks.get(slot))) count = Math.addExact(count, stacks.get(slot).getCount());
        return count;
    }
    private static boolean equalStock(List<ItemStack> first, List<ItemStack> second) {
        for (int slot = 0; slot < first.size(); slot++) if (!ItemStack.areEqual(first.get(slot), second.get(slot))) return false;
        return true;
    }
    private static boolean otherStockEqual(List<ItemStack> baseline, List<ItemStack> current) {
        for (int slot = 0; slot < baseline.size(); slot++) {
            ItemStack before = baseline.get(slot), after = current.get(slot);
            if (slot < 36 && (before.isEmpty() || ordinaryBread(before)) && (after.isEmpty() || ordinaryBread(after))) continue;
            if (!ItemStack.areEqual(before, after)) return false;
        }
        return true;
    }
    private static String row(ItemStack stack) {
        String components = stack.getComponents().toString();
        if (components.length() > 4096) throw new IllegalStateException("fixture component observation limit");
        return Registries.ITEM.getId(stack.getItem()) + "/" + stack.getCount() + "/" + components;
    }
    private static List<String> rows(List<ItemStack> stacks) { return stacks.stream().map(NativePlayerFixture::row).toList(); }
    private DropObservation drops() {
        ArrayList<String> result = new ArrayList<>(); int inspected = 0, ordinaryBread = 0;
        for (var entity : world.iterateEntities()) {
            if (++inspected > ENTITY_CAP) throw new IllegalStateException("fixture entity census limit");
            if (!(entity instanceof ItemEntity drop) || drop.isRemoved() || Math.abs(drop.getX() - originX) > 64
                    || Math.abs(drop.getZ() - originZ) > 64) continue;
            ItemStack stack = drop.getStack();
            if (ordinaryBread(stack)) ordinaryBread = Math.addExact(ordinaryBread, stack.getCount());
            result.add(drop.getUuid() + "/" + drop.getX() + "/" + drop.getY() + "/" + drop.getZ() + "/" + row(drop.getStack()));
        }
        return new DropObservation(List.copyOf(result), ordinaryBread);
    }
}
