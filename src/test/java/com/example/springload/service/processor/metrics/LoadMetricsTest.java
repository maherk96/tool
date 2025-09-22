package com.example.springload.service.processor.metrics;

import com.example.springload.dto.TaskRunReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class LoadMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(LoadMetricsTest.class);

    private LoadMetrics newMetrics(String taskId) {
        return new LoadMetrics(new LoadMetrics.TaskConfig(
                taskId,
                "REST_LOAD",
                "https://svc",
                LoadMetrics.ModelKind.OPEN,
                null, // users
                null, // iterationsPerUser
                null, // warmup
                null, // rampUp
                null, // holdFor
                5.0, // arrivalRatePerSec
                Duration.ofSeconds(5), // duration
                1, // requestsPerIteration
                25, // expectedTotalRequests
                5.0 // expectedRps
        ), log);
    }

    // ===== ORIGINAL TESTS =====

    @Test
    void snapshotNowReflectsRequestsAndLatency() {
        LoadMetrics metrics = newMetrics("t-snap");

        // 2 successes and 1 categorized failure (counts as a request and contributes latency)
        metrics.recordRequestSuccess(10);
        metrics.recordRequestSuccess(20);
        metrics.recordFailure("HTTP_5xx", 50);

        LoadMetrics.LoadSnapshot snap = metrics.snapshotNow();
        assertThat(snap.totalRequests()).isEqualTo(3);
        assertThat(snap.totalErrors()).isEqualTo(1);
        assertThat(snap.latencyMinMs()).isEqualTo(10L);
        assertThat(snap.latencyMaxMs()).isEqualTo(50L);
        assertThat(snap.latencyAvgMs()).isEqualTo((10L + 20L + 50L) / 3);
        assertThat(snap.activeUserIterations()).isEmpty();
        assertThat(snap.achievedRps()).isNotNull();
        assertThat(snap.config().expectedTotalRequests()).isEqualTo(25);
    }

    @Test
    void userLifecycleTracksCountsAndHistogram() {
        LoadMetrics metrics = newMetrics("t-users");

        // Start two users
        metrics.recordUserStarted(0);
        metrics.recordUserStarted(1);
        // Progress user #1 then complete
        metrics.recordUserProgress(0, 2);
        metrics.recordRequestSuccess(10);
        metrics.recordUserCompleted(0, 3);
        // User #2 still active
        metrics.recordUserProgress(1, 1);

        assertThat(metrics.totalUsersStarted()).isEqualTo(2);
        assertThat(metrics.totalUsersCompleted()).isEqualTo(1);
        assertThat(metrics.currentUserIterations()).containsEntry(1, 1); // user index 1 at iteration 1
        assertThat(metrics.currentUserIterations()).doesNotContainKey(0);

        TaskRunReport r = metrics.buildFinalReport();
        assertThat(r.metrics.usersStarted).isEqualTo(2);
        assertThat(r.metrics.usersCompleted).isEqualTo(1);
        assertThat(r.metrics.userCompletionHistogram).hasSize(1);
        assertThat(r.metrics.userCompletionHistogram.getFirst().userId).isEqualTo(1); // 1-based id
        assertThat(r.metrics.userCompletionHistogram.getFirst().iterationsCompleted).isEqualTo(3);
    }

    @Test
    void errorSamplesAreCappedAndBreakdownIncludesUnknown() {
        LoadMetrics metrics = newMetrics("t-errors");

        // Record more than 5 failures; samples should be capped to 5
        for (int i = 0; i < 7; i++) {
            metrics.recordRequestFailure(new RuntimeException("boom-" + i));
        }
        // Also record a direct failure with null/blank category -> UNKNOWN
        metrics.recordFailure(null, 12);
        metrics.recordFailure("   ", 8);

        TaskRunReport r = metrics.buildFinalReport();
        assertThat(r.metrics.errorSamples).isNotNull();
        assertThat(r.metrics.errorSamples.size()).isBetween(1, 5);

        Map<String, Long> breakdown = r.metrics.errorBreakdown.stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.type, e -> e.count));
        assertThat(breakdown.getOrDefault("UNKNOWN", 0L)).isGreaterThanOrEqualTo(1L);
        assertThat(breakdown.getOrDefault("RuntimeException", 0L)).isGreaterThanOrEqualTo(1L);
        assertThat(r.metrics.failureCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void reservoirPercentilesAreDeterministicWithoutSampling() {
        LoadMetrics.Reservoir res = new LoadMetrics.Reservoir(200);
        for (int i = 1; i <= 100; i++) res.add(i);

        // With 1..100 data, 95th percentile should be ~95 and 99th ~99
        assertThat(res.percentile(95)).isPresent();
        assertThat(res.percentile(95).orElseThrow()).isEqualTo(95L);
        assertThat(res.percentile(99)).isPresent();
        assertThat(res.percentile(99).orElseThrow()).isEqualTo(99L);
    }

    @Test
    void timeSeriesWindowsReflectPerWindowLatency() {
        LoadMetrics metrics = newMetrics("t-ts");

        // Window 1: single high latency
        metrics.recordRequestSuccess(1000);
        metrics.forceSnapshotForTest();

        // Window 2: two small latencies
        metrics.recordRequestSuccess(10);
        metrics.recordRequestSuccess(20);
        metrics.forceSnapshotForTest();

        var series = metrics.getTimeSeries();
        assertThat(series.size()).isGreaterThanOrEqualTo(2);

        var first = series.get(0);
        var second = series.get(1);

        assertThat(first.latMaxMs()).isGreaterThanOrEqualTo(1000);
        assertThat(first.latMinMs()).isGreaterThanOrEqualTo(0);

        // Second window should not carry over the large max from the first window
        assertThat(second.latMinMs()).isEqualTo(10L);
        assertThat(second.latMaxMs()).isGreaterThanOrEqualTo(20L);
        assertThat(second.latMaxMs()).isLessThan(1000L);
        assertThat(second.latAvgMs()).isBetween(10L, 20L);
    }

    // ===== NEW ADDITIONAL TESTS =====

    @Test
    void startAndStopManagesScheduledSnapshots() throws InterruptedException {
        LoadMetrics metrics = newMetrics("t-lifecycle");

        // Should not have started snapshots yet
        assertThat(metrics.getTimeSeries()).isEmpty();

        metrics.start();
        // Wait for at least one scheduled snapshot
        await().atMost(6, TimeUnit.SECONDS)
                .until(() -> metrics.getTimeSeries().size() > 0);

        metrics.stopAndSummarize();
        int snapshotCount = metrics.getTimeSeries().size();

        // Should stop taking snapshots
        Thread.sleep(6000);
        assertThat(metrics.getTimeSeries()).hasSize(snapshotCount);
    }

    @Test
    void concurrentRequestRecordingIsThreadSafe() throws InterruptedException {
        LoadMetrics metrics = newMetrics("t-concurrent");
        int numThreads = 10;
        int requestsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(numThreads);

        // Simulate multiple threads recording requests simultaneously
        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        metrics.recordRequestSuccess(ThreadLocalRandom.current().nextLong(1, 100));
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(10, TimeUnit.SECONDS);
        assertThat(metrics.totalRequests()).isEqualTo(numThreads * requestsPerThread);
    }

    @Test
    void achievedRpsCalculationWithVeryShortDuration() {
        LoadMetrics metrics = newMetrics("t-rps");

        metrics.recordRequestSuccess(10);
        LoadMetrics.LoadSnapshot snap = metrics.snapshotNow();

        // Should handle very short durations without division by zero
        assertThat(snap.achievedRps()).isNotNull();
        assertThat(snap.achievedRps()).isGreaterThan(0.0);
    }

    @Test
    void achievedRpsWithZeroRequests() {
        LoadMetrics metrics = newMetrics("t-zero-rps");
        LoadMetrics.LoadSnapshot snap = metrics.snapshotNow();

        assertThat(snap.achievedRps()).isEqualTo(0.0);
    }

    @Test
    void protocolProvidersAffectFinalReport() {
        LoadMetrics metrics = newMetrics("t-protocol");

        ProtocolMetricsProvider mockProvider = mock(ProtocolMetricsProvider.class);
        metrics.registerProtocolMetrics(mockProvider);

        TaskRunReport report = metrics.buildFinalReport();

        verify(mockProvider).applyTo(report);
    }

    @Test
    void multipleProtocolProvidersAllApplied() {
        LoadMetrics metrics = newMetrics("t-multi-protocol");

        ProtocolMetricsProvider provider1 = mock(ProtocolMetricsProvider.class);
        ProtocolMetricsProvider provider2 = mock(ProtocolMetricsProvider.class);

        metrics.registerProtocolMetrics(provider1);
        metrics.registerProtocolMetrics(provider2);

        TaskRunReport report = metrics.buildFinalReport();

        verify(provider1).applyTo(report);
        verify(provider2).applyTo(report);
    }

    @Test
    void closedModelConfigurationInReport() {
        LoadMetrics metrics = new LoadMetrics(new LoadMetrics.TaskConfig(
                "t-closed",
                "REST_LOAD",
                "https://api.example.com",
                LoadMetrics.ModelKind.CLOSED,
                10, // users
                5,  // iterationsPerUser
                Duration.ofSeconds(30), // warmup
                Duration.ofSeconds(60), // rampUp
                Duration.ofMinutes(5),  // holdFor
                null, // arrivalRatePerSec (not used in closed model)
                null, // duration (not used in closed model)
                2,    // requestsPerIteration
                100,  // expectedTotalRequests
                null  // expectedRps (calculated)
        ), log);

        TaskRunReport report = metrics.buildFinalReport();

        assertThat(report.model).isEqualTo(TaskRunReport.ModelKind.CLOSED);
        assertThat(report.config.users).isEqualTo(10);
        assertThat(report.config.expectedRps).isNotNull(); // Should be calculated
    }

    @Test
    void negativeLatencyIsHandledGracefully() {
        LoadMetrics metrics = newMetrics("t-negative");

        metrics.recordRequestSuccess(-50); // Invalid negative latency
        metrics.recordFailure("TEST_ERROR", -10);

        LoadMetrics.LoadSnapshot snap = metrics.snapshotNow();

        // Should clamp negative values to 0
        assertThat(snap.latencyMinMs()).isEqualTo(0L);
    }

    @Test
    void nullAndBlankErrorCategoriesHandled() {
        LoadMetrics metrics = newMetrics("t-null-errors");

        metrics.recordFailure(null, 10);
        metrics.recordFailure("", 20);
        metrics.recordFailure("   ", 30);
        metrics.recordRequestFailure(null); // null exception

        Map<String, Long> breakdown = metrics.errorBreakdown();
        assertThat(breakdown.getOrDefault("UNKNOWN", 0L)).isGreaterThan(0L);
    }

    @Test
    void duplicateUserStartsAreHandled() {
        LoadMetrics metrics = newMetrics("t-duplicate");

        metrics.recordUserStarted(0);
        metrics.recordUserStarted(0); // Duplicate start

        assertThat(metrics.totalUsersStarted()).isEqualTo(2); // Should count both
        assertThat(metrics.currentUserIterations()).containsKey(0);
    }

    @Test
    void userCompletionWithoutStartIsHandled() {
        LoadMetrics metrics = newMetrics("t-orphan");

        // Complete user that was never started
        metrics.recordUserCompleted(99, 5);

        assertThat(metrics.totalUsersCompleted()).isEqualTo(1);

        TaskRunReport report = metrics.buildFinalReport();
        // Should handle missing start time gracefully
        assertThat(report.metrics.userCompletionHistogram).isNotNull();
    }

    @Test
    void reservoirHandlesLargeDatasets() {
        LoadMetrics.Reservoir reservoir = new LoadMetrics.Reservoir(100);

        // Add more data than capacity
        for (int i = 1; i <= 1000; i++) {
            reservoir.add(i);
        }

        // Should still provide reasonable percentiles
        assertThat(reservoir.percentile(50)).isPresent();
        assertThat(reservoir.percentile(95)).isPresent();
        assertThat(reservoir.percentile(99)).isPresent();
    }

    @Test
    void finalReportContainsAllExpectedFields() {
        LoadMetrics metrics = newMetrics("t-complete");

        // Add some data
        metrics.recordUserStarted(0);
        metrics.recordRequestSuccess(100);
        metrics.recordUserCompleted(0, 1);
        metrics.forceSnapshotForTest();

        TaskRunReport report = metrics.buildFinalReport();

        // Verify all major sections are present
        assertThat(report.taskId).isNotNull();
        assertThat(report.startTime).isNotNull();
        assertThat(report.endTime).isNotNull();
        assertThat(report.durationSec).isGreaterThan(0.0);
        assertThat(report.environment).isNotNull();
        assertThat(report.config).isNotNull();
        assertThat(report.metrics).isNotNull();
        assertThat(report.timeseries).isNotNull();
    }

    @Test
    void reservoirWithEmptyDataReturnsEmptyPercentiles() {
        LoadMetrics.Reservoir reservoir = new LoadMetrics.Reservoir(100);

        assertThat(reservoir.percentile(50)).isEmpty();
        assertThat(reservoir.percentile(95)).isEmpty();
        assertThat(reservoir.percentile(99)).isEmpty();
    }

    @Test
    void concurrentUserLifecycleOperations() throws InterruptedException {
        LoadMetrics metrics = newMetrics("t-concurrent-users");
        int numUsers = 20;
        CountDownLatch startLatch = new CountDownLatch(numUsers);
        CountDownLatch completeLatch = new CountDownLatch(numUsers);

        // Start users concurrently
        for (int i = 0; i < numUsers; i++) {
            final int userId = i;
            new Thread(() -> {
                try {
                    metrics.recordUserStarted(userId);
                    metrics.recordUserProgress(userId, 1);
                    metrics.recordUserCompleted(userId, 2);
                } finally {
                    startLatch.countDown();
                    completeLatch.countDown();
                }
            }).start();
        }

        startLatch.await(10, TimeUnit.SECONDS);
        completeLatch.await(10, TimeUnit.SECONDS);

        assertThat(metrics.totalUsersStarted()).isEqualTo(numUsers);
        assertThat(metrics.totalUsersCompleted()).isEqualTo(numUsers);
        assertThat(metrics.currentUserIterations()).isEmpty(); // All completed
    }

    @Test
    void errorClassificationLogic() {
        LoadMetrics metrics = newMetrics("t-error-classification");

        // Test different exception types get classified correctly
        metrics.recordRequestFailure(new java.net.ConnectException("Connection refused"));
        metrics.recordRequestFailure(new java.util.concurrent.TimeoutException("Request timed out"));
        metrics.recordRequestFailure(new RuntimeException("Generic error"));

        Map<String, Long> breakdown = metrics.errorBreakdown();

        assertThat(breakdown.getOrDefault("CONNECT", 0L)).isGreaterThan(0L);
        assertThat(breakdown.getOrDefault("TIMEOUT", 0L)).isGreaterThan(0L);
        assertThat(breakdown.getOrDefault("RuntimeException", 0L)).isGreaterThan(0L);
    }

    @Test
    void latencyMetricsWithMixedSuccessAndFailures() {
        LoadMetrics metrics = newMetrics("t-mixed");

        // Mix of successes and failures with different latencies
        metrics.recordRequestSuccess(100);
        metrics.recordFailure("HTTP_5xx", 200);
        metrics.recordRequestSuccess(50);
        metrics.recordRequestFailure(new RuntimeException("error"));
        metrics.recordRequestSuccess(150);

        LoadMetrics.LoadSnapshot snap = metrics.snapshotNow();

        // All should contribute to request count
        assertThat(snap.totalRequests()).isEqualTo(5);
        assertThat(snap.totalErrors()).isEqualTo(2);

        // Latency should be calculated from all requests (including categorized failures)
        assertThat(snap.latencyMinMs()).isEqualTo(50L);
        assertThat(snap.latencyMaxMs()).isEqualTo(200L);

        // Only successes and categorized failures contribute latency; uncategorized failures get 0
        // So we have: 100, 200, 50, 0, 150 -> avg = 500/5 = 100
        assertThat(snap.latencyAvgMs()).isEqualTo(100L);
    }
}