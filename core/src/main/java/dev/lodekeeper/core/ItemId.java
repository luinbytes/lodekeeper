package dev.lodekeeper.core;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable Minecraft-style item identifier. Unqualified values use the minecraft namespace. */
public record ItemId(String namespace, String path) implements Comparable<ItemId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    public ItemId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid item identifier: " + namespace + ":" + path);
        }
    }

    public static ItemId parse(String value) {
        String[] parts = split(value, "item");
        return new ItemId(parts[0], parts[1]);
    }

    static String[] split(String value, String kind) {
        Objects.requireNonNull(value, kind + " identifier");
        String input = value.trim().toLowerCase(java.util.Locale.ROOT);
        int separator = input.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : input.substring(0, separator);
        String path = separator < 0 ? input : input.substring(separator + 1);
        if (input.isEmpty() || path.isEmpty() || (separator >= 0 && input.indexOf(':', separator + 1) >= 0)) {
            throw new IllegalArgumentException("Invalid " + kind + " identifier: " + value);
        }
        return new String[] {namespace, path};
    }

    @Override
    public int compareTo(ItemId other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
