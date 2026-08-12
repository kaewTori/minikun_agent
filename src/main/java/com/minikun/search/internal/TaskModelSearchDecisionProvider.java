package com.minikun.search.internal;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;
import com.minikun.search.SearchDecisionClient;
import com.minikun.search.SearchDecisionClientException;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionPrompt;
import com.minikun.search.model.SearchDecisionReason;

final class TaskModelSearchDecisionProvider implements SearchDecisionClient {
    private final TaskModelProvider taskModelProvider;
    private final ObjectMapper objectMapper;

    TaskModelSearchDecisionProvider(TaskModelProvider taskModelProvider, ObjectMapper objectMapper) {
        this.taskModelProvider = Objects.requireNonNull(taskModelProvider, "taskModelProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public SearchDecision classify(SearchDecisionPrompt prompt) {
        try {
            String response = taskModelProvider.generate(new TaskModelRequest(
                    List.of(new TaskModelMessage("system", prompt.instructions()),
                            new TaskModelMessage("user", "/no_think\ncurrentDate: %s\nuserMessage: %s"
                                    .formatted(prompt.currentDate(), prompt.userMessage()))),
                    256, 0.0, TaskModelRequest.ResponseFormat.JSON_OBJECT));
            JsonNode root = objectMapper.readTree(response);
            validateSchema(root);
            return new SearchDecision(root.get("shouldSearch").booleanValue(),
                    prompt.userMessage(), SearchDecisionReason.valueOf(root.get("reason").textValue()));
        } catch (SearchDecisionClientException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new SearchDecisionClientException("search decision provider failed", exception);
        }
    }

    private void validateSchema(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != 2
                || !root.has("shouldSearch") || !root.has("reason")
                || !root.get("shouldSearch").isBoolean() || !root.get("reason").isTextual()) {
            throw new SearchDecisionClientException("search decision response has invalid fields", null);
        }
        SearchDecisionReason reason;
        try {
            reason = SearchDecisionReason.valueOf(root.get("reason").textValue());
        } catch (IllegalArgumentException exception) {
            throw new SearchDecisionClientException("search decision response has an invalid reason", exception);
        }
        if (reason == SearchDecisionReason.RULE_FALLBACK) {
            throw new SearchDecisionClientException("search decision response contains an internal reason", null);
        }
    }
}