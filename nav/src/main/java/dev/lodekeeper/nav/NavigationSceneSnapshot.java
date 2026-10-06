package dev.lodekeeper.nav;

import java.util.Arrays;
import java.util.Objects;

/** Bounded, immutable world markers captured by adapters on their owning thread. */
public final class NavigationSceneSnapshot {
    public static final int MAX_MOVEMENTS = 256;
    public static final int MAX_ACTIONS = 16;
    public static final int MAX_MARKERS = 64;

    public static final byte MOVEMENT_OTHER = 0;
    public static final byte MOVEMENT_PARKOUR = 1;

    public static final int PREFERRED_STATIONS = 1;

    public static final NavigationSceneSnapshot EMPTY = new NavigationSceneSnapshot(
            new byte[0], new WorldAction[0], new Marker[0]);

    public enum ActionKind { BREAK, PLACE }
    public enum ActionEvidence { PLANNED_PATH, PLANNED_NATIVE, NATIVE_ATTEMPT, SERVER_CONFIRMED }
    public enum MarkerKind {
        CLAIM_BOUNDARY,
        CONFIRMED_OWNED_STATION,
        STATION_RECOVERY_PENDING,
        BACKFILL_PENDING_MATCH,
        BACKFILL_MATCHED
    }

    /** A world action copied from path metadata, a native input attempt, or server evidence. */
    public record WorldAction(long position, ActionKind kind, ActionEvidence evidence) {
        public WorldAction {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(evidence, "evidence");
            if (Position.pack(Position.x(position), Position.y(position), Position.z(position)) != position)
                throw new IllegalArgumentException("Invalid packed action position");
        }
    }

    /** Inclusive block bounds for claims; point markers use equal minimum and maximum bounds. */
    public record Marker(MarkerKind kind, int minX, int minY, int minZ,
                         int maxX, int maxY, int maxZ, int flags) {
        public Marker {
            Objects.requireNonNull(kind, "kind");
            Position.pack(minX, minY, minZ);
            Position.pack(maxX, maxY, maxZ);
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Invalid marker bounds");
            if ((flags & ~PREFERRED_STATIONS) != 0
                    || kind != MarkerKind.CLAIM_BOUNDARY && flags != 0)
                throw new IllegalArgumentException("Invalid marker flags");
        }

        public static Marker claim(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                   boolean preferredStations) {
            return new Marker(MarkerKind.CLAIM_BOUNDARY, minX, minY, minZ, maxX, maxY, maxZ,
                    preferredStations ? PREFERRED_STATIONS : 0);
        }

        public static Marker confirmedStation(int x, int y, int z) {
            return point(MarkerKind.CONFIRMED_OWNED_STATION, x, y, z);
        }

        public static Marker stationRecoveryPending(int x, int y, int z) {
            return point(MarkerKind.STATION_RECOVERY_PENDING, x, y, z);
        }

        public static Marker backfillPendingMatch(int x, int y, int z) {
            return point(MarkerKind.BACKFILL_PENDING_MATCH, x, y, z);
        }

        public static Marker backfillMatched(int x, int y, int z) {
            return point(MarkerKind.BACKFILL_MATCHED, x, y, z);
        }

        public boolean preferredStations() { return (flags & PREFERRED_STATIONS) != 0; }

        public double distanceSquared(double x, double y, double z) {
            if (kind == MarkerKind.CLAIM_BOUNDARY) return distanceToBoundarySquared(x, y, z);
            double nearX = clamp(x, minX, (double) maxX + 1.0);
            double nearY = clamp(y, minY, (double) maxY + 1.0);
            double nearZ = clamp(z, minZ, (double) maxZ + 1.0);
            double dx = x - nearX, dy = y - nearY, dz = z - nearZ;
            return dx * dx + dy * dy + dz * dz;
        }

        private double distanceToBoundarySquared(double x, double y, double z) {
            double loX = minX, hiX = (double) maxX + 1.0;
            double loY = minY, hiY = (double) maxY + 1.0;
            double loZ = minZ, hiZ = (double) maxZ + 1.0;
            double nearest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                double edgeX = (i & 1) == 0 ? loX : hiX;
                double edgeZ = (i & 2) == 0 ? loZ : hiZ;
                nearest = Math.min(nearest, segmentDistanceSquared(x, y, z,
                        edgeX, loY, edgeZ, edgeX, hiY, edgeZ));
            }
            for (int height = 0; height < 2; height++) {
                double edgeY = height == 0 ? loY : hiY;
                nearest = Math.min(nearest, segmentDistanceSquared(x, y, z,
                        loX, edgeY, loZ, hiX, edgeY, loZ));
                nearest = Math.min(nearest, segmentDistanceSquared(x, y, z,
                        loX, edgeY, hiZ, hiX, edgeY, hiZ));
                nearest = Math.min(nearest, segmentDistanceSquared(x, y, z,
                        loX, edgeY, loZ, loX, edgeY, hiZ));
                nearest = Math.min(nearest, segmentDistanceSquared(x, y, z,
                        hiX, edgeY, loZ, hiX, edgeY, hiZ));
            }
            return nearest;
        }

