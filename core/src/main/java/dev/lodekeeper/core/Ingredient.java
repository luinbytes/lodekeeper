package dev.lodekeeper.core;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Ingredient alternatives and the amount required for one source operation. */
public record Ingredient(List<ItemSelector> alternatives, int count) {
    public Ingredient {
        Objects.requireNonNull(alternatives, "alternatives");
        alternatives = List.copyOf(alternatives);
        if (alternatives.isEmpty()) throw new IllegalArgumentException("Ingredient needs an alternative");
        if (alternatives.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null ingredient alternative");
        if (count < 1) throw new IllegalArgumentException("Ingredient count must be positive");
    }

    public static Ingredient of(ItemId... items) {
        return new Ingredient(Arrays.stream(items).map(ItemSelector::item).toList(), 1);
    }

    public static Ingredient of(int count, ItemId... items) {
        return new Ingredient(Arrays.stream(items).map(ItemSelector::item).toList(), count);
    }

    public static Ingredient tag(TagId tag) {
        return new Ingredient(List.of(ItemSelector.tag(tag)), 1);
    }

    public static Ingredient tag(TagId tag, int count) {
        return new Ingredient(List.of(ItemSelector.tag(tag)), count);
    }

    public static Ingredient choices(List<ItemId> items, int count) {
        Objects.requireNonNull(items, "items");
        return new Ingredient(items.stream().map(ItemSelector::item).toList(), count);
    }
}
