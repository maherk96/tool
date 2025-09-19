package com.example.springload.model;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum TaskType {
    REST_LOAD,
    FIX_LOAD,
    GRPC_LOAD,
    WEBSOCKET_LOAD,
    CUSTOM;

    public static TaskType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported task type: " + value));
    }

    public static Set<String> asStrings() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
