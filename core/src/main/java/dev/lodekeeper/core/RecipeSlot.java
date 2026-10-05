package dev.lodekeeper.core;

import java.util.Objects;

/** One recipe ingredient; shaped indices are row-major and shapeless indices are -1. */
public record RecipeSlot(int slotIndex, Ingredient ingredient) {
    public RecipeSlot {
        Objects.requireNonNull(ingredient, "ingredient");
        if (slotIndex < -1) throw new IllegalArgumentException("slotIndex must be -1 or nonnegative");
    }
}
