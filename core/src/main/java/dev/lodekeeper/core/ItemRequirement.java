package dev.lodekeeper.core;

import java.util.Objects;

/** Items may be consumed as materials or retained as supplies. */
public record ItemRequirement(Ingredient ingredient, boolean consume, String purpose) implements Requirement {
    public ItemRequirement {
        Objects.requireNonNull(ingredient, "ingredient");
        purpose = RequirementText.normalize(purpose);
    }
}
