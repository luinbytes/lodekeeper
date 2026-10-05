package dev.lodekeeper.core;

/** Concrete item, tool or station requirement selected by the planner. */
public sealed interface SelectedRequirement permits SelectedItemRequirement, SelectedToolRequirement, SelectedStationRequirement {
    String purpose();
}
