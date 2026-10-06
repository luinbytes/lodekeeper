package dev.lodekeeper.fabric;

import net.minecraft.item.ItemStack;

/** Component-aware stack comparison for the 1.20.5–1.20.6 item profile. */
final class PlacementStackProfile {
    private PlacementStackProfile() { }

    static boolean sameItemAndComponents(ItemStack left, ItemStack right) {
        return ItemStack.areItemsAndComponentsEqual(left, right);
    }
}
