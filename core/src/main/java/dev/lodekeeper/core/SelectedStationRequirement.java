package dev.lodekeeper.core;

import java.util.Objects;

/** A station that must be available when a step executes. */
public record SelectedStationRequirement(StationId station, String purpose) implements SelectedRequirement {
    public SelectedStationRequirement {
        Objects.requireNonNull(station, "station");
        purpose = RequirementText.normalize(purpose);
    }
}
