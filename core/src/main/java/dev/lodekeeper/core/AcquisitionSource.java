package dev.lodekeeper.core;

import java.util.List;
import java.util.Map;

/** Pure description of a way to obtain an item. Implementations may be supplied by extensions. */
public interface AcquisitionSource {
    String sourceId();
    ItemId output();
    int outputCount();
    List<Requirement> requirements();

    /** Extension metadata is copied into the immutable catalog snapshot. */
    default String sourceType() { return "extension"; }
    default Map<String, String> attributes() { return Map.of(); }
}
