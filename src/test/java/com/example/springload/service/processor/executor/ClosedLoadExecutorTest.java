package com.example.springload.service.processor.executor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class ClosedLoadExecutorTest {

    private static final Logger log = LoggerFactory.getLogger(ClosedLoadExecutorTest.class);

    @Test
    void completesAllUsersWhenNoInterruptions() throws Exception {
        ClosedLoadExecutor.ClosedLoadParameters parameters =
                new ClosedLoadExecutor.ClosedLoadParameters(3, 2, Duration.ZERO, Duration.ofMillis(20), Duration.ofSeconds(2));

        AtomicInteger iterationCounter = new AtomicInteger();
        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                () -> false,
                (user, iteration) -> iterationCounter.incrementAndGet(),
                log);

        assertEquals(6, iterationCounter.get());
        assertEquals(3, result.completedUsers());
        assertFalse(result.cancelled());
        assertFalse(result.holdExpired());
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
}
