package dev.lodekeeper.core;

import java.util.Objects;

/** A prerequisite that an acquisition source needs before it can run. */
public sealed interface Requirement permits ItemRequirement, ToolRequirement, StationRequirement {
    String purpose();
}

final class RequirementText {
    private RequirementText() { }

    static String normalize(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 160) throw new IllegalArgumentException("Requirement purpose is too long");
        return normalized;
    }
}
