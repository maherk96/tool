package com.example.springload.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

public class TaskSubmissionRequest {

    @NotBlank
    @JsonProperty("taskId")
    private String taskId;

    @NotBlank
    @JsonProperty("taskType")
    private String taskType;

    @NotNull
    @JsonProperty("createdAt")
    private Instant createdAt;

    @NotNull
    @JsonProperty("data")
    private Map<String, Object> data;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
