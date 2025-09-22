package com.example.springload.web;

import com.example.springload.dto.TaskSubmissionRequest;
import com.example.springload.model.LoadTask;
import com.example.springload.model.TaskType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface TaskMapper {

    @Mapping(target = "id", expression = "java(UUID.randomUUID())")
    @Mapping(target = "createdAt", expression = "java(Instant.now())")
    @Mapping(target = "taskType", source = "taskType", qualifiedByName = "mapTaskType")
    LoadTask toDomain(TaskSubmissionRequest request);

    @Named("mapTaskType")
    default TaskType mapTaskType(String taskType) {
        return TaskType.fromValue(taskType);
    }
}