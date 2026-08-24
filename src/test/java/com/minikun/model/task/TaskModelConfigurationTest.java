package com.minikun.model.task;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class TaskModelConfigurationTest {
    private final TaskModelConfiguration configuration = new TaskModelConfiguration();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void tinyGradUsesOllamaFailoverByDefault() {
        TaskModelProvider provider = provider("tinygrad", true);

        assertInstanceOf(FailoverTaskModelProvider.class, provider);
    }

    @Test
    void failoverCanBeDisabledForDiagnosis() {
        TaskModelProvider provider = provider("tinygrad", false);

        assertInstanceOf(OllamaTaskModelProvider.class, provider);
    }

    @Test
    void ollamaSelectionDoesNotCreateFailoverWrapper() {
        TaskModelProvider provider = provider("ollama", true);

        assertInstanceOf(OllamaTaskModelProvider.class, provider);
    }

    @Test
    void rejectsUnknownProvider() {
        assertThrows(IllegalArgumentException.class, () -> provider("unknown", true));
    }

    private TaskModelProvider provider(String selected, boolean failoverEnabled) {
        return configuration.taskModelProvider(
                objectMapper,
                selected,
                "http://127.0.0.1:11434",
                "qwen-task",
                Duration.ofSeconds(10),
                "http://127.0.0.1:8001/v1",
                "gemma-task",
                Duration.ofSeconds(20),
                failoverEnabled,
                Duration.ofSeconds(30));
    }
}
