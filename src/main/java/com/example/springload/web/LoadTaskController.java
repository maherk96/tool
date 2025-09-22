package com.example.springload.web;

import com.example.springload.dto.*;
import com.example.springload.model.LoadTask;
import com.example.springload.model.TaskStatus;
import com.example.springload.service.LoadTaskService;
import com.example.springload.service.LoadTaskService.CancellationResult;
import com.example.springload.service.LoadTaskService.TaskSubmissionOutcome;
import com.example.springload.service.processor.metrics.LoadMetrics;
import com.example.springload.service.processor.metrics.LoadMetricsRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "Load Tasks", description = "Endpoints for submitting, monitoring, and managing load execution tasks")
@RestController
@RequestMapping("/api/tasks")
@Validated
public class LoadTaskController {

    private final LoadTaskService loadTaskService;
    private final LoadMetricsRegistry metricsRegistry;
    private final TaskMapper taskMapper;

    public LoadTaskController(LoadTaskService loadTaskService,
                              LoadMetricsRegistry metricsRegistry,
                              TaskMapper taskMapper) {
        this.loadTaskService = loadTaskService;
        this.metricsRegistry = metricsRegistry;
        this.taskMapper = taskMapper;
    }

    @Operation(
            summary = "Submit a new load task",
            description = "Creates and queues a new load test task for execution.",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Task accepted and queued",
                            content = @Content(schema = @Schema(implementation = TaskSubmissionResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Invalid request payload",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                    @ApiResponse(responseCode = "503", description = "Service not accepting new tasks",
                            content = @Content(schema = @Schema(implementation = TaskSubmissionResponse.class)))
            }
    )
    @PostMapping
    public ResponseEntity<TaskSubmissionResponse> submitTask(@Valid @RequestBody TaskSubmissionRequest request) {
        LoadTask loadTask = taskMapper.toDomain(request);
        Optional<TaskSubmissionOutcome> outcomeOpt = loadTaskService.submitTask(loadTask);

        if (outcomeOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new TaskSubmissionResponse(null, TaskStatus.CANCELLED, "Service not accepting new tasks"));
        }

