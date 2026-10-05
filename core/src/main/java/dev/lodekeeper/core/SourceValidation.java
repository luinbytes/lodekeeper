package dev.lodekeeper.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SourceValidation {
    private SourceValidation() { }

    static String sourceId(String value) {
        Objects.requireNonNull(value, "sourceId");
        if (value.isEmpty() || value.length() > 256 || value.chars().anyMatch(character -> Character.isISOControl(character) || Character.isWhitespace(character))) {
            throw new IllegalArgumentException("Invalid source id: " + value);
        }
        return value;
    }

    static ItemId output(ItemId value) { return Objects.requireNonNull(value, "output"); }

    static int outputCount(int value) {
        if (value < 1 || value > 1_000_000) throw new IllegalArgumentException("outputCount out of range");
        return value;
    }

    static List<Requirement> requirements(List<Requirement> values) {
        List<Requirement> copy = List.copyOf(Objects.requireNonNull(values, "requirements"));
        if (copy.size() > 256 || copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Invalid or excessive requirements");
        }
        return copy;
    }

    static Map<String, String> attributes(Map<String, String> values) {
        Objects.requireNonNull(values, "attributes");
        if (values.size() > 64) throw new IllegalArgumentException("Too many source attributes");
        var copy = new java.util.TreeMap<String, String>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "attribute key");
            Objects.requireNonNull(value, "attribute value");
            if (key.length() > 128 || value.length() > 512) throw new IllegalArgumentException("Source attribute too long");
            copy.put(key, value);
        });
        return java.util.Collections.unmodifiableMap(copy);
    }
}
