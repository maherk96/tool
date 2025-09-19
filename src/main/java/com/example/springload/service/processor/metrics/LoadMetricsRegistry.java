package com.example.springload.service.processor.metrics;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LoadMetricsRegistry {

    private final Map<UUID, LoadMetrics> active = new ConcurrentHashMap<>();
    private final Map<UUID, LoadMetrics.LoadSnapshot> completed = new ConcurrentHashMap<>();
    private final Map<UUID, com.example.springload.dto.TaskRunReport> reports = new ConcurrentHashMap<>();

    public void register(UUID taskId, LoadMetrics metrics) {
        active.put(taskId, metrics);
    }

    public void unregister(UUID taskId) {
        active.remove(taskId);
    }

    public void complete(UUID taskId, LoadMetrics.LoadSnapshot finalSnapshot) {
        completed.put(taskId, finalSnapshot);
        active.remove(taskId);
    }

    public Optional<LoadMetrics.LoadSnapshot> getSnapshot(UUID taskId) {
        LoadMetrics m = active.get(taskId);
        if (m != null) {
            return Optional.of(m.snapshotNow());
        }
        return Optional.ofNullable(completed.get(taskId));
    }

    public void saveReport(UUID taskId, com.example.springload.dto.TaskRunReport report) {
        reports.put(taskId, report);
    }

    public Optional<com.example.springload.dto.TaskRunReport> getReport(UUID taskId) {
        return Optional.ofNullable(reports.get(taskId));
    }
}
