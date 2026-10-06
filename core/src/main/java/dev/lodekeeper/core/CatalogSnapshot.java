package dev.lodekeeper.core;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable catalog assembled by an adapter from registry, tag, recipe and extension data. */
public final class CatalogSnapshot {
    private static final int MAX_OUTPUT_SOURCE_RESTRICTIONS = 64;
    private static final int MAX_RESTRICTED_SOURCE_ENTRIES = 4_096;

    private final Map<ItemId, ItemDefinition> itemDefinitions;
    private final Map<String, List<ItemId>> aliases;
    private final Map<TagId, List<ItemId>> tags;
    private final Map<ItemId, List<AcquisitionSource>> sources;
    private final Map<String, AcquisitionSource> sourcesById;
    private final Map<ItemId, OutputSourceRestriction> sourceRestrictions;
    private final Set<ItemId> gatherOutputs;
    private final List<GatherSource> baseGatherSources;
    private final List<GatherSource> gatherSources;
    private final Set<ItemId> recipeSourceOutputs;
    private final Set<ItemId> knownItems;

    private CatalogSnapshot(Builder builder) {
        this.itemDefinitions = Map.copyOf(builder.items);
        this.aliases = immutableLists(builder.aliases);
        this.tags = immutableLists(builder.tags);
        this.sourcesById = Map.copyOf(builder.sources);
        this.sourceRestrictions = Map.of();
        this.baseGatherSources = builder.sources.values().stream()
                .filter(GatherSource.class::isInstance)
                .map(GatherSource.class::cast)
                .toList();
        this.gatherSources = baseGatherSources;
        this.gatherOutputs = baseGatherSources.stream().map(GatherSource::output)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var byOutput = new TreeMap<ItemId, List<AcquisitionSource>>();
        var recipeOutputs = new TreeSet<ItemId>();
        builder.sources.values().forEach(source -> byOutput.computeIfAbsent(source.output(), ignored -> new ArrayList<>()).add(source));
        builder.sources.values().stream()
                .filter(source -> source instanceof CraftingSource || source instanceof SmeltingSource)
                .forEach(source -> recipeOutputs.add(source.output()));
        byOutput.replaceAll((item, values) -> values.stream().sorted(Comparator.comparing(AcquisitionSource::sourceId)).toList());
        this.sources = Map.copyOf(byOutput);
        this.recipeSourceOutputs = Set.copyOf(recipeOutputs);
        var allItems = new TreeSet<ItemId>(builder.items.keySet());
        builder.sources.values().forEach(source -> allItems.add(source.output()));
        builder.tags.values().forEach(allItems::addAll);
        this.knownItems = Set.copyOf(allItems);
    }

    private CatalogSnapshot(CatalogSnapshot base, Map<ItemId, OutputSourceRestriction> restrictions) {
        this.itemDefinitions = base.itemDefinitions;
        this.aliases = base.aliases;
        this.tags = base.tags;
        this.sources = base.sources;
        this.sourcesById = base.sourcesById;
        this.sourceRestrictions = Map.copyOf(restrictions);
        this.gatherOutputs = base.gatherOutputs;
        this.baseGatherSources = base.baseGatherSources;
        boolean restrictsGatherOutput = this.sourceRestrictions.keySet().stream().anyMatch(gatherOutputs::contains);
        this.gatherSources = restrictsGatherOutput
                ? new RestrictedGatherSources(baseGatherSources, this.sourceRestrictions)
                : baseGatherSources;
        this.recipeSourceOutputs = base.recipeSourceOutputs;
        this.knownItems = base.knownItems;
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
        Objects.requireNonNull(item, "item");
        OutputSourceRestriction restriction = sourceRestrictions.get(item);
        return restriction == null ? sources.getOrDefault(item, List.of()) : restriction.sources;
    }

    /** Gather sources in deterministic source-ID order, cached when this snapshot is built. */
    public List<GatherSource> gatherSources() { return gatherSources; }

    AcquisitionSource sourceById(String sourceId) {
        AcquisitionSource source = sourcesById.get(Objects.requireNonNull(sourceId, "sourceId"));
        if (source == null) return null;
        OutputSourceRestriction restriction = sourceRestrictions.get(source.output());
        return restriction == null ? source : restriction.sourcesById.get(sourceId);
    }

    boolean hasRecipeSource(ItemId item) {
        Objects.requireNonNull(item, "item");
        OutputSourceRestriction restriction = sourceRestrictions.get(item);
        if (restriction == null) return recipeSourceOutputs.contains(item);
        return restriction.sources.stream().anyMatch(source ->
                source instanceof CraftingSource || source instanceof SmeltingSource);
    }

    /** Returns a view that restricts one output to the selected source IDs. Nested restrictions intersect. */
    public CatalogSnapshot withOutputSources(ItemId output, Set<String> allowedSourceIds) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(allowedSourceIds, "allowedSourceIds");
        List<AcquisitionSource> visibleSources = sourcesFor(output);
        if (allowedSourceIds.size() == visibleSources.size()
                && visibleSources.stream().allMatch(source -> allowedSourceIds.contains(source.sourceId()))) return this;
        if (allowedSourceIds.size() > MAX_RESTRICTED_SOURCE_ENTRIES) {
            throw new IllegalArgumentException("Output source selection exceeds view limit");
        }

