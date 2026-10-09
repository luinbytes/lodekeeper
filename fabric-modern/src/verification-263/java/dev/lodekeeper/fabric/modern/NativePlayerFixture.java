package dev.lodekeeper.fabric.modern;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

final class NativePlayerFixture {
    private static final int EVENT_CAP = 256, RESPONSE_CAP = 256, ENTITY_CAP = 256;
    private static final long LIFETIME = 120_000_000_000L;
    private static final List<EquipmentSlot> EQUIPMENT = List.of(EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);
    private final MinecraftServer server;
    private final ServerLevel world;
    private final ServerPlayer host, peer;
    private final UUID id, hostId;
    private final int entityId, startedTick;
    private final long startedAt = System.nanoTime();
    private final double originX, originZ;
    private final Thread ownerThread = Thread.currentThread();
    private final Connection connection = new Connection(PacketFlow.SERVERBOUND);
    private final ArrayDeque<Packet<? super ServerGamePacketListener>> responses = new ArrayDeque<>();
    private final ArrayList<String> events = new ArrayList<>();
    private final List<ItemStack> hostInitial, peerInitial;
    private final EmbeddedChannel channel;
    private ProtocolInfo<ServerGamePacketListener> inbound;
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

    static Object join(ServerPlayer host, ServerLevel world, boolean fullRecipient) {
        if (!world.getServer().isSameThread() || world != world.getServer().overworld()
                || host.level() != world || world.getServer().getPlayerList().getPlayer(host.getUUID()) != host)
            throw new IllegalStateException("fixture peer requires the current overworld server thread");
        NativePlayerFixture fixture = new NativePlayerFixture(host, world, fullRecipient);
        return fixture;
    }

