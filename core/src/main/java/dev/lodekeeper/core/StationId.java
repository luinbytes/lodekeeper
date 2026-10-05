package dev.lodekeeper.core;

import java.util.Objects;

/** Stable identifier for a crafting or processing station understood by an adapter. */
public record StationId(String namespace, String path) implements Comparable<StationId> {
    public StationId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        new ItemId(namespace, path);
    }

    public static StationId parse(String value) {
        String[] parts = ItemId.split(value, "station");
        return new StationId(parts[0], parts[1]);
    }

    @Override
    public int compareTo(StationId other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
