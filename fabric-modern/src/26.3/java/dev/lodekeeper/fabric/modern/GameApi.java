package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.Level;
import com.mojang.blaze3d.platform.InputConstants;

/** Small Mojang API seam for GUI and item-component fuel access in 26.3. */
final class GameApi {
    private GameApi() {}

    static Screen screen(Minecraft client) { return client.gui.screen(); }

    static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }

    static KeyMapping keyMapping(String name, int keyCode, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, keyCode, category);
    }

    static long fuelTicks(Level level, ItemStack stack) {
        if (level == null || stack.isEmpty()) return 0;
        CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null) return 0;
        try {
            // Context-dependent providers remain unknown to the static planner catalog.
            int ticks = fuel.burnTime().get(null, 0);
            return Math.max(0, Math.min(10_000_000, ticks));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
