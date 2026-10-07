package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.navigation.kernel.OwnedKernelRuntime;
import dev.lodekeeper.navigation.kernel.api.IBaritone;
import dev.lodekeeper.navigation.kernel.api.event.events.PathEvent;
import dev.lodekeeper.navigation.kernel.api.pathing.goals.Goal;
import dev.lodekeeper.navigation.kernel.pathing.movement.CalculationContext;
import dev.lodekeeper.navigation.kernel.snapshot.ChunkSnapshot;
import dev.lodekeeper.navigation.kernel.snapshot.OwnedWorldSnapshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.Map;

public final class RetreatSnapshotDiagnostics {
    public interface SnapshotMaps {
        Map<Long, ?> lodekeeper$pending();
        Map<Long, ChunkSnapshot> lodekeeper$ready();
        java.util.concurrent.ConcurrentMap<Long, Long> lodekeeper$revisions();
        boolean lodekeeper$hasInterestCenter();
        int lodekeeper$interestX();
        int lodekeeper$interestZ();
    }

    private static final int MAX_CONTEXTS = 8;
    private static final int MAX_CHUNKS = 49 + 16;
    private static Request active;
    private static long nextRequest;

    private static final class Request {
        final Object controller;
        final Minecraft client;
        final LodekeeperConfig config;
        final OwnedKernelRuntime runtime;
        final OwnedKernelRuntime.Session session;
        final LocalPlayer player;
        final IBaritone bot;
        final Goal goal;
        final List<BlockPos> goals;
        final BlockPos startFeet;
        final long id;
        final Capture[] captures = new Capture[MAX_CONTEXTS];
        int count;

        Request(Object controller, Minecraft client, LodekeeperConfig config, OwnedKernelRuntime runtime,
                IBaritone bot, Goal goal, List<BlockPos> goals) {
            this.controller = controller;
            this.client = client;
            this.config = config;
            this.runtime = runtime;
            session = runtime.captureSession();
            player = client.player;
            this.bot = bot;
            this.goal = goal;
            this.goals = List.copyOf(goals);
            startFeet = bot.getPlayerContext().playerFeet().immutable();
            id = ++nextRequest;
        }

        boolean current() {
            return config.debugLogging && client.isSameThread() && OwnedKernelRuntime.current() == runtime
                    && runtime.isCurrent(session) && session.world() == client.level && player == client.player
                    && runtime.getPrimaryBaritone() == bot && bot.getPlayerContext().player() == player
                    && bot.getPlayerContext().world() == session.world();
        }
    }

    private static final class Capture {
        final int contextId;
        final long[] keys = new long[MAX_CHUNKS];
        final long[] readyRevisions = new long[MAX_CHUNKS];
        int count;
        boolean completionLogged;

        Capture(CalculationContext context) { contextId = System.identityHashCode(context); }

        void add(int x, int z) {
            long key = ChunkPos.pack(x, z);
            for (int i = 0; i < count; i++) if (keys[i] == key) return;
            if (count < MAX_CHUNKS) keys[count++] = key;
        }
    }

    private RetreatSnapshotDiagnostics() { }

    static void register(Object controller, Minecraft client, LodekeeperConfig config,
                         IBaritone bot, Goal goal, List<BlockPos> goals) {
        if (!config.debugLogging) return;
        try {
            OwnedKernelRuntime runtime = OwnedKernelRuntime.current();
            if (!client.isSameThread() || runtime == null || bot == null || goals.size() > 16) return;
            Request request = new Request(controller, client, config, runtime, bot, goal, goals);
            if (!request.current()) return;
            active = request;
        } catch (Throwable failure) {
            active = null;
        }
    }

    static void clear(Object controller) {
        if (active != null && active.controller == controller) active = null;
    }

    public static void snapshotsReset(OwnedWorldSnapshots snapshots) {
        if (active != null && active.runtime.snapshots() == snapshots) active = null;
    }

