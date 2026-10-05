package dev.lodekeeper.core;

import java.util.Objects;

/** Immutable item-tag identifier. */
public record TagId(String namespace, String path) implements Comparable<TagId> {
    public TagId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        // Reuse the resource identifier grammar without retaining an ItemId.
        new ItemId(namespace, path);
    }

    public static TagId parse(String value) {
        Objects.requireNonNull(value, "tag identifier");
        String input = value.trim();
        if (input.startsWith("#")) input = input.substring(1);
        String[] parts = ItemId.split(input, "tag");
        return new TagId(parts[0], parts[1]);
    }

    @Override
    public int compareTo(TagId other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
