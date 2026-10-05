package dev.lodekeeper.core;

import java.util.Objects;

/** A concrete held tool reserved for a step. */
public record SelectedToolRequirement(ItemId item, int minimumDurability, String purpose) implements SelectedRequirement {
    public SelectedToolRequirement {
        Objects.requireNonNull(item, "item");
        if (minimumDurability < 0) throw new IllegalArgumentException("minimumDurability cannot be negative");
        purpose = RequirementText.normalize(purpose);
    }
}
