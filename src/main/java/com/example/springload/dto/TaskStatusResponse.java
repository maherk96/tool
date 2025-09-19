package com.example.springload.dto;

import com.example.springload.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public class TaskStatusResponse {

    private final UUID taskId;
    private final String taskType;
    private final TaskStatus status;
    private final Instant submittedAt;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Long processingTimeMillis;
    private final String errorMessage;

    public TaskStatusResponse(
            UUID taskId,
            String taskType,
            TaskStatus status,
            Instant submittedAt,
            Instant startedAt,
            Instant completedAt,
            Long processingTimeMillis,
            String errorMessage) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.status = status;
        this.submittedAt = submittedAt;
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

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getProcessingTimeMillis() {
        return processingTimeMillis;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
