package dev.lodekeeper.fabric.modern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

import java.util.Set;
import java.util.stream.Collectors;

/** Small Mojang API seam shared by 26.1, 26.1.1, and 26.1.2. */
final class GameApi {
    private GameApi() {}

    static Screen screen(Minecraft client) { return client.screen; }

    static void setScreen(Minecraft client, Screen screen) { client.setScreen(screen); }

    static KeyMapping keyMapping(String name, int keyCode, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, keyCode, category);
    }

    static int blockBreakWear(ItemStack stack) {
        if (!stack.isDamageableItem()) return 0;
        var tool = stack.get(net.minecraft.core.component.DataComponents.TOOL);
        return tool == null ? -1 : tool.damagePerBlock();
    }

    static void swing(LocalPlayer player, InteractionHand hand) { player.swing(hand); }

    static Set<Item> tagItems(SlotDisplay.TagSlotDisplay display, Level level) {
        var holders = level.registryAccess().lookupOrThrow(Registries.ITEM).get(display.tag())
                .orElseThrow(() -> new IllegalArgumentException("Unknown item tag: " + display.tag()));
        if (!holders.isBound()) throw new IllegalArgumentException("Unbound item tag: " + display.tag());
        Set<Item> items = holders.stream().map(Holder::value).collect(Collectors.toUnmodifiableSet());
        if (items.isEmpty()) throw new IllegalArgumentException("Empty item tag: " + display.tag());
        return items;
    }

    static long initialFuelTicks(Level level, ItemStack stack) { return fuelTicks(level, stack); }

    static long fuelTicks(Level level, ItemStack stack) {
        if (level == null || stack.isEmpty()) return 0;
        return Math.max(0, Math.min(10_000_000, level.fuelValues().burnDuration(stack)));
    }
}
