package dev.lodekeeper.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.lodekeeper.core.TravelGoal;
import dev.lodekeeper.core.WorldScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class WaypointStore {
    private static final int MAX_BYTES = 262144, MAX_SCOPES = 16, MAX_LABELS = 64;
    record Entry(WorldScope scope, String label, TravelGoal.PointGoal goal) { }
    private record Data(int schemaVersion, List<Entry> waypoints) { }
    private final Path path;
    private List<Entry> entries = List.of();
    private boolean locked;

    WaypointStore(Path path) {
        this.path = path;
        try {
            if (Files.size(path) > MAX_BYTES) throw new IOException("Waypoint file exceeds its limit");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IOException("Waypoint file exceeds its limit");
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var root = JsonParser.parseString(text).getAsJsonObject();
            if (!root.keySet().equals(Set.of("schemaVersion", "waypoints"))
                    || integer(root.get("schemaVersion")) != 1) throw new IOException("Unknown waypoint format");
            var rows = root.getAsJsonArray("waypoints");
            if (rows.size() > MAX_SCOPES * MAX_LABELS) throw new IOException("Waypoint count exceeds its limit");
            List<Entry> loaded = new ArrayList<>();
            for (var value : rows) {
                var row = value.getAsJsonObject();
                if (!row.keySet().equals(Set.of("scope", "label", "goal"))) throw new IOException("Unknown waypoint fields");
                var scope = row.getAsJsonObject("scope"); var goal = row.getAsJsonObject("goal");
                if (!scope.keySet().equals(Set.of("worldId", "dimension")) || !goal.keySet().equals(Set.of("x", "feetY", "z")))
                    throw new IOException("Unknown waypoint fields");
                String label = row.get("label").getAsString();
                if (!label.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IOException("Invalid waypoint label");
                WorldScope world = new WorldScope(scope.get("worldId").getAsString(), scope.get("dimension").getAsString());
                if (loaded.stream().anyMatch(entry -> entry.scope().equals(world) && entry.label().equals(label)))
                    throw new IOException("Duplicate waypoint label");
                loaded.add(new Entry(world, label, new TravelGoal.PointGoal(integer(goal.get("x")),
                        integer(goal.get("feetY")), integer(goal.get("z")))));
            }
            validateBounds(loaded);
            entries = List.copyOf(loaded);
        } catch (java.nio.file.NoSuchFileException missing) { locked = Files.isSymbolicLink(path); }
        catch (IOException | RuntimeException failure) { locked = true; }
    }
    private static int integer(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected an integer waypoint field");
        return value.getAsBigDecimal().intValueExact();
    }
    List<Entry> list(WorldScope scope) { requireAvailable(); return entries.stream().filter(entry -> entry.scope().equals(scope)).toList(); }
    TravelGoal.PointGoal require(WorldScope scope, String label) {
        return list(scope).stream().filter(entry -> entry.label().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No waypoint named " + label + " in this world/dimension")).goal();
    }
    void set(WorldScope scope, String label, TravelGoal.PointGoal goal) throws IOException {
        requireAvailable();
        List<Entry> next = new ArrayList<>(entries);
        next.removeIf(entry -> entry.scope().equals(scope) && entry.label().equals(label));
        next.add(new Entry(scope, label, goal));
        save(next);
    }
    void remove(WorldScope scope, String label) throws IOException {
        requireAvailable();
        List<Entry> next = new ArrayList<>(entries);
        if (!next.removeIf(entry -> entry.scope().equals(scope) && entry.label().equals(label)))
            throw new IllegalArgumentException("No waypoint named " + label + " in this world/dimension");
        save(next);
    }
    private void requireAvailable() { if (locked) throw new IllegalStateException("Waypoint file could not be read; inspect it before editing"); }
    private static void validateBounds(List<Entry> values) {
        var scopes = values.stream().map(Entry::scope).distinct().toList();
        if (scopes.size() > MAX_SCOPES || scopes.stream().anyMatch(scope -> values.stream()
                .filter(entry -> entry.scope().equals(scope)).count() > MAX_LABELS))
            throw new IllegalStateException("Waypoint limit is 64 labels per scope and 16 scopes");
    }
    private void save(List<Entry> next) throws IOException {
        validateBounds(next);
        String encoded = new Gson().toJson(new Data(1, next));
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("Waypoint file exceeds its limit");
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "lodekeeper-waypoints-", ".tmp");
        try {
            Files.writeString(temporary, encoded);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        entries = List.copyOf(next);
    }
}
