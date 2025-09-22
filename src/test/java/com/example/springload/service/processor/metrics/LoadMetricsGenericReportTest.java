package com.example.springload.service.processor.metrics;

import com.example.springload.dto.TaskRunReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoadMetricsGenericReportTest {
    private static final Logger log = LoggerFactory.getLogger(LoadMetricsGenericReportTest.class);

    @Test
    void protocolDetailsNullWhenNoProviders() {
        LoadMetrics metrics = new LoadMetrics(new LoadMetrics.TaskConfig(
                "task-x", "REST_LOAD", "", LoadMetrics.ModelKind.OPEN,
                null, null, null, null, null,
                1.0, Duration.ofSeconds(1), 1, 1, 1.0
        ), log);
        metrics.recordRequestSuccess(5);
        TaskRunReport report = metrics.buildFinalReport();
        assertThat(report.protocolDetails).isNull();
    }

    @Test
    void errorCategorizationForExceptions() {
        LoadMetrics metrics = new LoadMetrics(new LoadMetrics.TaskConfig(
                "task-y", "REST_LOAD", "", LoadMetrics.ModelKind.OPEN,
                null, null, null, null, null,
                1.0, Duration.ofSeconds(1), 1, 1, 1.0
        ), log);

        metrics.recordRequestFailure(new SocketTimeoutException("read timed out"));
        metrics.recordRequestFailure(new ConnectException("connection refused"));
        metrics.recordRequestFailure(new RuntimeException("boom"));

        TaskRunReport r = metrics.buildFinalReport();
        Map<String, Long> breakdown = r.metrics.errorBreakdown.stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.type, e -> e.count));

        assertThat(breakdown.keySet()).contains("TIMEOUT", "CONNECT", "RuntimeException");
        assertThat(breakdown.get("TIMEOUT")).isGreaterThanOrEqualTo(1L);
        assertThat(breakdown.get("CONNECT")).isGreaterThanOrEqualTo(1L);
        assertThat(breakdown.get("RuntimeException")).isGreaterThanOrEqualTo(1L);

        // error samples should be present and bounded
        assertThat(r.metrics.errorSamples).isNotNull();
        assertThat(r.metrics.errorSamples.size()).isBetween(1, 5);
        assertThat(r.metrics.errorSamples.get(0).type).isNotBlank();
        assertThat(r.metrics.errorSamples.get(0).stack).isNotEmpty();
    }

    @Test
    void successOnlyAggregatesLatency() {
        LoadMetrics metrics = new LoadMetrics(new LoadMetrics.TaskConfig(
                "task-z", "REST_LOAD", "", LoadMetrics.ModelKind.OPEN,
                null, null, null, null, null,
                1.0, Duration.ofSeconds(1), 1, 1, 1.0
        ), log);
        metrics.recordRequestSuccess(10);
        metrics.recordRequestSuccess(20);
        metrics.recordRequestSuccess(30);

        TaskRunReport r = metrics.buildFinalReport();
        assertThat(r.metrics.failureCount).isZero();
        assertThat(r.metrics.successCount).isEqualTo(3);
        assertThat(r.metrics.latency.min).isEqualTo(10);
        assertThat(r.metrics.latency.max).isEqualTo(30);
        assertThat(r.metrics.latency.avg).isEqualTo((10 + 20 + 30) / 3);
    }
}
