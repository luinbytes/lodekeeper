package dev.lodekeeper.core;

import java.util.Objects;

/** Immutable block identifier used by gather sources. */
public record BlockId(String namespace, String path) implements Comparable<BlockId> {
    public BlockId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        new ItemId(namespace, path);
    }

    public static BlockId parse(String value) {
        String[] parts = ItemId.split(value, "block");
        return new BlockId(parts[0], parts[1]);
    }

    @Override
    public int compareTo(BlockId other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
