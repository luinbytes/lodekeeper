package dev.lodekeeper.core;

import java.io.IOException;
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

    public SettingsDraft(List<SettingSpec> specs, Function<String, Object> reader) {
        this.specs = List.copyOf(specs);
        this.byKey = index(this.specs);
        for (SettingSpec spec : this.specs) {
            Object value = spec.normalize(reader.apply(spec.key()));
            values.put(spec.key(), value);
            original.put(spec.key(), value);
        }
    }

    public List<SettingSpec> specs() { return specs; }

    public Object value(String key) { return values.get(requireSpec(key).key()); }

    public void setValue(String key, Object value) {
        SettingSpec spec = requireSpec(key);
        values.put(key, spec.validate(value));
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

    public void putNavigationPreference(String key, String value) {
        SettingSpec spec = requireSpec("navigationPreferences");
        if (key == null || key.isBlank() || key.length() > 64)
            throw new IllegalArgumentException("Preference keys must contain 1 to 64 characters.");
        if (value == null || value.isBlank() || value.length() > 128)
            throw new IllegalArgumentException("Preference values must contain 1 to 128 characters.");
        Map<String, String> preferences = new LinkedHashMap<>(navigationPreferences());
        if (!preferences.containsKey(key) && preferences.size() >= (int) spec.maximum())
            throw new IllegalArgumentException("Navigation preferences are limited to " + (int) spec.maximum() + " entries.");
        preferences.put(key, value);
        setValue(spec.key(), preferences);
    }

    public boolean removeNavigationPreference(String key) {
        Map<String, String> preferences = new LinkedHashMap<>(navigationPreferences());
        if (preferences.remove(key) == null) return false;
        setValue("navigationPreferences", preferences);
        return true;
    }

    public boolean isDirty() { return !values.equals(original); }

    public Map<String, Object> validatedValues() {
        Map<String, Object> validated = new LinkedHashMap<>();
        for (SettingSpec spec : specs) validated.put(spec.key(), spec.validate(values.get(spec.key())));
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
        return Map.copyOf(indexed);
    }

    private static boolean contains(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
