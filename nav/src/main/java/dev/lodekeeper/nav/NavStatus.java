package dev.lodekeeper.nav;

/** The externally observable state of an incremental navigation search. */
public enum NavStatus {
    IN_PROGRESS,
    FOUND,
    PARTIAL_LIMIT,
    NO_PATH,
    CANCELLED,
    STALE
}
