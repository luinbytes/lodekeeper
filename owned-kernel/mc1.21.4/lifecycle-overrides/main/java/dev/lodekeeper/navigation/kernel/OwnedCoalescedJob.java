package dev.lodekeeper.navigation.kernel;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class OwnedCoalescedJob<I, R> {
    private final OwnedWorkScheduler workers;
    private final OwnedResultMailbox<Request<I>, Result<R>> mailbox = new OwnedResultMailbox<>();
    private Request<I> active;

    private static final class Request<I> {
        final I input;
        final BooleanSupplier valid;
        Future<?> future;
        Request(I input, BooleanSupplier valid) { this.input = input; this.valid = valid; }
    }

    public record Result<R>(R value, Throwable failure) {
        public R valueOrThrow() {
            if (failure != null) { throw new IllegalStateException("Owned maintenance job failed", failure); }
            return value;
        }
    }

    public OwnedCoalescedJob(OwnedWorkScheduler workers) { this.workers = workers; }

    public synchronized boolean submit(I input, BooleanSupplier valid, Function<I, R> operation) {
        if (active != null || !valid.getAsBoolean()) { return false; }
        Request<I> request = new Request<>(input, valid);
        active = request;
        mailbox.begin(request);
        try {
            request.future = workers.maintenance(() -> {
                Result<R> result;
                try {
                    if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) { return; }
                    result = new Result<>(operation.apply(input), null);
                } catch (Throwable failure) {
                    result = new Result<>(null, failure);
                }
                synchronized (OwnedCoalescedJob.this) {
                    if (active == request && valid.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                        mailbox.complete(request, result);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            mailbox.cancel(request);
            active = null;
            return false;
        }
    }

    public synchronized Result<R> poll() {
        if (active == null) { return null; }
        if (!active.valid.getAsBoolean()) { cancel(); return null; }
        Result<R> result = mailbox.take(active);
        if (result != null) { active = null; }
        return result;
    }

    public synchronized void cancel() {
        if (active == null) { return; }
        mailbox.cancel(active);
        if (active.future != null) { active.future.cancel(true); }
        active = null;
    }

    public synchronized boolean busy() { return active != null; }
}
