package com.minikun.task;

public enum TaskStatus {
    OPEN,
    IN_PROGRESS,
    BLOCKED,
    DONE,
    CANCELLED;

    public boolean active() {
        return this != DONE && this != CANCELLED;
    }

    public static TaskStatus parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.isBlank() ? OPEN : valueOf(normalized);
    }
}
