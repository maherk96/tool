package com.example.springload.dto;

public class QueueStatusResponse {

    private final int queueSize;
    private final int activeTasks;
    private final boolean acceptingTasks;

    public QueueStatusResponse(int queueSize, int activeTasks, boolean acceptingTasks) {
        this.queueSize = queueSize;
        this.activeTasks = activeTasks;
        this.acceptingTasks = acceptingTasks;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public int getActiveTasks() {
        return activeTasks;
    }

    public boolean isAcceptingTasks() {
        return acceptingTasks;
    }
}
