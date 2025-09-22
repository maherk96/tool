package com.example.springload.service.processor.metrics;

import com.example.springload.dto.TaskRunReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RestProtocolMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(RestProtocolMetricsTest.class);

    @Test
    void endpointStatsIncludeLatencyAndStatusBreakdown() {
        // Arrange generic metrics and rest provider
        LoadMetrics.TaskConfig cfg = new LoadMetrics.TaskConfig(
                "task-1", "REST_LOAD", "https://svc", LoadMetrics.ModelKind.OPEN,
                null, null, null, null, null, 5.0, java.time.Duration.ofSeconds(5), 1, 5, 5.0
        );
        LoadMetrics metrics = new LoadMetrics(cfg, log);
        RestProtocolMetrics rest = new RestProtocolMetrics();
        metrics.registerProtocolMetrics(rest);

        // Act: successes produce family buckets (2xx), failures produce HTTP_4xx/5xx categories
        rest.recordSuccess("GET", "/a", 15, 200);
        metrics.recordRequestSuccess(15);
        rest.recordSuccess("GET", "/a", 25, 204);
        metrics.recordRequestSuccess(25);

        String cat4 = rest.recordHttpFailure(404, "GET", "/a");
        metrics.recordFailure(cat4, 30);
        String cat5 = rest.recordHttpFailure(500, "GET", "/b");
        metrics.recordFailure(cat5, 40);

        TaskRunReport report = metrics.buildFinalReport();

        assertThat(report.protocolDetails).isNotNull();
        assertThat(report.protocolDetails.rest).isNotNull();
        assertThat(report.protocolDetails.rest.endpoints).isNotEmpty();

        TaskRunReport.RestEndpoint epA = report.protocolDetails.rest.endpoints.stream()
                .filter(e -> "GET".equals(e.method) && "/a".equals(e.path))
                .findFirst().orElseThrow();
        assertThat(epA.total).isEqualTo(epA.success + epA.failure);
        assertThat(epA.latency).isNotNull();
        assertThat(epA.latency.min).isNotNull();
        assertThat(epA.statusBreakdown).containsKeys("2xx", "HTTP_4xx");

        TaskRunReport.RestEndpoint epB = report.protocolDetails.rest.endpoints.stream()
                .filter(e -> "/b".equals(e.path))
                .findFirst().orElseThrow();
        assertThat(epB.failure).isGreaterThanOrEqualTo(1);
        assertThat(epB.statusBreakdown).containsKey("HTTP_5xx");
    }

    @Test
    void connectionFailureDoesNotCreateEndpointReport() {
        LoadMetrics.TaskConfig cfg = new LoadMetrics.TaskConfig(
                "task-2", "REST_LOAD", "https://svc", LoadMetrics.ModelKind.OPEN,
                null, null, null, null, null, 1.0, java.time.Duration.ofSeconds(1), 1, 1, 1.0
        );
        LoadMetrics metrics = new LoadMetrics(cfg, log);
        RestProtocolMetrics rest = new RestProtocolMetrics();
        metrics.registerProtocolMetrics(rest);

        // Simulate a connection failure before any HTTP response
        metrics.recordRequestFailure(new java.net.ConnectException("connection refused"));

        TaskRunReport report = metrics.buildFinalReport();
        assertThat(report.protocolDetails).isNull();
    }
}
