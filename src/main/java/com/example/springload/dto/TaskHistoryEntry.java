package com.example.springload.dto;

import com.example.springload.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public class TaskHistoryEntry {

    private final UUID taskId;
    private final String taskType;
    private final TaskStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final long processingTimeMillis;
    private final String errorMessage;

    public TaskHistoryEntry(
            UUID taskId,
            String taskType,
            TaskStatus status,
            Instant startedAt,
            Instant completedAt,
            long processingTimeMillis,
            String errorMessage) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.processingTimeMillis = processingTimeMillis;
        this.errorMessage = errorMessage;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getProcessingTimeMillis() {
        return processingTimeMillis;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
