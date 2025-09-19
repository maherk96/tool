package com.example.springload.service;

import com.example.springload.config.TaskProcessingProperties;
import com.example.springload.dto.QueueStatusResponse;
import com.example.springload.dto.TaskHistoryEntry;
import com.example.springload.dto.TaskMetricsResponse;
import com.example.springload.dto.TaskStatusResponse;
import com.example.springload.dto.TaskSummaryResponse;
import com.example.springload.dto.TaskSubmissionRequest;
import com.example.springload.model.LoadTask;
import com.example.springload.model.TaskRecord;
import com.example.springload.model.TaskStatus;
import com.example.springload.model.TaskType;
import com.example.springload.service.processor.LoadTaskProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newFixedThreadPool;

@Service
public class LoadTaskService {

    private static final Logger log = LoggerFactory.getLogger(LoadTaskService.class);

    private final TaskProcessingProperties properties;
    private final ThreadPoolExecutor executor;
    private final Map<UUID, TaskRecord> taskRecords;
    private final Map<UUID, Future<?>> activeTasks;
    private final Map<TaskType, LoadTaskProcessor> processors;
    private final Deque<TaskRecord> taskHistory;
    private final AtomicBoolean acceptingTasks;
    private final AtomicInteger activeTaskCount;
    private final AtomicLong totalCompleted;
    private final AtomicLong totalFailed;
    private final AtomicLong totalCancelled;
    private final AtomicLong cumulativeProcessingTime;

    public LoadTaskService(TaskProcessingProperties properties, List<LoadTaskProcessor> processors) {
        this.properties = properties;
        this.executor = createExecutor(properties.getConcurrency());
        this.taskRecords = new ConcurrentHashMap<>();
        this.activeTasks = new ConcurrentHashMap<>();
        this.taskHistory = new ConcurrentLinkedDeque<>();
        this.acceptingTasks = new AtomicBoolean(true);
        this.activeTaskCount = new AtomicInteger();
        this.totalCompleted = new AtomicLong();
        this.totalFailed = new AtomicLong();
        this.totalCancelled = new AtomicLong();
        this.cumulativeProcessingTime = new AtomicLong();
        this.processors = initializeProcessors(processors);
    }

    @PostConstruct
    void logConfiguration() {
        log.info("LoadTaskService initialized with concurrency={} historySize={} simulatedDurationMs={}",
                properties.getConcurrency(), properties.getHistorySize(), properties.getSimulatedDurationMs());
    }

    private ThreadPoolExecutor createExecutor(int concurrency) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("load-task-worker-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor pool = (ThreadPoolExecutor) newFixedThreadPool(concurrency, threadFactory);
        pool.setRejectedExecutionHandler((runnable, exec) -> {
            throw new RejectedExecutionException("Task queue is full");
        });
        return pool;
    }

    private Map<TaskType, LoadTaskProcessor> initializeProcessors(List<LoadTaskProcessor> availableProcessors) {
        Map<TaskType, LoadTaskProcessor> map = new EnumMap<>(TaskType.class);
        for (LoadTaskProcessor processor : availableProcessors) {
            Objects.requireNonNull(processor, "Processor entry cannot be null");
            TaskType taskType = Objects.requireNonNull(processor.supportedTaskType(), "Processor must declare supported task type");
            LoadTaskProcessor existing = map.putIfAbsent(taskType, processor);
            if (existing != null) {
                throw new IllegalStateException("Multiple processors registered for task type " + taskType);
            }
        }
        return Map.copyOf(map);
    }

    public Optional<TaskSubmissionOutcome> submitTask(LoadTask task) {
        if (!acceptingTasks.get()) {
            return Optional.empty();
        }

        LoadTaskProcessor processor = processors.get(task.getTaskType());
        if (processor == null) {
            return Optional.of(new TaskSubmissionOutcome(task.getId(), TaskStatus.ERROR,
                    "No processor available for task type " + task.getTaskType().name()));
        }

        TaskRecord record = new TaskRecord(task, Instant.now());
        TaskRecord previous = taskRecords.putIfAbsent(task.getId(), record);
        if (previous != null) {
            return Optional.of(new TaskSubmissionOutcome(task.getId(), TaskStatus.ERROR, "Task ID already exists"));
        }

        Runnable runnable = () -> executeTask(record);

        try {
            Future<?> future = executor.submit(runnable);
            activeTasks.put(task.getId(), future);
            log.info("Task {} submitted (type={})", task.getId(), task.getTaskType());
            TaskStatus statusSnapshot = record.getStatus();
            String message = switch (statusSnapshot) {
                case QUEUED -> "Task queued";
                case PROCESSING -> "Task is processing";
                case COMPLETED -> "Task completed";
                case ERROR -> "Task failed";
                case CANCELLED -> "Task cancelled";
            };
            return Optional.of(new TaskSubmissionOutcome(task.getId(), statusSnapshot, message));
        } catch (RejectedExecutionException ex) {
            log.warn("Task {} rejected: {}", task.getId(), ex.getMessage());
            taskRecords.remove(task.getId());
            return Optional.of(new TaskSubmissionOutcome(task.getId(), TaskStatus.ERROR, "Task queue is full"));
        }
    }

