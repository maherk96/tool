package com.example.springload.dto;

import com.example.springload.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public class TaskSummaryResponse {

    private final UUID taskId;
    private final String taskType;
    private final TaskStatus status;
    private final Instant submittedAt;

    public TaskSummaryResponse(UUID taskId, String taskType, TaskStatus status, Instant submittedAt) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.status = status;
        this.submittedAt = submittedAt;
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
}
