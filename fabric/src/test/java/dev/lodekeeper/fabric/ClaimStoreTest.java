package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ClaimBox;
import dev.lodekeeper.core.ClaimSnapshot;
import dev.lodekeeper.core.WorldScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaimStoreTest {
    private static final WorldScope WORLD = new WorldScope("world-a", "minecraft:overworld");
    private static final String CLAIM = "{\"id\":\"claim-a\",\"name\":\"Workshop\","
            + "\"scope\":{\"worldId\":\"world-a\",\"dimension\":\"minecraft:overworld\"},"
            + "\"minX\":-2,\"minY\":63,\"minZ\":4,\"maxX\":2,\"maxY\":72,\"maxZ\":8,"
            + "\"preferredStations\":true}";
    private static final String VALID = "{\"schemaVersion\":1,\"claims\":[" + CLAIM + "]}";

    @TempDir
    Path directory;

    @Test
    void validClaimLoadsAllCoordinatesAndFlags() throws IOException {
        ClaimStore store = read("valid.json", VALID);

        assertFalse(store.locked());
        assertEquals(1, store.all().size());
        ClaimBox claim = store.all().get(0);
        assertEquals(-2, claim.minX());
        assertEquals(63, claim.minY());
        assertEquals(4, claim.minZ());
        assertEquals(2, claim.maxX());
        assertEquals(72, claim.maxY());
        assertEquals(8, claim.maxZ());
        assertTrue(claim.preferredStations());
    }

    @Test
    void malformedClaimShapesFailClosedBeforeDefaultsOrCoercionsCanApply() throws IOException {
        List<String> malformed = List.of(
                VALID.replace("\"maxX\":2,", ""),
                VALID.replace("\"maxX\":2", "\"maxX\":\"2\""),
                VALID.replace("\"maxX\":2", "\"maxX\":2.5"),
                VALID.replace("\"maxX\":2", "\"maxX\":2147483648"),
                VALID.replace("\"id\":\"claim-a\",", ""),
                VALID.replace("\"worldId\":\"world-a\"", "\"worldId\":7"),
                VALID.replace("\"preferredStations\":true", "\"preferredStations\":1"),
                "{\"schemaVersion\":1,\"claims\":["
                        + String.join(",", Collections.nCopies(ClaimSnapshot.MAX_CLAIMS + 1, CLAIM)) + "]}");

        for (int index = 0; index < malformed.size(); index++) {
            ClaimStore store = read("invalid-" + index + ".json", malformed.get(index));
            assertTrue(store.locked(), "malformed claim shape " + index + " must lock edits");
            assertTrue(store.forbidsEdit(WORLD, 0, 64, 6, ClaimSnapshot.ActionKind.BREAK));
            assertTrue(store.forbidsEdit(WORLD, 0, 64, 6, ClaimSnapshot.ActionKind.PLACE));
        }
    }

    @Test
    void malformedUtf8WorldIdentityLocksEdits() throws IOException {
        byte[] bytes = VALID.getBytes(StandardCharsets.UTF_8);
        int worldStart = VALID.indexOf("world-a");
        bytes[worldStart + 6] = (byte) 0xC3;
        Path file = directory.resolve("invalid-utf8.json");
        Files.write(file, bytes);

        ClaimStore store = new ClaimStore(file);
        assertTrue(store.locked());
        assertTrue(store.forbidsEdit(WORLD, 0, 64, 6, ClaimSnapshot.ActionKind.BREAK));
        assertTrue(store.forbidsEdit(WORLD, 0, 64, 6, ClaimSnapshot.ActionKind.PLACE));
    }

    private ClaimStore read(String name, String json) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return new ClaimStore(file);
    }
}
