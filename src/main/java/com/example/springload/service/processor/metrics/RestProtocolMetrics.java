package com.example.springload.service.processor.metrics;

import com.example.springload.dto.TaskRunReport;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects REST protocol-specific endpoint metrics.
 */
public class RestProtocolMetrics implements ProtocolMetricsProvider {

    private final Map<String, EndpointStats> endpointStats = new ConcurrentHashMap<>();

    public void recordSuccess(String method, String path, long latencyMs, int statusCode) {
        EndpointStats s = endpointStats.computeIfAbsent(key(method, path), k -> new EndpointStats(method, path));
        s.onSuccess(latencyMs, statusCode);
    }

    public void recordHttpFailure(int statusCode, long latencyMs, String method, String path, java.util.function.Consumer<String> errorCategorizer) {
        // let the caller update global error breakdown; here focus on endpoint aggregates
        EndpointStats s = endpointStats.computeIfAbsent(key(method, path), k -> new EndpointStats(method, path));
        String category = httpCategory(statusCode);
        s.onFailure(category);
    }

    public void recordExceptionFailure(String method, String path, String category) {
        EndpointStats s = endpointStats.computeIfAbsent(key(method, path), k -> new EndpointStats(method, path));
        s.onFailure(category);
    }

    private String key(String method, String path) {
        return (method == null ? "" : method) + " " + (path == null ? "" : path);
    }

    private String httpCategory(int statusCode) {
        if (statusCode >= 500) return "HTTP_5xx";
        if (statusCode >= 400) return "HTTP_4xx";
        if (statusCode >= 300) return "HTTP_3xx";
        return "HTTP_" + statusCode;
    }

    @Override
    public void applyTo(TaskRunReport report) {
        if (endpointStats.isEmpty()) {
            return;
        }
        TaskRunReport.ProtocolDetails pd = report.protocolDetails == null ? new TaskRunReport.ProtocolDetails() : report.protocolDetails;
        TaskRunReport.RestDetails rd = new TaskRunReport.RestDetails();
        java.util.List<TaskRunReport.RestEndpoint> list = new java.util.ArrayList<>();
        for (EndpointStats s : endpointStats.values()) {
            TaskRunReport.RestEndpoint es = new TaskRunReport.RestEndpoint();
            es.method = s.method;
            es.path = s.path;
            es.total = s.total.get();
            es.success = s.success.get();
            es.failure = s.failure.get();
            TaskRunReport.Latency l = new TaskRunReport.Latency();
            l.min = s.minLatency.get() == Long.MAX_VALUE ? null : s.minLatency.get();
            l.max = s.maxLatency.get();
            l.avg = s.avgMs();
            l.p95 = s.p95Ms();
            l.p99 = s.p99Ms();
            es.latency = l;
            es.statusBreakdown = s.statusBreakdownSnapshot();
            list.add(es);
        }
        rd.endpoints = java.util.List.copyOf(list);
        pd.rest = rd;
        report.protocolDetails = pd;
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
        final LoadMetrics.Reservoir reservoir = new LoadMetrics.Reservoir(2000);
        final Map<String, AtomicLong> statusBreakdown = new ConcurrentHashMap<>();

        EndpointStats(String method, String path) { this.method = method; this.path = path; }

        void onSuccess(long latencyMs, int statusCode) {
            total.incrementAndGet();
            success.incrementAndGet();
            long v = Math.max(0, latencyMs);
            sumLatency.addAndGet(v);
            minLatency.accumulateAndGet(v, Math::min);
            maxLatency.accumulateAndGet(v, Math::max);
            reservoir.add(v);
            String codeGroup = statusCode >= 200 && statusCode < 600 ? (statusCode / 100) + "xx" : String.valueOf(statusCode);
            statusBreakdown.computeIfAbsent(codeGroup, k -> new AtomicLong()).incrementAndGet();
        }

        void onFailure(String category) {
            total.incrementAndGet();
            failure.incrementAndGet();
            statusBreakdown.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
        }

        Long avgMs() { long s = success.get(); return s == 0 ? null : sumLatency.get() / s; }
        Long p95Ms() { return reservoir.percentile(95).orElse(null); }
        Long p99Ms() { return reservoir.percentile(99).orElse(null); }

        Map<String, Long> statusBreakdownSnapshot() {
            Map<String, Long> map = new java.util.HashMap<>();
            for (var e : statusBreakdown.entrySet()) map.put(e.getKey(), e.getValue().get());
            return java.util.Map.copyOf(map);
        }
    }
}

