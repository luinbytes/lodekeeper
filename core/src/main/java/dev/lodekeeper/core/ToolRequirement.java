package dev.lodekeeper.core;

import java.util.Objects;

/** A selected tool is reserved and kept in inventory. Wear is predicted once per source operation. */
public record ToolRequirement(Ingredient tools, int minimumDurability, String purpose, int wearPerOperation) implements Requirement {
    private static final int MAX_WEAR_PER_OPERATION = 1_000_000;

    public ToolRequirement {
        Objects.requireNonNull(tools, "tools");
        if (tools.count() != 1) throw new IllegalArgumentException("Tool requirement count must be one");
        if (minimumDurability < 0) throw new IllegalArgumentException("Minimum durability cannot be negative");
        if (wearPerOperation < 0 || wearPerOperation > MAX_WEAR_PER_OPERATION) {
            throw new IllegalArgumentException("Wear per operation out of range");
        }
        if (wearPerOperation > 0 && minimumDurability < wearPerOperation + 1) {
            throw new IllegalArgumentException("A wear-bearing tool must retain one durability point after an operation");
        }
        purpose = RequirementText.normalize(purpose);
    }

    /** Retains the legacy minimum-only contract for custom sources. */
    public ToolRequirement(Ingredient tools, int minimumDurability, String purpose) {
        this(tools, minimumDurability, purpose, 0);
    }
}
