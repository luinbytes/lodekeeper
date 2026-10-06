package dev.lodekeeper.core;

import java.util.Objects;

/** World and dimension identity supplied by the game adapter. */
public record WorldScope(String worldId, String dimension) {
    public static final int MAX_WORLD_ID_LENGTH = 256;
    public static final int MAX_DIMENSION_LENGTH = 256;

    public WorldScope {
        worldId = requireBoundedText(worldId, "worldId", MAX_WORLD_ID_LENGTH);
        dimension = requireBoundedText(dimension, "dimension", MAX_DIMENSION_LENGTH);
    }

    private static String requireBoundedText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }
}
