package dev.lodekeeper.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded catalog of native navigation settings that automation may lease. */
public final class NavigationPreferenceCatalog {
    private static final int MAX_OVERRIDES = 128;
    private static final List<Preference> PREFERENCES = List.of(
            bool("allowPlaceInFluidsSource", "Place in still water", "Placement",
                    "Allow permitted block placement in still source fluids. Requires block placement to be enabled.", true, "allowBuilding"),
            bool("allowPlaceInFluidsFlow", "Place in flowing water", "Placement",
                    "Allow permitted block placement in flowing fluids. Requires block placement to be enabled.", true, "allowBuilding"),
            bool("allowDownward", "Mine directly below", "Movement",
                    "Allow navigation to mine the block underfoot. Turn this off to favor stairs over shafts.", true, "allowBreaking"),
            bool("allowWalkOnBottomSlab", "Walk on bottom slabs", "Movement",
                    "Allow bottom slabs as walkable floors. Turn this off if slab layouts make routes unreliable.", true, null),
            bool("allowParkourAscend", "Parkour while climbing", "Movement",
                    "Allow parkour jumps on upward routes. This only applies when Allow parkour is enabled.", true, "allowParkour"),
            bool("allowJumpAtBuildLimit", "Jump at the build limit", "Movement",
                    "Allow parkour jumps from the highest buildable blocks. Some servers reject these jumps.", false, "allowParkour"),
            bool("sprintAscends", "Sprint on climbs", "Movement",
                    "Start sprinting and jumping one block early on upward routes when sprinting is enabled.", true, "allowSprint"),
            bool("sprintInWater", "Sprint while swimming", "Movement",
                    "Allow sprinting while moving through water. This does not enable walking on water.", true, "allowSprint"),
            bool("overshootTraverse", "Accept overshot traverses", "Movement",
                    "Treat a traverse as complete when fast movement carries the player one block past it.", true, null),
            bool("strictLiquidCheck", "Protect blocks beside liquids", "Safety",
                    "Do not break blocks adjacent to liquid. This helps with custom fluid behavior.", false, "allowBreaking"),
            bool("avoidUpdatingFallingBlocks", "Prevent falling-block cascades", "Safety",
                    "Avoid breaking blocks beside unsupported sand or gravel that could fall.", true, "allowBreaking"),
            bool("pauseMiningForFallingBlocks", "Wait for falling blocks", "Safety",
                    "Wait for falling blocks to settle before continuing a mining movement.", true, "allowBreaking"),
            bool("cutoffAtLoadBoundary", "Stop at loaded chunks", "Safety",
                    "Cut a calculated route at the edge of loaded chunks. This can make long routes replan sooner.", false, null),
            integer("costVerificationLookahead", "Hazard check distance", "Safety",
                    "Stop this many movements before a path segment whose cost became unsafe. The minimum keeps the native default margin.", 5, 16, 5, null),
            decimal("maxCostIncrease", "Allowed path cost change", "Safety",
                    "Cancel and recalculate if a movement's cost rises above this amount. Lower values react sooner to world changes.", 1, 10, 10, null),
            bool("splicePath", "Join path segments", "Path planning",
                    "Join the next calculated path segment to the current path when they meet.", true, null),
            bool("blacklistClosestOnFailure", "Retry around failed routes", "Path planning",
                    "Temporarily avoid the closest failed path position when calculating a retry.", true, null),
            bool("considerPotionEffects", "Account for potion effects", "Path costs",
                    "Adjust block-breaking estimates for effects such as Haste and Mining Fatigue.", true, null),
            decimal("blockPlacementPenalty", "Block placement cost", "Path costs",
                    "Path cost for placing a block. Higher values discourage routes that use blocks.", 20, 60, 20, "allowBuilding"),
            decimal("blockBreakAdditionalPenalty", "Extra block-breaking cost", "Path costs",
                    "Small extra path cost for breaking a block when another route is otherwise equal.", 2, 10, 2, "allowBreaking"),
            decimal("jumpPenalty", "Jump cost", "Path costs",
                    "Path cost added for jumps and other movements that use hunger.", 2, 10, 2, null),
            decimal("mobAvoidanceCoefficient", "Mob avoidance strength", "Mob avoidance",
                    "How strongly native path costs avoid mobs. Values below one encourage paths near mobs and are excluded.", 1, 4, 1.5, "avoidance"),
            decimal("mobSpawnerAvoidanceCoefficient", "Spawner avoidance strength", "Mob avoidance",
                    "How strongly native path costs avoid spawners. Values below one encourage paths near spawners and are excluded.", 1, 4, 2, "avoidance")
    );
    private static final Map<String, Preference> BY_KEY = index();
    private static final List<String> CATEGORIES = categoriesOf(PREFERENCES);

