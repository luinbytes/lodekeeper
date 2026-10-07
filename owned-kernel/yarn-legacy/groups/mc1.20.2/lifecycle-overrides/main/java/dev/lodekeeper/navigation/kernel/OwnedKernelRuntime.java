package dev.lodekeeper.navigation.kernel;

import dev.lodekeeper.navigation.kernel.api.IBaritone;
import dev.lodekeeper.navigation.kernel.api.OwnedKernelAPI;
import dev.lodekeeper.navigation.kernel.api.OwnedKernelProvider;
import dev.lodekeeper.navigation.kernel.api.cache.IWorldScanner;
import dev.lodekeeper.navigation.kernel.cache.FasterWorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import dev.lodekeeper.navigation.kernel.snapshot.OwnedWorldSnapshots;
import dev.lodekeeper.navigation.kernel.snapshot.ImmutableWorldView;
import dev.lodekeeper.navigation.kernel.cache.WorldData;
import java.util.Map;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

public final class OwnedKernelRuntime implements OwnedKernelProvider, AutoCloseable {
    private static volatile OwnedKernelRuntime active;
    private final Minecraft minecraft;
    private final OwnedWorkScheduler workers = new OwnedWorkScheduler();
    private final OwnedWorldSnapshots snapshots = new OwnedWorldSnapshots(this);
    private volatile WorldEditPolicy worldEditPolicy;
    private volatile long policyGeneration;
    private Baritone kernel;
    private volatile boolean closed;
    private volatile Session session;
    private long generation;
    private boolean workersStopped;

    public record Session(OwnedKernelRuntime owner, Level world, String dimension, long generation) {}

