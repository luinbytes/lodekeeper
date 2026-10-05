package dev.lodekeeper.core;

import java.util.Objects;

/** An exact item or an item tag that can satisfy an ingredient. */
public sealed interface ItemSelector permits ItemSelector.Exact, ItemSelector.Tag {
    record Exact(ItemId item) implements ItemSelector {
        public Exact { Objects.requireNonNull(item, "item"); }
    }

    record Tag(TagId tag) implements ItemSelector {
        public Tag { Objects.requireNonNull(tag, "tag"); }
    }

    static ItemSelector item(ItemId item) { return new Exact(item); }
    static ItemSelector tag(TagId tag) { return new Tag(tag); }
}
