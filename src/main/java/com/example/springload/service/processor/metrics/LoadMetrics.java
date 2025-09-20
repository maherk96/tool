package com.example.springload.service.processor.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import com.example.springload.dto.TaskRunReport;
import com.example.springload.service.processor.metrics.EnvironmentInfo;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

/**
 * Generic load metrics and logging helper used by protocol processors.
 * Tracks per-request latency, request counts, per-user iteration progress, and emits
 * periodic snapshots (every 5 seconds) and a final summary.
 */
public class LoadMetrics {

    public enum ModelKind { OPEN, CLOSED }

    public record TaskConfig(
            String taskId,
            String taskType,
            String baseUrl,
            ModelKind model,
            Integer users,
            Integer iterationsPerUser,
            Duration warmup,
            Duration rampUp,
            Duration holdFor,
            Double arrivalRatePerSec,
            Duration duration,
            int requestsPerIteration,
            long expectedTotalRequests,
            Double expectedRps
    ) {}

    private final TaskConfig config;
    private final Logger log;

    private final Instant startedAt = Instant.now();
    private final AtomicInteger usersStarted = new AtomicInteger();
    private final AtomicInteger usersCompleted = new AtomicInteger();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong latencyMin = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong latencyMax = new AtomicLong(0);
    private final AtomicLong latencySum = new AtomicLong(0);
    private final Reservoir latencyReservoir = new Reservoir(5000);

    private final Map<String, AtomicLong> errorBreakdown = new ConcurrentHashMap<>();
    private final java.util.List<ProtocolMetricsProvider> protocolProviders = new java.util.concurrent.CopyOnWriteArrayList<>();

    // user index -> current iteration (0-based)
    private final Map<Integer, Integer> userIterations = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> userStartTimes = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> userEndTimes = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> userIterationsCompleted = new ConcurrentHashMap<>();

    private ScheduledExecutorService snapshots;
    private final java.util.List<TimeSeriesPoint> timeSeries = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Instant lastSnapshotAt = startedAt;

