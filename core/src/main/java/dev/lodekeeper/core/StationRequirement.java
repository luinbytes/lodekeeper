package dev.lodekeeper.core;

import java.util.Objects;

/** A station requirement can be met by a known station or placed from its item. */
public record StationRequirement(StationId station, ItemId placementItem, String purpose) implements Requirement {
    public StationRequirement {
        Objects.requireNonNull(station, "station");
        Objects.requireNonNull(placementItem, "placementItem");
        purpose = RequirementText.normalize(purpose);
    }
}
