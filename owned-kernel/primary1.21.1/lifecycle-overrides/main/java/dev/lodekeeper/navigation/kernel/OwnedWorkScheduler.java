package dev.lodekeeper.navigation.kernel;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class OwnedWorkScheduler {
    private final ThreadPoolExecutor search = lane("search", 1);
    private final ThreadPoolExecutor maintenance = lane("maintenance", 64);

    private static ThreadPoolExecutor lane(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "lodekeeper-navigation-" + name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public Future<?> search(Runnable task) { return submit(search, task); }
    public Future<?> maintenance(Runnable task) { return submit(maintenance, task); }

    private static Future<?> submit(ThreadPoolExecutor lane, Runnable task) {
        lane.purge();
        FutureTask<Void> future = new FutureTask<>(task, null) {
            @Override protected void done() {
                if (isCancelled()) { lane.remove(this); }
            }
        };
        lane.execute(future);
        return future;
    }

    public boolean maintain(Runnable task) {
        try {
            maintenance.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    public boolean isStopped() {
        return search.isTerminated() && maintenance.isTerminated();
    }

    public boolean stop(long timeoutMillis) {
        search.shutdownNow();
        maintenance.shutdown();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            await(search, deadline);
            await(maintenance, deadline);
            if (!maintenance.isTerminated()) {
                maintenance.shutdownNow();
                await(maintenance, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
            }
        } catch (InterruptedException interrupted) {
            search.shutdownNow();
            maintenance.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return search.isTerminated() && maintenance.isTerminated();
    }

    private static void await(ThreadPoolExecutor lane, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            lane.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        }
    }
}
