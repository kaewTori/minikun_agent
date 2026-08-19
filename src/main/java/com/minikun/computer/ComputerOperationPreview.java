package com.minikun.computer;

import java.util.Map;

public record ComputerOperationPreview(String operation, String summary, Map<String, Object> executionArguments) {
    public ComputerOperationPreview {
        executionArguments = executionArguments == null ? Map.of() : Map.copyOf(executionArguments);
    }
}
