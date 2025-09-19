package com.example.springload.service.processor.rest;

import com.example.springload.clients.HttpMethod;
import com.example.springload.clients.LoadHttpClient;
import com.example.springload.clients.Request;
import com.example.springload.clients.utils.JsonUtil;
import com.example.springload.dto.TaskSubmissionRequest;
import com.example.springload.model.TaskType;
import com.example.springload.service.processor.LoadTaskProcessor;
import com.example.springload.service.processor.executor.ClosedLoadExecutor;
import com.example.springload.service.processor.executor.OpenLoadExecutor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.springload.service.processor.metrics.LoadMetrics;
import com.example.springload.service.processor.metrics.LoadMetrics.ModelKind;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RestLoadTaskProcessor implements LoadTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(RestLoadTaskProcessor.class);

    private static final Duration DEFAULT_OPEN_DURATION = Duration.ofMinutes(1);
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
    private static final long SLEEP_CHUNK_MILLIS = 200L;

    private final Map<UUID, AtomicBoolean> cancellationTokens = new ConcurrentHashMap<>();

    @Override
    public TaskType supportedTaskType() {
        return TaskType.REST_LOAD;
    }

    @Override
    public void execute(TaskSubmissionRequest request) throws Exception {
        Objects.requireNonNull(request, "Task request must not be null");
        UUID taskId = UUID.fromString(request.getTaskId());
        AtomicBoolean cancelled = cancellationTokens.computeIfAbsent(taskId, key -> new AtomicBoolean(false));
        cancelled.set(false);

        RestLoadTaskDefinition definition = JsonUtil.mapper().convertValue(request.getData(), RestLoadTaskDefinition.class);
        validateDefinition(definition);

        RestTestSpec testSpec = definition.getTestSpec();
        ExecutionConfig execution = definition.getExecution();
        ThinkTimeStrategy thinkTime = ThinkTimeStrategy.from(execution.getThinkTime());
        LoadModelConfig loadModel = execution.getLoadModel();

        try (LoadHttpClient client = buildClient(testSpec)) {
            switch (loadModel.getType()) {
                case OPEN -> executeOpenModel(taskId, client, testSpec, thinkTime, loadModel, cancelled);
                case CLOSED -> executeClosedModel(taskId, client, testSpec, thinkTime, loadModel, cancelled);
                default -> throw new IllegalArgumentException("Unsupported load model type: " + loadModel.getType());
            }
        } finally {
            cancellationTokens.remove(taskId);
        }
    }

    @Override
    public void cancel(UUID taskId) {
        cancellationTokens.computeIfAbsent(taskId, key -> new AtomicBoolean(true)).set(true);
    }

    private LoadHttpClient buildClient(RestTestSpec testSpec) {
        GlobalConfig globalConfig = testSpec.getGlobalConfig();
        if (globalConfig == null || globalConfig.getBaseUrl() == null || globalConfig.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("testSpec.globalConfig.baseUrl must be provided");
        }
        TimeoutConfig timeouts = globalConfig.getTimeouts();
        int connTimeoutSeconds = toSeconds(timeouts != null ? timeouts.getConnectionTimeoutMs() : DEFAULT_CONNECTION_TIMEOUT_MS);
        int requestTimeoutSeconds = toSeconds(timeouts != null ? timeouts.getRequestTimeoutMs() : DEFAULT_REQUEST_TIMEOUT_MS);
        Map<String, String> headers = globalConfig.getHeaders();
        Map<String, String> vars = globalConfig.getVars();
        return new LoadHttpClient(globalConfig.getBaseUrl(), connTimeoutSeconds, requestTimeoutSeconds, headers, vars);
    }

    private void executeOpenModel(UUID taskId,
                                  LoadHttpClient client,
                                  RestTestSpec testSpec,
                                  ThinkTimeStrategy thinkTime,
                                  LoadModelConfig loadModel,
                                  AtomicBoolean cancelled) throws Exception {
        int maxConcurrent = Math.max(1, loadModel.getMaxConcurrent() != null ? loadModel.getMaxConcurrent() : 1);
        double rate = loadModel.getArrivalRatePerSec() != null ? loadModel.getArrivalRatePerSec() : 1.0;
        if (rate <= 0.0) {
            throw new IllegalArgumentException("OPEN load model requires arrivalRatePerSec > 0");
        }
        Duration duration = loadModel.getDuration() != null ? parseDuration(loadModel.getDuration()) : DEFAULT_OPEN_DURATION;
        int requestsPerIteration = countRequestsPerIteration(testSpec.getScenarios());

        long expectedIterations = Math.max(0, (long) Math.floor(duration.toMillis() / 1000.0 * rate));
        long expectedTotalRequests = expectedIterations * requestsPerIteration;
        Double expectedRps = rate * requestsPerIteration;

        LoadMetrics metrics = new LoadMetrics(new LoadMetrics.TaskConfig(
                taskId.toString(),
                supportedTaskType().name(),
                testSpec.getGlobalConfig() != null ? testSpec.getGlobalConfig().getBaseUrl() : "",
                ModelKind.OPEN,
                null,
                null,
                null,
                null,
                null,
                rate,
                duration,
                requestsPerIteration,
                expectedTotalRequests,
                expectedRps
        ), log);
        metrics.start();

        OpenLoadExecutor.OpenLoadParameters parameters =
                new OpenLoadExecutor.OpenLoadParameters(rate, maxConcurrent, duration);

        OpenLoadExecutor.OpenLoadResult result = OpenLoadExecutor.execute(
                taskId,
                parameters,
                cancelled::get,
                () -> {
                    try {
                        executeAllScenarios(client, testSpec.getScenarios(), thinkTime, cancelled, metrics);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                log);

        metrics.stopAndSummarize();

        log.info("Open load completed: launched={} completed={} cancelled={} rate={} maxConcurrent={} duration={}",
                result.launched(),
                result.completed(),
                result.cancelled(),
                rate,
                maxConcurrent,
                duration);
    }

    private void executeClosedModel(UUID taskId,
                                    LoadHttpClient client,
                                    RestTestSpec testSpec,
                                    ThinkTimeStrategy thinkTime,
                                    LoadModelConfig loadModel,
                                    AtomicBoolean cancelled) throws Exception {
        int users = Math.max(1, loadModel.getUsers() != null ? loadModel.getUsers() : 1);
        int iterations = Math.max(1, loadModel.getIterations() != null ? loadModel.getIterations() : 1);
        Duration rampUp = loadModel.getRampUp() != null ? parseDuration(loadModel.getRampUp()) : Duration.ZERO;
        Duration warmup = loadModel.getWarmup() != null ? parseDuration(loadModel.getWarmup()) : Duration.ZERO;
        Duration holdFor = loadModel.getHoldFor() != null ? parseDuration(loadModel.getHoldFor()) : Duration.ZERO;
        int requestsPerIteration = countRequestsPerIteration(testSpec.getScenarios());

        long expectedTotalRequests = (long) users * iterations * requestsPerIteration;

        // We do not compute a strict expected RPS for CLOSED model (depends on SUT latency and think times).
        LoadMetrics metrics = new LoadMetrics(new LoadMetrics.TaskConfig(
                taskId.toString(),
                supportedTaskType().name(),
                testSpec.getGlobalConfig() != null ? testSpec.getGlobalConfig().getBaseUrl() : "",
                ModelKind.CLOSED,
                users,
                iterations,
                warmup,
                rampUp,
                holdFor,
                null,
                null,
                requestsPerIteration,
                expectedTotalRequests,
                null
        ), log);
        metrics.start();

        ClosedLoadExecutor.ClosedLoadParameters parameters =
                new ClosedLoadExecutor.ClosedLoadParameters(users, iterations, warmup, rampUp, holdFor);

        ClosedLoadExecutor.ClosedLoadResult result = ClosedLoadExecutor.execute(
                taskId,
                parameters,
                cancelled::get,
                (userIndex, iteration) -> {
                    if (iteration == 0) {
                        metrics.recordUserStarted(userIndex);
                    }
                    metrics.recordUserProgress(userIndex, iteration);
                    executeAllScenarios(client, testSpec.getScenarios(), thinkTime, cancelled, metrics);
                    if (iteration == (iterations - 1)) {
                        metrics.recordUserCompleted(userIndex, iterations);
                    }
                },
                log);

        metrics.stopAndSummarize();

        log.info("Task {} closed load finished: usersCompleted={}/{} cancelled={} holdExpired={}",
                taskId,
                result.completedUsers(),
                result.totalUsers(),
                result.cancelled(),
                result.holdExpired());
    }

    private void executeAllScenarios(LoadHttpClient client,
                                     List<Scenario> scenarios,
                                     ThinkTimeStrategy thinkTime,
                                     AtomicBoolean cancelled,
                                     LoadMetrics metrics) throws InterruptedException {
        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalArgumentException("testSpec.scenarios must contain at least one scenario");
        }
        for (Scenario scenario : scenarios) {
            checkCancelled(cancelled);
            if (scenario.getRequests() == null || scenario.getRequests().isEmpty()) {
                log.warn("Scenario {} has no requests", scenario.getName());
                continue;
            }
            for (RequestSpec requestSpec : scenario.getRequests()) {
                checkCancelled(cancelled);
                Request request = toHttpRequest(requestSpec);
                try {
                    var response = client.execute(request);
                    metrics.recordRequestSuccess(response.getResponseTimeMs(), response.getStatusCode());
                } catch (RuntimeException ex) {
                    metrics.recordRequestFailure(ex);
                    throw ex;
                }
                if (thinkTime.isEnabled()) {
                    thinkTime.pause(cancelled);
                }
            }
        }
    }

    private int countRequestsPerIteration(List<Scenario> scenarios) {
        if (scenarios == null) return 0;
        int total = 0;
        for (Scenario s : scenarios) {
            if (s.getRequests() != null) {
                total += s.getRequests().size();
            }
        }
        return total;
    }

    private Request toHttpRequest(RequestSpec requestSpec) {
        if (requestSpec.getMethod() == null || requestSpec.getMethod().isBlank()) {
            throw new IllegalArgumentException("Request method is required");
        }
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(requestSpec.getMethod().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported HTTP method: " + requestSpec.getMethod(), ex);
        }
        Request request = new Request();
        request.setMethod(method);
        request.setPath(requestSpec.getPath());
        request.setHeaders(requestSpec.getHeaders());
        request.setQuery(requestSpec.getQuery());
        request.setBody(requestSpec.getBody());
        return request;
    }

    private void validateDefinition(RestLoadTaskDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("data.testSpec definition is required");
        }
        if (definition.getTestSpec() == null) {
            throw new IllegalArgumentException("testSpec payload is required");
        }
        if (definition.getExecution() == null || definition.getExecution().getLoadModel() == null) {
            throw new IllegalArgumentException("execution.loadModel must be defined");
        }
        if (definition.getTestSpec().getScenarios() == null || definition.getTestSpec().getScenarios().isEmpty()) {
            throw new IllegalArgumentException("testSpec.scenarios must be provided");
        }
    }

    private void checkCancelled(AtomicBoolean cancelled) throws InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Task cancelled");
        }
    }

    private void sleepWithCancellation(Duration duration, AtomicBoolean cancelled) throws InterruptedException {
        long remaining = duration.toMillis();
        while (remaining > 0) {
            checkCancelled(cancelled);
            long chunk = Math.min(SLEEP_CHUNK_MILLIS, remaining);
            TimeUnit.MILLISECONDS.sleep(chunk);
            remaining -= chunk;
        }
    }

    private void sleepInterval(long intervalNanos, AtomicBoolean cancelled) throws InterruptedException {
        long millis = TimeUnit.NANOSECONDS.toMillis(intervalNanos);
        int nanos = (int) (intervalNanos - TimeUnit.MILLISECONDS.toNanos(millis));
        if (millis > 0) {
            sleepWithCancellation(Duration.ofMillis(millis), cancelled);
        }
        if (nanos > 0) {
            checkCancelled(cancelled);
            TimeUnit.NANOSECONDS.sleep(nanos);
        }
    }

    private int toSeconds(Integer millis) {
        int value = millis != null ? millis : DEFAULT_CONNECTION_TIMEOUT_MS;
        return (int) Math.max(1, Math.ceil(value / 1000.0));
    }

    private Duration parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return Duration.ZERO;
        }
        String trimmed = value.trim().toLowerCase();
        if (trimmed.endsWith("ms")) {
            long ms = Long.parseLong(trimmed.substring(0, trimmed.length() - 2));
            return Duration.ofMillis(ms);
        }
        char unit = trimmed.charAt(trimmed.length() - 1);
        long amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException("Unrecognised duration unit in " + value);
        };
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RestLoadTaskDefinition {
        @JsonProperty("testSpec")
        private RestTestSpec testSpec;

        @JsonProperty("execution")
        private ExecutionConfig execution;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RestTestSpec {
        @JsonProperty("id")
        private String id;

        @JsonProperty("globalConfig")
        private GlobalConfig globalConfig;

        @JsonProperty("scenarios")
        private List<Scenario> scenarios;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GlobalConfig {
        @JsonProperty("baseUrl")
        private String baseUrl;

        @JsonProperty("headers")
        private Map<String, String> headers;

        @JsonProperty("vars")
        private Map<String, String> vars;

        @JsonProperty("timeouts")
        private TimeoutConfig timeouts;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TimeoutConfig {
        @JsonProperty("connectionTimeoutMs")
        private Integer connectionTimeoutMs;

        @JsonProperty("requestTimeoutMs")
        private Integer requestTimeoutMs;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ExecutionConfig {
        @JsonProperty("thinkTime")
        private ThinkTimeConfig thinkTime;

        @JsonProperty("loadModel")
        private LoadModelConfig loadModel;

        @JsonProperty("globalSla")
        private Map<String, Object> globalSla;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ThinkTimeConfig {
        @JsonProperty("type")
        private ThinkTimeType type;

        @JsonProperty("min")
        private Long min;

        @JsonProperty("max")
        private Long max;

        @JsonProperty("fixedMs")
        private Long fixedMs;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class LoadModelConfig {
        @JsonProperty("type")
        private LoadModelType type;

        @JsonProperty("arrivalRatePerSec")
        private Double arrivalRatePerSec;

        @JsonProperty("maxConcurrent")
        private Integer maxConcurrent;

        @JsonProperty("duration")
        private String duration;

        @JsonProperty("iterations")
        private Integer iterations;

        @JsonProperty("users")
        private Integer users;

        @JsonProperty("rampUp")
        private String rampUp;

        @JsonProperty("holdFor")
        private String holdFor;

        @JsonProperty("warmup")
        private String warmup;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Scenario {
        @JsonProperty("name")
        private String name;

        @JsonProperty("requests")
        private List<RequestSpec> requests;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RequestSpec {
        @JsonProperty("method")
        private String method;

        @JsonProperty("path")
        private String path;

        @JsonProperty("headers")
        private Map<String, String> headers;

        @JsonProperty("query")
        private Map<String, String> query;

        @JsonProperty("body")
        private Object body;
    }

    private enum LoadModelType {
        OPEN,
        CLOSED
    }

    private enum ThinkTimeType {
        NONE,
        FIXED,
        RANDOM
    }

    private static class ThinkTimeStrategy {
        private final ThinkTimeType type;
        private final long min;
        private final long max;
        private final long fixed;

        private ThinkTimeStrategy(ThinkTimeType type, long min, long max, long fixed) {
            this.type = type;
            this.min = min;
            this.max = max;
            this.fixed = fixed;
        }

        static ThinkTimeStrategy from(ThinkTimeConfig config) {
            if (config == null || config.getType() == null) {
                return new ThinkTimeStrategy(ThinkTimeType.NONE, 0, 0, 0);
            }
            return switch (config.getType()) {
                case NONE -> new ThinkTimeStrategy(ThinkTimeType.NONE, 0, 0, 0);
                case FIXED -> new ThinkTimeStrategy(ThinkTimeType.FIXED, 0, 0,
                        config.getFixedMs() != null ? config.getFixedMs() : 0);
                case RANDOM -> new ThinkTimeStrategy(ThinkTimeType.RANDOM,
                        config.getMin() != null ? config.getMin() : 0,
                        config.getMax() != null ? config.getMax() : 0,
                        0);
            };
        }

        boolean isEnabled() {
            return type != ThinkTimeType.NONE;
        }

        void pause(AtomicBoolean cancelled) throws InterruptedException {
            long delay = switch (type) {
                case NONE -> 0;
                case FIXED -> Math.max(0, fixed);
                case RANDOM -> computeRandomDelay();
            };
            if (delay > 0) {
                long remaining = delay;
                while (remaining > 0) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Task cancelled");
                    }
                    long chunk = Math.min(SLEEP_CHUNK_MILLIS, remaining);
                    TimeUnit.MILLISECONDS.sleep(chunk);
                    remaining -= chunk;
                }
            }
        }

        private long computeRandomDelay() {
            long lower = Math.max(0, min);
            long upper = Math.max(lower, max);
            if (upper == lower) {
                return lower;
            }
            return ThreadLocalRandom.current().nextLong(lower, upper + 1);
        }
    }
}
