package dev.lodekeeper.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.lodekeeper.core.ClaimBox;
import dev.lodekeeper.core.ClaimSnapshot;
import dev.lodekeeper.core.WorldScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class ClaimStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private sealed interface State permits Available, Unavailable {}
    private record Available(ClaimSnapshot snapshot) implements State {}
    private record Unavailable(String problem) implements State {}
    private record Data(int schemaVersion, List<ClaimBox> claims) {}

    private final Path path;
    private volatile State state;

    ClaimStore(Path path) {
        this.path = path;
        reload();
    }

    synchronized void reload() {
        try {
            if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("claims file exceeds 256 KiB");
            Data data = GSON.fromJson(Files.readString(path), Data.class);
            if (data == null || data.schemaVersion() != 1 || data.claims() == null)
                throw new IOException("claims file has an unsupported format");
            state = new Available(new ClaimSnapshot(data.claims()));
        } catch (java.nio.file.NoSuchFileException missing) {
            state = Files.isSymbolicLink(path)
                    ? new Unavailable("Claims could not be loaded. Automated block edits are paused.")
                    : new Available(new ClaimSnapshot(List.of()));
        } catch (IOException | RuntimeException failure) {
            state = new Unavailable("Claims could not be loaded. Automated block edits are paused.");
            org.slf4j.LoggerFactory.getLogger("lodekeeper").warn("[Lodekeeper] CLAIMS load refused", failure);
        }
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
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, encoded);
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
        state = new Available(next);
    }
}
