package com.example.springload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@Validated
@ConfigurationProperties(prefix = "load.tasks")
public class TaskProcessingProperties {

    @Min(1)
    @Max(64)
    private int concurrency = 1;

    @Positive
    private int historySize = 50;

    @Min(0)
    private long simulatedDurationMs = 1_000L;

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getHistorySize() {
        return historySize;
    }

    public void setHistorySize(int historySize) {
        this.historySize = historySize;
    }

    public long getSimulatedDurationMs() {
        return simulatedDurationMs;
    }

    public void setSimulatedDurationMs(long simulatedDurationMs) {
        this.simulatedDurationMs = simulatedDurationMs;
    }
}
