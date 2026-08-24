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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minikun.model.ModelProviderException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
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
    private final boolean forceGreedy;

    HttpTinyGradClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
            String model, Duration requestTimeout) {
        this(httpClient, objectMapper, baseUrl, model, requestTimeout, false);
    }

    HttpTinyGradClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
            String model, Duration requestTimeout, boolean forceGreedy) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.endpoint = normalizeEndpoint(baseUrl) + CHAT_COMPLETIONS_PATH;
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.forceGreedy = forceGreedy;
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
            AtomicReference<InputStream> activeInput = new AtomicReference<>();
            emitter.onCancel(() -> closeQuietly(activeInput.getAndSet(null)));
            try {
                HttpResponse<InputStream> response = sendStream(
                        HttpRequest.BodyPublishers.ofString(requestBody(prompt, true)));
                if (!isSuccess(response.statusCode())) {
                    try (InputStream errorInput = response.body()) {
                        throw httpError(response.statusCode(), new String(errorInput.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
                activeInput.set(response.body());
                readEvents(response.body(), emitter);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!emitter.isCancelled()) {
                    emitter.error(new IllegalStateException("TinyGrad streaming request interrupted", exception));
                }
            } catch (IOException | RuntimeException exception) {
                if (!emitter.isCancelled()) {
                    emitter.error(exception instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new ModelProviderException("Unable to read TinyGrad stream", true, exception));
                }
            } finally {
                closeQuietly(activeInput.getAndSet(null));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher body) {
        try {
            HttpRequest request = request(body);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!isSuccess(response.statusCode())) {
                throw httpError(response.statusCode(), response.body());
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException("TinyGrad request interrupted", false, exception);
        } catch (IOException exception) {
            throw new ModelProviderException("Unable to call TinyGrad", true, exception);
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
            root.put("priority", "interactive");
            if (stream) {
                root.putObject("stream_options").put("include_usage", true);
            }
            ArrayNode messages = root.putArray("messages");
            for (Message message : prompt.getInstructions()) {
                addMessage(messages, message);
            }
            if (prompt.getOptions() instanceof ChatOptions options) {
                addOptions(root, options);
            }
            if (forceGreedy) {
                root.put("temperature", 0.0);
            }
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolOptions) {
                addTools(root, toolOptions.getToolCallbacks());
            }
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode TinyGrad request", exception);
        }
    }

    private void addMessage(ArrayNode messages, Message message) {
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                ObjectNode item = messages.addObject();
                item.put("role", "tool");
                item.put("tool_call_id", response.id());
                item.put("name", response.name());
                item.put("content", response.responseData());
            }
            return;
        }

        ObjectNode item = messages.addObject();
        item.put("role", message.getMessageType().getValue());
        if (message.getText() == null) {
            item.putNull("content");
        } else {
            item.put("content", message.getText());
        }
        if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            ArrayNode toolCalls = item.putArray("tool_calls");
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                ObjectNode call = toolCalls.addObject();
                call.put("id", toolCall.id());
                call.put("type", toolCall.type());
                ObjectNode function = call.putObject("function");
                function.put("name", toolCall.name());
                function.put("arguments", toolCall.arguments());
            }
        }
    }

    private void addTools(ObjectNode root, List<ToolCallback> callbacks) throws IOException {
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        ArrayNode tools = root.putArray("tools");
        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", objectMapper.readTree(definition.inputSchema()));
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
            List<AssistantMessage.ToolCall> toolCalls = toolCalls(message.path("tool_calls"));
            if (content == null && toolCalls.isEmpty()) {
                throw new IllegalStateException("TinyGrad response did not contain assistant content");
            }
            return response(assistantMessage(content, toolCalls, message), usage(root.path("usage")));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decode TinyGrad response", exception);
        }
    }

    private void readEvents(InputStream input, FluxSink<ChatResponse> emitter)
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

    private void emitEvent(StringBuilder eventData, FluxSink<ChatResponse> emitter) {
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
            JsonNode delta = root.path("choices").path(0).path("delta");
            JsonNode contentNode = delta.path("content");
            String content = contentNode.isTextual() ? contentNode.asText() : null;
            List<AssistantMessage.ToolCall> toolCalls = toolCalls(delta.path("tool_calls"));
            if ((content != null && !content.isEmpty()) || !toolCalls.isEmpty()) {
                emitter.next(response(assistantMessage(content, toolCalls, delta)));
            }
        } catch (IOException exception) {
            emitter.error(new IllegalStateException("Unable to decode TinyGrad stream event", exception));
        }
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse response(String content, DefaultUsage usage) {
        return response(new AssistantMessage(content), usage);
    }

    private ChatResponse response(AssistantMessage message, DefaultUsage usage) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    private AssistantMessage assistantMessage(String content, List<AssistantMessage.ToolCall> toolCalls, JsonNode source) {
        Map<String, Object> properties = new LinkedHashMap<>();
        JsonNode reasoning = source.path("reasoning_content");
        if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
            properties.put("reasoning_content", reasoning.asText());
        }
        return AssistantMessage.builder()
                .content(content == null ? "" : content)
                .properties(Map.copyOf(properties))
                .toolCalls(toolCalls)
                .build();
    }

    private List<AssistantMessage.ToolCall> toolCalls(JsonNode node) throws IOException {
        if (!node.isArray()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : node) {
            JsonNode function = call.path("function");
            String id = call.path("id").asText("");
            String type = call.path("type").asText("function");
            String name = function.path("name").asText("");
            JsonNode argumentsNode = function.path("arguments");
            String arguments = argumentsNode.isTextual()
                    ? argumentsNode.asText()
                    : objectMapper.writeValueAsString(argumentsNode.isMissingNode()
                            ? objectMapper.createObjectNode() : argumentsNode);
            if (name.isBlank()) {
                throw new IllegalStateException("TinyGrad tool call did not contain a function name");
            }
            toolCalls.add(new AssistantMessage.ToolCall(id, type, name, arguments));
        }
        return List.copyOf(toolCalls);
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

    private boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private ModelProviderException httpError(int statusCode, String body) {
        String prefix = "TinyGrad request failed with HTTP status " + statusCode;
        boolean retryable = statusCode == 429 || statusCode >= 500;
        if (body == null || body.isBlank()) {
            return new ModelProviderException(prefix, retryable);
        }
        try {
            String message = objectMapper.readTree(body).path("error").path("message").asText("").trim();
            return new ModelProviderException(message.isEmpty() ? prefix : prefix + ": " + message, retryable);
        } catch (IOException exception) {
            return new ModelProviderException(prefix, retryable);
        }
    }

    private void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // Closing is best-effort during cancellation/cleanup.
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
