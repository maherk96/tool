package com.example.springload.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TaskRecord {

    private final LoadTask task;
    private final Instant submittedAt;
    private final AtomicReference<TaskStatus> status;
    private final AtomicReference<Instant> startedAt;
    private final AtomicReference<Instant> completedAt;
    private final AtomicReference<String> errorMessage;
    private final AtomicLong processingDurationMillis;

    public TaskRecord(LoadTask task, Instant submittedAt) {
        this.task = task;
        this.submittedAt = submittedAt;
        this.status = new AtomicReference<>(TaskStatus.QUEUED);
        this.startedAt = new AtomicReference<>();
        this.completedAt = new AtomicReference<>();
        this.errorMessage = new AtomicReference<>();
        this.processingDurationMillis = new AtomicLong();
    }

    public UUID getTaskId() {
        return task.getId();
    }

    public LoadTask getTask() {
        return task;
    }

    public TaskStatus getStatus() {
        return status.get();
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Optional<Instant> getStartedAt() {
        return Optional.ofNullable(startedAt.get());
    }

    public Optional<Instant> getCompletedAt() {
        return Optional.ofNullable(completedAt.get());
    }

    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage.get());
    }

    public long getProcessingDurationMillis() {
        return processingDurationMillis.get();
    }

    public void markProcessing(Instant startTime) {
        startedAt.set(startTime);
        status.set(TaskStatus.PROCESSING);
    }

    public void markCompleted(Instant completionTime) {
        completedAt.set(completionTime);
        status.set(TaskStatus.COMPLETED);
        updateDuration();
    }

    public void markCancelled(Instant completionTime) {
        completedAt.set(completionTime);
        status.set(TaskStatus.CANCELLED);
        updateDuration();
    }

    public void markErrored(Instant completionTime, String message) {
        completedAt.set(completionTime);
        status.set(TaskStatus.ERROR);
        errorMessage.set(message);
        updateDuration();
    }

    private void updateDuration() {
        Instant start = startedAt.get();
        Instant end = completedAt.get();
        if (start != null && end != null) {
            processingDurationMillis.set(Duration.between(start, end).toMillis());
        }
    }
}
