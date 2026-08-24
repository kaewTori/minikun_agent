package com.minikun.model.task;

import java.util.List;
import java.util.Objects;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.ModelProviderException;

public final class OllamaTaskModelProvider implements TaskModelProvider {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final java.time.Duration timeout;
    private final String priority;

    public OllamaTaskModelProvider(RestClient restClient, ObjectMapper objectMapper,
            String model, java.time.Duration timeout) {
        this(restClient, objectMapper, model, timeout, null);
    }

    public OllamaTaskModelProvider(RestClient restClient, ObjectMapper objectMapper,
            String model, java.time.Duration timeout, String priority) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.priority = priority;
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
            String response = call(request);
            if (response == null || response.isBlank()) {
                throw new ModelProviderException("task model returned an empty response", false);
            }
            return response.trim();
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 429 || status >= 500;
            throw new ModelProviderException(
                    "task model returned HTTP " + status, retryable, exception);
        } catch (ResourceAccessException exception) {
            throw new ModelProviderException("task model is unavailable", true, exception);
        } catch (RuntimeException exception) {
            throw new ModelProviderException("task model generation failed", false, exception);
        }
    }

    private String call(TaskModelRequest request) {
        Response response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(model, request.messages(), false, request.maxOutputTokens(),
                        request.temperature(), responseFormat(request.responseFormat()), priority))
                .retrieve()
                .body(Response.class);
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new ModelProviderException("task model response has no message", false);
        }
        String content = response.choices().getFirst().message().content();
        if (content == null || content.isBlank()) {
            throw new ModelProviderException("task model response has empty content", false);
        }
        return content;
    }

    private ResponseFormat responseFormat(TaskModelRequest.ResponseFormat format) {
        return format == TaskModelRequest.ResponseFormat.JSON_OBJECT
                ? new ResponseFormat("json_object") : null;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Request(String model, List<TaskModelMessage> messages, boolean stream,
            int max_tokens, double temperature, ResponseFormat response_format, String priority) {}

    private record Response(List<Choice> choices) {}

    private record Choice(Message message) {}

    private record Message(String role, String content) {}

    private record ResponseFormat(String type) {}
}
