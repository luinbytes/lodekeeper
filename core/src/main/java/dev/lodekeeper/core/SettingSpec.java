package dev.lodekeeper.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared description and validation for a persisted automation option. */
public record SettingSpec(String key, String label, String category, String help, Type type,
                          double minimum, double maximum, Object defaultValue, String parentKey) {
    public enum Type { BOOLEAN, INTEGER, DECIMAL, TEXT, STRING_MAP }

    public SettingSpec {
        if (key == null || key.isBlank() || label == null || label.isBlank()
                || category == null || category.isBlank() || help == null || help.isBlank()
                || type == null || defaultValue == null || minimum > maximum)
            throw new IllegalArgumentException("Invalid setting descriptor");
        if (defaultValue instanceof Map<?, ?> map) defaultValue = Map.copyOf(map);
    }

    public Object parse(String text) {
        if (text == null) throw invalid();
        Object value;
        try {
            value = switch (type) {
                case BOOLEAN -> {
                    if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) throw invalid();
                    yield Boolean.valueOf(text);
                }
                case INTEGER -> Integer.valueOf(text);
                case DECIMAL -> Float.valueOf(text);
                case TEXT -> text;
                case STRING_MAP -> throw new IllegalArgumentException("Edit navigation preferences by their individual keys");
            };
        } catch (NumberFormatException ex) { throw invalid(); }
        return validate(value);
    }

    public Object validate(Object value) {
        Object normalized = normalize(value);
        boolean valid = switch (type) {
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() == Math.rint(number.doubleValue())
                    && number.doubleValue() >= minimum && number.doubleValue() <= maximum;
            case DECIMAL -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= minimum && number.doubleValue() <= maximum;
            case TEXT -> value instanceof String text && validText(text);
            case STRING_MAP -> value instanceof Map<?, ?> map && normalized.equals(map);
        };
        if (!valid) throw invalid();
        return normalized;
    }

    /** Persistence keeps valid values, clamps numeric bounds, and defaults malformed values. */
    public Object normalize(Object value) {
        return switch (type) {
            case BOOLEAN -> value instanceof Boolean ? value : defaultValue;
            case INTEGER -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != Math.rint(number.doubleValue())) yield defaultValue;
                yield (int) Math.max(minimum, Math.min(maximum, number.doubleValue()));
            }
            case DECIMAL -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) yield defaultValue;
                yield (float) Math.max(minimum, Math.min(maximum, number.doubleValue()));
            }
            case TEXT -> value instanceof String text && validText(text) ? text : defaultValue;
            case STRING_MAP -> normalizeMap(value);
        };
    }

    private boolean validText(String text) {
        return !text.isBlank() && text.length() >= minimum && text.length() <= maximum
                && (!key.equals("prefix") || !text.startsWith("/"));
    }

    private Map<String, String> normalizeMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> bounded = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (bounded.size() >= maximum) break;
            if (entry.getKey() instanceof String name && !name.isBlank() && name.length() <= 64
                    && entry.getValue() instanceof String text && !text.isBlank() && text.length() <= 128)
                bounded.put(name, text);
        }
        return Map.copyOf(bounded);
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid value for " + key + ", expected " + type
                + (type == Type.BOOLEAN ? "" : " within " + minimum + " to " + maximum));
    }
}