        private static Marker point(MarkerKind kind, int x, int y, int z) {
            return new Marker(kind, x, y, z, x, y, z, 0);
        }

        private static double clamp(double value, double low, double high) {
            return Math.max(low, Math.min(high, value));
        }

        private static double segmentDistanceSquared(double x, double y, double z,
                                                     double ax, double ay, double az,
                                                     double bx, double by, double bz) {
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double lengthSquared = dx * dx + dy * dy + dz * dz;
            double t = lengthSquared == 0.0 ? 0.0
                    : clamp(((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / lengthSquared, 0.0, 1.0);
            double nearX = ax + dx * t, nearY = ay + dy * t, nearZ = az + dz * t;
            double deltaX = x - nearX, deltaY = y - nearY, deltaZ = z - nearZ;
            return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        }
    }

    private final byte[] movementKinds;
    private final WorldAction[] actions;
    private final Marker[] markers;

    public NavigationSceneSnapshot(byte[] movementKinds, WorldAction[] actions, Marker[] markers) {
        Objects.requireNonNull(movementKinds, "movementKinds");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(markers, "markers");
        if (movementKinds.length > MAX_MOVEMENTS || actions.length > MAX_ACTIONS || markers.length > MAX_MARKERS)
            throw new IllegalArgumentException("Navigation scene exceeds its bounds");
        for (byte kind : movementKinds) {
            if (kind != MOVEMENT_OTHER && kind != MOVEMENT_PARKOUR)
                throw new IllegalArgumentException("Unknown native movement kind");
        }
        this.movementKinds = movementKinds.clone();
        this.actions = actions.clone();
        this.markers = markers.clone();
        for (WorldAction action : this.actions) Objects.requireNonNull(action, "action");
        for (Marker marker : this.markers) Objects.requireNonNull(marker, "marker");
    }

    public int movementCount() { return movementKinds.length; }
    public boolean isParkourMovement(int index) {
        return index >= 0 && index < movementKinds.length && movementKinds[index] == MOVEMENT_PARKOUR;
    }
    public byte[] movementKinds() { return movementKinds.clone(); }
    public int actionCount() { return actions.length; }
    public WorldAction action(int index) { return actions[index]; }
    public WorldAction[] actions() { return actions.clone(); }
    public int markerCount() { return markers.length; }
    public Marker marker(int index) { return markers[index]; }
    public Marker[] markers() { return markers.clone(); }

    public NavigationSceneSnapshot withMarkers(Marker[] snapshot) {
        return new NavigationSceneSnapshot(movementKinds, actions, snapshot);
    }

    public NavigationSceneSnapshot withAdditionalMarkers(Marker[] additional) {
        Objects.requireNonNull(additional, "additional");
        int count = Math.min(MAX_MARKERS, markers.length + additional.length);
        Marker[] combined = Arrays.copyOf(markers, count);
        int copied = Math.min(additional.length, count - markers.length);
        System.arraycopy(additional, 0, combined, markers.length, copied);
        return withMarkers(combined);
    }
}
