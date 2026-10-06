package dev.lodekeeper.fabric.modern;

import net.minecraft.world.item.ItemStack;

/** Component-aware stack comparison for the modern Mojang-mapped item profile. */
final class PlacementStackProfile {
    private PlacementStackProfile() { }

    static boolean sameItemAndComponents(ItemStack left, ItemStack right) {
        return ItemStack.isSameItemSameComponents(left, right);
    }
}