    private NativePlayerFixture(ServerPlayer host, ServerLevel world, boolean fullRecipient) {
        this.host = host; this.server = world.getServer(); this.world = world;
        this.hostId = host.getUUID();
        this.startedTick = server.getTickCount(); this.originX = host.getX(); this.originZ = host.getZ();
        id = UUID.randomUUID();
        if (id.equals(host.getUUID()) || server.getPlayerList().getPlayer(id) != null)
            throw new IllegalStateException("fixture profile collision");
        GameProfile profile = new GameProfile(id, "LKPeer_" + id.toString().substring(0, 8));
        ClientInformation defaults = ClientInformation.createDefault();
        ClientInformation options = new ClientInformation(defaults.language(), 2,
                defaults.chatVisibility(), defaults.chatColors(), defaults.modelCustomisation(),
                defaults.mainHand(), defaults.textFilteringEnabled(), defaults.allowsListing(), defaults.particleStatus());
        peer = new ServerPlayer(server, world, profile, options);
        entityId = peer.getId();
        double x = originX + 8, z = originZ;
        checkTarget(x, 64, z);
        peer.absSnapTo(x, 64, z, 0, 0);
        if (host.containerMenu != host.inventoryMenu || !host.containerMenu.getCarried().isEmpty())
            throw new IllegalStateException("fixture setup requires the host player menu and empty cursor");
        hostInitial = stock(host);
        channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel created) {
                created.pipeline().addLast("fixture-discard", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                        Object encoded = net.minecraft.network.HiddenByteBuf.unpack(message);
                        if (encoded instanceof ByteBuf bytes) discardedBytes += bytes.readableBytes();
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
                Connection.configureInMemoryPipeline(created.pipeline(), PacketFlow.SERVERBOUND);
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
                connection.configurePacketHandler(created.pipeline());
            }
        });
        try {
            if (!channel.isActive() || !connection.isConnected()) throw new IllegalStateException("fixture channel is inactive");
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())));
            server.getPlayerList().placeNewPlayer(connection, peer, new CommonListenerCookie(profile, 0, options, false));
            inbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()), peer.connection);
            if (server.getPlayerList().getPlayer(id) != peer || connection.getPacketListener() != peer.connection
                    || peer.level() != world) throw new IllegalStateException("native fixture registration failed");
            peer.setGameMode(GameType.SURVIVAL);
            peer.getInventory().clearContent();
            if (fullRecipient) {
                for (int slot = 0; slot < 36; slot++) peer.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                peer.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE, 64));
            }
            peer.getInventory().setChanged();
            peerInitial = stock(peer);
            hostPrevious = copies(hostInitial); peerPrevious = copies(peerInitial);
            initialCombinedBread = Math.addExact(Math.addExact(bread(hostInitial), bread(peerInitial)), drops().ordinaryBread());
            maxObservedCombinedBread = initialCombinedBread;
            drainResponses();
            queue(new ServerboundPlayerLoadedPacket());
            drainResponses();
            checkFailure();
            if (!peer.connection.hasClientLoaded()) throw new IllegalStateException("native peer loaded response was not admitted");
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
        if (message instanceof ClientboundPlayerPositionPacket teleport) {
            if (!teleport.relatives().isEmpty()) { failure = "relative fixture teleport is unsupported"; return; }
            queue(new ServerboundAcceptTeleportationPacket(teleport.id(), teleport.change().position().x, teleport.change().position().y, teleport.change().position().z, teleport.change().yRot(), teleport.change().xRot()));
            event("peer teleport packet=" + teleport.id());
        } else if (message instanceof ClientboundKeepAlivePacket keepAlive) {
            queue(new ServerboundKeepAlivePacket(keepAlive.getId()));
            event("peer keepalive packet=" + keepAlive.getId());
        } else if (message instanceof ClientboundChunkBatchFinishedPacket batch) {
            queue(new ServerboundChunkBatchReceivedPacket(1));
            event("peer chunk batch=" + batch.batchSize());
        }
    }

    private void queue(Packet<? super ServerGamePacketListener> packet) {
        if (closed || responses.size() >= RESPONSE_CAP) { failure = "fixture response limit"; return; }
        responses.addLast(packet);
    }

    private void drainResponses() {
        for (int count = 0; count < 32 && !responses.isEmpty(); count++) {
            requireResponseTransport();
            if (inboundFrame != null) throw new IllegalStateException("reentrant fixture response drain");
            Packet<? super ServerGamePacketListener> packet = responses.removeFirst();
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
        if (!connection.isConnected() || !channel.isOpen() || !channel.isActive())
            throw new IllegalStateException("fixture response transport is closed");
    }

    static Map<String, String> observe(Object handle, ServerPlayer host, int tick) {
        return ((NativePlayerFixture) handle).observeOwned(host, tick);
    }

    private Map<String, String> observeOwned(ServerPlayer currentHost, int tick) {
        ensureOpen();
        try {
            if (currentHost != host || host.level() != world || server.getPlayerList().getPlayer(host.getUUID()) != host
                    || server.getPlayerList().getPlayer(id) != peer || peer.level() != world)
                throw new IllegalStateException("fixture player/world identity changed");
            if (tick < lastTick) throw new IllegalStateException("fixture server clock regressed");
            if (tick == lastTick) return latest;
            lastTick = tick;
            drainResponses();
            connection.tick();
            drainResponses();
            checkFailure();
            if (!connection.isConnected() || !peer.isAlive() || peer.isSpectator() || peer.isCreative())
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
            result.put("peerZ", Double.toString(peer.getZ())); result.put("peerSelected", Integer.toString(peer.getInventory().getSelectedSlot()));
            result.put("hostSelected", Integer.toString(host.getInventory().getSelectedSlot()));
            result.put("peerOrdinaryBread", Integer.toString(bread(peerNow))); result.put("hostOrdinaryBread", Integer.toString(bread(hostNow)));
            result.put("peerOtherStockUnchanged", Boolean.toString(peerOtherStockUnchanged));
            result.put("hostOtherStockUnchanged", Boolean.toString(hostOtherStockUnchanged));
            result.put("hostInitialStock", rows(hostInitial).toString()); result.put("hostStock", rows(hostNow).toString());
            result.put("peerInitialStock", rows(peerInitial).toString()); result.put("peerStock", rows(peerNow).toString());
            result.put("hostPlayerMenu", Boolean.toString(host.containerMenu == host.inventoryMenu));
            result.put("peerPlayerMenu", Boolean.toString(peer.containerMenu == peer.inventoryMenu));
            result.put("hostCursorEmpty", Boolean.toString(host.containerMenu.getCarried().isEmpty()));
            result.put("peerCursorEmpty", Boolean.toString(peer.containerMenu.getCarried().isEmpty()));
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

    static Map<String, String> tracking(Object handle, Minecraft client) {
        NativePlayerFixture fixture = (NativePlayerFixture) handle;
        var handler = client.getConnection();
        var profile = handler == null ? null : handler.getPlayerInfo(fixture.id);
        var entity = client.level == null ? null : client.level.getEntity(fixture.entityId);
        boolean listed = profile != null && fixture.id.equals(profile.getProfile().id());
        boolean tracked = entity instanceof net.minecraft.client.player.AbstractClientPlayer
                && fixture.id.equals(entity.getUUID());
        boolean localContext = client.getSingleplayerServer() == fixture.server && client.player != null
                && client.player.getUUID().equals(fixture.hostId) && client.level != null
                && client.level.dimension().equals(Level.OVERWORLD);
        return Map.of("peerListed", Boolean.toString(listed), "peerTracked", Boolean.toString(tracked),
                "contextMatches", Boolean.toString(localContext), "peerUuid", fixture.id.toString());
    }

    static void move(Object handle, double x, double y, double z) {
        NativePlayerFixture fixture = (NativePlayerFixture) handle;
        fixture.ensureOpen(); fixture.checkTarget(x, y, z);
        if (++fixture.moves > 64) throw new IllegalStateException("fixture movement limit");
        fixture.event("issued server peer move=" + fixture.moves + " x=" + x + " y=" + y + " z=" + z);
        fixture.checkFailure();
        fixture.peer.connection.teleport(x, y, z, 0, 0);
        fixture.checkFailure();
    }

    private void checkTarget(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || y != 64
                || Math.abs(x - originX) > 64 || Math.abs(z - originZ) > 64)
            throw new IllegalStateException("fixture target outside finite flat-pad domain");
        BlockPos feet = BlockPos.containing(x, y, z);
        if (!world.hasChunkAt(feet) || !world.getBlockState(feet.below()).is(Blocks.BEDROCK)
                || !world.getBlockState(feet).isAir() || !world.getBlockState(feet.above()).isAir())
            throw new IllegalStateException("fixture target requires loaded bedrock support and clear feet/head");
    }

    private void ensureOpen() {
        if (!server.isSameThread() || Thread.currentThread() != ownerThread) throw new IllegalStateException("fixture server-thread violation");
        if (closed) throw new IllegalStateException("fixture peer is closed");
        if (failure != null) { closeOwned(); throw new IllegalStateException(failure); }
        if (host.level() != world || peer.level() != world
                || server.getPlayerList().getPlayer(hostId) != host || server.getPlayerList().getPlayer(id) != peer
                || connection.getPacketListener() != peer.connection) {
            closeOwned(); throw new IllegalStateException("fixture player/world/connection identity changed");
        }
        if (System.nanoTime() - startedAt >= LIFETIME || server.getTickCount() - startedTick >= 2400) {
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
        if (!server.isSameThread() || Thread.currentThread() != ownerThread) throw new IllegalStateException("fixture teardown requires server thread");
        if (closed) return;
        closed = true; responses.clear();
        boolean stillOwned = server.getPlayerList().getPlayer(id) == peer && connection.getPacketListener() == peer.connection;
        try {
            connection.disconnect(Component.literal("bounded native verifier peer removed"));
            if (stillOwned) connection.handleDisconnection();
        } finally { channel.finishAndReleaseAll(); }
        if (stillOwned && server.getPlayerList().getPlayer(id) == peer)
            throw new IllegalStateException("normal fixture disconnect failed to remove peer");
    }

    private static List<ItemStack> stock(ServerPlayer player) {
        ArrayList<ItemStack> stacks = new ArrayList<>(42);
        for (int slot = 0; slot < 36; slot++) stacks.add(player.getInventory().getItem(slot).copy());
        for (EquipmentSlot slot : EQUIPMENT) stacks.add(player.getItemBySlot(slot).copy());
        stacks.add(player.containerMenu.getCarried().copy());
        return List.copyOf(stacks);
    }
    private static List<ItemStack> copies(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
    private static boolean ordinaryBread(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, new ItemStack(Items.BREAD));
    }
    private static int bread(List<ItemStack> stacks) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) if (ordinaryBread(stacks.get(slot))) count = Math.addExact(count, stacks.get(slot).getCount());
        return count;
    }
    private static boolean equalStock(List<ItemStack> first, List<ItemStack> second) {
        for (int slot = 0; slot < first.size(); slot++) if (!ItemStack.matches(first.get(slot), second.get(slot))) return false;
        return true;
    }
    private static boolean otherStockEqual(List<ItemStack> baseline, List<ItemStack> current) {
        for (int slot = 0; slot < baseline.size(); slot++) {
            ItemStack before = baseline.get(slot), after = current.get(slot);
            if (slot < 36 && (before.isEmpty() || ordinaryBread(before)) && (after.isEmpty() || ordinaryBread(after))) continue;
            if (!ItemStack.matches(before, after)) return false;
        }
        return true;
    }
    private static String row(ItemStack stack) {
        String components = stack.getComponents().toString();
        if (components.length() > 4096) throw new IllegalStateException("fixture component observation limit");
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "/" + stack.getCount() + "/" + components;
    }
    private static List<String> rows(List<ItemStack> stacks) { return stacks.stream().map(NativePlayerFixture::row).toList(); }
    private DropObservation drops() {
        ArrayList<String> result = new ArrayList<>(); int inspected = 0, ordinaryBread = 0;
        for (var entity : world.getAllEntities()) {
            if (++inspected > ENTITY_CAP) throw new IllegalStateException("fixture entity census limit");
            if (!(entity instanceof ItemEntity drop) || drop.isRemoved() || Math.abs(drop.getX() - originX) > 64
                    || Math.abs(drop.getZ() - originZ) > 64) continue;
            ItemStack stack = drop.getItem();
            if (ordinaryBread(stack)) ordinaryBread = Math.addExact(ordinaryBread, stack.getCount());
            result.add(drop.getUUID() + "/" + drop.getX() + "/" + drop.getY() + "/" + drop.getZ() + "/" + row(drop.getItem()));
        }
        return new DropObservation(List.copyOf(result), ordinaryBread);
    }
}