    private void executeTask(TaskRecord record) {
        UUID taskId = record.getTaskId();
        boolean started = false;
        try {
            if (Thread.currentThread().isInterrupted()) {
                record.markCancelled(Instant.now());
                log.info("Task {} cancelled before start", taskId);
                totalCancelled.incrementAndGet();
                return;
            }

            record.markProcessing(Instant.now());
            started = true;
            activeTaskCount.incrementAndGet();
            log.info("Task {} started", taskId);

            LoadTask task = record.getTask();
            LoadTaskProcessor processor = processors.get(task.getTaskType());
            if (processor == null) {
                throw new IllegalStateException("No processor registered for task type " + task.getTaskType());
            }

            TaskSubmissionRequest submissionRequest = toSubmissionRequest(task);
            processor.execute(submissionRequest);

            record.markCompleted(Instant.now());
            totalCompleted.incrementAndGet();
            cumulativeProcessingTime.addAndGet(record.getProcessingDurationMillis());
            log.info("Task {} completed", taskId);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            record.markCancelled(Instant.now());
            totalCancelled.incrementAndGet();
            log.info("Task {} cancelled", taskId);
        } catch (Exception ex) {
            record.markErrored(Instant.now(), ex.getMessage());
            totalFailed.incrementAndGet();
            log.error("Task {} failed: {}", taskId, ex.getMessage(), ex);
        } finally {
            if (started) {
                activeTaskCount.decrementAndGet();
            }
            activeTasks.remove(taskId);
            addToHistory(record);
        }
    }

    private void addToHistory(TaskRecord record) {
        taskHistory.addFirst(record);
        while (taskHistory.size() > properties.getHistorySize()) {
            taskHistory.pollLast();
        }
    }

    private TaskSubmissionRequest toSubmissionRequest(LoadTask task) {
        TaskSubmissionRequest request = new TaskSubmissionRequest();
        request.setTaskId(task.getId().toString());
        request.setTaskType(task.getTaskType().name());
        request.setCreatedAt(task.getCreatedAt());
        request.setData(task.getData());
        return request;
    }

    public Optional<TaskStatusResponse> getTaskStatus(UUID taskId) {
        TaskRecord record = taskRecords.get(taskId);
        if (record == null) {
            return Optional.empty();
        }
        return Optional.of(toStatusResponse(record));
    }

    public List<TaskSummaryResponse> getTasksByStatus(TaskStatus status) {
        List<TaskSummaryResponse> results = new ArrayList<>();
        for (TaskRecord record : taskRecords.values()) {
            if (record.getStatus() == status) {
                results.add(new TaskSummaryResponse(
                        record.getTaskId(),
                        record.getTask().getTaskType().name(),
                        record.getStatus(),
                        record.getSubmittedAt()));
            }
        }
        return results;
    }

    public List<TaskHistoryEntry> getTaskHistory() {
        List<TaskHistoryEntry> snapshot = new ArrayList<>();
        for (TaskRecord record : taskHistory) {
            snapshot.add(new TaskHistoryEntry(
                    record.getTaskId(),
                    record.getTask().getTaskType().name(),
                    record.getStatus(),
                    record.getStartedAt().orElse(null),
                    record.getCompletedAt().orElse(null),
                    record.getProcessingDurationMillis(),
                    record.getErrorMessage().orElse(null)));
        }
        return snapshot;
    }

    public QueueStatusResponse getQueueStatus() {
        int pending = executor.getQueue().size();
        int active = activeTaskCount.get();
        return new QueueStatusResponse(pending, active, acceptingTasks.get());
    }

