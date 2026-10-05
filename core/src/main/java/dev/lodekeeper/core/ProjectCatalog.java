package dev.lodekeeper.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Stable immutable catalog of named item-count goals. Provider collisions fail closed. */
public final class ProjectCatalog {
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");
    private static final ProjectCatalog STANDARD = new Builder(true).build();

    private final Map<String, ProjectSpec> projects;
    private final List<String> names;

    private ProjectCatalog(Map<String, ProjectSpec> projects) {
        this.projects = Collections.unmodifiableMap(new LinkedHashMap<>(projects));
        this.names = List.copyOf(projects.keySet());
    }

    public static ProjectCatalog standard() { return STANDARD; }

    /** Starts with built-ins, allowing additional provider-owned projects. */
    public static Builder builder() { return new Builder(true); }

    /** Starts empty for adapters that want only their explicitly registered projects. */
    public static Builder emptyBuilder() { return new Builder(false); }

    public Optional<ProjectSpec> find(String name) {
        if (name == null) return Optional.empty();
        try {
            return Optional.ofNullable(projects.get(ProjectSpec.normalizeName(name)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public ProjectSpec require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown project: " + name));
    }

    /** Names are lower-case and sorted lexicographically for stable help and UI output. */
    public List<String> names() { return names; }

    /** Projects follow the same deterministic name ordering as {@link #names()}. */
    public List<ProjectSpec> projects() { return List.copyOf(projects.values()); }

    public static final class Builder {
        private final Map<String, ProjectSpec> builtIns = new TreeMap<>();
        private final Map<String, Map<String, ProjectSpec>> providers = new TreeMap<>();

        private Builder(boolean includeBuiltIns) {
            if (includeBuiltIns) {
                for (ProjectSpec project : builtIns()) builtIns.put(project.name(), project);
            }
        }

        /** Registers a provider atomically. Names colliding with built-ins or other providers are rejected. */
        public Builder registerProvider(String providerId, Collection<ProjectSpec> projects) {
            String id = normalizeProviderId(providerId);
            if (providers.containsKey(id)) throw new IllegalArgumentException("Project provider is already registered: " + id);
            Map<String, ProjectSpec> copy = copyProviderProjects(projects);
            ensureNoCollisions(id, copy);
            providers.put(id, copy);
            return this;
        }

        /** Explicitly replaces one provider's complete registration; built-ins remain protected. */
        public Builder replaceProvider(String providerId, Collection<ProjectSpec> projects) {
            String id = normalizeProviderId(providerId);
            if (!providers.containsKey(id)) throw new IllegalArgumentException("Project provider is not registered: " + id);
            Map<String, ProjectSpec> copy = copyProviderProjects(projects);
            ensureNoCollisions(id, copy);
            providers.put(id, copy);
            return this;
        }

        public Builder removeProvider(String providerId) {
            String id = normalizeProviderId(providerId);
            providers.remove(id);
            return this;
        }

        public ProjectCatalog build() {
            TreeMap<String, ProjectSpec> combined = new TreeMap<>(builtIns);
            for (Map<String, ProjectSpec> provider : providers.values()) {
                provider.forEach((name, project) -> {
                    if (combined.putIfAbsent(name, project) != null) {
                        throw new IllegalStateException("Duplicate project name: " + name);
                    }
                });
            }
            return new ProjectCatalog(combined);
        }

        private void ensureNoCollisions(String registeringProvider, Map<String, ProjectSpec> candidate) {
            for (String name : candidate.keySet()) {
                if (builtIns.containsKey(name)) throw new IllegalArgumentException("Project provider cannot override built-in: " + name);
                for (Map.Entry<String, Map<String, ProjectSpec>> existing : providers.entrySet()) {
                    if (!existing.getKey().equals(registeringProvider) && existing.getValue().containsKey(name)) {
                        throw new IllegalArgumentException("Project name " + name + " is already owned by provider " + existing.getKey());
                    }
                }
            }
        }

        private static Map<String, ProjectSpec> copyProviderProjects(Collection<ProjectSpec> projects) {
            Objects.requireNonNull(projects, "projects");
            if (projects.isEmpty()) throw new IllegalArgumentException("A project provider must register at least one project");
            TreeMap<String, ProjectSpec> copy = new TreeMap<>();
            for (ProjectSpec project : projects) {
                Objects.requireNonNull(project, "project");
                if (copy.putIfAbsent(project.name(), project) != null) {
                    throw new IllegalArgumentException("Duplicate project in provider: " + project.name());
                }
            }
            return Collections.unmodifiableMap(copy);
        }

        private static String normalizeProviderId(String providerId) {
            Objects.requireNonNull(providerId, "providerId");
            String id = providerId.trim().toLowerCase(Locale.ROOT);
            if (!PROVIDER_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid project provider id: " + providerId);
            return id;
        }
    }

    private static List<ProjectSpec> builtIns() {
        ProjectSpec.Purpose inventory = ProjectSpec.Purpose.INVENTORY_GOALS;
        ProjectSpec.Purpose supplies = ProjectSpec.Purpose.SUPPLIES_ONLY;
        List<ProjectSpec> result = new ArrayList<>();
        result.add(project("gear_stone", "A basic stone-tier equipment loadout.", inventory,
                "stone_sword", 1, "stone_pickaxe", 1, "stone_axe", 1, "stone_shovel", 1,
                "shield", 1, "torch", 16, "cooked_beef", 16));
        result.add(project("gear_iron", "An iron-tier equipment loadout with food and light.", inventory,
                "iron_sword", 1, "iron_pickaxe", 1, "iron_axe", 1, "iron_shovel", 1, "shield", 1,
                "iron_helmet", 1, "iron_chestplate", 1, "iron_leggings", 1, "iron_boots", 1,
                "torch", 32, "cooked_beef", 32));
        result.add(project("expedition", "A general exploration inventory loadout; no travel or navigation is guaranteed.", inventory,
                "iron_pickaxe", 1, "iron_sword", 1, "shield", 1, "torch", 64, "cooked_beef", 32,
                "white_bed", 1, "oak_boat", 1, "oak_planks", 32, "chest", 1, "bucket", 1));
        result.add(project("mining_trip", "A mining inventory loadout; no ore discovery is guaranteed.", inventory,
                "iron_pickaxe", 1, "stone_pickaxe", 2, "torch", 64, "cooked_beef", 32,
                "furnace", 1, "crafting_table", 1, "water_bucket", 1, "ladder", 32, "shield", 1));
        result.add(project("farming_supplies", "Seeds, a hoe, and supplies for farming; this does not plant or build a farm.", supplies,
                "iron_hoe", 1, "wheat_seeds", 32, "carrot", 8, "potato", 8, "water_bucket", 1, "bone_meal", 16, "oak_fence", 32));
        result.add(project("shelter_supplies", "Materials and tools for a shelter; this collects supplies and does not build or place a structure.", supplies,
                "oak_log", 16, "oak_planks", 128, "cobblestone", 64, "glass", 32, "oak_door", 1,
                "torch", 16, "white_bed", 1, "chest", 1, "furnace", 1, "crafting_table", 1));
        result.sort((first, second) -> first.name().compareTo(second.name()));
        return List.copyOf(result);
    }

    private static ProjectSpec project(String name, String description, ProjectSpec.Purpose purpose, Object... pairs) {
        if ((pairs.length & 1) != 0) throw new IllegalArgumentException("Project goals must be item/count pairs");
        Map<ItemId, Integer> goals = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            String item = (String) pairs[index];
            int count = (Integer) pairs[index + 1];
            goals.put(ItemId.parse("minecraft:" + item), count);
        }
        return new ProjectSpec(name, description, goals, purpose);
    }
}
