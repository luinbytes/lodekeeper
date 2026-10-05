package dev.lodekeeper.fabric.modern;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Objects;

/** Immutable native recipe facts needed to select one synchronized stonecutting option. */
record StonecuttingWork(String sourceId, Ingredient input, ItemStack outputPerOperation, Object selectionKey) {
    StonecuttingWork {
        if (sourceId == null || !sourceId.startsWith("stonecutting:")
                || input == null || input.isEmpty() || selectionKey == null) {
            throw new IllegalArgumentException("stonecutting work is missing its source, input, or selection key");
        }
        Objects.requireNonNull(outputPerOperation, "outputPerOperation");
        if (outputPerOperation.isEmpty() || outputPerOperation.getCount() > 99) {
            throw new IllegalArgumentException("stonecutting output must be nonempty with count at most 99");
        }
        outputPerOperation = outputPerOperation.copy();
    }

    @Override public ItemStack outputPerOperation() { return outputPerOperation.copy(); }
}
