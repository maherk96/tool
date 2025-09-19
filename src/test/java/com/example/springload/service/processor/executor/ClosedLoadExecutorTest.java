package com.example.springload.service.processor.executor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosedLoadExecutorTest {

    private static final Logger log = LoggerFactory.getLogger(ClosedLoadExecutorTest.class);

    @Test
    void closedLoadLowIntensityReachesExpectedIterations() throws Exception {
        int users = 2;
        int iterations = 50;
        ExecutionStats stats = runClosedLoad(users, iterations, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(15), 100);

        assertThat(stats.totalIterations()).isEqualTo(users * iterations);
        assertThat(stats.result().completedUsers()).isEqualTo(users);
        assertThat(stats.result().cancelled()).isFalse();
        assertThat(stats.result().holdExpired()).isFalse();
    }

    @Test
    void closedLoadMediumIntensityReachesExpectedIterations() throws Exception {
        int users = 3;
        int iterations = 150;
        ExecutionStats stats = runClosedLoad(users, iterations, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(45), 100);

        assertThat(stats.totalIterations()).isEqualTo(users * iterations);
        assertThat(stats.result().completedUsers()).isEqualTo(users);
        assertThat(stats.result().cancelled()).isFalse();
        assertThat(stats.result().holdExpired()).isFalse();
    }

    @Test
    void closedLoadHighIntensitySustainsTwoMinuteRun() throws Exception {
        int users = 2;
        int iterations = 1200;
        ExecutionStats stats = runClosedLoad(users, iterations, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(150), 100);

        assertThat(stats.totalIterations()).isEqualTo(users * iterations);
        assertThat(stats.result().completedUsers()).isEqualTo(users);
        assertThat(stats.result().cancelled()).isFalse();
        assertThat(stats.result().holdExpired()).isFalse();
    }

    @Test
    void stopsWhenHoldExpires() throws Exception {
        ClosedLoadExecutor.ClosedLoadParameters parameters =
                new ClosedLoadExecutor.ClosedLoadParameters(2, 100, Duration.ZERO, Duration.ZERO, Duration.ofMillis(100));

        AtomicInteger iterationCounter = new AtomicInteger();
        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                () -> false,
                (user, iteration) -> {
                    iterationCounter.incrementAndGet();
                    TimeUnit.MILLISECONDS.sleep(15);
                },
                log);

        assertTrue(result.holdExpired());
        assertTrue(iterationCounter.get() > 0);
        assertTrue(result.completedUsers() < result.totalUsers());
    }

    @Test
    void honoursCancellationRequest() throws Exception {
        ClosedLoadExecutor.ClosedLoadParameters parameters =
                new ClosedLoadExecutor.ClosedLoadParameters(4, 50, Duration.ZERO, Duration.ofMillis(10), Duration.ofSeconds(5));

        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(60);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancelRequested.set(true);
        });
        canceller.start();

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                cancelRequested::get,
                (user, iteration) -> TimeUnit.MILLISECONDS.sleep(5),
                log);

        canceller.join();

        assertTrue(result.cancelled());
        assertTrue(result.completedUsers() < result.totalUsers());
    }

    private ExecutionStats runClosedLoad(int users,
                                         int iterations,
                                         Duration warmup,
                                         Duration rampUp,
                                         Duration holdFor,
                                         long perIterationDelayMs) throws Exception {
        AtomicInteger iterationCounter = new AtomicInteger();
        ClosedLoadExecutor.ClosedLoadParameters parameters =
                new ClosedLoadExecutor.ClosedLoadParameters(users, iterations, warmup, rampUp, holdFor);

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                () -> false,
                (user, iteration) -> {
                    iterationCounter.incrementAndGet();
                    if (perIterationDelayMs > 0) {
                        TimeUnit.MILLISECONDS.sleep(perIterationDelayMs);
                    }
                },
                log);

        return new ExecutionStats(result, iterationCounter.get());
    }

    private record ExecutionStats(ClosedLoadExecutor.ClosedLoadResult result, int totalIterations) {}
}