        OutputSourceRestriction current = sourceRestrictions.get(output);
        var selected = new ArrayList<AcquisitionSource>();
        for (String sourceId : new TreeSet<>(allowedSourceIds)) {
            AcquisitionSource original = requireOutputSource(output, sourceId);
            AcquisitionSource visible = current == null ? original : current.sourcesById.get(sourceId);
            if (visible != null) selected.add(visible);
        }
        return withOutputRestriction(output, selected);
    }

    /**
     * Returns a view whose selected sources may carry immutable, output-specific narrowed data.
     * Nested restrictions intersect by source ID, so a later view cannot restore a removed source.
     */
    public CatalogSnapshot withOutputSources(ItemId output, List<? extends AcquisitionSource> selectedSources) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(selectedSources, "selectedSources");
        if (selectedSources == sourcesFor(output)) return this;
        if (selectedSources.size() > MAX_RESTRICTED_SOURCE_ENTRIES) {
            throw new IllegalArgumentException("Output source selection exceeds view limit");
        }

        OutputSourceRestriction current = sourceRestrictions.get(output);
        var selectedById = new TreeMap<String, AcquisitionSource>();
        var seen = new HashSet<String>();
        for (AcquisitionSource selected : selectedSources) {
            Objects.requireNonNull(selected, "selected source");
            if (!selected.output().equals(output))
                throw new IllegalArgumentException("Restricted source output must match its selected output");
            AcquisitionSource original = requireOutputSource(output, selected.sourceId());
            if (selected.getClass() != original.getClass()) {
                throw new IllegalArgumentException("Restricted source type must match its catalog source");
            }
            if (!seen.add(selected.sourceId())) {
                throw new IllegalArgumentException("Restricted source IDs must be unique");
            }
            if (current == null || current.sourcesById.containsKey(selected.sourceId())) {
                selectedById.put(selected.sourceId(), selected);
            }
        }
        return withOutputRestriction(output, new ArrayList<>(selectedById.values()));
    }

    private AcquisitionSource requireOutputSource(ItemId output, String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        AcquisitionSource original = sourcesById.get(sourceId);
        if (original == null || !original.output().equals(output)) {
            throw new IllegalArgumentException("Source ID does not belong to output " + output + ": " + sourceId);
        }
        return original;
    }

    private CatalogSnapshot withOutputRestriction(ItemId output, List<AcquisitionSource> selectedSources) {
        OutputSourceRestriction replacement = new OutputSourceRestriction(selectedSources);
        OutputSourceRestriction current = sourceRestrictions.get(output);
        if (current == null && replacement.sources.equals(sources.getOrDefault(output, List.of()))) return this;
        if (current != null && replacement.sources.equals(current.sources)) return this;
        if (current == null && sourceRestrictions.size() >= MAX_OUTPUT_SOURCE_RESTRICTIONS) {
            throw new IllegalStateException("Catalog view exceeds output source restriction limit");
        }
        int restrictedSourceCount = replacement.sources.size();
        for (Map.Entry<ItemId, OutputSourceRestriction> entry : sourceRestrictions.entrySet()) {
            if (!entry.getKey().equals(output)) restrictedSourceCount += entry.getValue().sources.size();
        }
        if (restrictedSourceCount > MAX_RESTRICTED_SOURCE_ENTRIES) {
            throw new IllegalStateException("Catalog view exceeds restricted source entry limit");
        }
        var updated = new HashMap<>(sourceRestrictions);
        updated.put(output, replacement);
        return new CatalogSnapshot(this, updated);
    }

    private static final class OutputSourceRestriction {
        private final List<AcquisitionSource> sources;
        private final Map<String, AcquisitionSource> sourcesById;

        private OutputSourceRestriction(List<AcquisitionSource> selectedSources) {
            var ordered = new ArrayList<>(selectedSources);
            ordered.sort(Comparator.comparing(AcquisitionSource::sourceId));
            this.sources = List.copyOf(ordered);
            var byId = new HashMap<String, AcquisitionSource>();
            for (AcquisitionSource source : sources) byId.put(source.sourceId(), source);
            this.sourcesById = Map.copyOf(byId);
        }
    }

    private static final class RestrictedGatherSources extends AbstractList<GatherSource> {
        private final List<GatherSource> base;
        private final Map<ItemId, OutputSourceRestriction> restrictions;

        private RestrictedGatherSources(List<GatherSource> base,
                                        Map<ItemId, OutputSourceRestriction> restrictions) {
            this.base = base;
            this.restrictions = restrictions;
        }

        @Override
        public Iterator<GatherSource> iterator() {
            Iterator<GatherSource> baseIterator = base.iterator();
            return new Iterator<>() {
                private GatherSource next;
                private boolean ready;

                @Override
                public boolean hasNext() {
                    advance();
                    return ready;
                }

                @Override
                public GatherSource next() {
                    advance();
                    if (!ready) throw new NoSuchElementException();
                    ready = false;
                    return next;
                }

                private void advance() {
                    if (ready) return;
                    while (baseIterator.hasNext()) {
                        GatherSource original = baseIterator.next();
                        OutputSourceRestriction restriction = restrictions.get(original.output());
                        if (restriction == null) {
                            next = original;
                            ready = true;
                            return;
                        }
                        AcquisitionSource selected = restriction.sourcesById.get(original.sourceId());
                        if (selected instanceof GatherSource gather) {
                            next = gather;
                            ready = true;
                            return;
                        }
                    }
                }
            };
        }

        @Override
        public Spliterator<GatherSource> spliterator() {
            return Spliterators.spliteratorUnknownSize(iterator(),
                    Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.DISTINCT | Spliterator.NONNULL);
        }

        @Override
        public GatherSource get(int index) {
            if (index < 0) throw new IndexOutOfBoundsException(index);
            Iterator<GatherSource> iterator = iterator();
            for (int current = 0; iterator.hasNext(); current++) {
                GatherSource source = iterator.next();
                if (current == index) return source;
            }
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public int size() {
            int count = 0;
            for (Iterator<GatherSource> iterator = iterator(); iterator.hasNext(); iterator.next()) count++;
            return count;
        }
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
