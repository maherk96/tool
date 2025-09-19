package com.example.springload.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class TaskRunReport {
    public enum ModelKind { OPEN, CLOSED }

    // 1. General Task Metadata
    public String taskId;
    public String taskType;
    public ModelKind model;
    public Instant startTime;
    public Instant endTime;
    public Duration duration;
    public EnvInfo environment;
    public Duration warmup;
    public Duration rampUp;
    public Duration holdFor;
    public int usersStarted;
    public int usersCompleted;

    // 2. Load Configuration Summary
    public Integer users; // closed
    public Integer iterationsPerUser; // closed
    public Double arrivalRatePerSec; // open
    public Duration openDuration; // open
    public int requestsPerIteration;
    public long expectedTotalRequests;
    public Double expectedRps;

    // 3. Execution Metrics (overall)
    public long totalRequests;
    public long totalSucceeded;
    public long totalFailed;
    public double successRate;
    public double achievedRps;
    public Long latencyAvgMs;
    public Long latencyMinMs;
    public Long latencyMaxMs;
    public Long latencyP95Ms;
    public Long latencyP99Ms;
    public Map<String, Long> errorBreakdown;

    // User-level stats
    public double avgIterationsPerUser;
    public Long minUserCompletionMillis;
    public Long maxUserCompletionMillis;
    public List<Integer> usersNotCompleted;

    // 4. Time-Series Data
    public List<TimeWindow> timeseries;

    // 5. Protocol-Specific Data (REST only for now)
    public List<RestEndpointStats> restEndpoints;

    public static class EnvInfo {
        public String branch;
        public String commit;
        public String host;
        public String triggeredBy;
    }

    public static class TimeWindow {
        public Instant timestamp;
        public long usersCompleted;
        public long totalRequests;
        public double rpsInWindow;
        public long latMinMs;
        public long latAvgMs;
        public long latMaxMs;
        public long errorsInWindow;
    }

    public static class RestEndpointStats {
        public String method;
        public String path;
        public long total;
        public long success;
        public long failure;
        public Long avgMs;
        public Long p95Ms;
        public Long p99Ms;
        public Map<String, Long> statusBreakdown; // 2xx, 4xx, 5xx or specific codes
    }
}

