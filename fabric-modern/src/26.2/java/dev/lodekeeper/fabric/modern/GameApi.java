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

/** Small Mojang API seam for GUI and fuel changes in 26.2. */
final class GameApi {
    private GameApi() {}

    static Screen screen(Minecraft client) { return client.gui.screen(); }

    static void setScreen(Minecraft client, Screen screen) { client.gui.setScreen(screen); }

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

    static boolean dynamicCookingSpeed() { return false; }

    static boolean supportedCookingFuelStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getMaxStackSize() <= 99
                && stack.getItem().getCraftingRemainder() == null;
    }

    static long cookingFuelProgressTicks(Level level, ItemStack stack,
            net.minecraft.world.level.block.Block station, int recipeDuration) {
        if (level == null || recipeDuration < 100 || recipeDuration > 10_000_000
                || !supportedCookingFuelStack(stack)) return 0;
        long burn = fuelTicks(level, stack);
        if (station == net.minecraft.world.level.block.Blocks.SMOKER
                || station == net.minecraft.world.level.block.Blocks.BLAST_FURNACE) burn /= 2;
        else if (station != net.minecraft.world.level.block.Blocks.FURNACE) return 0;
        if (burn < 32 || stack.getMaxStackSize() > 1
                && (long) Math.max(1, stack.getMaxStackSize() / 2) * burn < 800) return 0;
        return stack.getMaxStackSize() == 1 ? burn - burn % recipeDuration : burn;
    }

    static long initialFuelTicks(Level level, ItemStack stack) { return fuelTicks(level, stack); }

    static long fuelTicks(Level level, ItemStack stack) {
        if (level == null || stack.isEmpty()) return 0;
        return Math.max(0, Math.min(10_000_000, level.fuelValues().burnDuration(stack)));
    }
}
