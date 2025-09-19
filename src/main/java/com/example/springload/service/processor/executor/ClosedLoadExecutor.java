package com.example.springload.service.processor.executor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

import static java.util.concurrent.Executors.newFixedThreadPool;

/**
 * Generic executor for CLOSED load models. Handles warmup, ramp-up, and hold phases while coordinating
 * virtual user execution provided by protocol-specific processors.
 */
public final class ClosedLoadExecutor {

    private static final long SLEEP_CHUNK_MILLIS = 100L;

    private ClosedLoadExecutor() {
    }

    public static ClosedLoadResult execute(UUID taskId,
                                           ClosedLoadParameters parameters,
                                           BooleanSupplier cancellationRequested,
                                           VirtualUserIterationRunner iterationRunner,
                                           Logger logger) throws Exception {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        Objects.requireNonNull(iterationRunner, "iterationRunner");
        Objects.requireNonNull(logger, "logger");

        int users = Math.max(1, parameters.users());
        int iterations = Math.max(1, parameters.iterations());
        Duration warmup = toDuration(parameters.warmup());
        Duration rampUp = toDuration(parameters.rampUp());
        Duration holdFor = toDuration(parameters.holdFor());

        AtomicBoolean cancellationObserved = new AtomicBoolean(false);
        AtomicBoolean holdExpired = new AtomicBoolean(false);
        AtomicInteger completedUsers = new AtomicInteger();

        if (!warmup.isZero()) {
            logger.info("Task {} entering warmup for {}", taskId, warmup);
            sleepWithCancellation(warmup, cancellationRequested, cancellationObserved);
            logger.info("Task {} completed warmup", taskId);
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("closed-load-task-" + taskId + "-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        };

        var executor = newFixedThreadPool(users, threadFactory);
        List<Future<?>> futures = new ArrayList<>(users);

        long holdDeadline = holdFor.isZero() ? Long.MAX_VALUE : System.nanoTime() + holdFor.toNanos();
        double rampIntervalMillis = computeRampIntervalMillis(users, rampUp);

        try {
            logger.info("Task {} starting ramp-up for {} users over {}", taskId, users, rampUp);
            for (int userIndex = 0; userIndex < users; userIndex++) {
                if (shouldStop(cancellationRequested, cancellationObserved) || isHoldExpired(holdDeadline)) {
                    holdExpired.compareAndSet(false, isHoldExpired(holdDeadline));
                    logger.info("Task {} stopping ramp-up at user {} due to {}", taskId, userIndex,
                            holdExpired.get() ? "hold expiration" : "cancellation");
                    break;
                }

                final int currentUser = userIndex;
                futures.add(executor.submit(() -> runVirtualUser(taskId,
                        users,
                        currentUser,
                        iterations,
                        holdDeadline,
                        cancellationRequested,
                        cancellationObserved,
                        holdExpired,
                        completedUsers,
                        iterationRunner,
                        logger)));

                if (userIndex < users - 1 && rampIntervalMillis > 0) {
                    sleepWithCancellation(Duration.ofMillis((long) rampIntervalMillis), cancellationRequested, cancellationObserved);
                }
            }

            logger.info("Task {} ramp-up complete, awaiting user completion", taskId);
            waitForUsers(futures, holdDeadline, cancellationRequested, cancellationObserved, holdExpired, logger, taskId);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }

        return new ClosedLoadResult(users, completedUsers.get(), cancellationObserved.get(), holdExpired.get());
    }

    private static void runVirtualUser(UUID taskId,
                                       int totalUsers,
                                       int userIndex,
                                       int iterations,
                                       long holdDeadline,
                                       BooleanSupplier cancellationRequested,
                                       AtomicBoolean cancellationObserved,
                                       AtomicBoolean holdExpired,
                                       AtomicInteger completedUsers,
                                       VirtualUserIterationRunner iterationRunner,
                                       Logger logger) {
        logger.info("Task {} virtual user {} started", taskId, userIndex + 1);
        for (int iteration = 0; iteration < iterations; iteration++) {
            if (shouldStop(cancellationRequested, cancellationObserved)) {
                logger.info("Task {} virtual user {} stopping due to cancellation at iteration {}", taskId, userIndex + 1, iteration);
                return;
            }
            if (isHoldExpired(holdDeadline)) {
                holdExpired.set(true);
                logger.info("Task {} virtual user {} stopping due to hold expiration at iteration {}", taskId, userIndex + 1, iteration);
                return;
            }
            try {
                iterationRunner.run(userIndex, iteration);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                logger.info("Task {} virtual user {} interrupted", taskId, userIndex + 1);
                return;
            } catch (Exception ex) {
                throw new RuntimeException("Virtual user iteration failed", ex);
            }
        }
        int done = completedUsers.incrementAndGet();
        logger.info("Task {} virtual user {} completed all {} iterations (users completed {}/{})",
                taskId,
                userIndex + 1,
                iterations,
                done,
                totalUsers);
    }

    private static void waitForUsers(List<Future<?>> futures,
                                     long holdDeadline,
                                     BooleanSupplier cancellationRequested,
                                     AtomicBoolean cancellationObserved,
                                     AtomicBoolean holdExpired,
                                     Logger logger,
                                     UUID taskId) throws Exception {
        for (Future<?> future : futures) {
            if (future == null) {
                continue;
            }
            try {
                if (shouldStop(cancellationRequested, cancellationObserved)) {
                    future.cancel(true);
                    continue;
                }
                if (isHoldExpired(holdDeadline)) {
                    holdExpired.set(true);
                    future.cancel(true);
                    continue;
                }
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (java.util.concurrent.CancellationException ignored) {
                logger.debug("Task {} future cancelled", taskId);
            }
        }
    }

    private static boolean shouldStop(BooleanSupplier cancelled, AtomicBoolean cancellationObserved) {
        boolean requested = Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
        if (requested) {
            cancellationObserved.set(true);
        }
        return requested;
    }

    private static boolean isHoldExpired(long holdDeadline) {
        return System.nanoTime() >= holdDeadline;
    }

    private static Duration toDuration(Duration duration) {
        return duration != null ? duration : Duration.ZERO;
    }

    private static double computeRampIntervalMillis(int users, Duration rampUp) {
        if (users <= 1 || rampUp.isZero()) {
            return 0;
        }
        return rampUp.toMillis() / (double) (users - 1);
    }

    private static void sleepWithCancellation(Duration duration,
                                              BooleanSupplier cancellationRequested,
                                              AtomicBoolean cancellationObserved) throws InterruptedException {
        long remaining = duration.toMillis();
        while (remaining > 0) {
            if (shouldStop(cancellationRequested, cancellationObserved)) {
                throw new InterruptedException("Cancelled during sleep");
            }
            long chunk = Math.min(SLEEP_CHUNK_MILLIS, remaining);
            TimeUnit.MILLISECONDS.sleep(chunk);
            remaining -= chunk;
        }
    }

    public record ClosedLoadParameters(int users, int iterations, Duration warmup, Duration rampUp, Duration holdFor) {
    }

    public record ClosedLoadResult(int totalUsers, int completedUsers, boolean cancelled, boolean holdExpired) {
    }

    @FunctionalInterface
    public interface VirtualUserIterationRunner {
        void run(int userIndex, int iteration) throws Exception;
    }
}
