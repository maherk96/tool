package com.example.springload.service.processor.metrics;

import com.example.springload.dto.TaskRunReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures current behavior of window latency metrics: they reflect global aggregates
 * (min/avg/max) at snapshot time, not per-window values. This test documents the issue
 * where a high latency in an early window propagates into later windows.
 */
class LoadMetricsWindowLatencyTest {

    private static final Logger log = LoggerFactory.getLogger(LoadMetricsWindowLatencyTest.class);

    @Test
    void windowLatencyDoesNotPropagate_afterFix() {
        LoadMetrics metrics = new LoadMetrics(new LoadMetrics.TaskConfig(
                "t-win", "REST_LOAD", "", LoadMetrics.ModelKind.OPEN,
                null, null, null, null, null,
                10.0, Duration.ofSeconds(5), 1, 50, 10.0
        ), log);

        // Window 1: record a single high latency
        metrics.recordRequestSuccess(1000);
        metrics.forceSnapshotForTest();

        // Window 2: record only low latencies
        metrics.recordRequestSuccess(10);
        metrics.recordRequestSuccess(15);
        metrics.forceSnapshotForTest();

        TaskRunReport r = metrics.buildFinalReport();
        assertThat(r.timeseries.size()).isGreaterThanOrEqualTo(2);

        var first = r.timeseries.get(0);
        var second = r.timeseries.get(1);

        // First window max should be the high latency
        assertThat(first.latency.max).isGreaterThanOrEqualTo(1000);

        // After fix: second window reflects only its own latencies (10, 15),
        // so max should be small and not equal to the earlier 1000ms.
        assertThat(second.latency.max).isLessThan(1000);
        assertThat(second.latency.max).isGreaterThanOrEqualTo(15);
        assertThat(second.latency.min).isEqualTo(10);
        assertThat(second.latency.avg).isBetween(10L, 15L);
    }
}
