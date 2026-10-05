package dev.lodekeeper.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Immutable named inventory target. Goals are exact item counts, not world-building instructions. */
public record ProjectSpec(String name, String description, Map<ItemId, Integer> goals, Purpose purpose) {
    public static final int MAX_GOALS = 32;
    public static final int MAX_COUNT = 1_000_000;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    public enum Purpose {
        /** The project asks only for the listed inventory items. */
        INVENTORY_GOALS,
        /** The listed items are supplies; this project does not construct or place a structure. */
        SUPPLIES_ONLY
    }

    public ProjectSpec {
        name = normalizeName(name);
        Objects.requireNonNull(description, "description");
        description = description.trim();
        if (description.isEmpty() || description.length() > 240) {
            throw new IllegalArgumentException("Project description must be 1..240 characters");
        }
        Objects.requireNonNull(goals, "goals");
        Objects.requireNonNull(purpose, "purpose");
        if (goals.isEmpty() || goals.size() > MAX_GOALS) {
            throw new IllegalArgumentException("A project must contain 1.." + MAX_GOALS + " item goals");
        }
        TreeMap<ItemId, Integer> sortedGoals = new TreeMap<>();
        for (Map.Entry<ItemId, Integer> entry : goals.entrySet()) {
            ItemId item = Objects.requireNonNull(entry.getKey(), "goal item");
            Integer count = Objects.requireNonNull(entry.getValue(), "goal count");
            if (count < 1 || count > MAX_COUNT) {
                throw new IllegalArgumentException("Project item counts must be between 1 and " + MAX_COUNT);
            }
            sortedGoals.put(item, count);
        }
        LinkedHashMap<ItemId, Integer> ordered = new LinkedHashMap<>();
        sortedGoals.forEach(ordered::put);
        goals = Collections.unmodifiableMap(ordered);
    }

    public static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Project name must match [a-z][a-z0-9_]{0,31}");
        }
        return normalized;
    }
}
