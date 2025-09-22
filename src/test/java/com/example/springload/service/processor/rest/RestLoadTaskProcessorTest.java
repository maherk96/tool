package com.example.springload.service.processor.rest;

import com.example.springload.dto.TaskSubmissionRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestLoadTaskProcessorTest {

    private com.sun.net.httpserver.HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void executeClosedModelRunsScenario() throws Exception {
        AtomicInteger requestCounter = new AtomicInteger();
        server.createContext("/resource", exchange -> {
            requestCounter.incrementAndGet();
            byte[] response = "ok".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        RestLoadTaskProcessor processor = new RestLoadTaskProcessor();
        TaskSubmissionRequest request = buildClosedModelRequest();

        processor.execute(request);

        assertEquals(1, requestCounter.get());
    }

    @Test
    void cancelStopsOpenModel() throws Exception {
        AtomicInteger requestCounter = new AtomicInteger();
        server.createContext("/slow", exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            requestCounter.incrementAndGet();
            byte[] response = "ok".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        RestLoadTaskProcessor processor = new RestLoadTaskProcessor();
        TaskSubmissionRequest request = buildOpenModelRequest();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                processor.execute(request);
            } catch (Exception ignored) {
                // ignored for cancellation test
            }
        }, executor);

        try {
            TimeUnit.MILLISECONDS.sleep(150);
            processor.cancel(UUID.fromString(request.getTaskId()));

            future.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertTrue(requestCounter.get() > 0, "Requests should have started before cancellation");
    }

    @Test
    void closedModelStopsEarlyOnConnectionFailure() throws Exception {
        // Use a reserved TEST-NET-1 address that should be unroutable to trigger quick connect failures
        String badBaseUrl = "http://192.0.2.1"; // RFC 5737 TEST-NET-1

        RestLoadTaskProcessor processor = new RestLoadTaskProcessor();
        TaskSubmissionRequest request = new TaskSubmissionRequest();
        request.setTaskId(UUID.randomUUID().toString());
        request.setTaskType("REST_LOAD");
        request.setCreatedAt(java.time.Instant.now());

        Map<String, Object> testSpec = new HashMap<>();
        Map<String, Object> globalConfig = new HashMap<>();
        globalConfig.put("baseUrl", badBaseUrl);
        globalConfig.put("headers", Map.of());
        globalConfig.put("vars", Map.of());
        // keep small timeouts to make the test fast
        globalConfig.put("timeouts", Map.of("connectionTimeoutMs", 200, "requestTimeoutMs", 300));
        testSpec.put("id", "closed-failure-test");
        testSpec.put("globalConfig", globalConfig);
        Map<String, Object> req1 = new HashMap<>();
        req1.put("method", "GET");
        req1.put("path", "/posts/1");
        Map<String, Object> req2 = new HashMap<>();
        req2.put("method", "GET");
        req2.put("path", "/users/1");
        testSpec.put("scenarios", List.of(Map.of("name", "scenario", "requests", List.of(req1, req2))));

        Map<String, Object> thinkTime = Map.of("type", "RANDOM", "min", 50, "max", 100);
        Map<String, Object> loadModel = Map.of(
                "type", "CLOSED",
                "iterations", 100,
                "users", 4,
                "rampUp", "0s",
                "holdFor", "10s",
                "warmup", "0s");

        Map<String, Object> execution = Map.of(
                "thinkTime", thinkTime,
                "loadModel", loadModel,
                // SLA config currently not enforced; included for parity with provided example
                "globalSla", Map.of("errorRatePct", 5.0, "p95LtMs", 1200, "onError", Map.of("action", "STOP")));

        request.setData(Map.of("testSpec", testSpec, "execution", execution));

        long start = System.nanoTime();
        boolean threw = false;
        try {
            processor.execute(request);
        } catch (Exception ex) {
            threw = true;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // We expect an early failure (exception thrown), not waiting to finish all iterations or holdFor window
        assertTrue(threw, "Closed model should throw on connection failure");
        assertTrue(elapsedMs < 3000, "Execution should stop early on failure (elapsed=" + elapsedMs + "ms)");
    }

    private TaskSubmissionRequest buildClosedModelRequest() {
        UUID taskId = UUID.randomUUID();
        TaskSubmissionRequest request = new TaskSubmissionRequest();
        request.setTaskId(taskId.toString());
        request.setTaskType("REST_LOAD");
        request.setCreatedAt(java.time.Instant.now());
        request.setData(buildClosedModelData());
        return request;
    }

    private Map<String, Object> buildClosedModelData() {
        Map<String, Object> testSpec = new HashMap<>();
        Map<String, Object> globalConfig = new HashMap<>();
        globalConfig.put("baseUrl", baseUrl);
        globalConfig.put("headers", Map.of());
        globalConfig.put("vars", Map.of());
        globalConfig.put("timeouts", Map.of("connectionTimeoutMs", 1000, "requestTimeoutMs", 1000));
        testSpec.put("id", "closed-model-test");
        testSpec.put("globalConfig", globalConfig);
        Map<String, Object> request = new HashMap<>();
        request.put("method", "GET");
        request.put("path", "/resource");
        request.put("headers", Map.of());
        request.put("query", Map.of());
        request.put("body", Map.of());
        testSpec.put("scenarios", List.of(Map.of("name", "scenario", "requests", List.of(request))));

        Map<String, Object> thinkTime = Map.of("type", "NONE");
        Map<String, Object> loadModel = Map.of(
                "type", "CLOSED",
                "iterations", 1,
                "users", 1,
                "rampUp", "0s",
                "holdFor", "0s",
                "warmup", "0s");

        Map<String, Object> execution = Map.of(
                "thinkTime", thinkTime,
                "loadModel", loadModel);

        return Map.of(
                "testSpec", testSpec,
                "execution", execution);
    }

    private TaskSubmissionRequest buildOpenModelRequest() {
        UUID taskId = UUID.randomUUID();
        TaskSubmissionRequest request = new TaskSubmissionRequest();
        request.setTaskId(taskId.toString());
        request.setTaskType("REST_LOAD");
        request.setCreatedAt(java.time.Instant.now());
        request.setData(buildOpenModelData());
        return request;
    }

    private Map<String, Object> buildOpenModelData() {
        Map<String, Object> testSpec = new HashMap<>();
        Map<String, Object> globalConfig = new HashMap<>();
        globalConfig.put("baseUrl", baseUrl);
        globalConfig.put("headers", Map.of());
        globalConfig.put("vars", Map.of());
        globalConfig.put("timeouts", Map.of("connectionTimeoutMs", 1000, "requestTimeoutMs", 1000));
        testSpec.put("id", "open-model-test");
        testSpec.put("globalConfig", globalConfig);
        Map<String, Object> request = new HashMap<>();
        request.put("method", "GET");
        request.put("path", "/slow");
        request.put("headers", Map.of());
        request.put("query", Map.of());
        request.put("body", Map.of());
        testSpec.put("scenarios", List.of(Map.of("name", "scenario", "requests", List.of(request))));

        Map<String, Object> thinkTime = Map.of("type", "NONE");
        Map<String, Object> loadModel = Map.of(
                "type", "OPEN",
                "arrivalRatePerSec", 5.0,
                "maxConcurrent", 2,
                "duration", "5s");

        Map<String, Object> execution = Map.of(
                "thinkTime", thinkTime,
                "loadModel", loadModel);

        return Map.of(
                "testSpec", testSpec,
                "execution", execution);
    }
}
