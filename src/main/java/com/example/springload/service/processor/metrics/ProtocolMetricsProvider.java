package com.example.springload.service.processor.metrics;

import com.example.springload.dto.TaskRunReport;

/**
 * Pluggable protocol-specific metrics provider. Implementations collect
 * protocol data during a run and contribute to the final TaskRunReport.
 */
public interface ProtocolMetricsProvider {
    void applyTo(TaskRunReport report);
}

