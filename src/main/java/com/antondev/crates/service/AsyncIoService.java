package com.antondev.crates.service;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded worker pool for plugin-owned filesystem and migration I/O. */
public final class AsyncIoService implements AutoCloseable {
    private static final int WORKERS = 2;
    private static final int MAXIMUM_QUEUED_TASKS = 64;

    private final ThreadPoolExecutor executor;

    public AsyncIoService() {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(
                WORKERS,
                WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAXIMUM_QUEUED_TASKS),
                task -> {
                    Thread thread = new Thread(task, "PlexonCrates-IO-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(CheckedSupplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(rejected);
        }
        return future;
    }

    public CompletableFuture<Void> run(CheckedRunnable task) {
        Objects.requireNonNull(task, "task");
        return submit(() -> {
            task.run();
            return null;
        });
    }

    public int queuedTasks() {
        return executor.getQueue().size();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
