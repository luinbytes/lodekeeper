package dev.lodekeeper.fabric;

import dev.lodekeeper.nav.NavigationOverlay;
import dev.lodekeeper.nav.NavigationSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public final class WorldVisualization {
    private static final int SNAPSHOT_INTERVAL_TICKS = 4;
    private static final double NODE_RADIUS_SQUARED = 64.0 * 64.0;

    private final MinecraftClient client;
    private final AutomationEngine engine;
    private volatile Frame frame = Frame.EMPTY;
    private Object observedWorld;
    private Object observedDimension;
    private int ticksSinceSnapshot;

    static record Frame(NavigationSnapshot navigation, boolean enabled,
                        boolean showPath, boolean showSearch, boolean hasTarget,
                        int targetX, int targetY, int targetZ) {
        static final Frame EMPTY = new Frame(NavigationSnapshot.EMPTY, false,
                false, false, false, 0, 0, 0);
    }

    private WorldVisualization(MinecraftClient client, AutomationEngine engine) {
        this.client = client;
        this.engine = engine;
    }

    /** Register after the engine is created; world geometry remains depth-tested. */
    public static void register(MinecraftClient client, AutomationEngine engine) {
        WorldVisualization visualization = new WorldVisualization(client, engine);
        ClientTickEvents.END_CLIENT_TICK.register(visualization::tick);
        WorldVisualizationRenderApi.register(visualization);
    }

    private void tick(MinecraftClient tickClient) {
        if (tickClient != client) return;
        if (client.player == null || client.world == null || client.options.hudHidden
                || !engine.visualizationActive() || engine.visualizationPaused()) {
            clear();
            return;
        }

        Object world = client.world;
        Object dimension = client.world.getRegistryKey();
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
        if (!showPath && !showSearch) {
            frame = Frame.EMPTY;
            return;
        }

        NavigationSnapshot navigation = engine.visualizationNavigation(showSearch);
        if (navigation == null) navigation = NavigationSnapshot.EMPTY;
        BlockPos target = showPath ? engine.visualizationTarget() : null;
        frame = new Frame(navigation, true, showPath, showSearch,
                target != null, target == null ? 0 : target.getX(),
                target == null ? 0 : target.getY(), target == null ? 0 : target.getZ());
    }

    private void clear() {
        frame = Frame.EMPTY;
        observedWorld = null;
        observedDimension = null;
        ticksSinceSnapshot = 0;
    }

    Frame snapshotForRender() { return frame; }

    boolean isVisibleNow() { return engine.visualizationActive() && !engine.visualizationPaused(); }

    boolean isHudHiddenNow() { return client.options.hudHidden; }

    void draw(Frame current, NavigationOverlay.Lines lines,
              double cameraX, double cameraY, double cameraZ) {
        if (!current.enabled) return;

        if (current.showPath) {
            NavigationOverlay.draw(current.navigation, lines, cameraX, cameraY, cameraZ,
                    current.showSearch, current.hasTarget,
                    current.targetX, current.targetY, current.targetZ);
        } else if (current.showSearch) {
            drawSearchNodes(current.navigation, lines, cameraX, cameraY, cameraZ);
        }
    }

    private static void drawSearchNodes(NavigationSnapshot navigation, NavigationOverlay.Lines lines,
                                        double cameraX, double cameraY, double cameraZ) {
        for (int i = 0; i < navigation.nodeCount(); i++) {
            double x = navigation.nodeX(i) + .5;
            double y = navigation.nodeY(i) + .1;
            double z = navigation.nodeZ(i) + .5;
            double dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ;
            if (dx * dx + dy * dy + dz * dz > NODE_RADIUS_SQUARED) continue;
            int color = navigation.nodeIsClosed(i) ? NavigationOverlay.CLOSED : NavigationOverlay.OPEN;
            lines.line(x - .1, y, z, x + .1, y, z, color);
            lines.line(x, y, z - .1, x, y, z + .1, color);
        }
    }
}
