package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.nav.NavigationOverlay;
import dev.lodekeeper.nav.NavigationSceneSnapshot;
import dev.lodekeeper.nav.NavigationSnapshot;
import dev.lodekeeper.core.ClaimBox;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;

/** Client-thread snapshotting and bounded world-overlay geometry for the modern API family. */
public final class WorldVisualization {
    private static final int SNAPSHOT_INTERVAL_TICKS = 4;

    private final Minecraft client;
    private final AutomationEngine engine;
    private volatile Frame frame = Frame.EMPTY;
    private Object observedWorld;
    private Object observedDimension;
    private int ticksSinceSnapshot;

    static record Frame(NavigationSnapshot navigation, NavigationSceneSnapshot scene, boolean enabled,
                        boolean showPath, boolean showSearch, boolean showNextBreak,
                        boolean showNextPlace, boolean showParkour, boolean showClaims,
                        boolean showStations, boolean showBackfill, int visualizationDistance,
                        boolean hasTarget, int targetX, int targetY, int targetZ) {
        static final Frame EMPTY = new Frame(NavigationSnapshot.EMPTY, NavigationSceneSnapshot.EMPTY, false,
                false, false, false, false, false, false, false, false, 64, false, 0, 0, 0);
    }

    private WorldVisualization(Minecraft client, AutomationEngine engine) {
        this.client = client;
        this.engine = engine;
    }

    /** Register after the engine is created; rendering consumes only immutable snapshots. */
    public static void register(Minecraft client, AutomationEngine engine) {
        WorldVisualization visualization = new WorldVisualization(client, engine);
        ClientTickEvents.END_CLIENT_TICK.register(visualization::tick);
        WorldVisualizationRenderApi.register(visualization);
    }

    private void tick(Minecraft tickClient) {
        if (tickClient != client) return;
        boolean markerDisplay = engine.config.showClaims || engine.config.showStations || engine.config.showBackfill;
        if (client.player == null || client.level == null || WorldVisualizationHudApi.isHidden(client)
                || !engine.visualizationActive() && !markerDisplay || engine.visualizationPaused()) {
            clear();
            return;
        }

        Object world = client.level;
        Object dimension = client.level.dimension();
        if (world != observedWorld || !Objects.equals(dimension, observedDimension)) {
            frame = Frame.EMPTY;
            observedWorld = world;
            observedDimension = dimension;
            ticksSinceSnapshot = 0;
        }
        if (++ticksSinceSnapshot < SNAPSHOT_INTERVAL_TICKS) return;
        ticksSinceSnapshot = 0;

        boolean showPath = engine.config.showPath;
        boolean showSearch = engine.config.showSearch;
        boolean showNextBreak = engine.config.showNextBreak;
        boolean showNextPlace = engine.config.showNextPlace;
        boolean showParkour = engine.config.showParkour;
        boolean showClaims = engine.config.showClaims;
        boolean showStations = engine.config.showStations;
        boolean showBackfill = engine.config.showBackfill;
        if (!showPath && !showSearch && !showNextBreak && !showNextPlace && !showParkour
                && !showClaims && !showStations && !showBackfill) {
            frame = Frame.EMPTY;
            return;
        }

        boolean needsNavigation = showPath || showSearch || showNextBreak || showNextPlace || showParkour;
        NavigationSnapshot navigation = engine.visualizationActive() && needsNavigation
                ? engine.visualizationNavigation(showSearch) : NavigationSnapshot.EMPTY;
        if (navigation == null) navigation = NavigationSnapshot.EMPTY;
        int distance = Math.max(8, Math.min(128, engine.config.visualizationDistance));
        NavigationSceneSnapshot scene = captureScene(navigation.scene(), showClaims, showStations,
                showBackfill, client.player.getX(), client.player.getY(), client.player.getZ(), distance);
        BlockPos target = showPath ? engine.visualizationTarget() : null;
        frame = new Frame(navigation, scene, true, showPath, showSearch, showNextBreak,
                showNextPlace, showParkour, showClaims, showStations, showBackfill, distance, target != null,
                target == null ? 0 : target.getX(),
                target == null ? 0 : target.getY(), target == null ? 0 : target.getZ());
    }

    private NavigationSceneSnapshot captureScene(NavigationSceneSnapshot source,
                                                 boolean showClaims, boolean showStations,
                                                 boolean showBackfill, double x, double y, double z,
                                                 int distance) {
        ArrayList<NavigationSceneSnapshot.Marker> markers = new ArrayList<>();
        for (NavigationSceneSnapshot.Marker marker : source.markers()) {
            if (markerEnabled(marker, showClaims, showStations, showBackfill)
                    && marker.distanceSquared(x, y, z) <= (double) distance * distance) markers.add(marker);
        }
        if (showClaims || showStations) {
            WorldProtection.PolicySnapshot policy = engine.protection.capture();
            if (!policy.locked() && policy.scope() != null) {
                for (ClaimBox claim : policy.claims().forScope(policy.scope())) {
                    if (!showClaims && !(showStations && claim.preferredStations())) continue;
                    NavigationSceneSnapshot.Marker marker = NavigationSceneSnapshot.Marker.claim(
                            claim.minX(), claim.minY(), claim.minZ(), claim.maxX(), claim.maxY(), claim.maxZ(),
                            claim.preferredStations());
                    if (marker.distanceSquared(x, y, z) <= (double) distance * distance) markers.add(marker);
                }
            }
        }
        markers.sort(Comparator.comparingDouble(marker -> marker.distanceSquared(x, y, z)));
        if (markers.size() > NavigationSceneSnapshot.MAX_MARKERS)
            markers.subList(NavigationSceneSnapshot.MAX_MARKERS, markers.size()).clear();
        return source.withMarkers(markers.toArray(NavigationSceneSnapshot.Marker[]::new));
    }

    private static boolean markerEnabled(NavigationSceneSnapshot.Marker marker, boolean showClaims,
                                         boolean showStations, boolean showBackfill) {
        return switch (marker.kind()) {
            case CLAIM_BOUNDARY -> showClaims || showStations && marker.preferredStations();
            case CONFIRMED_OWNED_STATION, STATION_RECOVERY_PENDING -> showStations;
            case BACKFILL_PENDING_MATCH, BACKFILL_MATCHED -> showBackfill;
        };
    }

    private void clear() {
        frame = Frame.EMPTY;
        observedWorld = null;
        observedDimension = null;
        ticksSinceSnapshot = 0;
    }

    Frame snapshotForRender() { return frame; }

    void draw(Frame current, NavigationOverlay.Lines lines,
              double cameraX, double cameraY, double cameraZ) {
        if (!current.enabled) return;
        NavigationOverlay.draw(current.navigation, current.scene, lines, cameraX, cameraY, cameraZ,
                new NavigationOverlay.Options(current.showPath, current.showSearch, current.showNextBreak,
                        current.showNextPlace, current.showParkour, current.showClaims, current.showStations,
                        current.showBackfill, current.visualizationDistance),
                current.hasTarget, current.targetX, current.targetY, current.targetZ);
    }
}
