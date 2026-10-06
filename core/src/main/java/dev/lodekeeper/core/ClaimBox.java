package dev.lodekeeper.core;

import java.util.Objects;

/** Immutable inclusive three-dimensional claim bounds in one world and dimension. */
public record ClaimBox(
        String id,
        String name,
        WorldScope scope,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        boolean preferredStations
) {
    public static final int MAX_ID_LENGTH = 128;
    public static final int MAX_NAME_LENGTH = 128;

    public ClaimBox {
        id = requireBoundedText(id, "id", MAX_ID_LENGTH);
        name = requireBoundedText(name, "name", MAX_NAME_LENGTH);
        Objects.requireNonNull(scope, "scope");
        int lowerX = Math.min(minX, maxX);
        int lowerY = Math.min(minY, maxY);
        int lowerZ = Math.min(minZ, maxZ);
        int upperX = Math.max(minX, maxX);
        int upperY = Math.max(minY, maxY);
        int upperZ = Math.max(minZ, maxZ);
        minX = lowerX;
        minY = lowerY;
        minZ = lowerZ;
        maxX = upperX;
        maxY = upperY;
        maxZ = upperZ;
    }

    public static ClaimBox create(String id, String name, WorldScope scope,
                                  int x1, int y1, int z1, int x2, int y2, int z2,
                                  boolean preferredStations) {
        return new ClaimBox(id, name, scope, x1, y1, z1, x2, y2, z2, preferredStations);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(WorldScope queryScope, int x, int y, int z) {
        return scope.equals(requireScope(queryScope)) && contains(x, y, z);
    }

    private static String requireBoundedText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid claim " + field);
        }
        return normalized;
    }

    private static WorldScope requireScope(WorldScope scope) {
        if (scope == null) throw new IllegalArgumentException("scope is required");
        return scope;
    }
}
