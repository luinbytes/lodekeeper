package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable catalog assembled by an adapter from registry, tag, recipe and extension data. */
public final class CatalogSnapshot {
    private final Map<ItemId, ItemDefinition> itemDefinitions;
    private final Map<String, List<ItemId>> aliases;
    private final Map<TagId, List<ItemId>> tags;
    private final Map<ItemId, List<AcquisitionSource>> sources;
    private final Set<ItemId> knownItems;

    private CatalogSnapshot(Builder builder) {
        this.itemDefinitions = Map.copyOf(builder.items);
        this.aliases = immutableLists(builder.aliases);
        this.tags = immutableLists(builder.tags);
        var byOutput = new TreeMap<ItemId, List<AcquisitionSource>>();
        builder.sources.values().forEach(source -> byOutput.computeIfAbsent(source.output(), ignored -> new ArrayList<>()).add(source));
        byOutput.replaceAll((item, values) -> values.stream().sorted(Comparator.comparing(AcquisitionSource::sourceId)).toList());
        this.sources = Map.copyOf(byOutput);
        var allItems = new TreeSet<ItemId>(builder.items.keySet());
        builder.sources.values().forEach(source -> allItems.add(source.output()));
        builder.tags.values().forEach(allItems::addAll);
        this.knownItems = Set.copyOf(allItems);
    }

    private static <K, V extends Comparable<? super V>> Map<K, List<V>> immutableLists(Map<K, ? extends Collection<V>> input) {
        var copy = new LinkedHashMap<K, List<V>>();
        input.forEach((key, values) -> copy.put(key, values.stream().sorted().toList()));
        return Map.copyOf(copy);
    }

    public static Builder builder() { return new Builder(); }

    public Map<ItemId, ItemDefinition> itemDefinitions() { return itemDefinitions; }
    public Set<ItemId> knownItems() { return knownItems; }

    public List<AcquisitionSource> sourcesFor(ItemId item) {
        return sources.getOrDefault(Objects.requireNonNull(item, "item"), List.of());
    }

    public List<ItemId> itemsIn(TagId tag) {
        return tags.getOrDefault(Objects.requireNonNull(tag, "tag"), List.of());
    }

    public List<ItemId> expand(ItemSelector selector) {
        Objects.requireNonNull(selector, "selector");
        if (selector instanceof ItemSelector.Exact exact) {
            return knownItems.contains(exact.item()) ? List.of(exact.item()) : List.of();
        }
        return itemsIn(((ItemSelector.Tag) selector).tag());
    }

    public int maximumDurability(ItemId item) {
        ItemDefinition definition = itemDefinitions.get(item);
        return definition == null ? 0 : definition.maximumDurability();
    }

    public long fuelBurnTicks(ItemId item) {
        ItemDefinition definition = itemDefinitions.get(item);
        return definition == null ? 0 : definition.fuelBurnTicks();
    }

    /** Resolves a canonical ID, vanilla-default ID, item path or registered alias. */
    public ItemResolution resolveItem(String input) {
        if (input == null || input.isBlank() || input.length() > 256) return new ItemResolution(null, List.of());
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.indexOf(':') >= 0) {
            try {
                ItemId exact = ItemId.parse(value);
                return knownItems.contains(exact) ? new ItemResolution(exact, List.of(exact)) : new ItemResolution(null, List.of());
            } catch (IllegalArgumentException ignored) {
                return new ItemResolution(null, List.of());
            }
        }
        String alias = normalizeAlias(value);
        List<ItemId> matches = aliases.getOrDefault(alias, List.of());
        if (matches.isEmpty()) {
            try {
                ItemId vanillaPath = ItemId.parse(alias);
                if (knownItems.contains(vanillaPath)) matches = List.of(vanillaPath);
            } catch (IllegalArgumentException ignored) {
                // Friendly aliases such as "diamond boots" need not be valid resource paths.
            }
        }
        return matches.size() == 1 ? new ItemResolution(matches.get(0), matches) : new ItemResolution(null, matches);
    }

    static String normalizeAlias(String input) {
        Objects.requireNonNull(input, "alias");
        String value = input.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return value.replaceAll("_+", "_");
    }

    public static final class Builder {
        private final Map<ItemId, ItemDefinition> items = new TreeMap<>();
        private final Map<String, Set<ItemId>> aliases = new TreeMap<>();
        private final Map<TagId, Set<ItemId>> tags = new TreeMap<>();
        private final Map<String, AcquisitionSource> sources = new TreeMap<>();

        private Builder() { }

        public Builder item(ItemId id, int maximumDurability, String... itemAliases) {
            return item(id, maximumDurability, 0, itemAliases);
        }

        public Builder item(ItemId id, int maximumDurability, long fuelBurnTicks, String... itemAliases) {
            ItemDefinition definition = new ItemDefinition(id, maximumDurability, fuelBurnTicks, itemAliases);
            ItemDefinition previous = items.putIfAbsent(id, definition);
            if (previous != null && !previous.equals(definition)) throw new IllegalArgumentException("Conflicting item definition: " + id);
            for (String alias : definition.aliases()) aliases.computeIfAbsent(alias, ignored -> new TreeSet<>()).add(id);
            return this;
        }

        public Builder item(ItemDefinition definition) {
            Objects.requireNonNull(definition, "definition");
            return item(definition.id(), definition.maximumDurability(), definition.fuelBurnTicks(), definition.aliases().toArray(String[]::new));
        }

        public Builder tag(TagId tag, Collection<ItemId> members) {
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(members, "members");
            if (members.size() > 10_000 || members.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid tag members");
            tags.computeIfAbsent(tag, ignored -> new TreeSet<>()).addAll(members);
            return this;
        }

        public Builder source(AcquisitionSource source) {
            Objects.requireNonNull(source, "source");
            if (!(source instanceof GatherSource) && !(source instanceof CraftingSource)
                    && !(source instanceof SmeltingSource) && !(source instanceof CustomSource)) {
                source = new CustomSource(source.sourceId(), source.sourceType(), source.output(), source.outputCount(),
                        source.requirements(), source.attributes());
            }
            AcquisitionSource previous = sources.putIfAbsent(source.sourceId(), source);
            if (previous != null && !previous.equals(source)) throw new IllegalArgumentException("Conflicting source id: " + source.sourceId());
            return this;
        }

        public CatalogSnapshot build() {
            if (items.size() > 100_000 || sources.size() > 100_000 || tags.size() > 100_000) {
                throw new IllegalStateException("Catalog exceeds safety limits");
            }
            var aliasCopy = new TreeMap<String, Collection<ItemId>>();
            aliases.forEach(aliasCopy::put);
            var tagCopy = new TreeMap<TagId, Collection<ItemId>>();
            tags.forEach(tagCopy::put);
            return new CatalogSnapshot(this);
        }
    }
}
