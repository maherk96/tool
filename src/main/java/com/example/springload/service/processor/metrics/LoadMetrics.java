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

    // user index -> current iteration (0-based)
    private final Map<Integer, Integer> userIterations = new ConcurrentHashMap<>();

    private ScheduledExecutorService snapshots;

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
        log.info("Task {} user {} started (usersStarted={})", config.taskId(), userIndex + 1, usersStarted.get());
    }

    public void recordUserProgress(int userIndex, int iteration) {
        userIterations.put(userIndex, iteration);
    }

    public void recordUserCompleted(int userIndex, int totalIterationsCompleted) {
        usersCompleted.incrementAndGet();
        userIterations.remove(userIndex);
        log.info("Task {} user {} completed {} iterations (usersCompleted={})",
                config.taskId(), userIndex + 1, totalIterationsCompleted, usersCompleted.get());
    }

    public void recordRequestSuccess(long latencyMs, int statusCode) {
        requests.incrementAndGet();
        latencySum.addAndGet(Math.max(0, latencyMs));
        updateMinMax(latencyMs);
    }

    public void recordRequestFailure(Throwable t) {
        errors.incrementAndGet();
        requests.incrementAndGet();
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
        if (totalErrors() > 0) {
            sb.append(", errors=").append(totalErrors());
        }
        log.info(sb.toString());
    }
}