    private OwnedKernelRuntime(Minecraft minecraft, WorldEditPolicy policy) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.worldEditPolicy = minecraft.level == null ? WorldEditPolicy.denyAll() : policy;
    }

    public static synchronized OwnedKernelRuntime attach(Minecraft minecraft, WorldEditPolicy policy) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Attach navigation on the Minecraft thread");
        }
        WorldEditPolicy safePolicy = Objects.requireNonNullElseGet(policy, WorldEditPolicy::denyAll);
        if (active != null && !active.closed && active.minecraft == minecraft) {
            active.updateWorldEditPolicy(safePolicy);
            return active;
        }
        if (active != null) {
            active.close();
        }
        OwnedKernelRuntime runtime = new OwnedKernelRuntime(minecraft, safePolicy);
        try {
            runtime.kernel = new Baritone(minecraft, runtime.worldEditPolicy, runtime);
            if (minecraft.level != null) {
                runtime.beginWorld(minecraft.level);
            }
            active = runtime;
            OwnedKernelAPI.installProvider(runtime);
            return runtime;
        } catch (RuntimeException | Error failure) {
            runtime.close();
            throw failure;
        }
    }

    public static synchronized void shutdown() {
        if (active != null) {
            active.close();
        }
    }

    public static OwnedKernelRuntime current() { return active; }
    public Session captureSession() { return session; }
    public boolean isCurrent(Session expected) { return !closed && expected != null && session == expected; }
    public boolean isClosed() { return closed; }

    public void requireMainThread() {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Navigation lifecycle requires the Minecraft thread");
        }
    }

    public Future<?> submitSearch(Session expected, Runnable task) {
        if (!isCurrent(expected)) {
            throw new RejectedExecutionException("Navigation session is stale");
        }
        return workers.search(() -> {
            if (isCurrent(expected) && !Thread.currentThread().isInterrupted()) {
                task.run();
            }
        });
    }

    public OwnedWorldSnapshots snapshots() { return snapshots; }
    public ImmutableWorldView worldView() {
        requireMainThread();
        Session current = session;
        if (!isCurrent(current)) { throw new IllegalStateException("No navigation world session"); }
        WorldData data = kernel.getWorldProvider().getCurrentWorld();
        return new ImmutableWorldView(snapshots.view(), data == null ? Map.of() : data.cache.publishedChunks(), current.world().dimensionType());
    }
    public <I, R> OwnedCoalescedJob<I, R> maintenanceJob() { return new OwnedCoalescedJob<>(workers); }

    public OwnedFinalFlush finalFlush(Runnable save) {
        return new OwnedFinalFlush(workers, save);
    }

    public boolean maintain(Runnable task) {
        return !closed && workers.maintain(task);
    }

    public void invalidateWorld() {
        requireMainThread();
        session = null;
        worldEditPolicy = WorldEditPolicy.denyAll();
        policyGeneration++;
        snapshots.reset(null);
        generation++;
        if (kernel != null) {
            kernel.updateWorldEditPolicy(worldEditPolicy);
            kernel.getPathingBehavior().forceCancel();
            kernel.getInputOverrideHandler().restoreOwnedInput();
            kernel.getWorldProvider().closeWorld();
        }
    }

    public void beginWorld(Level world) {
        requireMainThread();
        if (closed) { return; }
        if (session != null) { invalidateWorld(); }
        session = new Session(this, Objects.requireNonNull(world), world.dimension().location().toString(), ++generation);
        snapshots.reset(session);
        kernel.getWorldProvider().initWorld(world);
    }

    public void tick() {
        requireMainThread();
        if (closed) { return; }
        Session current = session;
        if (current == null ? minecraft.level != null : current.world() != minecraft.level) {
            invalidateWorld();
            if (minecraft.level != null) { beginWorld(minecraft.level); }
        }
        if (session != null && kernel.getPlayerContext().player() != null) {
            snapshots.requestAround(kernel.getPlayerContext().playerFeet());
            snapshots.tick();
        }
        kernel.getWorldProvider().tick();
    }

    public WorldEditPolicy worldEditPolicy() { return worldEditPolicy; }
    public long policyGeneration() { return policyGeneration; }
    public BoundWorldEditPolicy capturePolicy() {
        requireMainThread();
        return new BoundWorldEditPolicy(this, session, policyGeneration, worldEditPolicy);
    }
    public void updateWorldEditPolicy(WorldEditPolicy policy) {
        requireMainThread();
        Objects.requireNonNull(policy);
        worldEditPolicy = isCurrent(session) ? policy : WorldEditPolicy.denyAll();
        policyGeneration++;
        if (kernel != null) {
            kernel.updateWorldEditPolicy(worldEditPolicy);
            kernel.getPathingBehavior().forceCancel();
            kernel.getInputOverrideHandler().restoreOwnedInput();
        }
    }

    public IBaritone getPrimaryBaritone() { return closed ? null : kernel; }
    public List<IBaritone> getAllBaritones() { return closed || kernel == null ? List.of() : List.of(kernel); }
    public IBaritone getBaritoneForPlayer(LocalPlayer player) {
        return !closed && kernel != null && player != null && player == kernel.getPlayerContext().player() ? kernel : null;
    }
    public IBaritone getBaritoneForMinecraft(Minecraft minecraft) { return closed ? null : this.minecraft == minecraft ? kernel : null; }
    public IBaritone getBaritoneForConnection(ClientPacketListener connection) {
        LocalPlayer player = closed || kernel == null ? null : kernel.getPlayerContext().player();
        return player != null && player.connection == connection ? kernel : null;
    }
    public IWorldScanner getWorldScanner() { return FasterWorldScanner.INSTANCE; }

    @Override
    public void close() {
        requireMainThread();
        synchronized (OwnedKernelRuntime.class) {
            if (!closed) {
                invalidateWorld();
                if (kernel != null) { kernel.getWorldProvider().close(); }
                closed = true;
                OwnedKernelAPI.clearProvider(this);
            }
            if (!workersStopped) { workersStopped = workers.stop(2000); }
            if (!workersStopped) {
                throw new IllegalStateException("Navigation workers did not terminate within the shutdown deadline");
            }
            if (kernel != null) { kernel.getWorldProvider().finishClosedFlushes(); }
            if (active == this) { active = null; }
        }
    }
}
