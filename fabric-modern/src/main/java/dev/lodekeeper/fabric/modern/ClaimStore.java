package dev.lodekeeper.fabric.modern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.lodekeeper.core.ClaimBox;
import dev.lodekeeper.core.ClaimSnapshot;
import dev.lodekeeper.core.WorldScope;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ClaimStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "claims");
    private static final Set<String> CLAIM_FIELDS = Set.of("id", "name", "scope", "minX", "minY", "minZ",
            "maxX", "maxY", "maxZ", "preferredStations");
    private static final Set<String> SCOPE_FIELDS = Set.of("worldId", "dimension");
    private sealed interface State permits Available, Unavailable {}
    private record Available(ClaimSnapshot snapshot) implements State {}
    private record Unavailable(String problem) implements State {}
    private record Data(int schemaVersion, List<ClaimBox> claims) {}

    record View(ClaimSnapshot claims, boolean locked, String problem) { }
    private static final ClaimSnapshot EMPTY = new ClaimSnapshot(List.of());

    private final Path path;
    private volatile State state;

    ClaimStore(Path path) {
        this.path = java.util.Objects.requireNonNull(path, "path").toAbsolutePath();
        reload();
    }

    synchronized void reload() {
        try {
            if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("claims file exceeds 256 KiB");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("claims file exceeds 256 KiB");
            String json = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            Data data = decode(json);
            state = new Available(new ClaimSnapshot(data.claims()));
        } catch (java.nio.file.NoSuchFileException missing) {
            state = Files.isSymbolicLink(path)
                    ? new Unavailable("Claims could not be loaded. Automated block edits are paused.")
                    : new Available(new ClaimSnapshot(List.of()));
        } catch (IOException | RuntimeException failure) {
            state = new Unavailable("Claims could not be loaded. Automated block edits are paused.");
            org.slf4j.LoggerFactory.getLogger("lodekeeper").warn("[Lodekeeper] CLAIMS load refused; automated block edits are paused");
        }
    }

    View view() {
        State current = state;
        return current instanceof Available available ? new View(available.snapshot(), false, "")
                : new View(EMPTY, true, ((Unavailable) current).problem());
    }

    boolean locked() { return state instanceof Unavailable; }

    String problem() {
        State current = state;
        return current instanceof Unavailable unavailable ? unavailable.problem() : "";
    }

    List<ClaimBox> all() {
        State current = state;
        return current instanceof Available available ? available.snapshot().all() : List.of();
    }

    boolean forbidsEdit(WorldScope scope, int x, int y, int z, ClaimSnapshot.ActionKind action) {
        if (scope == null) throw new IllegalArgumentException("scope is required");
        java.util.Objects.requireNonNull(action, "action");
        if (action != ClaimSnapshot.ActionKind.BREAK && action != ClaimSnapshot.ActionKind.PLACE) return false;
        State current = state;
        return current instanceof Unavailable
                || ((Available) current).snapshot().forbidsEdit(scope, x, y, z, action);
    }

    boolean preferredStation(WorldScope scope, int x, int y, int z) {
        State current = state;
        return current instanceof Available available && available.snapshot().preferredStation(scope, x, y, z);
    }

    synchronized void replace(List<ClaimBox> claims) throws IOException {
        if (state instanceof Unavailable unavailable) throw new IOException(unavailable.problem());
        ClaimSnapshot next = new ClaimSnapshot(claims);
        String encoded = GSON.toJson(new Data(1, next.all()));
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
            throw new IOException("claims exceed the file size limit");
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "lodekeeper-claims-", ".tmp");
        try {
            Files.writeString(temporary, encoded);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        state = new Available(next);
    }

    private static Data decode(String json) throws IOException {
        JsonObject root = requireObject(JsonParser.parseString(json), "claims file");
        requireFields(root, ROOT_FIELDS, "claims file");
        int schemaVersion = requiredInteger(root, "schemaVersion");
        if (schemaVersion != 1) throw new IOException("claims file has an unsupported format");

        JsonElement claimsValue = required(root, "claims");
        if (!claimsValue.isJsonArray()) throw new IOException("claims file has an unsupported format");
        JsonArray rows = claimsValue.getAsJsonArray();
        if (rows.size() > ClaimSnapshot.MAX_CLAIMS)
            throw new IOException("claims file exceeds the claim limit");

        List<ClaimBox> claims = new ArrayList<>(rows.size());
        for (JsonElement row : rows) {
            JsonObject claim = requireObject(row, "claim");
            requireFields(claim, CLAIM_FIELDS, "claim");
            JsonObject scope = requireObject(required(claim, "scope"), "claim scope");
            requireFields(scope, SCOPE_FIELDS, "claim scope");
            claims.add(new ClaimBox(
                    requiredString(claim, "id"),
                    requiredString(claim, "name"),
                    new WorldScope(requiredString(scope, "worldId"), requiredString(scope, "dimension")),
                    requiredInteger(claim, "minX"),
                    requiredInteger(claim, "minY"),
                    requiredInteger(claim, "minZ"),
                    requiredInteger(claim, "maxX"),
                    requiredInteger(claim, "maxY"),
                    requiredInteger(claim, "maxZ"),
                    requiredBoolean(claim, "preferredStations")));
        }
        return new Data(schemaVersion, claims);
    }

    private static JsonObject requireObject(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonObject()) throw new IOException("claims file has an invalid " + field);
        return value.getAsJsonObject();
    }

    private static void requireFields(JsonObject object, Set<String> fields, String field) throws IOException {
        if (!object.keySet().equals(fields)) throw new IOException("claims file has an invalid " + field);
    }

    private static JsonElement required(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) throw new IOException("claims file is missing " + field);
        return value;
    }

    private static JsonPrimitive requiredPrimitive(JsonObject object, String field) throws IOException {
        JsonElement value = required(object, field);
        if (!value.isJsonPrimitive()) throw new IOException("claims file has an invalid " + field);
        return value.getAsJsonPrimitive();
    }

    private static String requiredString(JsonObject object, String field) throws IOException {
        JsonPrimitive value = requiredPrimitive(object, field);
        if (!value.isString()) throw new IOException("claims file has an invalid " + field);
        return value.getAsString();
    }

    private static int requiredInteger(JsonObject object, String field) throws IOException {
        JsonPrimitive value = requiredPrimitive(object, field);
        if (!value.isNumber()) throw new IOException("claims file has an invalid " + field);
        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IOException("claims file has an invalid " + field, invalid);
        }
    }

    private static boolean requiredBoolean(JsonObject object, String field) throws IOException {
        JsonPrimitive value = requiredPrimitive(object, field);
        if (!value.isBoolean()) throw new IOException("claims file has an invalid " + field);
        return value.getAsBoolean();
    }
}
