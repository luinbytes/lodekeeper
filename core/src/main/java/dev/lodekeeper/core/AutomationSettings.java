package dev.lodekeeper.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Metadata and a cached codec for trusted public configuration fields. */
public final class AutomationSettings {
    private AutomationSettings() {}
    private static final List<SettingSpec> SPECS = List.of(
            new SettingSpec("prefix", "Command prefix", "General", "Prefix for local commands. Keep the trailing space when desired.", SettingSpec.Type.TEXT, 1, 16, "!lk ", null),
            new SettingSpec("searchRadius", "Nearby search radius", "Search", "Maximum nearby search distance in blocks.", SettingSpec.Type.INTEGER, 8, 96, 48, null),
            new SettingSpec("allowExploration", "Explore for resources", "Search", "Allow the job to travel beyond nearby resources.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("explorationAttempts", "Exploration attempts", "Search", "Maximum exploration attempts within one job.", SettingSpec.Type.INTEGER, 1, 128, 32, "allowExploration"),
            new SettingSpec("explorationDistance", "Exploration distance", "Search", "Maximum horizontal exploration distance in blocks.", SettingSpec.Type.INTEGER, 16, 2048, 512, "allowExploration"),
            new SettingSpec("scanBlocksPerTick", "Blocks scanned per tick", "Search", "Maximum world blocks inspected by local discovery each tick.", SettingSpec.Type.INTEGER, 32, 2048, 512, null),
            new SettingSpec("actionTimeoutTicks", "Action timeout", "Safety", "Maximum ticks before an action times out. Twenty ticks equal one second.", SettingSpec.Type.INTEGER, 100, 6000, 1200, null),
            new SettingSpec("pauseBelowHealth", "Pause below health", "Safety", "Pause ordinary work below this health. Two health points equal one heart.", SettingSpec.Type.DECIMAL, 1, 20, 6.0f, null),
            new SettingSpec("allowBreaking", "Allow block breaking", "World edits", "Allow automation to break blocks permitted by its protection rules.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("allowBuilding", "Allow block placement", "World edits", "Allow automation to place available blocks permitted by its protection rules.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("allowParkour", "Allow parkour", "Movement", "Allow native parkour movements during navigation.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("allowParkourPlace", "Place during parkour", "Movement", "Allow parkour placement when parkour and block placement are both enabled.", SettingSpec.Type.BOOLEAN, 0, 1, true, "allowParkour"),
            new SettingSpec("allowSprint", "Allow sprinting", "Movement", "Allow native navigation to sprint.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("allowDiagonalAscend", "Diagonal ascent", "Movement", "Allow native diagonal upward movements.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("allowDiagonalDescend", "Diagonal descent", "Movement", "Allow native diagonal downward movements.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("maxFallHeight", "Maximum fall height", "Movement", "Maximum navigation drop without water, in blocks.", SettingSpec.Type.INTEGER, 0, 8, 3, null),
            new SettingSpec("allowWaterBucketFall", "Water bucket falls", "Movement", "Allow native water bucket fall recovery when a bucket is available.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("allowContainers", "Use containers", "Inventory", "Allow automation to access supported storage containers.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("pauseOnScreen", "Pause with screens open", "Safety", "Pause ordinary automation when a screen is open.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("autoEat", "Eat automatically", "Safety", "Eat available food when the safety policy requires it.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("autoEquipArmor", "Equip armor automatically", "Safety", "Equip available armor to improve protection.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("autoDefend", "Defend automatically", "Safety", "Allow supported defensive combat against immediate threats.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("optimizeWoodTools", "Optimize wooden tools", "Inventory", "Avoid unnecessary wooden tools when stronger usable tools are available.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("avoidance", "Avoid mobs and spawners", "Movement", "Apply native mob and spawner avoidance costs to path searches.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("mobAvoidanceRadius", "Mob avoidance radius", "Movement", "Radius in blocks around mobs used by native avoidance.", SettingSpec.Type.INTEGER, 0, 32, 8, "avoidance"),
            new SettingSpec("spawnerAvoidanceRadius", "Spawner avoidance radius", "Movement", "Radius in blocks around spawners used by native avoidance.", SettingSpec.Type.INTEGER, 0, 64, 16, "avoidance"),
            new SettingSpec("mineGoalUpdateTicks", "Mining goal update interval", "Search", "Ticks between native mining goal updates. Zero disables periodic rescans.", SettingSpec.Type.INTEGER, 0, 1200, 0, null),
            new SettingSpec("pathInitialSearchMillis", "Initial search budget", "Search", "Primary native path calculation budget in milliseconds.", SettingSpec.Type.INTEGER, 50, 10000, 500, null),
            new SettingSpec("pathInitialFailureMillis", "Initial search failure budget", "Search", "Maximum initial native path search time before failure, in milliseconds.", SettingSpec.Type.INTEGER, 100, 30000, 2000, null),
            new SettingSpec("pathContinuationSearchMillis", "Continuation search budget", "Search", "Primary native search budget for the next path segment, in milliseconds.", SettingSpec.Type.INTEGER, 50, 10000, 4000, null),
            new SettingSpec("pathContinuationFailureMillis", "Continuation failure budget", "Search", "Maximum next-segment native search time before failure, in milliseconds.", SettingSpec.Type.INTEGER, 100, 30000, 5000, null),
            new SettingSpec("recoverPlacedStations", "Recover placed stations", "World edits", "Recover unchanged stations confirmed as placed by this job after draining them.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("stationRecoveryRange", "Station recovery range", "World edits", "Maximum distance in blocks for recovering an owned station.", SettingSpec.Type.INTEGER, 1, 16, 8, "recoverPlacedStations"),
            new SettingSpec("backfill", "Restore mined spaces", "World edits", "Restore confirmed bot breaks using surplus matching blocks.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("backfillPendingLimit", "Pending restoration limit", "World edits", "Maximum remembered breaks waiting for surplus restoration blocks.", SettingSpec.Type.INTEGER, 16, 2048, 256, "backfill"),
            new SettingSpec("backfillEquivalentStone", "Equivalent stone restoration", "World edits", "Treat stone and cobblestone as equivalent restoration material.", SettingSpec.Type.BOOLEAN, 0, 1, true, "backfill"),
            new SettingSpec("showPath", "Show navigation path", "Display", "Draw the active navigation path.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("showSearch", "Show search progress", "Display", "Draw bounded navigation search progress.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("showHud", "Show status display", "Display", "Show the automation status display.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("showNextBreak", "Show next block break", "Display", "Draw the next confirmed planned break action.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("showNextPlace", "Show next block placement", "Display", "Draw the next confirmed planned placement action.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("showParkour", "Show parkour movements", "Display", "Draw previews for actual native parkour movements.", SettingSpec.Type.BOOLEAN, 0, 1, false, "showPath"),
            new SettingSpec("showClaims", "Show protected claims", "Display", "Draw claim boundaries within the visual distance limit.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("showStations", "Show station ownership", "Display", "Draw known station ownership and recovery state.", SettingSpec.Type.BOOLEAN, 0, 1, false, null),
            new SettingSpec("showBackfill", "Show restoration progress", "Display", "Draw pending and completed restoration actions.", SettingSpec.Type.BOOLEAN, 0, 1, false, "backfill"),
            new SettingSpec("visualizationDistance", "Visualization distance", "Display", "Maximum distance in blocks for automation overlays.", SettingSpec.Type.INTEGER, 8, 128, 64, null),
            new SettingSpec("debugLogging", "Detailed logging", "General", "Write detailed local automation diagnostics.", SettingSpec.Type.BOOLEAN, 0, 1, true, null),
            new SettingSpec("navigationPreferences", "Advanced navigation preferences", "Advanced", "Per-profile native settings. At most 128 entries with keys up to 64 characters and values up to 128.", SettingSpec.Type.STRING_MAP, 0, 128, Map.of(), null)
    );
    private static final Map<String, SettingSpec> BY_KEY = index();
    private static final Map<Class<?>, Codec<?>> CODECS = new ConcurrentHashMap<>();
    private static final Set<String> RETIRED = Set.of("pathNodesPerTick", "pathNodeLimit", "pathMillisPerTick");

    public static List<SettingSpec> specs() { return SPECS; }
    public static SettingSpec spec(String key) {
        SettingSpec spec = BY_KEY.get(key);
        if (spec == null) throw new IllegalArgumentException("Unknown setting: " + key);
        return spec;
    }

    private static Map<String, SettingSpec> index() {
        Map<String, SettingSpec> indexed = new LinkedHashMap<>();
        for (SettingSpec spec : SPECS) {
            if (indexed.put(spec.key(), spec) != null) throw new IllegalStateException("Duplicate setting: " + spec.key());
            spec.validate(spec.defaultValue());
        }
        for (SettingSpec spec : SPECS)
            if (spec.parentKey() != null && !indexed.containsKey(spec.parentKey()))
                throw new IllegalStateException("Unknown parent setting: " + spec.parentKey());
        return Map.copyOf(indexed);
    }

    @SuppressWarnings("unchecked")
    public static <T> Codec<T> codec(Class<T> configClass) {
        return (Codec<T>) CODECS.computeIfAbsent(configClass, Codec::new);
    }

    public static final class Codec<T> {
        private final Map<String, Field> fields;

        private Codec(Class<T> configClass) {
            Map<String, Field> found = new LinkedHashMap<>();
            for (Field field : configClass.getFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                SettingSpec spec = BY_KEY.get(field.getName());
                if (spec == null || Modifier.isFinal(field.getModifiers()) || !matches(field, spec.type()))
                    throw new IllegalArgumentException("Unsupported public config field: " + field.getName());
                found.put(field.getName(), field);
            }
            if (!found.keySet().equals(BY_KEY.keySet()))
                throw new IllegalArgumentException("Config fields do not cover all automation settings: " + configClass.getName());
            fields = Map.copyOf(found);
        }

        private static boolean matches(Field field, SettingSpec.Type type) {
            return switch (type) {
                case BOOLEAN -> field.getType() == boolean.class;
                case INTEGER -> field.getType() == int.class;
                case DECIMAL -> field.getType() == float.class;
                case TEXT -> field.getType() == String.class;
                case STRING_MAP -> field.getType() == Map.class
                        && field.getGenericType() instanceof ParameterizedType parameterized
                        && List.of(parameterized.getActualTypeArguments()).equals(List.of(String.class, String.class));
            };
        }

        public Object read(T config, String key) {
            SettingSpec spec = spec(key);
            try { return spec.normalize(fields.get(key).get(config)); }
            catch (IllegalAccessException ex) { throw new IllegalStateException("Config field is inaccessible: " + key, ex); }
        }

        public void write(T config, String key, String text) { assign(config, key, spec(key).parse(text)); }
        public void writeValue(T config, String key, Object value) { assign(config, key, spec(key).validate(value)); }
        public void reset(T config, String key) { assign(config, key, spec(key).defaultValue()); }
        public void resetAll(T config) { for (SettingSpec spec : SPECS) reset(config, spec.key()); }

        public void sanitize(T config, Consumer<String> warning) {
            for (SettingSpec spec : SPECS) {
                try { importValue(config, spec, fields.get(spec.key()).get(config), warning); }
                catch (IllegalAccessException ex) { throw new IllegalStateException("Config field is inaccessible", ex); }
            }
        }

        public void importValues(T config, Map<String, ?> values, Consumer<String> warning) {
            for (var entry : values.entrySet()) {
                SettingSpec spec = BY_KEY.get(entry.getKey());
                if (spec == null) {
                    if (!RETIRED.contains(entry.getKey())) warning.accept("Unknown config setting ignored: " + entry.getKey());
                } else importValue(config, spec, entry.getValue(), warning);
            }
        }

        private void importValue(T config, SettingSpec spec, Object value, Consumer<String> warning) {
            Object normalized = spec.normalize(value);
            try { spec.validate(value); }
            catch (IllegalArgumentException ex) { warning.accept("Malformed or out-of-range config setting normalized: " + spec.key()); }
            assign(config, spec.key(), normalized);
        }

        private void assign(T config, String key, Object value) {
            if (value instanceof Map<?, ?> map) value = new LinkedHashMap<>(map);
            try { fields.get(key).set(config, value); }
            catch (IllegalAccessException ex) { throw new IllegalStateException("Config field is inaccessible: " + key, ex); }
        }
    }
}
