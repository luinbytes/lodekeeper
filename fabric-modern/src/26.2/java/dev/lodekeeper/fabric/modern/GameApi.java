package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Small Mojang API seam for GUI and fuel changes in 26.2. */
final class GameApi {
    private GameApi() {}

    static Screen screen(Minecraft client) { return client.gui.screen(); }

    static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }

    static KeyMapping keyMapping(String name, int keyCode, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, category);
    }

    static long fuelTicks(Level level, ItemStack stack) {
        if (level == null || stack.isEmpty()) return 0;
        return Math.max(0, Math.min(10_000_000, level.fuelValues().burnDuration(stack)));
    }
}
