package com.minikun.memory.internal;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryException;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;

final class TaskModelReflectionProvider implements ReflectionClient {
    private final TaskModelProvider taskModelProvider;
    private final ObjectMapper objectMapper;

    TaskModelReflectionProvider(TaskModelProvider taskModelProvider, ObjectMapper objectMapper) {
        this.taskModelProvider = taskModelProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public String reflect(ReflectionPrompt prompt) {
        String response = taskModelProvider.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("user", prompt.content())), 384, 0.0,
                TaskModelRequest.ResponseFormat.JSON_OBJECT));
        String normalized = normalizeContent(response);
        if (normalized.isBlank()) {
            throw new MemoryException("reflection provider returned an empty response");
        }
        return normalized;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.strip();
        if (normalized.startsWith("<think>")) {
            int closingTag = normalized.indexOf("</think>", "<think>".length());
            if (closingTag >= 0) {
                normalized = normalized.substring(closingTag + "</think>".length()).strip();
            }
        }
        try {
            JsonNode root = objectMapper.readTree(normalized);
            return root != null && root.isObject() ? root.toString() : normalized;
        } catch (Exception exception) {
            return normalized;
        }
    }
}