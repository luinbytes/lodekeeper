package dev.lodekeeper.core;

import java.util.Objects;

/** A selected tool is reserved and kept in inventory. */
public record ToolRequirement(Ingredient tools, int minimumDurability, String purpose) implements Requirement {
    public ToolRequirement {
        Objects.requireNonNull(tools, "tools");
        if (tools.count() != 1) throw new IllegalArgumentException("Tool requirement count must be one");
        if (minimumDurability < 0) throw new IllegalArgumentException("Minimum durability cannot be negative");
        purpose = RequirementText.normalize(purpose);
    }
}
