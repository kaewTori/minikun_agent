package com.minikun.task;

public enum TaskKind {
    TASK,
    GOAL;

    public static TaskKind parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.isBlank() ? TASK : valueOf(normalized);
    }
}
