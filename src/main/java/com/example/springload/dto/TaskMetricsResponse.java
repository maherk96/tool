package com.example.springload.dto;

public class TaskMetricsResponse {

    private final long totalCompleted;
    private final long totalFailed;
    private final long totalCancelled;
    private final double averageProcessingTimeMillis;
    private final double successRate;
    private final long totalProcessed;

    public TaskMetricsResponse(
            long totalCompleted,
            long totalFailed,
            long totalCancelled,
            double averageProcessingTimeMillis,
            double successRate,
            long totalProcessed) {
        this.totalCompleted = totalCompleted;
        this.totalFailed = totalFailed;
        this.totalCancelled = totalCancelled;
        this.averageProcessingTimeMillis = averageProcessingTimeMillis;
        this.successRate = successRate;
        this.totalProcessed = totalProcessed;
    }

    public long getTotalCompleted() {
        return totalCompleted;
    }

    public long getTotalFailed() {
        return totalFailed;
    }

    public long getTotalCancelled() {
        return totalCancelled;
    }

    public double getAverageProcessingTimeMillis() {
        return averageProcessingTimeMillis;
    }

    public double getSuccessRate() {
        return successRate;
    }

    public long getTotalProcessed() {
        return totalProcessed;
    }
}