    private NavigationPreferenceCatalog() {}

    public static List<Preference> entries() { return PREFERENCES; }

    public static List<String> categories() { return CATEGORIES; }

    public static Preference require(String key) {
        Preference preference = BY_KEY.get(key);
        if (preference == null) throw unsupported(key);
        return preference;
    }

    public static List<Preference> matching(String category, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return PREFERENCES.stream()
                .filter(preference -> category == null || category.isBlank() || category.equals("All")
                        || preference.category().equals(category))
                .filter(preference -> needle.isEmpty() || preference.matches(needle))
                .toList();
    }

    /** Validates user edits and removes overrides that equal their catalog defaults. */
    public static Map<String, String> validateOverrides(Object raw) {
        if (!(raw instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Advanced navigation preferences must be a key/value map.");
        if (map.size() > MAX_OVERRIDES)
            throw new IllegalArgumentException("Advanced navigation preferences are limited to " + MAX_OVERRIDES + " entries.");
        Map<String, String> validated = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text))
                throw new IllegalArgumentException("Advanced navigation keys and values must be text.");
            if (text.isBlank() || text.length() > 128)
                throw new IllegalArgumentException("Advanced navigation values must contain 1 to 128 characters.");
            Preference preference = require(key);
            String canonical = preference.format(preference.parse(text));
            if (!preference.isDefault(preference.parse(canonical))) validated.put(key, canonical);
        }
        return immutable(validated);
    }

    /** Filters old or malformed profile data before it can reach native settings. */
    public static Map<String, String> sanitizeStored(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> safe = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text)
                    || text.isBlank() || text.length() > 128) continue;
            Preference preference = BY_KEY.get(key);
            if (preference == null) continue;
            try {
                Object value = preference.parse(text);
                if (!preference.isDefault(value)) safe.put(key, preference.format(value));
            } catch (IllegalArgumentException ignored) { }
        }
        return immutable(safe);
    }

    /** Returns only typed, known, non-default values that the native adapter may lease. */
    public static Map<String, Object> nativeValues(Object raw) {
        Map<String, String> safe = sanitizeStored(raw);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Preference preference : PREFERENCES) {
            String text = safe.get(preference.key());
            if (text != null) values.put(preference.key(), preference.parse(text));
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Preference> index() {
        Map<String, Preference> indexed = new LinkedHashMap<>();
        for (Preference preference : PREFERENCES) {
            if (indexed.put(preference.key(), preference) != null)
                throw new IllegalStateException("Duplicate advanced navigation preference: " + preference.key());
            preference.validate(preference.defaultValue());
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static List<String> categoriesOf(List<Preference> preferences) {
        Set<String> categories = new LinkedHashSet<>();
        categories.add("All");
        for (Preference preference : preferences) categories.add(preference.category());
        return List.copyOf(categories);
    }

    private static Map<String, String> immutable(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Preference bool(String key, String label, String category, String help,
                                   boolean defaultValue, String parentKey) {
        return new Preference(key, label, category, help, Type.BOOLEAN, 0, 1,
                Boolean.toString(defaultValue), parentKey);
    }

    private static Preference integer(String key, String label, String category, String help,
                                      int minimum, int maximum, int defaultValue, String parentKey) {
        return new Preference(key, label, category, help, Type.INTEGER, minimum, maximum,
                Integer.toString(defaultValue), parentKey);
    }

    private static Preference decimal(String key, String label, String category, String help,
                                      double minimum, double maximum, double defaultValue, String parentKey) {
        return new Preference(key, label, category, help, Type.DECIMAL, minimum, maximum,
                Double.toString(defaultValue), parentKey);
    }

    private static IllegalArgumentException unsupported(String key) {
        return new IllegalArgumentException("Unsupported advanced navigation option: " + key);
    }

    public enum Type { BOOLEAN, INTEGER, DECIMAL }

    public record Preference(String key, String label, String category, String help, Type type,
                             double minimum, double maximum, String defaultText, String parentKey) {
        public Preference {
            if (key == null || key.isBlank() || label == null || label.isBlank()
                    || category == null || category.isBlank() || help == null || help.isBlank()
                    || type == null || defaultText == null || !Double.isFinite(minimum)
                    || !Double.isFinite(maximum) || minimum > maximum)
                throw new IllegalArgumentException("Invalid advanced navigation preference descriptor");
            switch (type) {
                case BOOLEAN -> {
                    if (!defaultText.equals("true") && !defaultText.equals("false"))
                        throw new IllegalArgumentException("Invalid boolean default for " + key);
                }
                case INTEGER -> {
                    if (minimum != Math.rint(minimum) || maximum != Math.rint(maximum))
                        throw new IllegalArgumentException("Integer preference bounds must be whole numbers for " + key);
                    try { Integer.parseInt(defaultText); }
                    catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid integer default for " + key, invalid); }
                }
                case DECIMAL -> {
                    try {
                        if (!Double.isFinite(Double.parseDouble(defaultText)))
                            throw new IllegalArgumentException("Invalid decimal default for " + key);
                    } catch (NumberFormatException invalid) {
                        throw new IllegalArgumentException("Invalid decimal default for " + key, invalid);
                    }
                }
            }
        }

        public Object defaultValue() { return parse(defaultText); }

        public Object parse(String text) {
            if (text == null) throw invalid();
            Object parsed;
            try {
                parsed = switch (type) {
                    case BOOLEAN -> {
                        if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) throw invalid();
                        yield Boolean.valueOf(text);
                    }
                    case INTEGER -> Integer.valueOf(text);
                    case DECIMAL -> Double.valueOf(text);
                };
            } catch (NumberFormatException invalidNumber) {
                throw invalid();
            }
            return validate(parsed);
        }

        public Object validate(Object value) {
            boolean valid = switch (type) {
                case BOOLEAN -> value instanceof Boolean;
                case INTEGER -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() == Math.rint(number.doubleValue())
                        && number.doubleValue() >= minimum && number.doubleValue() <= maximum;
                case DECIMAL -> value instanceof Number number && Double.isFinite(number.doubleValue())
                        && number.doubleValue() >= minimum && number.doubleValue() <= maximum;
            };
            if (!valid) throw invalid();
            return switch (type) {
                case BOOLEAN -> value;
                case INTEGER -> ((Number) value).intValue();
                case DECIMAL -> ((Number) value).doubleValue();
            };
        }

        public String format(Object value) {
            Object validated = validate(value);
            return switch (type) {
                case BOOLEAN -> Boolean.toString((Boolean) validated);
                case INTEGER -> Integer.toString(((Number) validated).intValue());
                case DECIMAL -> Double.toString(((Number) validated).doubleValue());
            };
        }

        public boolean isDefault(Object value) {
            return defaultValue().equals(validate(value));
        }

        public boolean matches(String needle) {
            return contains(key, needle) || contains(label, needle) || contains(category, needle)
                    || contains(help, needle) || contains(type.name(), needle)
                    || parentKey != null && contains(parentKey, needle);
        }

        private IllegalArgumentException invalid() {
            return switch (type) {
                case BOOLEAN -> new IllegalArgumentException("Invalid value for " + label + ", use true or false.");
                case INTEGER, DECIMAL -> new IllegalArgumentException("Invalid value for " + label
                        + ", use a value from " + minimum + " to " + maximum + ".");
            };
        }

        private static boolean contains(String value, String needle) {
            return value.toLowerCase(Locale.ROOT).contains(needle);
        }
    }
}
