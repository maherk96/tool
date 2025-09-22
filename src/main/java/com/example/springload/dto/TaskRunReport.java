package com.example.springload.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class TaskRunReport {
    public enum ModelKind { OPEN, CLOSED }

    // Core identifiers and timing
    public String taskId;
    public String taskType;
    public ModelKind model;
    public Instant startTime; // ISO-8601 UTC
    public Instant endTime;   // ISO-8601 UTC
    public double durationSec; // numeric seconds for easy calc

    // Environment
    public EnvInfo environment;

    // Configuration vs Metrics
    public Config config;
    public Metrics metrics;

    // Time-series snapshots and protocol specific details
    public List<TimeSeriesEntry> timeseries;
    public ProtocolDetails protocolDetails;

    // --- Nested Types ---
    public static class EnvInfo {
        public String branch;
        public String commit;
        public String host;
        public String triggeredBy;
    }

    public static class Config {
        public Integer users; // closed
        public Integer iterationsPerUser; // closed
        public Integer requestsPerIteration;
        public Duration warmup;
        public Duration rampUp;
        public Duration holdFor;
        public Double arrivalRatePerSec; // open
        public Duration openDuration;     // open
        public long expectedTotalRequests;
        public Double expectedRps; // target
    }

    public static class Metrics {
        public long totalRequests;
        public long successCount;
        public long failureCount;
        public double successRate;
        public double achievedRps;
        public Latency latency;
        public List<ErrorItem> errorBreakdown;
        public List<ErrorSample> errorSamples; // optional: recent sampled exceptions
        public List<UserCompletion> userCompletionHistogram; // optional
        public int usersStarted;
        public int usersCompleted;
        public Double expectedRps; // mirror target for convenience
    }

    public static class Latency {
        public Long min;
        public Long avg;
        public Long max;
        public Long p95;
        public Long p99;
    }

    public static class ErrorItem {
        public String type;
        public long count;
    }

    public static class ErrorSample {
        public String type;    // category: TIMEOUT, CONNECT, EXCEPTION, HTTP_4xx, etc.
        public String message; // exception message if available
        public List<String> stack; // top N frames formatted
    }

    public static class UserCompletion {
        public int userId;
        public long completionTimeMs;
        public int iterationsCompleted;
    }

    public static class TimeSeriesEntry {
        public Instant timestamp; // ISO-8601
        public int usersActive;
        public int usersCompleted;
        public long totalRequestsSoFar;
        public double rpsInWindow;
        public Double expectedRpsInWindow;
        public LatencyWindow latency;
        public long errorsInWindow;
    }

    public static class LatencyWindow {
        public long min;
        public long avg;
        public long max;
    }

    public static class ProtocolDetails {
        public RestDetails rest;
        // future: grpc, fix, mq
    }

    public static class RestDetails {
        public List<RestEndpoint> endpoints;
    }

    public static class RestEndpoint {
        public String method;
        public String path;
        public long total;
        public long success;
        public long failure;
        public Latency latency;
        public Map<String, Long> statusBreakdown; // 2xx, 4xx, 5xx or specific codes
    }
}
