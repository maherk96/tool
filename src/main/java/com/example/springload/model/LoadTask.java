package com.example.springload.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class LoadTask {

    private final UUID id;
    private final TaskType taskType;
    private final Instant createdAt;
    private final Map<String, Object> data;

    public LoadTask(UUID id, TaskType taskType, Instant createdAt, Map<String, Object> data) {
        this.id = id;
        this.taskType = taskType;
        this.createdAt = createdAt;
        this.data = data;
    }

    public UUID getId() {
        return id;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