        TaskSubmissionOutcome outcome = outcomeOpt.get();
        return ResponseEntity.status(determineSubmissionHttpStatus(outcome))
                .body(new TaskSubmissionResponse(outcome.getTaskId(), outcome.getStatus(), outcome.getMessage()));
    }

    @Operation(
            summary = "Get task status",
            description = "Returns the current status of a specific task by its ID.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Task found",
                            content = @Content(schema = @Schema(implementation = TaskStatusResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Task not found")
            }
    )
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(
            @Parameter(description = "UUID of the task") @PathVariable UUID taskId) {
        return loadTaskService.getTaskStatus(taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Cancel a task",
            description = "Attempts to cancel the specified task if it is still running or queued.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Task cancelled",
                            content = @Content(schema = @Schema(implementation = TaskCancellationResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Task not found",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
                    @ApiResponse(responseCode = "409", description = "Task not cancellable",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
            }
    )
    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> cancelTask(
            @Parameter(description = "UUID of the task to cancel") @PathVariable UUID taskId) {
        CancellationResult result = loadTaskService.cancelTask(taskId);
        return switch (result.getState()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Not Found", "Task not found"));
            case NOT_CANCELLABLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("Conflict", "Task cannot be cancelled in its current state"));
            case CANCELLED -> ResponseEntity.ok(new TaskCancellationResponse(taskId, TaskStatus.CANCELLED, "Task cancelled"));
            case CANCELLATION_REQUESTED -> {
                TaskStatus currentStatus = loadTaskService.getTaskStatus(taskId)
                        .map(TaskStatusResponse::getStatus)
                        .orElse(TaskStatus.PROCESSING);
                yield ResponseEntity.ok(new TaskCancellationResponse(taskId, currentStatus, "Cancellation requested"));
            }
        };
    }

    @Operation(
            summary = "Get tasks",
            description = "Retrieves all tasks, optionally filtered by status."
    )
    @GetMapping
    public ResponseEntity<?> getTasks(
            @Parameter(description = "Optional status filter (e.g., QUEUED, PROCESSING)") @RequestParam(required = false) String status) {
        if (status == null) {
            return ResponseEntity.ok(loadTaskService.getAllTasks());
        }
        TaskStatus taskStatus = TaskStatus.valueOf(status.toUpperCase());
        return ResponseEntity.ok(loadTaskService.getTasksByStatus(taskStatus));
    }

    @Operation(summary = "Get task history", description = "Returns a list of recently executed tasks.")
    @GetMapping("/history")
    public ResponseEntity<List<TaskHistoryEntry>> getTaskHistory() {
        return ResponseEntity.ok(loadTaskService.getTaskHistory());
    }

    @Operation(summary = "Get queue status", description = "Returns current queue size and capacity.")
    @GetMapping("/queue")
    public ResponseEntity<QueueStatusResponse> getQueueStatus() {
        return ResponseEntity.ok(loadTaskService.getQueueStatus());
    }

    @Operation(summary = "Get overall metrics", description = "Returns global execution metrics across all tasks.")
    @GetMapping("/metrics")
    public ResponseEntity<TaskMetricsResponse> getMetrics() {
        return ResponseEntity.ok(loadTaskService.getMetrics());
    }

    @Operation(summary = "Get task metrics", description = "Returns live metrics for a specific task.")
    @GetMapping("/{taskId}/metrics")
    public ResponseEntity<?> getTaskMetrics(
            @Parameter(description = "UUID of the task") @PathVariable UUID taskId) {
        return metricsRegistry.getSnapshot(taskId)
                .<ResponseEntity<?>>map(snapshot -> ResponseEntity.ok(mapToResponse(snapshot)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Not Found", "Metrics not found for task: " + taskId)));
    }

    @Operation(summary = "Get task report", description = "Returns final execution report for a completed task.")
    @GetMapping("/{taskId}/report")
    public ResponseEntity<?> getTaskReport(
            @Parameter(description = "UUID of the task") @PathVariable UUID taskId) {
        return metricsRegistry.getReport(taskId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Not Found", "Report not found for task: " + taskId)));
    }

    @Operation(summary = "Get supported task types", description = "Returns a set of all supported task types.")
    @GetMapping("/types")
    public ResponseEntity<Set<String>> getSupportedTaskTypes() {
        return ResponseEntity.ok(loadTaskService.getSupportedTaskTypes());
    }

    @Operation(summary = "Health check", description = "Simple health endpoint to verify the service is running.")
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse(loadTaskService.isHealthy() ? "UP" : "DOWN"));
    }

    private HttpStatus determineSubmissionHttpStatus(TaskSubmissionOutcome outcome) {
        return switch (outcome.getStatus()) {
            case COMPLETED -> HttpStatus.OK;
            case PROCESSING, QUEUED -> HttpStatus.ACCEPTED;
            case ERROR -> HttpStatus.BAD_REQUEST;
            case CANCELLED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private TaskLiveMetricsResponse mapToResponse(LoadMetrics.LoadSnapshot snapshot) {
        TaskLiveMetricsResponse resp = new TaskLiveMetricsResponse();
        var cfg = snapshot.config();
        resp.setTaskId(cfg.taskId());
        resp.setTaskType(cfg.taskType());
        resp.setBaseUrl(cfg.baseUrl());
        resp.setModel(TaskLiveMetricsResponse.ModelKind.valueOf(cfg.model().name()));
        resp.setUsers(cfg.users());
        resp.setIterationsPerUser(cfg.iterationsPerUser());
        resp.setWarmup(cfg.warmup());
        resp.setRampUp(cfg.rampUp());
        resp.setHoldFor(cfg.holdFor());
        resp.setArrivalRatePerSec(cfg.arrivalRatePerSec());
        resp.setDuration(cfg.duration());
        resp.setRequestsPerIteration(cfg.requestsPerIteration());
        resp.setExpectedTotalRequests(cfg.expectedTotalRequests());
        resp.setExpectedRps(cfg.expectedRps());
        resp.setUsersStarted(snapshot.usersStarted());
        resp.setUsersCompleted(snapshot.usersCompleted());
        resp.setTotalRequests(snapshot.totalRequests());
        resp.setTotalErrors(snapshot.totalErrors());
        resp.setAchievedRps(snapshot.achievedRps());
        resp.setLatencyMinMs(snapshot.latencyMinMs());
        resp.setLatencyAvgMs(snapshot.latencyAvgMs());
        resp.setLatencyMaxMs(snapshot.latencyMaxMs());
        resp.setActiveUserIterations(snapshot.activeUserIterations());
        return resp;
    }
}