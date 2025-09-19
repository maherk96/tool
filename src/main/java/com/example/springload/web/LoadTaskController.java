package com.example.springload.web;

import com.example.springload.dto.HealthResponse;
import com.example.springload.dto.QueueStatusResponse;
import com.example.springload.dto.TaskCancellationResponse;
import com.example.springload.dto.TaskHistoryEntry;
import com.example.springload.dto.TaskMetricsResponse;
import com.example.springload.dto.TaskStatusResponse;
import com.example.springload.dto.TaskSubmissionRequest;
import com.example.springload.dto.TaskSubmissionResponse;
import com.example.springload.dto.TaskSummaryResponse;
import com.example.springload.model.LoadTask;
import com.example.springload.model.TaskStatus;
import com.example.springload.model.TaskType;
import com.example.springload.service.LoadTaskService;
import com.example.springload.service.LoadTaskService.CancellationResult;
import com.example.springload.service.LoadTaskService.TaskSubmissionOutcome;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
@Validated
public class LoadTaskController {

    private static final Logger log = LoggerFactory.getLogger(LoadTaskController.class);

    private final LoadTaskService loadTaskService;

    public LoadTaskController(LoadTaskService loadTaskService) {
        this.loadTaskService = loadTaskService;
    }

    @PostMapping
    public ResponseEntity<?> submitTask(@Valid @RequestBody TaskSubmissionRequest request) {
        LoadTask loadTask;
        try {
            loadTask = mapToDomain(request);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }

        Optional<TaskSubmissionOutcome> submissionOutcome = loadTaskService.submitTask(loadTask);
        if (submissionOutcome.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Service is not accepting new tasks"));
        }

        TaskSubmissionOutcome outcome = submissionOutcome.get();
        HttpStatus httpStatus = determineSubmissionHttpStatus(outcome);
        TaskSubmissionResponse body = new TaskSubmissionResponse(outcome.getTaskId(), outcome.getStatus(), outcome.getMessage());
        return ResponseEntity.status(httpStatus).body(body);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(@PathVariable("taskId") UUID taskId) {
        return loadTaskService.getTaskStatus(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> cancelTask(@PathVariable("taskId") UUID taskId) {
        CancellationResult result = loadTaskService.cancelTask(taskId);
        return switch (result.getState()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Task not found"));
            case NOT_CANCELLABLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Task cannot be cancelled in its current state"));
            case CANCELLED -> {
                TaskCancellationResponse body = new TaskCancellationResponse(taskId, TaskStatus.CANCELLED, "Task cancelled");
                yield ResponseEntity.ok(body);
            }
            case CANCELLATION_REQUESTED -> {
                TaskStatusResponse latest = loadTaskService.getTaskStatus(taskId).orElse(null);
                TaskStatus currentStatus = latest != null ? latest.getStatus() : TaskStatus.PROCESSING;
                TaskCancellationResponse body = new TaskCancellationResponse(taskId, currentStatus, "Cancellation requested");
                yield ResponseEntity.ok(body);
            }
        };
    }

    @GetMapping
    public ResponseEntity<?> getTasks(@RequestParam(name = "status", required = false) String statusFilter) {
        if (statusFilter == null) {
            Collection<TaskStatusResponse> all = loadTaskService.getAllTasks();
            return ResponseEntity.ok(all);
        }
        try {
            TaskStatus status = TaskStatus.valueOf(statusFilter.toUpperCase());
            List<TaskSummaryResponse> filtered = loadTaskService.getTasksByStatus(status);
            return ResponseEntity.ok(filtered);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown status: " + statusFilter));
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<TaskHistoryEntry>> getTaskHistory() {
        return ResponseEntity.ok(loadTaskService.getTaskHistory());
    }

    @GetMapping("/queue")
    public ResponseEntity<QueueStatusResponse> getQueueStatus() {
        return ResponseEntity.ok(loadTaskService.getQueueStatus());
    }

    @GetMapping("/metrics")
    public ResponseEntity<TaskMetricsResponse> getMetrics() {
        return ResponseEntity.ok(loadTaskService.getMetrics());
    }

    @GetMapping("/types")
    public ResponseEntity<Set<String>> getSupportedTaskTypes() {
        return ResponseEntity.ok(loadTaskService.getSupportedTaskTypes());
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        String status = loadTaskService.isHealthy() ? "UP" : "DOWN";
        return ResponseEntity.ok(new HealthResponse(status));
    }

    private LoadTask mapToDomain(TaskSubmissionRequest request) {
        UUID taskId;
        try {
            taskId = UUID.fromString(request.getTaskId());
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid taskId {}", request.getTaskId());
            throw new IllegalArgumentException("taskId must be a valid UUID");
        }

        TaskType taskType;
        try {
            taskType = TaskType.fromValue(request.getTaskType());
        } catch (IllegalArgumentException ex) {
            log.warn("Unsupported taskType {}", request.getTaskType());
            throw new IllegalArgumentException("Unsupported taskType: " + request.getTaskType());
        }

        Map<String, Object> data = request.getData();
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("data payload must be provided");
        }

        Instant createdAt = request.getCreatedAt();
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt timestamp is required");
        }

        return new LoadTask(taskId, taskType, createdAt, data);
    }

    private HttpStatus determineSubmissionHttpStatus(TaskSubmissionOutcome outcome) {
        return switch (outcome.getStatus()) {
            case COMPLETED -> HttpStatus.OK;
            case PROCESSING, QUEUED -> HttpStatus.ACCEPTED;
            case ERROR -> HttpStatus.BAD_REQUEST;
            case CANCELLED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
