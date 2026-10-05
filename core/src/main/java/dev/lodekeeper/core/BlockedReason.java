package dev.lodekeeper.core;

import java.util.List;

/** A machine-readable reason why a requested item cannot be planned. */
public record BlockedReason(Code code, ItemId item, String detail, List<ItemId> dependencyPath) {
    public BlockedReason {
        if (code == null) throw new NullPointerException("code");
        detail = detail == null ? "" : detail;
        if (detail.length() > 512) detail = detail.substring(0, 512);
        dependencyPath = List.copyOf(dependencyPath == null ? List.of() : dependencyPath);
    }

    public enum Code {
        UNKNOWN_ITEM,
        AMBIGUOUS_ITEM,
        INVALID_COUNT,
        NO_SOURCE,
        CYCLE,
        DEPTH_LIMIT,
        NODE_LIMIT,
        TIME_LIMIT,
        STEP_LIMIT,
        EMPTY_TAG,
        UNREACHABLE_REQUIREMENT,
        UNSUPPORTED_SOURCE,
        INVALID_CATALOG
    }
}
