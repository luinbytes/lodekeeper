package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;

final class WorldVisualizationHudApi {
    private WorldVisualizationHudApi() { }

    static boolean isHidden(Minecraft client) { return client.gui.hud.isHidden(); }
}
