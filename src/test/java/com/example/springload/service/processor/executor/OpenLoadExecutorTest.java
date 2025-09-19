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

class OpenLoadExecutorTest {

    private static final Logger log = LoggerFactory.getLogger(OpenLoadExecutorTest.class);

    @Test
    void openLoadLowIntensityMeetsExpectedRate() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        OpenLoadExecutor.OpenLoadParameters parameters =
                new OpenLoadExecutor.OpenLoadParameters(5.0, 2, Duration.ofSeconds(5));

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                () -> false,
                executed::incrementAndGet,
                log);

        double expected = expectedIterations(parameters);
        assertLoadWithinTolerance(result, executed.get(), expected, 0.20);
    }

    @Test
    void openLoadMediumIntensityMeetsExpectedRate() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        OpenLoadExecutor.OpenLoadParameters parameters =
                new OpenLoadExecutor.OpenLoadParameters(15.0, 5, Duration.ofSeconds(15));

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                () -> false,
                executed::incrementAndGet,
                log);

        double expected = expectedIterations(parameters);
        assertLoadWithinTolerance(result, executed.get(), expected, 0.15);
    }

    @Test
    void openLoadHighIntensitySustainsTwoMinuteRun() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        OpenLoadExecutor.OpenLoadParameters parameters =
                new OpenLoadExecutor.OpenLoadParameters(30.0, 10, Duration.ofMinutes(2));

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                () -> false,
                executed::incrementAndGet,
                log);

        double expected = expectedIterations(parameters);
        assertLoadWithinTolerance(result, executed.get(), expected, 0.20);
    }

    @Test
    void honoursCancellationSignal() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        AtomicBoolean cancel = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancel.set(true);
        });
        canceller.start();

        OpenLoadExecutor.OpenLoadParameters parameters =
                new OpenLoadExecutor.OpenLoadParameters(50.0, 2, Duration.ofSeconds(2));

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                UUID.randomUUID(),
                parameters,
                cancel::get,
                executed::incrementAndGet,
                log);

        canceller.join();

        assertTrue(result.cancelled());
        assertTrue(result.launched() >= result.completed());
        assertTrue(executed.get() >= result.completed());
    }

    private double expectedIterations(OpenLoadExecutor.OpenLoadParameters parameters) {
        double durationSeconds = parameters.duration().toMillis() / 1000.0;
        return parameters.arrivalRatePerSec() * durationSeconds;
    }

    private void assertLoadWithinTolerance(OpenLoadExecutor.OpenLoadResult result,
                                           int executed,
                                           double expected,
                                           double toleranceFraction) {
        assertTrue(result.launched() > 0, "No iterations were launched");
        assertThat(result.cancelled()).isFalse();
        assertThat(result.completed()).isEqualTo(result.launched());
        assertThat((long) executed).isEqualTo(result.completed());

        double lowerBound = expected * (1.0 - toleranceFraction);
        double upperBound = expected * (1.0 + toleranceFraction);
        assertThat(result.completed()).isBetween((long) Math.floor(lowerBound), (long) Math.ceil(upperBound));
    }
}
