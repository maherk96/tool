package com.example.springload.dto;

import java.time.Duration;
import java.util.Map;

public class TaskLiveMetricsResponse {
    public enum ModelKind { OPEN, CLOSED }

    private String taskId;
    private String taskType;
    private String baseUrl;
    private ModelKind model;
    private Integer users;
    private Integer iterationsPerUser;
    private Duration warmup;
    private Duration rampUp;
    private Duration holdFor;
    private Double arrivalRatePerSec;
    private Duration duration;
    private int requestsPerIteration;
    private long expectedTotalRequests;
    private Double expectedRps;

    private int usersStarted;
    private int usersCompleted;
    private long totalRequests;
    private long totalErrors;
    private Double achievedRps;
    private Long latencyMinMs;
    private Long latencyAvgMs;
    private Long latencyMaxMs;
    private Map<Integer, Integer> activeUserIterations;

    public TaskLiveMetricsResponse() {}

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public ModelKind getModel() { return model; }
    public void setModel(ModelKind model) { this.model = model; }
    public Integer getUsers() { return users; }
    public void setUsers(Integer users) { this.users = users; }
    public Integer getIterationsPerUser() { return iterationsPerUser; }
    public void setIterationsPerUser(Integer iterationsPerUser) { this.iterationsPerUser = iterationsPerUser; }
    public Duration getWarmup() { return warmup; }
    public void setWarmup(Duration warmup) { this.warmup = warmup; }
    public Duration getRampUp() { return rampUp; }
    public void setRampUp(Duration rampUp) { this.rampUp = rampUp; }
    public Duration getHoldFor() { return holdFor; }
    public void setHoldFor(Duration holdFor) { this.holdFor = holdFor; }
    public Double getArrivalRatePerSec() { return arrivalRatePerSec; }
    public void setArrivalRatePerSec(Double arrivalRatePerSec) { this.arrivalRatePerSec = arrivalRatePerSec; }
    public Duration getDuration() { return duration; }
    public void setDuration(Duration duration) { this.duration = duration; }
    public int getRequestsPerIteration() { return requestsPerIteration; }
    public void setRequestsPerIteration(int requestsPerIteration) { this.requestsPerIteration = requestsPerIteration; }
    public long getExpectedTotalRequests() { return expectedTotalRequests; }
    public void setExpectedTotalRequests(long expectedTotalRequests) { this.expectedTotalRequests = expectedTotalRequests; }
    public Double getExpectedRps() { return expectedRps; }
    public void setExpectedRps(Double expectedRps) { this.expectedRps = expectedRps; }
    public int getUsersStarted() { return usersStarted; }
    public void setUsersStarted(int usersStarted) { this.usersStarted = usersStarted; }
    public int getUsersCompleted() { return usersCompleted; }
    public void setUsersCompleted(int usersCompleted) { this.usersCompleted = usersCompleted; }
    public long getTotalRequests() { return totalRequests; }
    public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }
    public long getTotalErrors() { return totalErrors; }
    public void setTotalErrors(long totalErrors) { this.totalErrors = totalErrors; }
    public Double getAchievedRps() { return achievedRps; }
    public void setAchievedRps(Double achievedRps) { this.achievedRps = achievedRps; }
    public Long getLatencyMinMs() { return latencyMinMs; }
    public void setLatencyMinMs(Long latencyMinMs) { this.latencyMinMs = latencyMinMs; }
    public Long getLatencyAvgMs() { return latencyAvgMs; }
    public void setLatencyAvgMs(Long latencyAvgMs) { this.latencyAvgMs = latencyAvgMs; }
    public Long getLatencyMaxMs() { return latencyMaxMs; }
    public void setLatencyMaxMs(Long latencyMaxMs) { this.latencyMaxMs = latencyMaxMs; }
    public Map<Integer, Integer> getActiveUserIterations() { return activeUserIterations; }
    public void setActiveUserIterations(Map<Integer, Integer> activeUserIterations) { this.activeUserIterations = activeUserIterations; }
}