    public static void capture(CalculationContext context) {
        Request request = active;
        if (request == null) return;
        try {
            if (!request.current()) { active = null; return; }
            if (!context.safeForThreadedUse || context.baritone != request.bot || context.session != request.session
                    || request.bot.getCustomGoalProcess().getGoal() != request.goal
                    || request.bot.getPathingBehavior().getGoal() != request.goal || request.count == MAX_CONTEXTS) return;
            OwnedWorldSnapshots snapshots = request.runtime.snapshots();
            SnapshotMaps maps = (SnapshotMaps) (Object) snapshots;
            Map<Long, ?> pending = maps.lodekeeper$pending();
            Map<Long, ChunkSnapshot> ready = maps.lodekeeper$ready();
            boolean hasCenter = maps.lodekeeper$hasInterestCenter();
            int centerX = hasCenter ? maps.lodekeeper$interestX() : context.playerFeet.getX() >> 4;
            int centerZ = hasCenter ? maps.lodekeeper$interestZ() : context.playerFeet.getZ() >> 4;
            Capture capture = new Capture(context);
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
                capture.add(centerX + dx, centerZ + dz);
            for (BlockPos goal : request.goals) capture.add(goal.getX() >> 4, goal.getZ() >> 4);
            StringBuilder chunks = new StringBuilder();
            for (int i = 0; i < capture.count; i++) {
                long key = capture.keys[i];
                int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
                ChunkSnapshot chunk = ready.get(key);
                capture.readyRevisions[i] = chunk == null ? -1 : chunk.revision();
                boolean live = request.client.level.getChunk(x, z, ChunkStatus.FULL, false) != null;
                String frozen = context.snapshot.hasLiveChunk(x << 4, z << 4) ? "live"
                        : context.snapshot.isLoaded(x << 4, z << 4) ? "cached" : "absent";
                if (i != 0) chunks.append(';');
                chunks.append(x).append(',').append(z).append("/liveFULL=").append(live)
                        .append("/pending=").append(pending.containsKey(key))
                        .append("/readyRevision=").append(chunk == null ? -1 : chunk.revision())
                        .append("/readyCurrentAtFreeze=").append(chunk != null && snapshots.current(chunk))
                        .append("/frozen=").append(frozen);
            }
            request.captures[request.count++] = capture;
            org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                    "[Lodekeeper] RETREAT_SNAPSHOT phase=freeze request={} context={} sample={} runtime={} bot={} session={} generation={} world={} player={} goal={} requestFeet={} contextFeet={} goals={} interestCenterPresent={} interestCenter={},{} chunks={}",
                    request.id, capture.contextId, request.count, System.identityHashCode(request.runtime),
                    System.identityHashCode(request.bot), System.identityHashCode(request.session), request.session.generation(),
                    System.identityHashCode(request.session.world()), System.identityHashCode(request.player),
                    System.identityHashCode(request.goal), request.startFeet, context.playerFeet, request.goals,
                    hasCenter, centerX, centerZ, chunks);
        } catch (Throwable failure) {
            active = null;
        }
    }

    static void pathEvent(Object controller, IBaritone bot, PathEvent event) {
        Request request = active;
        if (request == null || request.controller != controller || request.bot != bot) return;
        try {
            if (!request.current()) { active = null; return; }
            if (event != PathEvent.CALC_FAILED && event != PathEvent.CALC_FINISHED_NOW_EXECUTING
                    && event != PathEvent.NEXT_CALC_FAILED && event != PathEvent.NEXT_SEGMENT_CALC_FINISHED) return;
            if (bot.getPathingBehavior().getGoal() != request.goal) { active = null; return; }
            for (int c = 0; c < request.count; c++) {
                Capture capture = request.captures[c];
                if (capture.completionLogged) continue;
                capture.completionLogged = true;
                StringBuilder chunks = new StringBuilder();
                SnapshotMaps maps = (SnapshotMaps) (Object) request.runtime.snapshots();
                for (int i = 0; i < capture.count; i++) {
                    long key = capture.keys[i];
                    long revision = capture.readyRevisions[i];
                    if (!chunks.isEmpty()) chunks.append(';');
                    chunks.append(ChunkPos.getX(key)).append(',').append(ChunkPos.getZ(key))
                            .append("/readyRevisionAtFreeze=").append(revision)
                            .append("/currentAtCompletion=").append(revision >= 0
                                    && maps.lodekeeper$revisions().getOrDefault(key, -1L) == revision)
                            .append("/pendingAtCompletion=").append(maps.lodekeeper$pending().containsKey(key))
                            .append("/readyAtCompletion=").append(maps.lodekeeper$ready().containsKey(key));
                }
                org.slf4j.LoggerFactory.getLogger("lodekeeper").info(
                        "[Lodekeeper] RETREAT_SNAPSHOT phase=completion request={} context={} sample={} event={} contextAssociation=observedOnly sessionCurrentAtCompletion={} chunks={}",
                        request.id, capture.contextId, c + 1, event, request.runtime.isCurrent(request.session), chunks);
            }
        } catch (Throwable failure) {
            active = null;
        }
    }
}
