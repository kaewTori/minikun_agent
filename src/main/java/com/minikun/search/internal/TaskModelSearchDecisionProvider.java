package com.minikun.search.internal;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
    private static final Set<SearchDecisionReason> MODEL_REASONS = Set.of(
            SearchDecisionReason.CURRENT_INFORMATION,
            SearchDecisionReason.FACT_LOOKUP,
            SearchDecisionReason.EXTERNAL_RESOURCE,
            SearchDecisionReason.GENERAL_KNOWLEDGE);
    private final TaskModelProvider taskModelProvider;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    TaskModelSearchDecisionProvider(TaskModelProvider taskModelProvider, ObjectMapper objectMapper) {
        this(taskModelProvider, objectMapper, Duration.ofSeconds(5));
    }

    TaskModelSearchDecisionProvider(
            TaskModelProvider taskModelProvider, ObjectMapper objectMapper, Duration timeout) {
        this.taskModelProvider = Objects.requireNonNull(taskModelProvider, "taskModelProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    @Override
    public SearchDecision classify(SearchDecisionPrompt prompt) {
        try {
            TaskModelRequest request = new TaskModelRequest(
                    List.of(new TaskModelMessage("system", prompt.instructions()),
                            new TaskModelMessage("user", "/no_think\ncurrentDate: %s\nuserMessage: %s"
                                    .formatted(prompt.currentDate(), prompt.userMessage()))),
                    256, 0.0, TaskModelRequest.ResponseFormat.JSON_OBJECT);
            String response;
            try {
                response = CompletableFuture.supplyAsync(() -> taskModelProvider.generate(request))
                        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .get();
            } catch (ExecutionException exception) {
                throw new SearchDecisionClientException("search decision timed out", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SearchDecisionClientException("search decision interrupted", exception);
            }
            JsonNode root = objectMapper.readTree(response);
            validateSchema(root);
            SearchDecisionReason reason = SearchDecisionReason.valueOf(root.get("reason").textValue());
            boolean reasonRequiresSearch = reason != SearchDecisionReason.GENERAL_KNOWLEDGE;
            return new SearchDecision(reasonRequiresSearch, prompt.userMessage(), reason);
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
        if (!MODEL_REASONS.contains(reason)) {
            throw new SearchDecisionClientException("search decision response contains a disallowed reason", null);
        }
    }
}
