package com.example.springload.service.processor.executor;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

import static java.util.concurrent.Executors.newScheduledThreadPool;

/**
 * Executor for OPEN load model that schedules request iterations at a target arrival rate while honoring
 * max concurrency, duration limits, and cancellation signals.
 */
public final class OpenLoadExecutor {

    private OpenLoadExecutor() {
    }

    public static OpenLoadResult execute(UUID taskId,
                                         OpenLoadParameters parameters,
                                         BooleanSupplier cancellationRequested,
                                         Runnable iterationTask,
                                         Logger logger) throws InterruptedException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(iterationTask, "iterationTask");
        Objects.requireNonNull(logger, "logger");

        int maxConcurrent = Math.max(1, parameters.maxConcurrent());
        double arrivalRatePerSec = Math.max(0.000001, parameters.arrivalRatePerSec());
        Duration duration = toDuration(parameters.duration());

        long durationNanos = duration.isZero() ? Long.MAX_VALUE : duration.toNanos();
        long startNanos = System.nanoTime();
        long intervalNanos = (long) Math.max(1, 1_000_000_000L / arrivalRatePerSec);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicLong launchedTasks = new AtomicLong();
        AtomicLong completedTasks = new AtomicLong();

        Semaphore permits = new Semaphore(maxConcurrent);

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("open-load-task-" + taskId + "-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        };

        ScheduledExecutorService scheduler = newScheduledThreadPool(maxConcurrent, threadFactory);

        Runnable scheduleLoop = () -> {
            try {
                if (shouldStop(cancellationRequested)) {
                    cancelled.set(true);
                    return;
                }
                long elapsed = System.nanoTime() - startNanos;
                if (elapsed >= durationNanos) {
                    return;
                }
                if (!permits.tryAcquire()) {
                    return;
                }
                launchedTasks.incrementAndGet();
                scheduler.execute(() -> executeIteration(iterationTask, permits, completedTasks, logger));
            } catch (Throwable throwable) {
                logger.error("Task {} open load scheduler encountered error: {}", taskId, throwable.getMessage(), throwable);
                cancelled.set(true);
            }
        };

        long initialDelay = 0L;
        scheduler.scheduleAtFixedRate(scheduleLoop, initialDelay, intervalNanos, TimeUnit.NANOSECONDS);

        while (!cancelled.get()) {
            if (shouldStop(cancellationRequested)) {
                cancelled.set(true);
                break;
            }
            long elapsed = System.nanoTime() - startNanos;
            if (elapsed >= durationNanos) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }

        scheduler.shutdown();
        try {
            scheduler.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }

        return new OpenLoadResult(launchedTasks.get(), completedTasks.get(), cancelled.get());
    }

    private static void executeIteration(Runnable iterationTask,
                                         Semaphore permits,
                                         AtomicLong completedTasks,
                                         Logger logger) {
        try {
            iterationTask.run();
        } catch (Exception ex) {
            logger.error("Open load iteration failed: {}", ex.getMessage(), ex);
        } finally {
            permits.release();
            completedTasks.incrementAndGet();
        }
    }

    private static boolean shouldStop(BooleanSupplier cancellationRequested) {
        return Thread.currentThread().isInterrupted() || cancellationRequested.getAsBoolean();
    }

    private static Duration toDuration(Duration duration) {
        return duration != null ? duration : Duration.ZERO;
    }

    public record OpenLoadParameters(double arrivalRatePerSec, int maxConcurrent, Duration duration) {
    }

    public record OpenLoadResult(long launched, long completed, boolean cancelled) {
    }
}
