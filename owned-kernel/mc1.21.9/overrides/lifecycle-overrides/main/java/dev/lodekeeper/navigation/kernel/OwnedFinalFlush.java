package dev.lodekeeper.navigation.kernel;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class OwnedFinalFlush {
    private final OwnedWorkScheduler workers;
    private final Runnable save;
    private volatile CompletableFuture<Void> completion = new CompletableFuture<>();
    private boolean scheduled;
    private boolean executing;

    public OwnedFinalFlush(OwnedWorkScheduler workers, Runnable save) {
        this.workers = Objects.requireNonNull(workers);
        this.save = Objects.requireNonNull(save);
    }

    public synchronized void request() {
        if (scheduled || completion.isDone()) { return; }
        scheduled = true;
        if (!workers.maintain(this::run)) { scheduled = false; }
    }

    private void run() {
        CompletableFuture<Void> attempt;
        synchronized (this) {
            if (completion.isDone() || executing) { return; }
            executing = true;
            attempt = completion;
        }
        try {
            save.run();
            attempt.complete(null);
        } catch (Throwable failure) {
            attempt.completeExceptionally(failure);
        } finally {
            synchronized (this) { executing = false; }
        }
    }

    public boolean isComplete() {
        if (!completion.isDone()) { return false; }
        return !completion.isCompletedExceptionally();
    }

    public void finishAfterWorkersStop() {
        if (!workers.isStopped()) {
            throw new IllegalStateException("Final flush fallback requires stopped workers");
        }
        synchronized (this) {
            if (completion.isCompletedExceptionally()) { completion = new CompletableFuture<>(); }
        }
        run();
        completion.join();
    }
}
