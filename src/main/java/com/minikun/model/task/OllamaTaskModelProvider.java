package com.minikun.model.task;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

public final class OllamaTaskModelProvider implements TaskModelProvider {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final java.time.Duration timeout;

    public OllamaTaskModelProvider(RestClient restClient, ObjectMapper objectMapper,
            String model, java.time.Duration timeout) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public String generate(TaskModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            String response = CompletableFuture.supplyAsync(() -> call(request))
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .get();
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("task model returned an empty response");
            }
            String normalized = response.trim();
            return request.responseFormat() == TaskModelRequest.ResponseFormat.JSON_OBJECT
                    ? normalizeJsonObject(normalized) : normalized;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task model generation was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() instanceof CompletionException completion
                    ? completion.getCause() : exception.getCause();
            if (cause instanceof TimeoutException) {
                throw new IllegalStateException("task model generation timed out", cause);
            }
            throw new IllegalStateException("task model generation failed", cause);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("task model generation failed", exception);
        }
    }

    private String normalizeJsonObject(String response) {
        JsonNode direct = readObject(response);
        if (direct != null) return response;

        String candidate = response;
        if (candidate.startsWith("```")) {
            int firstLine = candidate.indexOf('\n');
            int closingFence = candidate.lastIndexOf("```");
            if (firstLine >= 0 && closingFence > firstLine) {
                candidate = candidate.substring(firstLine + 1, closingFence).strip();
            }
        }
        if (readObject(candidate) != null) return candidate;

        int firstObject = candidate.indexOf('{');
        int lastObject = candidate.lastIndexOf('}');
        if (firstObject >= 0 && lastObject > firstObject) {
            String embedded = candidate.substring(firstObject, lastObject + 1).strip();
            if (readObject(embedded) != null) return embedded;
        }
        throw new IllegalStateException("task model did not return a valid JSON object");
    }

    private JsonNode readObject(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            return node != null && node.isObject() ? node : null;
        } catch (java.io.IOException exception) {
            return null;
        }
    }

    private String call(TaskModelRequest request) {
        Response response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(model, request.messages(), false, request.maxOutputTokens(),
                        request.temperature(), responseFormat(request.responseFormat())))
                .retrieve()
                .body(Response.class);
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new IllegalStateException("task model response has no message");
        }
        String content = response.choices().getFirst().message().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("task model response has empty content");
        }
        return content;
    }

    private ResponseFormat responseFormat(TaskModelRequest.ResponseFormat format) {
        return format == TaskModelRequest.ResponseFormat.JSON_OBJECT
                ? new ResponseFormat("json_object") : null;
    }

    private record Request(String model, List<TaskModelMessage> messages, boolean stream,
            int max_tokens, double temperature, ResponseFormat response_format) {}

    private record Response(List<Choice> choices) {}

    private record Choice(Message message) {}

    private record Message(String role, String content) {}

    private record ResponseFormat(String type) {}
}
