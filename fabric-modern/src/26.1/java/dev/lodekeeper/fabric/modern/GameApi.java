package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Small Mojang API seam shared by 26.1, 26.1.1, and 26.1.2. */
final class GameApi {
    private GameApi() {}

    static Screen screen(Minecraft client) { return client.screen; }

    static void setScreen(Minecraft client, Screen screen) { client.setScreen(screen); }

    static KeyMapping keyMapping(String name, int keyCode, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, category);
    }

    static long fuelTicks(Level level, ItemStack stack) {
        if (level == null || stack.isEmpty()) return 0;
        return Math.max(0, Math.min(10_000_000, level.fuelValues().burnDuration(stack)));
    }
}
