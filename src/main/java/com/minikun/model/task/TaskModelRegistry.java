package com.minikun.model.task;

import java.util.Map;
import java.util.Objects;

public final class TaskModelRegistry {
    private final Map<TaskModelId, TaskModelProvider> providers;

    public TaskModelRegistry(Map<TaskModelId, TaskModelProvider> providers) {
        this.providers = Map.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
    }

    public TaskModelProvider provider(TaskModelId id) {
        Objects.requireNonNull(id, "id must not be null");
        TaskModelProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("task model provider is not configured: " + id);
        }
        return provider;
    }
}