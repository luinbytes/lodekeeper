package dev.lodekeeper.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.lodekeeper.core.ClaimSnapshot.ActionKind.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaimSnapshotTest {
    private static final WorldScope HOME = new WorldScope("world-a", "minecraft:overworld");

    @Test void allSixFacesProtectEditsButPermitTravelAndStationUse() {
        ClaimSnapshot claims = new ClaimSnapshot(List.of(new ClaimBox("home", "Home", HOME,
                10, 20, 30, 12, 22, 32, true)));
        int[][] faces = {{10,21,31}, {12,21,31}, {11,20,31}, {11,22,31}, {11,21,30}, {11,21,32}};
        for (int[] p : faces) {
            assertTrue(claims.forbidsEdit(HOME, p[0], p[1], p[2], BREAK));
            assertTrue(claims.forbidsEdit(HOME, p[0], p[1], p[2], PLACE));
            assertFalse(claims.forbidsEdit(HOME, p[0], p[1], p[2], TRAVEL));
            assertFalse(claims.forbidsEdit(HOME, p[0], p[1], p[2], STATION_INTERACTION));
            assertTrue(claims.preferredStation(HOME, p[0], p[1], p[2]));
        }
        int[][] outside = {{9,21,31}, {13,21,31}, {11,19,31}, {11,23,31}, {11,21,29}, {11,21,33}};
        for (int[] p : outside) {
            assertFalse(claims.forbidsEdit(HOME, p[0], p[1], p[2], BREAK));
            assertFalse(claims.forbidsEdit(HOME, p[0], p[1], p[2], PLACE));
            assertFalse(claims.preferredStation(HOME, p[0], p[1], p[2]));
        }
    }

    @Test void reversedCornersAndNegativeChunkBoundariesRemainProtected() {
        ClaimSnapshot claims = new ClaimSnapshot(List.of(ClaimBox.create("base", "Base", HOME,
                -1, 65, -1, -17, 63, -17, false)));
        for (int x : new int[]{-17, -16, -1}) for (int z : new int[]{-17, -16, -1}) {
            assertTrue(claims.forbidsEdit(HOME, x, 64, z, BREAK));
            assertTrue(claims.forbidsEdit(HOME, x, 64, z, PLACE));
        }
        assertFalse(claims.contains(HOME, 0, 64, -1));
        assertFalse(claims.contains(HOME, -18, 64, -1));
        assertFalse(claims.preferredStation(HOME, -1, 64, -1));
    }

    @Test void matchingCoordinatesInAnotherWorldOrDimensionAreIndependent() {
        ClaimSnapshot claims = new ClaimSnapshot(List.of(new ClaimBox("base", "Base", HOME,
                -2, 0, -2, 2, 100, 2, true)));
        WorldScope otherWorld = new WorldScope("world-b", "minecraft:overworld");
        WorldScope otherDimension = new WorldScope("world-a", "minecraft:the_nether");
        for (WorldScope scope : List.of(otherWorld, otherDimension)) {
            assertFalse(claims.forbidsEdit(scope, 0, 64, 0, BREAK));
            assertFalse(claims.forbidsEdit(scope, 0, 64, 0, PLACE));
            assertFalse(claims.preferredStation(scope, 0, 64, 0));
            assertEquals(List.of(), claims.forScope(scope));
        }
    }

    @Test void enormousClaimsDoNotLoseBoundaryProtectionThroughOverflow() {
        ClaimSnapshot claims = new ClaimSnapshot(List.of(new ClaimBox("all", "All", HOME,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, true)));
        for (int coordinate : new int[]{Integer.MIN_VALUE, -17, -1, 0, 16, Integer.MAX_VALUE}) {
            assertTrue(claims.forbidsEdit(HOME, coordinate, coordinate, coordinate, BREAK));
            assertTrue(claims.forbidsEdit(HOME, coordinate, coordinate, coordinate, PLACE));
            assertTrue(claims.preferredStation(HOME, coordinate, coordinate, coordinate));
        }
    }

    @Test void snapshotsCannotBeChangedThroughTheInputOrPublishedCollections() {
        List<ClaimBox> input = new ArrayList<>();
        ClaimBox box = new ClaimBox("base", "Base", HOME, 0, 0, 0, 1, 1, 1, false);
        input.add(box);
        ClaimSnapshot claims = new ClaimSnapshot(input);
        input.clear();
        assertTrue(claims.forbidsEdit(HOME, 0, 0, 0, BREAK));
        assertEquals(List.of(box), claims.all());
        assertThrows(UnsupportedOperationException.class, () -> claims.all().clear());
        assertThrows(UnsupportedOperationException.class, () -> claims.forScope(HOME).clear());
        assertThrows(IllegalArgumentException.class, () -> claims.forbidsEdit(null, 0, 0, 0, TRAVEL));
    }

    @Test void duplicateIdsAndTooManyClaimsAreRejectedWithoutTruncation() {
        ClaimBox box = new ClaimBox("base", "Base", HOME, 0, 0, 0, 1, 1, 1, false);
        assertThrows(IllegalArgumentException.class, () -> new ClaimSnapshot(List.of(box, box)));
        List<ClaimBox> maximum = new ArrayList<>();
        for (int i = 0; i < 128; i++) maximum.add(new ClaimBox("plot-" + i, "Plot", HOME,
                i, 0, 0, i, 0, 0, false));
        ClaimSnapshot claims = new ClaimSnapshot(maximum);
        assertTrue(claims.forbidsEdit(HOME, 127, 0, 0, BREAK));
        maximum.add(new ClaimBox("extra", "Extra", HOME, 128, 0, 0, 128, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new ClaimSnapshot(maximum));
    }
}