    public TaskMetricsResponse getMetrics() {
        long completed = totalCompleted.get();
        long failed = totalFailed.get();
        long cancelled = totalCancelled.get();
        long processedForSuccessRate = completed + failed;
        double avgProcessing = completed == 0 ? 0.0 : (double) cumulativeProcessingTime.get() / completed;
        double successRate = processedForSuccessRate == 0 ? 0.0 : (double) completed / processedForSuccessRate;
        return new TaskMetricsResponse(
                completed,
                failed,
                cancelled,
                avgProcessing,
                successRate,
                processedForSuccessRate);
    }

    public CancellationResult cancelTask(UUID taskId) {
        TaskRecord record = taskRecords.get(taskId);
        if (record == null) {
            return CancellationResult.notFound();
        }
        TaskStatus status = record.getStatus();
        LoadTask task = record.getTask();
        LoadTaskProcessor processor = processors.get(task.getTaskType());
        if (status == TaskStatus.COMPLETED || status == TaskStatus.ERROR || status == TaskStatus.CANCELLED) {
            return CancellationResult.notCancellable(status);
        }

        Future<?> future = activeTasks.get(taskId);
        if (future != null) {
            boolean cancelled = future.cancel(true);
            if (cancelled && status == TaskStatus.QUEUED) {
                if (processor != null) {
                    processor.cancel(taskId);
                }
                record.markCancelled(Instant.now());
                totalCancelled.incrementAndGet();
                addToHistory(record);
                activeTasks.remove(taskId);
                log.info("Task {} cancelled while queued", taskId);
                return CancellationResult.cancelled(record.getStatus());
            } else if (cancelled) {
                if (processor != null) {
                    processor.cancel(taskId);
                }
                log.info("Task {} cancellation requested", taskId);
                return CancellationResult.cancellationRequested(record.getStatus());
            }
            return CancellationResult.notCancellable(record.getStatus());
        }

        if (status == TaskStatus.QUEUED) {
            if (processor != null) {
                processor.cancel(taskId);
            }
            record.markCancelled(Instant.now());
            totalCancelled.incrementAndGet();
            addToHistory(record);
            log.info("Task {} cancelled while queued", taskId);
            return CancellationResult.cancelled(record.getStatus());
        }

        return CancellationResult.cancellationRequested(record.getStatus());
    }

    public Set<String> getSupportedTaskTypes() {
        return processors.keySet().stream()
                .map(TaskType::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void shutdown() {
        if (acceptingTasks.compareAndSet(true, false)) {
            executor.shutdown();
        }
    }

    @PreDestroy
    void onShutdown() {
        shutdown();
    }

    public boolean isHealthy() {
        return acceptingTasks.get() && !executor.isShutdown();
    }

    private TaskStatusResponse toStatusResponse(TaskRecord record) {
        return new TaskStatusResponse(
                record.getTaskId(),
                record.getTask().getTaskType().name(),
                record.getStatus(),
                record.getSubmittedAt(),
                record.getStartedAt().orElse(null),
                record.getCompletedAt().orElse(null),
                record.getProcessingDurationMillis(),
                record.getErrorMessage().orElse(null));
    }

    public Collection<TaskStatusResponse> getAllTasks() {
        List<TaskStatusResponse> responses = new ArrayList<>();
        for (TaskRecord record : taskRecords.values()) {
            responses.add(toStatusResponse(record));
        }
        return responses;
    }

    public static class TaskSubmissionOutcome {
        private final UUID taskId;
        private final TaskStatus status;
        private final String message;

        public TaskSubmissionOutcome(UUID taskId, TaskStatus status, String message) {
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

    public static class CancellationResult {
        public enum CancellationState {
            CANCELLED,
            CANCELLATION_REQUESTED,
            NOT_FOUND,
            NOT_CANCELLABLE
        }

        private final CancellationState state;
        private final TaskStatus taskStatus;

        private CancellationResult(CancellationState state, TaskStatus taskStatus) {
            this.state = state;
            this.taskStatus = taskStatus;
        }

        public static CancellationResult cancelled(TaskStatus status) {
            return new CancellationResult(CancellationState.CANCELLED, status);
        }

        public static CancellationResult cancellationRequested(TaskStatus status) {
            return new CancellationResult(CancellationState.CANCELLATION_REQUESTED, status);
        }

        public static CancellationResult notFound() {
            return new CancellationResult(CancellationState.NOT_FOUND, null);
        }

        public static CancellationResult notCancellable(TaskStatus status) {
            return new CancellationResult(CancellationState.NOT_CANCELLABLE, status);
        }

        public CancellationState getState() {
            return state;
        }

        public TaskStatus getTaskStatus() {
            return taskStatus;
        }
    }
}