    public LoadMetrics(TaskConfig config, Logger log) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
    }

    // region lifecycle
    public void start() {
        logStart();
        snapshots = newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("metrics-snapshots-" + config.taskId());
            t.setDaemon(true);
            return t;
        });
        snapshots.scheduleAtFixedRate(this::snapshot, 5, 5, TimeUnit.SECONDS);
    }

    public void stopAndSummarize() {
        if (snapshots != null) {
            snapshots.shutdownNow();
            try {
                snapshots.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        logFinalSummary();
    }
    // endregion

    // region recorders
    public void recordUserStarted(int userIndex) {
        userIterations.putIfAbsent(userIndex, 0);
        usersStarted.incrementAndGet();
        userStartTimes.put(userIndex, Instant.now());
        userIterationsCompleted.putIfAbsent(userIndex, new AtomicInteger());
        log.info("Task {} user {} started (usersStarted={})", config.taskId(), userIndex + 1, usersStarted.get());
    }

    public void recordUserProgress(int userIndex, int iteration) {
        userIterations.put(userIndex, iteration);
        userIterationsCompleted.computeIfAbsent(userIndex, k -> new AtomicInteger()).set(iteration + 1);
    }

    public void recordUserCompleted(int userIndex, int totalIterationsCompleted) {
        usersCompleted.incrementAndGet();
        userIterations.remove(userIndex);
        userEndTimes.put(userIndex, Instant.now());
        userIterationsCompleted.computeIfAbsent(userIndex, k -> new AtomicInteger()).set(totalIterationsCompleted);
        log.info("Task {} user {} completed {} iterations (usersCompleted={})",
                config.taskId(), userIndex + 1, totalIterationsCompleted, usersCompleted.get());
    }

    public void recordRequestSuccess(long latencyMs, int statusCode) {
        requests.incrementAndGet();
        latencySum.addAndGet(Math.max(0, latencyMs));
        updateMinMax(latencyMs);
        latencyReservoir.add(latencyMs);
    }

    public void recordRequestFailure(Throwable t) {
        errors.incrementAndGet();
        requests.incrementAndGet();
        String key = classifyError(t);
        errorBreakdown.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public void registerProtocolMetrics(ProtocolMetricsProvider provider) {
        protocolProviders.add(provider);
    }

    public void recordHttpFailure(int statusCode, long latencyMs) {
        errors.incrementAndGet();
        requests.incrementAndGet();
        latencySum.addAndGet(Math.max(0, latencyMs));
        updateMinMax(latencyMs);
        latencyReservoir.add(latencyMs);
        String category = httpCategory(statusCode);
        errorBreakdown.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
    }
    // endregion

    // region accessors
    public long totalRequests() { return requests.get(); }
    public long totalErrors() { return errors.get(); }
    public int totalUsersStarted() { return usersStarted.get(); }
    public int totalUsersCompleted() { return usersCompleted.get(); }
    public Optional<Long> latencyAvgMs() {
        long count = requests.get();
        if (count == 0) return Optional.empty();
        return Optional.of(latencySum.get() / Math.max(1, count));
    }
    public Optional<Long> latencyP95Ms() {
        return latencyReservoir.percentile(95);
    }
    public Optional<Long> latencyP99Ms() {
        return latencyReservoir.percentile(99);
    }
    public Optional<Long> latencyMinMs() {
        long min = latencyMin.get();
        return min == Long.MAX_VALUE ? Optional.empty() : Optional.of(min);
    }
    public Optional<Long> latencyMaxMs() {
        long max = latencyMax.get();
        return max == 0 ? Optional.empty() : Optional.of(max);
    }
    public Map<Integer, Integer> currentUserIterations() { return Collections.unmodifiableMap(userIterations); }
    // endregion

    public record LoadSnapshot(
            TaskConfig config,
            int usersStarted,
            int usersCompleted,
            long totalRequests,
            long totalErrors,
            Double achievedRps,
            Long latencyMinMs,
            Long latencyAvgMs,
            Long latencyMaxMs,
            Map<Integer, Integer> activeUserIterations
    ) {}

    public TaskConfig getConfig() { return config; }

    public LoadSnapshot snapshotNow() {
        return new LoadSnapshot(
                config,
                usersStarted.get(),
                usersCompleted.get(),
                requests.get(),
                errors.get(),
                achievedRps(),
                latencyMinMs().orElse(null),
                latencyAvgMs().orElse(null),
                latencyMaxMs().orElse(null),
                Map.copyOf(userIterations)
        );
    }

    private void updateMinMax(long latencyMs) {
        long v = Math.max(0, latencyMs);
        latencyMax.accumulateAndGet(v, Math::max);
        latencyMin.accumulateAndGet(v, Math::min);
    }

    private double achievedRps() {
        double elapsedSec = Math.max(0.001, Duration.between(startedAt, Instant.now()).toMillis() / 1000.0);
        return requests.get() / elapsedSec;
    }

    private void logStart() {
        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(config.taskId()).append(" started: ")
          .append("type=").append(config.taskType())
          .append(", model=").append(config.model())
          .append(", baseUrl=").append(config.baseUrl());
        if (config.model() == ModelKind.OPEN) {
            sb.append(", rate=").append(config.arrivalRatePerSec())
              .append("/s, duration=").append(config.duration());
        } else {
            sb.append(", users=").append(config.users())
              .append(", iterationsPerUser=").append(config.iterationsPerUser())
              .append(", warmup=").append(config.warmup())
              .append(", rampUp=").append(config.rampUp())
              .append(", holdFor=").append(config.holdFor());
        }
        sb.append(", requestsPerIteration=").append(config.requestsPerIteration())
          .append(", expectedTotalRequests=").append(config.expectedTotalRequests());
        if (config.expectedRps() != null) {
            sb.append(", expectedRps=").append(String.format("%.2f", config.expectedRps()));
        }
        log.info(sb.toString());
    }

    private void snapshot() {
        double rps = achievedRps();
        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(config.taskId()).append(" snapshot: ")
          .append("usersStarted=").append(totalUsersStarted())
          .append(", usersCompleted=").append(totalUsersCompleted())
          .append(", requests=").append(totalRequests()).append("/").append(config.expectedTotalRequests());
        if (config.expectedRps() != null) {
            sb.append(", rps actual/expected=")
              .append(String.format("%.2f", rps)).append("/")
              .append(String.format("%.2f", config.expectedRps()));
        } else {
            sb.append(", rps actual=").append(String.format("%.2f", rps));
        }
        latencyMinMs().ifPresent(min -> sb.append(", lat(ms) min=").append(min));
        latencyAvgMs().ifPresent(avg -> sb.append(", avg=").append(avg));
        latencyMaxMs().ifPresent(max -> sb.append(", max=").append(max));
        latencyP95Ms().ifPresent(p95 -> sb.append(", p95=").append(p95));
        latencyP99Ms().ifPresent(p99 -> sb.append(", p99=").append(p99));

        // which users running and their iteration
        if (!userIterations.isEmpty()) {
            sb.append(", activeUsers=");
            // print up to first 10 to avoid noisy logs
            int shown = 0;
            for (Map.Entry<Integer, Integer> e : userIterations.entrySet()) {
                if (shown++ >= 10) { sb.append(" …"); break; }
                sb.append("[#").append(e.getKey() + 1).append(":iter=").append(e.getValue()).append("]");
            }
        }
        log.info(sb.toString());

        // store time-series point
        Instant now = Instant.now();
        long req = requests.get();
        long err = errors.get();
        long min = latencyMin.get();
        long max = latencyMax.get();
        long avg = latencyAvgMs().orElse(0L);
        timeSeries.add(new TimeSeriesPoint(now,
                req,
                err,
                min == Long.MAX_VALUE ? 0 : min,
                max,
                avg,
                usersStarted.get(),
                usersCompleted.get()));
        lastSnapshotAt = now;
    }

    // Test helper to trigger a snapshot without waiting for the scheduler
    public void forceSnapshotForTest() {
        snapshot();
    }

    private void logFinalSummary() {
        double rps = achievedRps();
        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(config.taskId()).append(" summary: ")
          .append("type=").append(config.taskType())
          .append(", model=").append(config.model())
          .append(", usersStarted=").append(totalUsersStarted())
          .append(", usersCompleted=").append(totalUsersCompleted())
          .append(", totalRequests=").append(totalRequests())
          .append(", expectedTotalRequests=").append(config.expectedTotalRequests());
        if (config.expectedRps() != null) {
            sb.append(", rps actual/expected=")
              .append(String.format("%.2f", rps)).append("/")
              .append(String.format("%.2f", config.expectedRps()));
        } else {
            sb.append(", rps actual=").append(String.format("%.2f", rps));
        }
        latencyMinMs().ifPresent(min -> sb.append(", lat(ms) min=").append(min));
        latencyAvgMs().ifPresent(avg -> sb.append(", avg=").append(avg));
        latencyMaxMs().ifPresent(max -> sb.append(", max=").append(max));
        latencyP95Ms().ifPresent(p95 -> sb.append(", p95=").append(p95));
        latencyP99Ms().ifPresent(p99 -> sb.append(", p99=").append(p99));
        if (totalErrors() > 0) {
            sb.append(", errors=").append(totalErrors());
        }
        log.info(sb.toString());
    }

    public TaskRunReport buildFinalReport() {
        TaskRunReport r = new TaskRunReport();
        // identifiers & timing
        r.taskId = config.taskId();
        r.taskType = config.taskType();
        r.model = TaskRunReport.ModelKind.valueOf(config.model().name());
        r.startTime = startedAt; // ISO-8601 via Jackson
        Instant end = Instant.now();
        r.endTime = end;
        r.durationSec = Math.max(0.0, Duration.between(startedAt, end).toMillis() / 1000.0);

        // environment
        TaskRunReport.EnvInfo env = new TaskRunReport.EnvInfo();
        env.branch = EnvironmentInfo.branch();
        env.commit = EnvironmentInfo.commit();
        env.host = EnvironmentInfo.host();
        env.triggeredBy = EnvironmentInfo.triggeredBy();
        r.environment = env;

        // config
        TaskRunReport.Config cfg = new TaskRunReport.Config();
        cfg.users = config.users();
        cfg.iterationsPerUser = config.iterationsPerUser();
        cfg.requestsPerIteration = config.requestsPerIteration();
        cfg.warmup = config.warmup();
        cfg.rampUp = config.rampUp();
        cfg.holdFor = config.holdFor();
        cfg.arrivalRatePerSec = config.arrivalRatePerSec();
        cfg.openDuration = config.duration();
        cfg.expectedTotalRequests = config.expectedTotalRequests();
        Double expectedRps = config.expectedRps();
        if (expectedRps == null && config.model() == ModelKind.CLOSED && config.holdFor() != null && !config.holdFor().isZero()) {
            double holdSec = Math.max(0.001, config.holdFor().toMillis() / 1000.0);
            expectedRps = cfg.expectedTotalRequests / holdSec;
        }
        cfg.expectedRps = expectedRps;
        r.config = cfg;

        // metrics aggregate
        TaskRunReport.Metrics m = new TaskRunReport.Metrics();
        m.totalRequests = totalRequests();
        m.failureCount = totalErrors();
        m.successCount = Math.max(0, m.totalRequests - m.failureCount);
        m.successRate = m.totalRequests == 0 ? 0.0 : (double) m.successCount / m.totalRequests;
        m.achievedRps = achievedRps();
        TaskRunReport.Latency lat = new TaskRunReport.Latency();
        lat.avg = latencyAvgMs().orElse(null);
        lat.min = latencyMinMs().orElse(null);
        lat.max = latencyMaxMs().orElse(null);
        lat.p95 = latencyP95Ms().orElse(null);
        lat.p99 = latencyP99Ms().orElse(null);
        m.latency = lat;
        // error breakdown array
        java.util.List<TaskRunReport.ErrorItem> errs = new java.util.ArrayList<>();
        for (var e : errorBreakdown().entrySet()) {
            TaskRunReport.ErrorItem item = new TaskRunReport.ErrorItem();
            item.type = e.getKey();
            item.count = e.getValue();
            errs.add(item);
        }
        m.errorBreakdown = java.util.List.copyOf(errs);
        // user histogram
        java.util.List<TaskRunReport.UserCompletion> histogram = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Instant> e : userEndTimes.entrySet()) {
            Instant start = userStartTimes.get(e.getKey());
            if (start != null) {
                TaskRunReport.UserCompletion uc = new TaskRunReport.UserCompletion();
                uc.userId = e.getKey() + 1;
                uc.completionTimeMs = Duration.between(start, e.getValue()).toMillis();
                uc.iterationsCompleted = userIterationsCompleted.getOrDefault(e.getKey(), new AtomicInteger(0)).get();
                histogram.add(uc);
            }
        }
        m.userCompletionHistogram = java.util.List.copyOf(histogram);
        m.usersStarted = totalUsersStarted();
        m.usersCompleted = totalUsersCompleted();
        m.expectedRps = cfg.expectedRps;
        r.metrics = m;

        // timeseries
        java.util.List<TaskRunReport.TimeSeriesEntry> windows = new java.util.ArrayList<>();
        long prevReq = 0;
        long prevErr = 0;
        Instant prevTs = startedAt;
        for (TimeSeriesPoint p : timeSeries) {
            TaskRunReport.TimeSeriesEntry w = new TaskRunReport.TimeSeriesEntry();
            w.timestamp = p.timestamp();
            w.usersCompleted = p.usersCompleted();
            int usersActive = Math.max(0, p.usersStarted() - p.usersCompleted());
            w.usersActive = usersActive;
            long deltaReq = p.totalRequests() - prevReq;
            long deltaErr = p.totalErrors() - prevErr;
            double secs = Math.max(0.001, Duration.between(prevTs, p.timestamp()).toMillis() / 1000.0);
            w.rpsInWindow = deltaReq / secs;
            w.expectedRpsInWindow = cfg.expectedRps; // constant target for now
            w.totalRequestsSoFar = p.totalRequests();
            w.errorsInWindow = deltaErr;
            TaskRunReport.LatencyWindow lw = new TaskRunReport.LatencyWindow();
            lw.min = p.latMinMs();
            lw.avg = p.latAvgMs();
            lw.max = p.latMaxMs();
            w.latency = lw;
            windows.add(w);
            prevReq = p.totalRequests();
            prevErr = p.totalErrors();
            prevTs = p.timestamp();
        }
        r.timeseries = java.util.List.copyOf(windows);

        // protocol-specific providers contribute
        for (ProtocolMetricsProvider provider : protocolProviders) {
            provider.applyTo(r);
        }
        return r;
    }

    private String classifyError(Throwable t) {
        if (t == null) return "UNKNOWN";
        String cls = t.getClass().getSimpleName();
        String msg = String.valueOf(t.getMessage()).toLowerCase();
        if (cls.contains("Timeout") || msg.contains("timed out") || msg.contains("timeout")) return "TIMEOUT";
        if (msg.contains("connection") || msg.contains("connect")) return "CONNECT";
        return "EXCEPTION";
    }

    private String httpCategory(int statusCode) {
        if (statusCode >= 500) return "HTTP_5xx";
        if (statusCode >= 400) return "HTTP_4xx";
        if (statusCode >= 300) return "HTTP_3xx";
        return "HTTP_" + statusCode;
    }

    public Map<String, Long> errorBreakdown() {
        Map<String, Long> map = new java.util.HashMap<>();
        for (var e : errorBreakdown.entrySet()) map.put(e.getKey(), e.getValue().get());
        return java.util.Map.copyOf(map);
    }

    public java.util.List<TimeSeriesPoint> getTimeSeries() { return java.util.List.copyOf(timeSeries); }

    public record TimeSeriesPoint(Instant timestamp,
                                  long totalRequests,
                                  long totalErrors,
                                  long latMinMs,
                                  long latMaxMs,
                                  long latAvgMs,
                                  int usersStarted,
                                  int usersCompleted) {}

    // Simple thread-safe reservoir sampling for percentiles
    public static final class Reservoir {
        private final int capacity;
        private final java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();
        private final long[] data;
        private final java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();

        Reservoir(int capacity) { this.capacity = capacity; this.data = new long[capacity]; }

        void add(long value) {
            long c = count.incrementAndGet();
            if (c <= capacity) {
                data[(int) c - 1] = value;
            } else {
                long j = rnd.nextLong(c);
                if (j < capacity) {
                    data[(int) j] = value;
                }
            }
        }

        Optional<Long> percentile(int p) {
            long c = Math.min(count.get(), capacity);
            if (c == 0) return Optional.empty();
            long[] copy = java.util.Arrays.copyOf(data, (int) c);
            java.util.Arrays.sort(copy);
            int idx = Math.min((int) c - 1, Math.max(0, (int) Math.ceil((p / 100.0) * c) - 1));
            return Optional.of(copy[idx]);
        }
    }
}
