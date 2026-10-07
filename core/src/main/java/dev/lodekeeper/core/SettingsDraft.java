package dev.lodekeeper.core;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Isolated working values for an explicit, validated settings save. */
public final class SettingsDraft {
    private final List<SettingSpec> specs;
    private final Map<String, SettingSpec> byKey;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, Object> original = new LinkedHashMap<>();
    private int ignoredNavigationPreferenceCount;

    public SettingsDraft(List<SettingSpec> specs, Function<String, Object> reader) {
        this.specs = List.copyOf(specs);
        this.byKey = index(this.specs);
        for (SettingSpec spec : this.specs) {
            Object persisted = reader.apply(spec.key());
            Object value = spec.normalize(persisted);
            Object originalValue = value;
            if (spec.key().equals("navigationPreferences")) {
                Map<String, String> safe = NavigationPreferenceCatalog.sanitizeStored(value);
                ignoredNavigationPreferenceCount = Math.max(ignoredNavigationPreferenceCount,
                        mapSize(persisted) - safe.size());
                value = safe;
                originalValue = snapshot(persisted);
            }
            values.put(spec.key(), value);
            original.put(spec.key(), originalValue);
        }
    }

    public List<SettingSpec> specs() { return specs; }

    public Object value(String key) { return values.get(requireSpec(key).key()); }

    public void setValue(String key, Object value) {
        SettingSpec spec = requireSpec(key);
        Object validated = spec.validate(value);
        if (spec.key().equals("navigationPreferences"))
            validated = NavigationPreferenceCatalog.validateOverrides(validated);
        values.put(spec.key(), validated);
    }

    public List<SettingSpec> matching(String category, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return specs.stream()
                .filter(spec -> category == null || category.isBlank() || category.equals("All")
                        || spec.category().equals(category))
                .filter(spec -> needle.isEmpty() || contains(spec.key(), needle)
                        || contains(spec.label(), needle) || contains(spec.category(), needle)
                        || contains(spec.help(), needle))
                .toList();
    }

    public boolean enabled(String key) {
        SettingSpec spec = requireSpec(key);
        while (spec.parentKey() != null) {
            Object parent = values.get(spec.parentKey());
            if (!Boolean.TRUE.equals(parent)) return false;
            spec = requireSpec(spec.parentKey());
        }
        return true;
    }

    public void reset(String key) { setValue(key, requireSpec(key).defaultValue()); }

    public void resetAll() {
        for (SettingSpec spec : specs) values.put(spec.key(), spec.defaultValue());
    }

    public Map<String, String> navigationPreferences() {
        Object value = value("navigationPreferences");
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Navigation preferences are not a map");
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) result.put((String) entry.getKey(), (String) entry.getValue());
        return Map.copyOf(result);
    }

    public Object navigationPreferenceValue(String key) {
        NavigationPreferenceCatalog.Preference preference = NavigationPreferenceCatalog.require(key);
        String value = navigationPreferences().get(key);
        return value == null ? preference.defaultValue() : preference.parse(value);
    }

    public boolean hasNavigationPreferenceOverride(String key) {
        NavigationPreferenceCatalog.Preference preference = NavigationPreferenceCatalog.require(key);
        String value = navigationPreferences().get(key);
        return value != null && !preference.isDefault(preference.parse(value));
    }

    public void setNavigationPreferenceValue(String key, Object value) {
        NavigationPreferenceCatalog.Preference preference = NavigationPreferenceCatalog.require(key);
        String formatted = preference.format(value);
        Map<String, String> preferences = new LinkedHashMap<>(navigationPreferences());
        if (preference.isDefault(preference.parse(formatted))) preferences.remove(key);
        else preferences.put(key, formatted);
        setValue("navigationPreferences", preferences);
    }

    public void putNavigationPreference(String key, String value) {
        NavigationPreferenceCatalog.Preference preference = NavigationPreferenceCatalog.require(key);
        setNavigationPreferenceValue(key, preference.parse(value));
    }

    public boolean removeNavigationPreference(String key) {
        NavigationPreferenceCatalog.require(key);
        Map<String, String> preferences = new LinkedHashMap<>(navigationPreferences());
        if (preferences.remove(key) == null) return false;
        setValue("navigationPreferences", preferences);
        return true;
    }

    public int ignoredNavigationPreferenceCount() { return ignoredNavigationPreferenceCount; }

    public boolean isDirty() { return !values.equals(original); }

    public Map<String, Object> validatedValues() {
        Map<String, Object> validated = new LinkedHashMap<>();
        for (SettingSpec spec : specs) {
            Object value = spec.validate(values.get(spec.key()));
            if (spec.key().equals("navigationPreferences"))
                value = NavigationPreferenceCatalog.validateOverrides(value);
            validated.put(spec.key(), value);
        }
        return Map.copyOf(validated);
    }

    public void saveTo(Function<String, Object> reader, BiConsumer<String, Object> writer,
                       SaveAction save) throws IOException {
        Map<String, Object> validated = validatedValues();
        Map<String, Object> previous = new LinkedHashMap<>();
        for (SettingSpec spec : specs) previous.put(spec.key(), spec.normalize(reader.apply(spec.key())));

        try {
            for (SettingSpec spec : specs) writer.accept(spec.key(), validated.get(spec.key()));
            save.save();
        } catch (IOException | RuntimeException failure) {
            for (SettingSpec spec : specs) {
                try { writer.accept(spec.key(), previous.get(spec.key())); }
                catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            }
            throw failure;
        }

        original.clear();
        original.putAll(validated);
        ignoredNavigationPreferenceCount = 0;
    }

    @FunctionalInterface
    public interface SaveAction { void save() throws IOException; }

    private SettingSpec requireSpec(String key) {
        SettingSpec spec = byKey.get(key);
        if (spec == null) throw new IllegalArgumentException("Unknown setting: " + key);
        return spec;
    }

    private static Map<String, SettingSpec> index(List<SettingSpec> specs) {
        Map<String, SettingSpec> indexed = new LinkedHashMap<>();
        for (SettingSpec spec : specs) {
            Objects.requireNonNull(spec, "setting spec");
            if (indexed.put(spec.key(), spec) != null) throw new IllegalArgumentException("Duplicate setting: " + spec.key());
        }
        for (SettingSpec spec : specs)
            if (spec.parentKey() != null && !indexed.containsKey(spec.parentKey()))
                throw new IllegalArgumentException("Unknown parent setting: " + spec.parentKey());
        for (NavigationPreferenceCatalog.Preference preference : NavigationPreferenceCatalog.entries())
            if (indexed.containsKey(preference.key()))
                throw new IllegalArgumentException("Advanced navigation option shadows an automation setting: " + preference.key());
        return Map.copyOf(indexed);
    }

    private static int mapSize(Object value) {
        return value instanceof Map<?, ?> map ? map.size() : value == null ? 0 : 1;
    }

    private static Object snapshot(Object value) {
        if (!(value instanceof Map<?, ?> map)) return value;
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static boolean contains(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
