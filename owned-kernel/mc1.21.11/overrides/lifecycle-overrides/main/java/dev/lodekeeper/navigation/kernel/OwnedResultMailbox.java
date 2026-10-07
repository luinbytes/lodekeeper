package dev.lodekeeper.navigation.kernel;

import java.util.Objects;

public final class OwnedResultMailbox<Q, R> {
    private Q request;
    private R result;

    public synchronized void begin(Q next) {
        request = Objects.requireNonNull(next);
        result = null;
    }

    public synchronized boolean complete(Q expected, R completed) {
        if (request != expected || result != null) { return false; }
        result = Objects.requireNonNull(completed);
        return true;
    }

    public synchronized R take(Q expected) {
        if (request != expected || result == null) { return null; }
        R ready = result;
        request = null;
        result = null;
        return ready;
    }

    public synchronized void cancel(Q expected) {
        if (request == expected) {
            request = null;
            result = null;
        }
    }
}
