package com.example.springload.service.processor;

import com.example.springload.dto.TaskSubmissionRequest;
import com.example.springload.model.TaskType;
import java.util.UUID;

public interface LoadTaskProcessor {

    TaskType supportedTaskType();

    void execute(TaskSubmissionRequest request) throws Exception;

    void cancel(UUID taskId);
}
