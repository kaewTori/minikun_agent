package com.minikun.computer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One fixed argv workflow owned by application configuration, never by the model. */
public record ComputerWorkflowDefinition(String id, String description, List<String> command) {
    public ComputerWorkflowDefinition {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")
                || description == null || description.isBlank() || command == null || command.isEmpty()) {
            throw new IllegalArgumentException("computer workflow requires id, description, and command");
        }
        description = description.trim();
        command = List.copyOf(command);
        if (command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("computer workflow command parts must not be blank");
        }
    }

    static List<ComputerWorkflowDefinition> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<ComputerWorkflowDefinition> result = new ArrayList<>();
        for (String entry : value.split(";")) {
            String[] fields = entry.split("\\|", -1);
            if (fields.length < 3) {
                throw new IllegalArgumentException(
                        "computer workflows must use id|description|executable|arg1 format");
            }
            result.add(new ComputerWorkflowDefinition(fields[0].trim(), fields[1].trim(),
                    Arrays.stream(fields).skip(2).map(String::trim).toList()));
        }
        return List.copyOf(result);
    }
}
