package com.example.springload.service.processor.executor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ClosedLoadExecutorTest {

    private static final Logger log = LoggerFactory.getLogger(ClosedLoadExecutorTest.class);

    private static String threadPrefix(UUID taskId) {
        return "closed-load-task-" + taskId;
    }

    private static void assertNoLeakedThreads(String namePrefix) throws InterruptedException {
        // allow a short grace period for threads to terminate
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean any = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.isAlive() && t.getName().startsWith(namePrefix));
            if (!any) return;
            TimeUnit.MILLISECONDS.sleep(10);
        }
        boolean stillAlive = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().startsWith(namePrefix));
        assertFalse(stillAlive, "Executor threads with prefix '" + namePrefix + "' should be terminated");
    }

    private static ClosedLoadExecutor.ClosedLoadParameters validParams() {
        return new ClosedLoadExecutor.ClosedLoadParameters(2, 5, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    @Test
    void executeCompletesAllIterationsWithoutCancellationOrErrors() throws Exception {
        UUID taskId = UUID.randomUUID();
        int users = 3;
        int iterations = 5;
        AtomicInteger callCount = new AtomicInteger();
        BooleanSupplier cancelled = () -> false;

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(users, iterations, Duration.ZERO, Duration.ZERO, Duration.ZERO);

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                params,
                cancelled,
                (userIndex, iteration) -> {
                    callCount.incrementAndGet();
                },
                log);

        assertEquals(users, result.totalUsers());
        assertEquals(users, result.completedUsers());
        assertFalse(result.cancelled());
        assertFalse(result.holdExpired());
        assertEquals(users * iterations, callCount.get());
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void executeCancelsWhenCancellationRequested() throws Exception {
        UUID taskId = UUID.randomUUID();
        int users = 4;
        int iterations = 10_000; // large to ensure long-running without cancellation
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        BooleanSupplier cancelled = cancelFlag::get;

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(users, iterations, Duration.ZERO, Duration.ZERO, Duration.ZERO);

        // Trigger cancellation shortly after start
        Thread canceller = new Thread(() -> {
            try { TimeUnit.MILLISECONDS.sleep(50); } catch (InterruptedException ignored) { }
            cancelFlag.set(true);
        });
        canceller.start();

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                params,
                cancelled,
                (userIndex, iteration) -> {
                    // small pause to give canceller time to flip the flag
                    TimeUnit.MILLISECONDS.sleep(2);
                },
                log);

        assertTrue(result.cancelled());
        assertTrue(result.completedUsers() >= 0 && result.completedUsers() <= users);
        assertFalse(result.holdExpired());
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void executeStopsOnHoldExpiration() throws Exception {
        UUID taskId = UUID.randomUUID();
        int users = 3;
        int iterations = 10000; // high to rely on holdFor cutoff
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        BooleanSupplier cancelled = cancelFlag::get;

        Duration holdFor = Duration.ofMillis(150);
        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(users, iterations, Duration.ZERO, Duration.ZERO, holdFor);

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                params,
                cancelled,
                (userIndex, iteration) -> {
                    // keep iterations somewhat long
                    TimeUnit.MILLISECONDS.sleep(5);
                },
                log);

        assertTrue(result.holdExpired());
        assertTrue(result.completedUsers() < users, "Not all users should complete on hold expiration");
        assertFalse(result.cancelled());
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void executePropagatesExceptionFromIterationRunner() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        int users = 2;
        int iterations = 5;
        BooleanSupplier cancelled = () -> false;

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(users, iterations, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(5));

        long start = System.nanoTime();
        Exception thrown = assertThrows(Exception.class, () ->
                ClosedLoadExecutor.execute(
                        taskId,
                        params,
                        cancelled,
                        (userIndex, iteration) -> {
                            throw new IllegalStateException("boom at user=" + userIndex + ", it=" + iteration);
                        },
                        log)
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Exception should be an ExecutionException bubbling up (or similar), within a short time window
        assertTrue(thrown instanceof ExecutionException || thrown.getClass().getName().contains("ExecutionException"));
        assertTrue(elapsedMs < 2000, "Should fail fast on iteration exception (elapsed=" + elapsedMs + "ms)");

        // Ensure threads cleaned up
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void warmupHonorsCancellationDuringSleep() {
        UUID taskId = UUID.randomUUID();
        AtomicBoolean cancel = new AtomicBoolean(false);
        BooleanSupplier cancelled = cancel::get;

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(1, 1, Duration.ofSeconds(5), Duration.ZERO, Duration.ZERO);

        Thread t = new Thread(() -> {
            try { TimeUnit.MILLISECONDS.sleep(100); } catch (InterruptedException ignored) { }
            cancel.set(true);
        });
        t.start();

        Exception ex = assertThrows(Exception.class, () ->
                ClosedLoadExecutor.execute(
                        taskId,
                        params,
                        cancelled,
                        (userIndex, iteration) -> {},
                        log)
        );

        assertTrue(ex instanceof InterruptedException || ex.getClass().getName().contains("Interrupted"),
                "Cancellation during warmup should interrupt execution");
    }

    @Test
    void rampUpTimingIsAccurate() throws Exception {
        UUID taskId = UUID.randomUUID();
        int users = 4;
        Duration rampUp = Duration.ofMillis(300);
        List<Long> startTimes = Collections.synchronizedList(new ArrayList<>());

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(users, 1, Duration.ZERO, rampUp, Duration.ZERO);

        long testStart = System.nanoTime();
        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                params,
                () -> false,
                (userIndex, iteration) -> startTimes.add(System.nanoTime() - testStart),
                log);

        assertEquals(users, result.totalUsers());
        assertEquals(users, result.completedUsers());
        assertEquals(users, startTimes.size());

        // Verify users started at approximately correct intervals
        if (users > 1) {
            for (int i = 1; i < startTimes.size(); i++) {
                long interval = TimeUnit.NANOSECONDS.toMillis(startTimes.get(i) - startTimes.get(i-1));
                long expectedInterval = rampUp.toMillis() / (users - 1);
                assertTrue(Math.abs(interval - expectedInterval) <= 100,
                        "Ramp-up interval should be approximately " + expectedInterval + "ms, was " + interval + "ms");
            }
        }
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void combinesAllPhases() throws Exception {
        UUID taskId = UUID.randomUUID();
        int users = 2;
        int iterations = 3;
        Duration warmup = Duration.ofMillis(100);
        Duration rampUp = Duration.ofMillis(200);
        Duration holdFor = Duration.ofMillis(500);

        AtomicInteger callCount = new AtomicInteger();
        AtomicLong testStart = new AtomicLong();
        List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(users, iterations, warmup, rampUp, holdFor);

        testStart.set(System.nanoTime());
        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                params,
                () -> false,
                (userIndex, iteration) -> {
                    callCount.incrementAndGet();
                    executionTimes.add(System.nanoTime() - testStart.get());
                    TimeUnit.MILLISECONDS.sleep(50);
                },
                log);

        assertEquals(users, result.totalUsers());
        assertEquals(users, result.completedUsers());
        assertEquals(users * iterations, callCount.get());
        assertFalse(result.cancelled());
        assertFalse(result.holdExpired());

        // First execution should start after warmup + some ramp-up time
        long firstExecution = Collections.min(executionTimes);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(firstExecution) >= warmup.toMillis(),
                "First execution should occur after warmup period");

        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void cancellationDuringRampUp() throws Exception {
        UUID taskId = UUID.randomUUID();
        int users = 5;
        Duration rampUp = Duration.ofMillis(500);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        AtomicInteger uniqueUsersStarted = new AtomicInteger();
        AtomicInteger totalIterations = new AtomicInteger();

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(users, 100, Duration.ZERO, rampUp, Duration.ZERO);

        // Cancel during ramp-up
        Thread canceller = new Thread(() -> {
            try { TimeUnit.MILLISECONDS.sleep(250); } catch (InterruptedException ignored) { }
            cancelFlag.set(true);
        });
        canceller.start();

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                params,
                cancelFlag::get,
                (userIndex, iteration) -> {
                    if (iteration == 0) {
                        uniqueUsersStarted.incrementAndGet();
                    }
                    totalIterations.incrementAndGet();
                    TimeUnit.MILLISECONDS.sleep(10);
                },
                log);

        assertTrue(result.cancelled());
        assertEquals(users, result.totalUsers(), "totalUsers should always equal the original target");
        assertTrue(uniqueUsersStarted.get() < users, "Not all users should start executing due to cancellation during ramp-up");
        assertTrue(totalIterations.get() < users * 100, "Not all iterations should complete");
        assertTrue(result.completedUsers() < users, "Not all users should complete all iterations");
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void parameterValidation() {
        UUID taskId = UUID.randomUUID();
        BooleanSupplier cancelled = () -> false;
        ClosedLoadExecutor.VirtualUserIterationRunner runner = (u, i) -> {};

        // Test null parameters
        assertThrows(NullPointerException.class, () ->
                ClosedLoadExecutor.execute(null, validParams(), cancelled, runner, log));
        assertThrows(NullPointerException.class, () ->
                ClosedLoadExecutor.execute(taskId, null, cancelled, runner, log));
        assertThrows(NullPointerException.class, () ->
                ClosedLoadExecutor.execute(taskId, validParams(), null, runner, log));
        assertThrows(NullPointerException.class, () ->
                ClosedLoadExecutor.execute(taskId, validParams(), cancelled, null, log));
        assertThrows(NullPointerException.class, () ->
                ClosedLoadExecutor.execute(taskId, validParams(), cancelled, runner, null));
    }

    @Test
    void singleUserSingleIteration() throws Exception {
        UUID taskId = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger();
        BooleanSupplier cancelled = () -> false;

        ClosedLoadExecutor.ClosedLoadParameters params =
                new ClosedLoadExecutor.ClosedLoadParameters(1, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO);

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                params,
                cancelled,
                (userIndex, iteration) -> {
                    assertEquals(0, userIndex, "Single user should have index 0");
                    assertEquals(0, iteration, "Single iteration should have index 0");
                    callCount.incrementAndGet();
                },
                log);

        assertEquals(1, result.totalUsers());
        assertEquals(1, result.completedUsers());
        assertEquals(1, callCount.get());
        assertFalse(result.cancelled());
        assertFalse(result.holdExpired());
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void boundaryCaseParameters() throws Exception {
        UUID taskId = UUID.randomUUID();
        BooleanSupplier cancelled = () -> false;
        AtomicInteger callCount = new AtomicInteger();

        // Test zero users (should be clamped to 1)
        var zeroUsersParams = new ClosedLoadExecutor.ClosedLoadParameters(0, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        var result = ClosedLoadExecutor.execute(taskId, zeroUsersParams, cancelled,
                (u, i) -> callCount.incrementAndGet(), log);

        assertEquals(1, result.totalUsers());
        assertEquals(1, result.completedUsers());
        assertEquals(1, callCount.get());

        // Test zero iterations (should be clamped to 1)
        callCount.set(0);
        var zeroIterationsParams = new ClosedLoadExecutor.ClosedLoadParameters(1, 0, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        result = ClosedLoadExecutor.execute(taskId, zeroIterationsParams, cancelled,
                (u, i) -> callCount.incrementAndGet(), log);

        assertEquals(1, result.totalUsers());
        assertEquals(1, result.completedUsers());
        assertEquals(1, callCount.get());

        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void resourceCleanupOnException() throws Exception {
        UUID taskId = UUID.randomUUID();

        // Count active threads before test
        int threadsBefore = Thread.activeCount();

        assertThrows(ExecutionException.class, () ->
                ClosedLoadExecutor.execute(taskId,
                        new ClosedLoadExecutor.ClosedLoadParameters(3, 5, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(1)),
                        () -> false,
                        (u, i) -> {
                            if (i == 1) { // Fail on second iteration
                                throw new RuntimeException("test failure");
                            }
                        },
                        log));

        // Allow time for cleanup
        TimeUnit.SECONDS.sleep(1);

        int threadsAfter = Thread.activeCount();
        assertTrue(threadsAfter <= threadsBefore + 3,
                "Thread count should not increase significantly after exception cleanup");

        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void concurrentCancellationScenarios() throws Exception {
        UUID taskId = UUID.randomUUID();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger iterationsRun = new AtomicInteger();

        // Cancel during execution but not immediately
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                cancelled.set(true);
                timer.cancel();
            }
        }, 150);

        var params = new ClosedLoadExecutor.ClosedLoadParameters(3, 1000, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        var result = ClosedLoadExecutor.execute(taskId, params, cancelled::get,
                (u, i) -> {
                    iterationsRun.incrementAndGet();
                    TimeUnit.MILLISECONDS.sleep(10);
                }, log);

        assertTrue(result.cancelled());
        assertTrue(iterationsRun.get() > 0, "Some iterations should have run before cancellation");
        assertTrue(iterationsRun.get() < 3000, "Not all iterations should complete");
        assertTrue(result.completedUsers() < 3, "Not all users should complete");
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void nullDurationsAreHandledGracefully() throws Exception {
        UUID taskId = UUID.randomUUID();
        AtomicInteger callCount = new AtomicInteger();

        // Test with null durations (should be treated as Duration.ZERO)
        var params = new ClosedLoadExecutor.ClosedLoadParameters(2, 3, null, null, null);
        var result = ClosedLoadExecutor.execute(taskId, params, () -> false,
                (u, i) -> callCount.incrementAndGet(), log);

        assertEquals(2, result.totalUsers());
        assertEquals(2, result.completedUsers());
        assertEquals(6, callCount.get());
        assertFalse(result.cancelled());
        assertFalse(result.holdExpired());
        assertNoLeakedThreads(threadPrefix(taskId));
    }
}