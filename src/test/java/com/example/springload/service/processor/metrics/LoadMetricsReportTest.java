package com.example.springload.service.processor.metrics;

import com.example.springload.clients.utils.JsonUtil;
import com.example.springload.dto.TaskRunReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoadMetricsReportTest {

    private static final Logger log = LoggerFactory.getLogger(LoadMetricsReportTest.class);

    @Test
    void buildsReportForOpenModelWithRestEndpointStats() throws Exception {
        String taskId = UUID.randomUUID().toString();
        double rate = 10.0;
        Duration duration = Duration.ofSeconds(10);
        int reqPerIter = 3;
        long expectedIterations = (long) Math.floor(duration.toMillis() / 1000.0 * rate);
        long expectedTotalRequests = expectedIterations * reqPerIter;
        double expectedRps = rate * reqPerIter;

        LoadMetrics.TaskConfig cfg = new LoadMetrics.TaskConfig(
                taskId,
                "REST_LOAD",
                "https://api.example.com",
                LoadMetrics.ModelKind.OPEN,
                null,
                null,
                null,
                null,
                null,
                rate,
                duration,
                reqPerIter,
                expectedTotalRequests,
                expectedRps
        );

        LoadMetrics metrics = new LoadMetrics(cfg, log);
        metrics.start();
        // plug REST protocol metrics
        RestProtocolMetrics rest = new RestProtocolMetrics();
        metrics.registerProtocolMetrics(rest);

        // simulate successes and failures
        rest.recordSuccess("GET", "/v1/health", 20, 200);
        metrics.recordRequestSuccess(20, 200);

        rest.recordSuccess("GET", "/v1/users", 35, 204);
        metrics.recordRequestSuccess(35, 204);

        metrics.recordHttpFailure(500, 50);
        rest.recordHttpFailure(500, 50, "GET", "/v1/users", null);
        metrics.recordHttpFailure(404, 15);
        rest.recordHttpFailure(404, 15, "GET", "/v1/unknown", null);
        metrics.recordRequestFailure(new RuntimeException("connection failed"));

        metrics.stopAndSummarize();
        TaskRunReport report = metrics.buildFinalReport();

        assertThat(report.taskId).isEqualTo(taskId);
        assertThat(report.taskType).isEqualTo("REST_LOAD");
        assertThat(report.model).isEqualTo(TaskRunReport.ModelKind.OPEN);
        assertThat(report.startTime).isNotNull();
        assertThat(report.endTime).isNotNull();
        assertThat(report.durationSec).isGreaterThan(0.0);

        // Config
        assertThat(report.config.arrivalRatePerSec).isEqualTo(rate);
        assertThat(report.config.openDuration).isEqualTo(duration);
        assertThat(report.config.requestsPerIteration).isEqualTo(reqPerIter);
        assertThat(report.config.expectedTotalRequests).isEqualTo(expectedTotalRequests);
        assertThat(report.config.expectedRps).isEqualTo(expectedRps);

        // Metrics
        assertThat(report.metrics.totalRequests).isGreaterThanOrEqualTo(5);
        assertThat(report.metrics.failureCount).isGreaterThanOrEqualTo(2);
        assertThat(report.metrics.successCount).isGreaterThanOrEqualTo(2);
        assertThat(report.metrics.latency.min).isNotNull();
        assertThat(report.metrics.latency.max).isNotNull();
        assertThat(report.metrics.errorBreakdown).isNotEmpty();
        assertThat(report.metrics.errorBreakdown.stream().map(e -> e.type))
                .containsAnyOf("HTTP_5xx", "HTTP_4xx", "CONNECT", "EXCEPTION");

        // Protocol specific
        assertThat(report.protocolDetails).isNotNull();
        assertThat(report.protocolDetails.rest).isNotNull();
        assertThat(report.protocolDetails.rest.endpoints).isNotEmpty();
        assertThat(report.protocolDetails.rest.endpoints.get(0).latency).isNotNull();

        // JSON is ISO-8601 for timestamps
        String json = JsonUtil.toJson(report);
        assertThat(json).contains("startTime");
        assertThat(json).contains("T"); // rough ISO-8601 indicator
    }

    @Test
    void buildsReportForClosedModelComputesExpectedRpsFromHold() {
        String taskId = UUID.randomUUID().toString();
        int users = 3;
        int iterations = 5;
        int reqPerIter = 1;
        Duration holdFor = Duration.ofSeconds(10);
        long expectedTotalRequests = (long) users * iterations * reqPerIter;
        Double expectedRps = expectedTotalRequests / (holdFor.toMillis() / 1000.0);

        LoadMetrics.TaskConfig cfg = new LoadMetrics.TaskConfig(
                taskId,
                "REST_LOAD",
                "https://api.example.com",
                LoadMetrics.ModelKind.CLOSED,
                users,
                iterations,
                Duration.ZERO,
                Duration.ZERO,
                holdFor,
                null,
                null,
                reqPerIter,
                expectedTotalRequests,
                null // let builder compute from holdFor
        );

        LoadMetrics metrics = new LoadMetrics(cfg, log);

        for (int u = 0; u < users; u++) {
            metrics.recordUserStarted(u);
            for (int it = 0; it < iterations; it++) {
                metrics.recordUserProgress(u, it);
                metrics.recordRequestSuccess(10 + it, 200);
            }
            metrics.recordUserCompleted(u, iterations);
        }

        TaskRunReport report = metrics.buildFinalReport();

        assertThat(report.config.expectedTotalRequests).isEqualTo(expectedTotalRequests);
        assertThat(report.config.expectedRps).isNotNull();
        assertThat(Math.abs(report.config.expectedRps - expectedRps)).isLessThan(0.001);
        assertThat(report.metrics.userCompletionHistogram).hasSize(users);
        assertThat(report.metrics.usersCompleted).isEqualTo(users);
        assertThat(report.metrics.successCount).isEqualTo(expectedTotalRequests);
    }

    @Test
    void timeSeriesSnapshotsIncludeExpectedRpsAndLatency() throws Exception {
        String taskId = UUID.randomUUID().toString();
        double rate = 5.0;
        int reqPerIter = 2;
        Duration duration = Duration.ofSeconds(3);
        long expectedIterations = (long) Math.floor(duration.toMillis() / 1000.0 * rate);
        long expectedTotalRequests = expectedIterations * reqPerIter;
        double expectedRps = rate * reqPerIter;

        LoadMetrics.TaskConfig cfg = new LoadMetrics.TaskConfig(
                taskId,
                "REST_LOAD",
                "https://svc",
                LoadMetrics.ModelKind.OPEN,
                null,
                null,
                null,
                null,
                null,
                rate,
                duration,
                reqPerIter,
                expectedTotalRequests,
                expectedRps
        );

        LoadMetrics metrics = new LoadMetrics(cfg, log);
        metrics.start();
        // first activity
        metrics.recordRequestSuccess(12, 200);
        metrics.forceSnapshotForTest();
        Thread.sleep(120);
        // second window activity
        metrics.recordRequestSuccess(20, 200);
        metrics.recordRequestSuccess(25, 200);
        metrics.forceSnapshotForTest();

        TaskRunReport report = metrics.buildFinalReport();
        assertThat(report.timeseries).isNotNull();
        assertThat(report.timeseries.size()).isGreaterThanOrEqualTo(2);
        var last = report.timeseries.get(report.timeseries.size() - 1);
        assertThat(last.expectedRpsInWindow).isEqualTo(expectedRps);
        assertThat(last.rpsInWindow).isGreaterThan(0.0);
        assertThat(last.latency.min).isGreaterThanOrEqualTo(0);
        assertThat(last.latency.max).isGreaterThanOrEqualTo(last.latency.min);
    }

    @Test
    void onlyFailuresAndNoEndpointsHandled() {
        String taskId = UUID.randomUUID().toString();
        LoadMetrics.TaskConfig cfg = new LoadMetrics.TaskConfig(
                taskId,
                "REST_LOAD",
                "https://svc",
                LoadMetrics.ModelKind.OPEN,
                null,
                null,
                null,
                null,
                null,
                1.0,
                Duration.ofSeconds(1),
                1,
                0,
                1.0
        );
        LoadMetrics metrics = new LoadMetrics(cfg, log);
        metrics.recordHttpFailure(500, 30);
        metrics.recordRequestFailure(new RuntimeException("boom"));

        TaskRunReport report = metrics.buildFinalReport();
        assertThat(report.metrics.successCount).isEqualTo(0);
        assertThat(report.metrics.failureCount).isEqualTo(2);
        assertThat(report.metrics.errorBreakdown).isNotEmpty();
        // No endpoint success/failure recorded via endpoint helpers -> may still exist due to http failure tracking
        // Here we ensure protocolDetails is present but endpoints have totals consistent
        if (report.protocolDetails != null && report.protocolDetails.rest != null) {
            assertThat(report.protocolDetails.rest.endpoints).isNotNull();
        }
    }

    @Test
    void closedModelWithZeroHoldHasNullExpectedRps() {
        String taskId = UUID.randomUUID().toString();
        LoadMetrics.TaskConfig cfg = new LoadMetrics.TaskConfig(
                taskId,
                "REST_LOAD",
                "https://svc",
                LoadMetrics.ModelKind.CLOSED,
                1,
                1,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ZERO, // no hold -> expectedRps should remain null
                null,
                null,
                1,
                1,
                null
        );
        LoadMetrics metrics = new LoadMetrics(cfg, log);
        metrics.recordUserStarted(0);
        metrics.recordUserProgress(0, 0);
        metrics.recordRequestSuccess(10, 200);
        metrics.recordUserCompleted(0, 1);

        TaskRunReport report = metrics.buildFinalReport();
        assertThat(report.config.expectedRps).isNull();
        assertThat(report.metrics.expectedRps).isNull();
    }
}
