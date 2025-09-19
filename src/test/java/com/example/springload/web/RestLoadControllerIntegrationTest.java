package com.example.springload.web;

import com.example.springload.model.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class RestLoadControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private HttpServer server;
    private String baseUrl;
    private AtomicInteger requestCounter;

    @BeforeEach
    void setUp() throws IOException {
        requestCounter = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/resource", this::handleRequest);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void openModelCompletesEndToEnd() throws Exception {
        String payload = buildOpenPayload(Duration.ofMillis(400), 15.0, 3);
        UUID taskId = submitTask(payload);

        TaskStatus finalStatus = waitForStatus(taskId, TaskStatus.COMPLETED, Duration.ofSeconds(10));
        assertEquals(TaskStatus.COMPLETED, finalStatus);
        assertTrue(requestCounter.get() > 0, "Expected at least one HTTP call to mock server");
    }

    @Test
    void openModelCancellationEndToEnd() throws Exception {
        String payload = buildOpenPayload(Duration.ofSeconds(5), 30.0, 4);
        UUID taskId = submitTask(payload);

        waitUntilStatusIn(taskId, Duration.ofSeconds(5), TaskStatus.PROCESSING);
        TimeUnit.MILLISECONDS.sleep(200);

        mockMvc.perform(delete("/api/tasks/{taskId}", taskId))
                .andReturn();

        TaskStatus finalStatus = waitForStatus(taskId, TaskStatus.CANCELLED, Duration.ofSeconds(10));
        assertEquals(TaskStatus.CANCELLED, finalStatus);
        assertTrue(requestCounter.get() > 0, "Cancellation should occur after some HTTP traffic");
    }

    @Test
    void closedModelCompletesEndToEnd() throws Exception {
        String payload = buildClosedPayload(2, 2, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        UUID taskId = submitTask(payload);

        TaskStatus finalStatus = waitForStatus(taskId, TaskStatus.COMPLETED, Duration.ofSeconds(10));
        assertEquals(TaskStatus.COMPLETED, finalStatus);
        assertThat(requestCounter.get()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void closedModelCancellationEndToEnd() throws Exception {
        String payload = buildClosedPayload(3, 100, Duration.ZERO, Duration.ofSeconds(30), Duration.ZERO);
        UUID taskId = submitTask(payload);

        waitUntilStatusIn(taskId, Duration.ofSeconds(5), TaskStatus.PROCESSING);
        TimeUnit.MILLISECONDS.sleep(200);

        mockMvc.perform(delete("/api/tasks/{taskId}", taskId))
                .andReturn();

        TaskStatus finalStatus = waitForStatus(taskId, TaskStatus.CANCELLED, Duration.ofSeconds(10));
        assertEquals(TaskStatus.CANCELLED, finalStatus);
        assertTrue(requestCounter.get() > 0, "Expected some iterations before cancellation");
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            requestCounter.incrementAndGet();
            TimeUnit.MILLISECONDS.sleep(10);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private UUID submitTask(String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 202, "Expected 200 or 202 from submission but got " + status);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("taskId").asText());
    }

    private TaskStatus waitForStatus(UUID taskId, TaskStatus expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        TaskStatus last = null;
        while (Instant.now().isBefore(deadline)) {
            TaskStatus current = fetchStatus(taskId);
            if (current != null) {
                last = current;
                if (current == expected) {
                    return current;
                }
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("Timed out waiting for status " + expected + " (last=" + last + ")");
    }

    private TaskStatus waitUntilStatusIn(UUID taskId, Duration timeout, TaskStatus... statuses) throws Exception {
        EnumSet<TaskStatus> accepted = EnumSet.noneOf(TaskStatus.class);
        for (TaskStatus status : statuses) {
            accepted.add(status);
        }
        Instant deadline = Instant.now().plus(timeout);
        TaskStatus last = null;
        while (Instant.now().isBefore(deadline)) {
            TaskStatus current = fetchStatus(taskId);
            if (current != null) {
                last = current;
                if (accepted.contains(current)) {
                    return current;
                }
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("Timed out waiting for status in " + accepted + " (last=" + last + ")");
    }

    private TaskStatus fetchStatus(UUID taskId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/tasks/{taskId}", taskId)).andReturn();
        int status = result.getResponse().getStatus();
        if (status == 404) {
            return null;
        }
        assertEquals(200, status);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return TaskStatus.valueOf(body.get("status").asText());
    }

    private String buildOpenPayload(Duration duration, double rate, int maxConcurrent) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("taskType", "REST_LOAD");
        ObjectNode data = root.putObject("data");
        populateTestSpec(data);
        ObjectNode execution = data.putObject("execution");
        execution.putObject("thinkTime").put("type", "NONE");
        ObjectNode loadModel = execution.putObject("loadModel");
        loadModel.put("type", "OPEN");
        loadModel.put("arrivalRatePerSec", rate);
        loadModel.put("maxConcurrent", maxConcurrent);
        loadModel.put("duration", formatDuration(duration));
        return objectMapper.writeValueAsString(root);
    }

    private String buildClosedPayload(int users,
                                      int iterations,
                                      Duration warmup,
                                      Duration holdFor,
                                      Duration rampUp) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("taskType", "REST_LOAD");
        ObjectNode data = root.putObject("data");
        populateTestSpec(data);
        ObjectNode execution = data.putObject("execution");
        execution.putObject("thinkTime").put("type", "NONE");
        ObjectNode loadModel = execution.putObject("loadModel");
        loadModel.put("type", "CLOSED");
        loadModel.put("iterations", iterations);
        loadModel.put("users", users);
        loadModel.put("warmup", formatDuration(warmup));
        loadModel.put("holdFor", formatDuration(holdFor));
        loadModel.put("rampUp", formatDuration(rampUp));
        return objectMapper.writeValueAsString(root);
    }

    private void populateTestSpec(ObjectNode data) {
        ObjectNode testSpec = data.putObject("testSpec");
        testSpec.put("id", "integration-rest-test");
        ObjectNode global = testSpec.putObject("globalConfig");
        global.put("baseUrl", baseUrl);
        ObjectNode headers = global.putObject("headers");
        headers.put("Content-Type", "application/json");
        global.putObject("vars");
        ObjectNode timeouts = global.putObject("timeouts");
        timeouts.put("connectionTimeoutMs", 500);
        timeouts.put("requestTimeoutMs", 500);
        var scenarios = testSpec.putArray("scenarios");
        ObjectNode scenario = scenarios.addObject();
        scenario.put("name", "basic");
        var requests = scenario.putArray("requests");
        ObjectNode request = requests.addObject();
        request.put("method", "GET");
        request.put("path", "/resource");
        request.set("headers", objectMapper.createObjectNode());
        request.set("query", objectMapper.createObjectNode());
        request.putNull("body");
    }

    private String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis <= 0) {
            return "0s";
        }
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return millis + "ms";
    }
}
