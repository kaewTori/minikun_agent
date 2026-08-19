package com.minikun.guardian;

import java.util.ArrayList;
import java.util.List;

/** One fixed remediation action parsed from application-owned configuration. */
public record GuardianActionDefinition(String id, String description, List<String> command) {
    public GuardianActionDefinition {
        if (id == null || id.isBlank() || description == null || description.isBlank()
                || command == null || command.isEmpty()) {
            throw new IllegalArgumentException("guardian action requires id, description, and command");
        }
        id = id.trim();
        description = description.trim();
        command = List.copyOf(command);
        if (command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("guardian action command parts must not be blank");
        }
    }

    static List<GuardianActionDefinition> parseList(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<GuardianActionDefinition> result = new ArrayList<>();
        for (String entry : value.split(";")) {
            String[] fields = entry.split("\\|", -1);
            if (fields.length < 3) {
                throw new IllegalArgumentException(
                        "guardian actions must use id|description|executable|arg1 format");
            }
            result.add(new GuardianActionDefinition(fields[0], fields[1],
                    java.util.Arrays.stream(fields).skip(2).map(String::trim).toList()));
        }
        return List.copyOf(result);
    }
}
