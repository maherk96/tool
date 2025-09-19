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
    private final Map<String, EndpointStats> endpointStats = new ConcurrentHashMap<>();

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

    public void recordEndpointSuccess(String method, String path, long latencyMs, int statusCode) {
        String key = endpointKey(method, path);
        EndpointStats s = endpointStats.computeIfAbsent(key, k -> new EndpointStats(method, path));
        s.onSuccess(latencyMs, statusCode);
    }

    public void recordEndpointFailure(String method, String path, String category) {
        String key = endpointKey(method, path);
        EndpointStats s = endpointStats.computeIfAbsent(key, k -> new EndpointStats(method, path));
        s.onFailure(category);
    }

    public void recordHttpFailure(int statusCode, long latencyMs, String method, String path) {
        errors.incrementAndGet();
        requests.incrementAndGet();
        latencySum.addAndGet(Math.max(0, latencyMs));
        updateMinMax(latencyMs);
        latencyReservoir.add(latencyMs);
        String category = httpCategory(statusCode);
        errorBreakdown.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
        recordEndpointFailure(method, path, category);
    }

    private String endpointKey(String method, String path) {
        return (method == null ? "" : method) + " " + (path == null ? "" : path);
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
        timeSeries.add(new TimeSeriesPoint(now, req, err, min == Long.MAX_VALUE ? 0 : min, max, avg));
        lastSnapshotAt = now;
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
        // metadata
        r.taskId = config.taskId();
        r.taskType = config.taskType();
        r.model = TaskRunReport.ModelKind.valueOf(config.model().name());
        r.startTime = startedAt;
        Instant end = Instant.now();
        r.endTime = end;
        r.duration = Duration.between(startedAt, end);
        TaskRunReport.EnvInfo env = new TaskRunReport.EnvInfo();
        env.branch = EnvironmentInfo.branch();
        env.commit = EnvironmentInfo.commit();
        env.host = EnvironmentInfo.host();
        env.triggeredBy = EnvironmentInfo.triggeredBy();
        r.environment = env;
        r.warmup = config.warmup();
        r.rampUp = config.rampUp();
        r.holdFor = config.holdFor();
        r.usersStarted = totalUsersStarted();
        r.usersCompleted = totalUsersCompleted();

        // load config
        r.users = config.users();
        r.iterationsPerUser = config.iterationsPerUser();
        r.arrivalRatePerSec = config.arrivalRatePerSec();
        r.openDuration = config.duration();
        r.requestsPerIteration = config.requestsPerIteration();
        r.expectedTotalRequests = config.expectedTotalRequests();
        r.expectedRps = config.expectedRps();

        // execution metrics
        r.totalRequests = totalRequests();
        r.totalFailed = totalErrors();
        r.totalSucceeded = Math.max(0, r.totalRequests - r.totalFailed);
        long processedForSuccessRate = r.totalRequests;
        r.successRate = processedForSuccessRate == 0 ? 0.0 : (double) r.totalSucceeded / processedForSuccessRate;
        r.achievedRps = achievedRps();
        r.latencyAvgMs = latencyAvgMs().orElse(null);
        r.latencyMinMs = latencyMinMs().orElse(null);
        r.latencyMaxMs = latencyMaxMs().orElse(null);
        r.latencyP95Ms = latencyP95Ms().orElse(null);
        r.latencyP99Ms = latencyP99Ms().orElse(null);
        r.errorBreakdown = errorBreakdown();

        // user-level stats
        int usersCount = Math.max(1, usersStarted.get());
        int sumIters = userIterationsCompleted.values().stream().mapToInt(AtomicInteger::get).sum();
        r.avgIterationsPerUser = usersCount == 0 ? 0.0 : (double) sumIters / usersCount;
        java.util.List<Long> completionTimes = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Instant> e : userEndTimes.entrySet()) {
            Instant start = userStartTimes.get(e.getKey());
            if (start != null) {
                completionTimes.add(Duration.between(start, e.getValue()).toMillis());
            }
        }
        r.minUserCompletionMillis = completionTimes.stream().mapToLong(Long::longValue).min().isPresent() ?
                completionTimes.stream().mapToLong(Long::longValue).min().getAsLong() : null;
        r.maxUserCompletionMillis = completionTimes.stream().mapToLong(Long::longValue).max().isPresent() ?
                completionTimes.stream().mapToLong(Long::longValue).max().getAsLong() : null;
        java.util.List<Integer> notCompleted = new java.util.ArrayList<>();
        for (Integer idx : userStartTimes.keySet()) {
            if (!userEndTimes.containsKey(idx)) notCompleted.add(idx + 1);
        }
        r.usersNotCompleted = java.util.List.copyOf(notCompleted);

        // time-series windows
        java.util.List<TaskRunReport.TimeWindow> windows = new java.util.ArrayList<>();
        long prevReq = 0;
        long prevErr = 0;
        Instant prevTs = startedAt;
        for (TimeSeriesPoint p : timeSeries) {
            TaskRunReport.TimeWindow w = new TaskRunReport.TimeWindow();
            w.timestamp = p.timestamp();
            w.usersCompleted = usersCompleted.get();
            long deltaReq = p.totalRequests() - prevReq;
            long deltaErr = p.totalErrors() - prevErr;
            double secs = Math.max(0.001, Duration.between(prevTs, p.timestamp()).toMillis() / 1000.0);
            w.rpsInWindow = deltaReq / secs;
            w.totalRequests = p.totalRequests();
            w.errorsInWindow = deltaErr;
            w.latMinMs = p.latMinMs();
            w.latAvgMs = p.latAvgMs();
            w.latMaxMs = p.latMaxMs();
            windows.add(w);
            prevReq = p.totalRequests();
            prevErr = p.totalErrors();
            prevTs = p.timestamp();
        }
        r.timeseries = java.util.List.copyOf(windows);

        // REST endpoint stats placeholder (populated by REST processor via endpoint methods)
        if (!endpointStats.isEmpty()) {
            java.util.List<TaskRunReport.RestEndpointStats> list = new java.util.ArrayList<>();
            for (EndpointStats s : endpointStats.values()) {
                TaskRunReport.RestEndpointStats es = new TaskRunReport.RestEndpointStats();
                es.method = s.method;
                es.path = s.path;
                es.total = s.total.get();
                es.success = s.success.get();
                es.failure = s.failure.get();
                es.avgMs = s.avgMs();
                es.p95Ms = s.p95Ms();
                es.p99Ms = s.p99Ms();
                es.statusBreakdown = s.statusBreakdownSnapshot();
                list.add(es);
            }
            r.restEndpoints = java.util.List.copyOf(list);
        }
        return r;
    }

    private static final class EndpointStats {
        final String method;
        final String path;
        final AtomicLong total = new AtomicLong();
        final AtomicLong success = new AtomicLong();
        final AtomicLong failure = new AtomicLong();
        final AtomicLong sumLatency = new AtomicLong();
        final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong maxLatency = new AtomicLong(0);
        final Reservoir reservoir = new Reservoir(2000);
        final Map<String, AtomicLong> statusBreakdown = new ConcurrentHashMap<>();

        EndpointStats(String method, String path) { this.method = method; this.path = path; }

        void onSuccess(long latencyMs, int statusCode) {
            total.incrementAndGet();
            success.incrementAndGet();
            sumLatency.addAndGet(Math.max(0, latencyMs));
            minLatency.accumulateAndGet(Math.max(0, latencyMs), Math::min);
            maxLatency.accumulateAndGet(Math.max(0, latencyMs), Math::max);
            reservoir.add(latencyMs);
            String codeGroup = statusCode >= 200 && statusCode < 600 ? (statusCode / 100) + "xx" : String.valueOf(statusCode);
            statusBreakdown.computeIfAbsent(codeGroup, k -> new AtomicLong()).incrementAndGet();
        }

        void onFailure(String category) {
            total.incrementAndGet();
            failure.incrementAndGet();
            statusBreakdown.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
        }

        Long avgMs() {
            long s = success.get();
            if (s == 0) return null;
            return sumLatency.get() / s;
        }

        Long p95Ms() { return reservoir.percentile(95).orElse(null); }
        Long p99Ms() { return reservoir.percentile(99).orElse(null); }

        Map<String, Long> statusBreakdownSnapshot() {
            Map<String, Long> map = new java.util.HashMap<>();
            for (var e : statusBreakdown.entrySet()) map.put(e.getKey(), e.getValue().get());
            return java.util.Map.copyOf(map);
        }
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

    public record TimeSeriesPoint(Instant timestamp, long totalRequests, long totalErrors, long latMinMs, long latMaxMs, long latAvgMs) {}

    // Simple thread-safe reservoir sampling for percentiles
    private static final class Reservoir {
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
