package dev.lodekeeper.core;

import java.util.Objects;

/** Exact item amount selected for a step. count is total across all operations; planner recipe slots identify concrete shaped or shapeless ingredients. */
public record SelectedItemRequirement(ItemId item, int count, boolean consumed, String purpose, int recipeSlot) implements SelectedRequirement {
    public SelectedItemRequirement {
        Objects.requireNonNull(item, "item");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
        purpose = RequirementText.normalize(purpose);
        if (recipeSlot < -1) throw new IllegalArgumentException("recipeSlot must be -1 or nonnegative");
    }
}
