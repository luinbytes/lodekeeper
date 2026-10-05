package dev.lodekeeper.core;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Optional, advisory source ranks for deterministic planner ordering.
 *
 * A lower rank means a source is preferred among otherwise comparable choices.
 * Missing ranks remain unknown and never make a source unusable.
 */
public final class PlanningPreferences {
    public static final int MAX_ENTRIES = 100_000;
    public static final int MAX_RANK = 1_000_000_000;
    public static final PlanningPreferences NONE = new PlanningPreferences(Map.of());

    private final Map<String, Integer> sourceRanks;
    private final List<String> rankedSourceIds;

    public PlanningPreferences(Map<String, Integer> sourceRanks) {
        Objects.requireNonNull(sourceRanks, "sourceRanks");
        if (sourceRanks.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many source preference ranks");
        }
        var copy = new TreeMap<String, Integer>();
        sourceRanks.forEach((sourceId, rank) -> {
            String validSourceId = SourceValidation.sourceId(sourceId);
            Objects.requireNonNull(rank, "rank");
            if (rank < 0 || rank > MAX_RANK) {
                throw new IllegalArgumentException("Source preference rank out of range for " + validSourceId);
            }
            copy.put(validSourceId, rank);
        });
        this.sourceRanks = Collections.unmodifiableMap(copy);
        this.rankedSourceIds = copy.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Returns the immutable source-ID to rank snapshot. */
    public Map<String, Integer> sourceRanks() {
        return sourceRanks;
    }

    Integer rankOf(String sourceId) {
        return sourceRanks.get(sourceId);
    }

    List<String> rankedSourceIds() {
        return rankedSourceIds;
    }

    public boolean isEmpty() {
        return sourceRanks.isEmpty();
    }
}
