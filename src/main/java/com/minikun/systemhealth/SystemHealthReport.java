package com.minikun.systemhealth;

import java.util.LinkedHashMap;
import java.util.Map;

/** Sanitized, model-facing host health snapshot. */
public record SystemHealthReport(
        String status,
        boolean healthy,
        Map<String, Object> cpu,
        Map<String, Object> memory,
        Map<String, Object> swap,
        Map<String, Object> disk,
        Map<String, Object> jvm,
        Map<String, Object> process,
        Map<String, Map<String, Object>> dependencies) {

    public SystemHealthReport {
        cpu = immutableMap(cpu);
        memory = immutableMap(memory);
        swap = immutableMap(swap);
        disk = immutableMap(disk);
        jvm = immutableMap(jvm);
        process = immutableMap(process);
        dependencies = immutableNestedMap(dependencies);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        return Map.copyOf(value == null ? Map.of() : value);
    }

    private static Map<String, Map<String, Object>> immutableNestedMap(
            Map<String, Map<String, Object>> value) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        if (value != null) {
            value.forEach((key, entry) -> copy.put(key, immutableMap(entry)));
        }
        return Map.copyOf(copy);
    }
}
