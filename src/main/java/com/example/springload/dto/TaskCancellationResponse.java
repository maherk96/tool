package com.example.springload.dto;

import com.example.springload.model.TaskStatus;

import java.util.UUID;

public class TaskCancellationResponse {

    private final UUID taskId;
    private final TaskStatus status;
    private final String message;

    public TaskCancellationResponse(UUID taskId, TaskStatus status, String message) {
        this.taskId = taskId;
        this.status = status;
        this.message = message;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
