package com.minikun.model.tinygrad;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

final class HttpTinyGradClient implements TinyGradClient {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DONE_EVENT = "[DONE]";
    private static final String JSON_CONTENT_TYPE = MediaType.APPLICATION_JSON_VALUE;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String model;
    private final Duration requestTimeout;
    private final int contextSize;

    HttpTinyGradClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
            String model, Duration requestTimeout, int contextSize) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = normalizeEndpoint(baseUrl) + CHAT_COMPLETIONS_PATH;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.contextSize = contextSize;
    }

    @Override
    public ChatResponse chat(Prompt prompt) {
        requireModel();
        HttpResponse<String> response = send(HttpRequest.BodyPublishers.ofString(requestBody(prompt, false)));
        return responseFrom(response.body());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        requireModel();
        return Flux.<ChatResponse>create(emitter -> {
            try {
                HttpResponse<InputStream> response = sendStream(
                        HttpRequest.BodyPublishers.ofString(requestBody(prompt, true)));
                ensureSuccess(response.statusCode());
                readEvents(response.body(), emitter);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                emitter.error(new IllegalStateException("TinyGrad streaming request interrupted", exception));
            } catch (IOException | RuntimeException exception) {
                emitter.error(exception instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new IllegalStateException("Unable to read TinyGrad stream", exception));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body) {
        try {
            HttpRequest request = request(body);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response.statusCode());
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TinyGrad request interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to call TinyGrad", exception);
        }
    }

    private HttpResponse<InputStream> sendStream(HttpRequest.BodyPublisher body)
            throws IOException, InterruptedException {
        return httpClient.send(request(body), HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpRequest request(HttpRequest.BodyPublisher body) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Content-Type", JSON_CONTENT_TYPE)
                .POST(body)
                .build();
    }

    private String requestBody(Prompt prompt, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", model);
            root.put("stream", stream);
            root.put("thinking", false);
            ObjectNode requestOptions = root.putObject("options");
            requestOptions.put("num_ctx", contextSize);
            var messages = root.putArray("messages");
            for (Message message : prompt.getInstructions()) {
                ObjectNode item = messages.addObject();
                item.put("role", message.getMessageType().getValue());
                item.put("content", message.getText());
            }
            if (prompt.getOptions() instanceof ChatOptions options) {
                addOptions(root, options);
            }
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode TinyGrad request", exception);
        }
    }

    private void addOptions(ObjectNode root, ChatOptions options) {
        putNullable(root, "temperature", options.getTemperature());
        putNullable(root, "max_tokens", options.getMaxTokens());
        putNullable(root, "top_p", options.getTopP());
        putNullable(root, "frequency_penalty", options.getFrequencyPenalty());
        putNullable(root, "presence_penalty", options.getPresencePenalty());
        if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            root.putPOJO("stop", options.getStopSequences());
        }
    }

    private void putNullable(ObjectNode root, String name, Number value) {
        if (value != null) {
            if (value instanceof Integer integer) {
                root.put(name, integer);
            } else {
                root.put(name, value.doubleValue());
            }
        }
    }

    private ChatResponse responseFrom(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").textValue();
            if (content == null) {
                throw new IllegalStateException("TinyGrad response did not contain assistant content");
            }
            return response(content, usage(root.path("usage")));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decode TinyGrad response", exception);
        }
    }

    private void readEvents(InputStream input, reactor.core.publisher.FluxSink<ChatResponse> emitter)
            throws IOException {
        try (input; BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder eventData = new StringBuilder();
            String line;
            while (!emitter.isCancelled() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    emitEvent(eventData, emitter);
                    eventData.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (eventData.length() > 0) {
                        eventData.append('\n');
                    }
                    eventData.append(line.substring("data:".length()).stripLeading());
                }
            }
            if (!emitter.isCancelled() && eventData.length() > 0) {
                emitEvent(eventData, emitter);
            }
            if (!emitter.isCancelled()) {
                emitter.complete();
            }
        }
    }

    private void emitEvent(StringBuilder eventData, reactor.core.publisher.FluxSink<ChatResponse> emitter) {
        if (eventData.isEmpty() || DONE_EVENT.equals(eventData.toString())) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(eventData.toString());
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                emitter.next(response("", usage(usage)));
                return;
            }
            JsonNode content = root.path("choices").path(0).path("delta").path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                emitter.next(response(content.asText()));
            }
        } catch (IOException exception) {
            emitter.error(new IllegalStateException("Unable to decode TinyGrad stream event", exception));
        }
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ChatResponse response(String content, DefaultUsage usage) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    private DefaultUsage usage(JsonNode usage) {
        Integer promptTokens = integerValue(usage, "prompt_tokens");
        Integer completionTokens = integerValue(usage, "completion_tokens");
        Integer totalTokens = integerValue(usage, "total_tokens");
        return new DefaultUsage(promptTokens, completionTokens, totalTokens, usage);
    }

    private Integer integerValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.intValue() : null;
    }

    private void ensureSuccess(int statusCode) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("TinyGrad request failed with HTTP status " + statusCode);
        }
    }

    private void requireModel() {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("TinyGrad model must be configured before invocation");
        }
    }

    private static String normalizeEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("TinyGrad base URL must not be blank");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
