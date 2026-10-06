package dev.lodekeeper.nav;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NavigationOverlayTest {
    @Test
    void lineSegmentsAreClippedToConfiguredVisualizationRadius() {
        Path path = path(step(-100, 0, 0, Path.Movement.START),
                step(100, 0, 0, Path.Movement.WALK));
        NavigationSnapshot view = snapshot(path, NavigationSceneSnapshot.EMPTY);
        ArrayList<Line> lines = new ArrayList<>();

        NavigationOverlay.draw(view, NavigationSceneSnapshot.EMPTY, drawn(lines),
                0, 0, 0, options(true, false, false, false, false, false, false, false, 8),
                false, 0, 0, 0);

        assertEquals(1, lines.size());
        assertWithinRadius(lines, 0, 0, 0, 8.0);
    }

    @Test
    void curvedPreviewRequiresNativeParkourMetadata() {
        Path path = path(step(0, 0, 0, Path.Movement.START),
                step(4, 0, 0, Path.Movement.WALK));
        NavigationSceneSnapshot nativeParkour = new NavigationSceneSnapshot(
                new byte[]{NavigationSceneSnapshot.MOVEMENT_PARKOUR},
                new NavigationSceneSnapshot.WorldAction[0], new NavigationSceneSnapshot.Marker[0]);
        ArrayList<Line> lines = new ArrayList<>();

        NavigationOverlay.draw(snapshot(path, nativeParkour), nativeParkour, drawn(lines),
                0, 0, 0, options(false, false, false, false, true, false, false, false, 16),
                false, 0, 0, 0);

        assertEquals(NavigationOverlay.PARKOUR_ARC_SEGMENTS, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.y1 > .5 || line.y2 > .5));
        assertFinite(lines);

        lines.clear();
        NavigationOverlay.draw(snapshot(path, NavigationSceneSnapshot.EMPTY), NavigationSceneSnapshot.EMPTY,
                drawn(lines), 0, 0, 0,
                options(false, false, false, false, true, false, false, false, 16), false, 0, 0, 0);
        assertTrue(lines.isEmpty());
    }

    @Test
    void claimAndActionMarkersRespectTheirTogglesAndStayFinite() {
        NavigationSceneSnapshot.Marker claim = NavigationSceneSnapshot.Marker.claim(0, 0, 0, 1, 4, 1, true);
        NavigationSceneSnapshot.WorldAction action = new NavigationSceneSnapshot.WorldAction(
                Position.pack(2, 1, 0), NavigationSceneSnapshot.ActionKind.PLACE,
                NavigationSceneSnapshot.ActionEvidence.PLANNED_NATIVE);
        NavigationSceneSnapshot scene = new NavigationSceneSnapshot(new byte[0],
                new NavigationSceneSnapshot.WorldAction[]{action},
                new NavigationSceneSnapshot.Marker[]{claim});
        ArrayList<Line> lines = new ArrayList<>();

        NavigationOverlay.draw(snapshot(null, scene), scene, drawn(lines),
                0, 0, 0, options(false, false, false, true, false, false, false, false, 8),
                false, 0, 0, 0);
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(line -> line.argb == NavigationOverlay.PLANNED_NATIVE_PLACE));
        assertFinite(lines);

        lines.clear();
        NavigationOverlay.draw(snapshot(null, scene), scene, drawn(lines),
                0, 0, 0, options(false, false, false, false, false, false, false, false, 8),
                false, 0, 0, 0);
        assertTrue(lines.isEmpty());

        NavigationOverlay.draw(snapshot(null, scene), scene, drawn(lines),
                0, 0, 0, options(false, false, false, false, false, false, true, false, 8),
                false, 0, 0, 0);
        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().anyMatch(line -> line.argb == NavigationOverlay.PREFERRED_STATION));
        assertFinite(lines);
    }

    @Test
    void snapshotLimitsRejectOversizedSceneInputsAndDistanceIsClamped() {
        assertThrows(IllegalArgumentException.class, () -> new NavigationSceneSnapshot(
                new byte[NavigationSceneSnapshot.MAX_MOVEMENTS + 1],
                new NavigationSceneSnapshot.WorldAction[0], new NavigationSceneSnapshot.Marker[0]));
        assertThrows(IllegalArgumentException.class, () -> new NavigationSceneSnapshot(new byte[0],
                new NavigationSceneSnapshot.WorldAction[NavigationSceneSnapshot.MAX_ACTIONS + 1],
                new NavigationSceneSnapshot.Marker[0]));
        assertThrows(IllegalArgumentException.class, () -> new NavigationSceneSnapshot(new byte[0],
                new NavigationSceneSnapshot.WorldAction[0],
                new NavigationSceneSnapshot.Marker[NavigationSceneSnapshot.MAX_MARKERS + 1]));
        assertThrows(IllegalArgumentException.class, () -> new NavigationSceneSnapshot.Marker(
                NavigationSceneSnapshot.MarkerKind.CLAIM_BOUNDARY, 2, 0, 0, 1, 0, 0, 0));
        assertEquals(8, options(false, false, false, false, false, false, false, false, -1)
                .visualizationDistance());
        assertEquals(128, options(false, false, false, false, false, false, false, false, 500)
                .visualizationDistance());
    }

    private static NavigationOverlay.Options options(boolean path, boolean search,
                                                     boolean nextBreak, boolean nextPlace,
                                                     boolean parkour, boolean claims,
                                                     boolean stations, boolean backfill, int distance) {
        return new NavigationOverlay.Options(path, search, nextBreak, nextPlace, parkour,
                claims, stations, backfill, distance);
    }

    private static NavigationOverlay.Lines drawn(List<Line> output) {
        return (x1, y1, z1, x2, y2, z2, argb) ->
                output.add(new Line(x1, y1, z1, x2, y2, z2, argb));
    }

    private static NavigationSnapshot snapshot(Path path, NavigationSceneSnapshot scene) {
        return new NavigationSnapshot(path, 0, 0, 0, 0, 0, 0, 0, false,
                new long[0], new byte[0], new boolean[0], scene);
    }

    private static Path path(Path.Step... steps) {
        return new Path(steps, 0, 0, 0);
    }

    private static Path.Step step(int x, int y, int z, Path.Movement movement) {
        return new Path.Step(x, y, z, movement, new Action[0]);
    }

    private static void assertWithinRadius(List<Line> lines, double x, double y, double z, double radius) {
        assertFinite(lines);
        for (Line line : lines) {
            assertTrue(distance(line.x1, line.y1, line.z1, x, y, z) <= radius + 1.0e-6);
            assertTrue(distance(line.x2, line.y2, line.z2, x, y, z) <= radius + 1.0e-6);
        }
    }

    private static double distance(double x, double y, double z, double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void assertFinite(List<Line> lines) {
        for (Line line : lines) {
            assertTrue(Double.isFinite(line.x1));
            assertTrue(Double.isFinite(line.y1));
            assertTrue(Double.isFinite(line.z1));
            assertTrue(Double.isFinite(line.x2));
            assertTrue(Double.isFinite(line.y2));
            assertTrue(Double.isFinite(line.z2));
        }
    }

    private record Line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) { }
}
