package com.example.springload.service;

import com.example.springload.config.TaskProcessingProperties;
import com.example.springload.dto.QueueStatusResponse;
import com.example.springload.dto.TaskHistoryEntry;
import com.example.springload.dto.TaskMetricsResponse;
import com.example.springload.dto.TaskStatusResponse;
import com.example.springload.dto.TaskSummaryResponse;
import com.example.springload.model.LoadTask;
import com.example.springload.model.TaskStatus;
import com.example.springload.model.TaskType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoadTaskServiceTest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void submitTaskReturnsEmptyWhenServiceShutdown() {
        LoadTaskService service = newService(1, 10, 0);
        try {
            service.shutdown();

            Optional<LoadTaskService.TaskSubmissionOutcome> result = service.submitTask(newTask());

            assertTrue(result.isEmpty(), "Service should not accept tasks after shutdown");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void submitTaskRejectsDuplicateTaskIds() {
        LoadTaskService service = newService(1, 10, 0);
        LoadTask task = newTask();
        try {
            Optional<LoadTaskService.TaskSubmissionOutcome> first = service.submitTask(task);
            assertTrue(first.isPresent());
            TaskStatus firstStatus = first.get().getStatus();
            assertNotEquals(TaskStatus.ERROR, firstStatus);

            Optional<LoadTaskService.TaskSubmissionOutcome> duplicate = service.submitTask(task);

            assertTrue(duplicate.isPresent());
            assertEquals(TaskStatus.ERROR, duplicate.get().getStatus());
            assertEquals("Task ID already exists", duplicate.get().getMessage());

            awaitStatus(service, task.getId(), TaskStatus.COMPLETED, DEFAULT_TIMEOUT);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void submitTaskProcessesTaskToCompletion() {
        LoadTaskService service = newService(2, 10, 5);
        LoadTask task = newTask();
        try {
            Optional<LoadTaskService.TaskSubmissionOutcome> submission = service.submitTask(task);

            assertTrue(submission.isPresent());
            assertNotEquals(TaskStatus.ERROR, submission.get().getStatus());

            TaskStatusResponse completed = awaitStatus(service, task.getId(), TaskStatus.COMPLETED, DEFAULT_TIMEOUT);
            assertNotNull(completed.getStartedAt());
            assertNotNull(completed.getCompletedAt());
            assertEquals(TaskStatus.COMPLETED, completed.getStatus());

            QueueStatusResponse queueStatus = service.getQueueStatus();
            assertEquals(0, queueStatus.getQueueSize());
            assertEquals(0, queueStatus.getActiveTasks());
            assertTrue(queueStatus.isAcceptingTasks());

            TaskMetricsResponse metrics = service.getMetrics();
            assertEquals(1, metrics.getTotalCompleted());
            assertEquals(0, metrics.getTotalFailed());
            assertEquals(0, metrics.getTotalCancelled());
            assertEquals(1, metrics.getTotalProcessed());
            assertTrue(metrics.getAverageProcessingTimeMillis() >= 0.0);
            assertEquals(1.0, metrics.getSuccessRate(), 1e-9);

            List<TaskHistoryEntry> history = service.getTaskHistory();
            assertEquals(1, history.size());
            assertEquals(task.getId(), history.get(0).getTaskId());
            assertEquals(TaskStatus.COMPLETED, history.get(0).getStatus());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancelQueuedTaskUpdatesStateAndMetrics() {
        LoadTaskService service = newService(1, 10, 200);
        LoadTask running = newTask();
        LoadTask queued = newTask();
        try {
            service.submitTask(running);
            awaitStatus(service, running.getId(), TaskStatus.PROCESSING, DEFAULT_TIMEOUT);

            service.submitTask(queued);

            LoadTaskService.CancellationResult result = service.cancelTask(queued.getId());
            assertEquals(LoadTaskService.CancellationResult.CancellationState.CANCELLED, result.getState());
            assertEquals(TaskStatus.CANCELLED, result.getTaskStatus());

            TaskStatusResponse queuedStatus = getStatusOrThrow(service, queued.getId());
            assertEquals(TaskStatus.CANCELLED, queuedStatus.getStatus());

            awaitStatus(service, running.getId(), TaskStatus.COMPLETED, DEFAULT_TIMEOUT);

            TaskMetricsResponse metrics = service.getMetrics();
            assertEquals(1, metrics.getTotalCompleted());
            assertEquals(1, metrics.getTotalCancelled());
            assertEquals(0, metrics.getTotalFailed());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cancelProcessingTaskHonorsInterruption() {
        LoadTaskService service = newService(1, 10, 200);
        LoadTask task = newTask();
        try {
            service.submitTask(task);
            TaskStatusResponse inFlight = awaitStatus(service, task.getId(), TaskStatus.PROCESSING, DEFAULT_TIMEOUT);
            assertEquals(TaskStatus.PROCESSING, inFlight.getStatus());

            LoadTaskService.CancellationResult result = service.cancelTask(task.getId());
            assertEquals(LoadTaskService.CancellationResult.CancellationState.CANCELLATION_REQUESTED, result.getState());
            TaskStatus reportedStatus = result.getTaskStatus();
            assertTrue(reportedStatus == TaskStatus.PROCESSING || reportedStatus == TaskStatus.CANCELLED);

            TaskStatusResponse cancelled = awaitStatus(service, task.getId(), TaskStatus.CANCELLED, DEFAULT_TIMEOUT);
            assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());

            TaskMetricsResponse metrics = service.getMetrics();
            assertEquals(0, metrics.getTotalCompleted());
            assertEquals(0, metrics.getTotalFailed());
            assertEquals(1, metrics.getTotalCancelled());
            assertEquals(0, metrics.getTotalProcessed());
            assertEquals(0.0, metrics.getSuccessRate(), 1e-9);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void getTaskHistoryRespectsConfiguredLimit() {
        LoadTaskService service = newService(1, 2, 5);
        LoadTask first = newTask();
        LoadTask second = newTask();
        LoadTask third = newTask();
        try {
            service.submitTask(first);
            awaitStatus(service, first.getId(), TaskStatus.COMPLETED, DEFAULT_TIMEOUT);

            service.submitTask(second);
            awaitStatus(service, second.getId(), TaskStatus.COMPLETED, DEFAULT_TIMEOUT);

            service.submitTask(third);
            awaitStatus(service, third.getId(), TaskStatus.COMPLETED, DEFAULT_TIMEOUT);

            List<TaskHistoryEntry> history = service.getTaskHistory();
            assertEquals(2, history.size());
            assertEquals(third.getId(), history.get(0).getTaskId());
            assertEquals(second.getId(), history.get(1).getTaskId());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void getTasksByStatusFiltersByStatus() {
        LoadTaskService service = newService(1, 10, 100);
        LoadTask completed = newTask();
        LoadTask cancelled = newTask();
        try {
            service.submitTask(completed);
            awaitStatus(service, completed.getId(), TaskStatus.COMPLETED, DEFAULT_TIMEOUT);

            service.submitTask(cancelled);
            awaitStatus(service, cancelled.getId(), TaskStatus.PROCESSING, DEFAULT_TIMEOUT);

            LoadTaskService.CancellationResult result = service.cancelTask(cancelled.getId());
            assertEquals(LoadTaskService.CancellationResult.CancellationState.CANCELLATION_REQUESTED, result.getState());

            awaitStatus(service, cancelled.getId(), TaskStatus.CANCELLED, DEFAULT_TIMEOUT);

            List<TaskSummaryResponse> completedTasks = service.getTasksByStatus(TaskStatus.COMPLETED);
            assertEquals(1, completedTasks.size());
            assertEquals(completed.getId(), completedTasks.get(0).getTaskId());

            List<TaskSummaryResponse> cancelledTasks = service.getTasksByStatus(TaskStatus.CANCELLED);
            assertEquals(1, cancelledTasks.size());
            assertEquals(cancelled.getId(), cancelledTasks.get(0).getTaskId());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void getTaskStatusReturnsEmptyForUnknownTask() {
        LoadTaskService service = newService(1, 10, 0);
        try {
            assertTrue(service.getTaskStatus(UUID.randomUUID()).isEmpty());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void shutdownMarksServiceUnhealthy() {
        LoadTaskService service = newService(1, 10, 0);
        try {
            assertTrue(service.isHealthy());
            service.shutdown();
            assertFalse(service.isHealthy());
        } finally {
            service.shutdown();
        }
    }

    private static LoadTaskService newService(int concurrency, int historySize, long simulatedDurationMs) {
        TaskProcessingProperties properties = new TaskProcessingProperties();
        properties.setConcurrency(concurrency);
        properties.setHistorySize(historySize);
        properties.setSimulatedDurationMs(simulatedDurationMs);
        return new LoadTaskService(properties);
    }

    private static LoadTask newTask() {
        return new LoadTask(UUID.randomUUID(), TaskType.REST, Instant.now(), Map.of("payload", "value"));
    }

    private static TaskStatusResponse awaitStatus(LoadTaskService service, UUID taskId, TaskStatus expectedStatus, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        TaskStatusResponse last = null;
        while (System.nanoTime() < deadline) {
            Optional<TaskStatusResponse> current = service.getTaskStatus(taskId);
            if (current.isPresent()) {
                last = current.get();
                if (last.getStatus() == expectedStatus) {
                    return last;
                }
            }
            sleep(Duration.ofMillis(10));
        }
        throw new AssertionError("Timed out waiting for status " + expectedStatus + " (last=" + (last == null ? "none" : last.getStatus()) + ")");
    }

    private static TaskStatusResponse getStatusOrThrow(LoadTaskService service, UUID taskId) {
        return service.getTaskStatus(taskId)
                .orElseThrow(() -> new AssertionError("Expected task status to be present for " + taskId));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", ex);
        }
    }
}
