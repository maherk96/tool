package com.example.springload.service.processor.executor;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class OpenLoadExecutorTest {

    private static final Logger log = LoggerFactory.getLogger(OpenLoadExecutorTest.class);

    private static String threadPrefix(UUID taskId) { return "open-load-task-" + taskId; }

    private static void assertNoLeakedThreads(String namePrefix) throws InterruptedException {
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

    private static OpenLoadExecutor.OpenLoadParameters validParams() {
        return new OpenLoadExecutor.OpenLoadParameters(10.0, 5, Duration.ofSeconds(1));
    }

    @Test
    void normalRunCompletesWithinDuration() throws Exception {
        UUID taskId = UUID.randomUUID();
        BooleanSupplier cancelled = () -> false;
        double rate = 50.0; // per second
        int maxConcurrent = 4;
        Duration duration = Duration.ofMillis(250);

        AtomicInteger inFlight = new AtomicInteger();
        Runnable task = () -> {
            inFlight.incrementAndGet();
            try { TimeUnit.MILLISECONDS.sleep(10); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { inFlight.decrementAndGet(); }
        };

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(rate, maxConcurrent, duration),
                cancelled,
                task,
                log);

        assertFalse(result.cancelled());
        assertTrue(result.launched() >= 1);
        assertEquals(result.launched(), result.completed());
        assertEquals(0, inFlight.get());
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void cancelsWhenCancellationFlagSet() throws Exception {
        UUID taskId = UUID.randomUUID();
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        BooleanSupplier cancelled = cancelFlag::get;

        Thread canceller = new Thread(() -> {
            try { TimeUnit.MILLISECONDS.sleep(80); } catch (InterruptedException ignored) { }
            cancelFlag.set(true);
        });
        canceller.start();

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(100.0, 4, Duration.ofSeconds(3)),
                cancelled,
                () -> { try { TimeUnit.MILLISECONDS.sleep(5); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } },
                log);

        assertTrue(result.cancelled());
        assertTrue(result.completed() <= result.launched());
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void stopsSchedulingOnIterationException() throws Exception {
        UUID taskId = UUID.randomUUID();
        BooleanSupplier cancelled = () -> false;

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(100.0, 2, Duration.ofSeconds(1)),
                cancelled,
                () -> { throw new RuntimeException("boom"); },
                log);

        assertTrue(result.cancelled(), "Executor should set cancelled on iteration failure");
        assertTrue(result.launched() >= 1);
        assertEquals(result.launched(), result.completed(), "Permits should be released even on failure");
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void honorsMaxConcurrency() throws Exception {
        UUID taskId = UUID.randomUUID();
        BooleanSupplier cancelled = () -> false;
        int maxConcurrent = 3;
        AtomicInteger current = new AtomicInteger();
        AtomicInteger observedMax = new AtomicInteger();

        Runnable task = () -> {
            int cur = current.incrementAndGet();
            observedMax.accumulateAndGet(cur, Math::max);
            try { TimeUnit.MILLISECONDS.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { current.decrementAndGet(); }
        };

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(500.0, maxConcurrent, Duration.ofMillis(250)),
                cancelled,
                task,
                log);

        assertFalse(result.cancelled());
        assertTrue(observedMax.get() <= maxConcurrent, "Observed concurrency should not exceed maxConcurrent");
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void maintainsRequestedArrivalRate() throws Exception {
        UUID taskId = UUID.randomUUID();
        double targetRate = 20.0; // requests per second
        Duration testDuration = Duration.ofSeconds(2);
        AtomicInteger requestCount = new AtomicInteger();

        long startTime = System.nanoTime();
        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(targetRate, 10, testDuration),
                () -> false,
                () -> requestCount.incrementAndGet(),
                log);

        long actualDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        double actualRate = requestCount.get() / (actualDurationMs / 1000.0);
        double tolerance = targetRate * 0.25; // 25% tolerance for timing variations

        assertFalse(result.cancelled());
        assertEquals(requestCount.get(), result.launched());
        assertEquals(result.launched(), result.completed());
        assertTrue(Math.abs(actualRate - targetRate) <= tolerance,
                "Actual rate " + actualRate + " should be within " + tolerance + " of target " + targetRate);
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void handlesVeryLowArrivalRates() throws Exception {
        UUID taskId = UUID.randomUUID();
        double lowRate = 0.5; // 0.5 requests per second (one every 2 seconds)
        Duration testDuration = Duration.ofMillis(3000);
        AtomicInteger requestCount = new AtomicInteger();

        long startTime = System.nanoTime();
        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(lowRate, 1, testDuration),
                () -> false,
                () -> {
                    requestCount.incrementAndGet();
                    // Quick task to avoid duration issues
                },
                log);

        long actualDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

        assertFalse(result.cancelled());
        assertTrue(result.launched() >= 1, "Should launch at least 1 request");
        assertTrue(result.launched() <= 3, "Should not launch too many requests for very low rate");
        assertEquals(result.launched(), result.completed());
        assertTrue(actualDurationMs >= testDuration.toMillis() - 100, "Should respect test duration");
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void handlesVeryHighArrivalRates() throws Exception {
        UUID taskId = UUID.randomUUID();
        double highRate = 1000.0; // 1000 requests per second
        int maxConcurrent = 50;
        Duration testDuration = Duration.ofMillis(500);
        AtomicInteger requestCount = new AtomicInteger();
        AtomicInteger maxObservedConcurrency = new AtomicInteger();
        AtomicInteger currentConcurrency = new AtomicInteger();

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(highRate, maxConcurrent, testDuration),
                () -> false,
                () -> {
                    requestCount.incrementAndGet();
                    int current = currentConcurrency.incrementAndGet();
                    maxObservedConcurrency.accumulateAndGet(current, Math::max);
                    try {
                        TimeUnit.MILLISECONDS.sleep(1); // Very short task
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        currentConcurrency.decrementAndGet();
                    }
                },
                log);

        assertFalse(result.cancelled());
        assertTrue(result.launched() >= 100, "Should handle high rate and launch many requests");
        assertEquals(result.launched(), result.completed());
        assertTrue(maxObservedConcurrency.get() <= maxConcurrent,
                "Should respect max concurrency even at high rates");
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void parameterValidation() {
        UUID taskId = UUID.randomUUID();
        BooleanSupplier cancelled = () -> false;
        Runnable task = () -> {};

        // Test null parameters
        assertThrows(NullPointerException.class, () ->
                OpenLoadExecutor.execute(null, validParams(), cancelled, task, log));
        assertThrows(NullPointerException.class, () ->
                OpenLoadExecutor.execute(taskId, null, cancelled, task, log));
        assertThrows(NullPointerException.class, () ->
                OpenLoadExecutor.execute(taskId, validParams(), null, task, log));
        assertThrows(NullPointerException.class, () ->
                OpenLoadExecutor.execute(taskId, validParams(), cancelled, null, log));
        assertThrows(NullPointerException.class, () ->
                OpenLoadExecutor.execute(taskId, validParams(), cancelled, task, null));
    }

    @Test
    void boundaryValueParameters() throws Exception {
        UUID taskId = UUID.randomUUID();
        BooleanSupplier cancelled = () -> false;
        AtomicInteger callCount = new AtomicInteger();
        Runnable task = () -> callCount.incrementAndGet();

        // Test zero max concurrent (should be clamped to 1)
        var zeroMaxParams = new OpenLoadExecutor.OpenLoadParameters(10.0, 0, Duration.ofMillis(100));
        var result = OpenLoadExecutor.execute(taskId, zeroMaxParams, cancelled, task, log);

        assertFalse(result.cancelled());
        assertTrue(result.launched() >= 1);
        assertEquals(result.launched(), result.completed());

        // Test very small arrival rate (should be clamped to minimum)
        callCount.set(0);
        var smallRateParams = new OpenLoadExecutor.OpenLoadParameters(0.0, 1, Duration.ofMillis(100));
        result = OpenLoadExecutor.execute(taskId, smallRateParams, cancelled, task, log);

        assertFalse(result.cancelled());
        assertTrue(result.launched() >= 1, "Even very small rates should launch at least one request");

        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void semaphoreReleasedOnTaskFailure() throws Exception {
        UUID taskId = UUID.randomUUID();
        int maxConcurrent = 2;
        AtomicInteger failureCount = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();

        // Task that always fails
        Runnable task = () -> {
            failureCount.incrementAndGet();
            throw new RuntimeException("Intentional failure #" + failureCount.get());
        };

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(100.0, maxConcurrent, Duration.ofMillis(200)),
                () -> false,
                task,
                log);

        // Should be cancelled due to exceptions, but all permits should be released
        assertTrue(result.cancelled(), "Should be cancelled due to task failures");
        assertTrue(result.launched() >= 1, "Should have launched at least one task before cancelling");
        assertEquals(result.launched(), result.completed(), "All permits should be released even on failure");
        assertTrue(failureCount.get() >= 1, "Should have at least one failure");
        assertEquals(0, successCount.get(), "Should not have reached success tasks due to cancellation");
        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void concurrentCancellationAndExecution() throws Exception {
        UUID taskId = UUID.randomUUID();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger executionCount = new AtomicInteger();
        AtomicLong lastExecutionTime = new AtomicLong();

        // Schedule cancellation after some executions
        Thread canceller = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(100);
                cancelled.set(true);
            } catch (InterruptedException ignored) { }
        });
        canceller.start();

        long startTime = System.nanoTime();
        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(50.0, 5, Duration.ofSeconds(2)),
                cancelled::get,
                () -> {
                    executionCount.incrementAndGet();
                    lastExecutionTime.set(System.nanoTime() - startTime);
                    try { TimeUnit.MILLISECONDS.sleep(10); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                },
                log);

        assertTrue(result.cancelled());
        assertTrue(executionCount.get() > 0, "Some tasks should execute before cancellation");
        assertTrue(executionCount.get() < 100, "Not too many tasks should execute after cancellation");
        assertEquals(result.launched(), result.completed());

        // Verify cancellation happened in reasonable time (more lenient timing)
        long cancellationTimeMs = TimeUnit.NANOSECONDS.toMillis(lastExecutionTime.get());
        assertTrue(cancellationTimeMs >= 50, "Should have run for at least 50ms before final task");
        assertTrue(cancellationTimeMs <= 500, "Should not have run too long after cancellation was set");

        assertNoLeakedThreads(threadPrefix(taskId));
    }

    @Test
    void handlesInterruptedTasks() throws Exception {
        UUID taskId = UUID.randomUUID();
        AtomicInteger startedTasks = new AtomicInteger();
        AtomicInteger interruptedTasks = new AtomicInteger();

        Runnable task = () -> {
            startedTasks.incrementAndGet();
            try {
                TimeUnit.SECONDS.sleep(1); // Long sleep that should be interrupted
            } catch (InterruptedException e) {
                interruptedTasks.incrementAndGet();
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        };

        // Start execution then cancel quickly
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try { TimeUnit.MILLISECONDS.sleep(50); } catch (InterruptedException ignored) { }
            cancelled.set(true);
        });
        canceller.start();

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                new OpenLoadExecutor.OpenLoadParameters(10.0, 3, Duration.ofSeconds(2)),
                cancelled::get,
                task,
                log);

        assertTrue(result.cancelled());
        assertTrue(startedTasks.get() >= 1, "At least some tasks should start");
        assertEquals(result.launched(), result.completed());

        // Allow time for interrupted tasks to complete their cleanup
        TimeUnit.MILLISECONDS.sleep(100);
        assertNoLeakedThreads(threadPrefix(taskId));
    }
}