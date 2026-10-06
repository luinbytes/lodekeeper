package dev.lodekeeper.fabric;

import net.minecraft.item.ItemStack;

/** Component-aware stack comparison for the 1.21.4 item profile. */
final class PlacementStackProfile {
    private PlacementStackProfile() { }

    static boolean sameItemAndComponents(ItemStack left, ItemStack right) {
        return ItemStack.areItemsAndComponentsEqual(left, right);
    }
}
