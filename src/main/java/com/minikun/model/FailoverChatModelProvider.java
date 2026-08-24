package com.minikun.model;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import reactor.core.publisher.Flux;

/**
 * Retries a provider-unavailable request on a rollback provider without ever
 * mixing two streaming responses.
 */
public final class FailoverChatModelProvider implements ChatModelProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailoverChatModelProvider.class);

    private final ChatModelProvider primary;
    private final ChatModelProvider fallback;
    private final ModelPerformanceMetrics metrics;
    private final String fallbackModel;

    public FailoverChatModelProvider(
            ChatModelProvider primary,
            ChatModelProvider fallback,
            ModelPerformanceMetrics metrics) {
        this(primary, fallback, metrics, null);
    }

    public FailoverChatModelProvider(
            ChatModelProvider primary,
            ChatModelProvider fallback,
            ModelPerformanceMetrics metrics,
            String fallbackModel) {
        this.primary = Objects.requireNonNull(primary, "primary provider must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback provider must not be null");
        if (primary.id() == fallback.id()) {
            throw new IllegalArgumentException("primary and fallback providers must be different");
        }
        this.metrics = metrics;
        this.fallbackModel = fallbackModel == null || fallbackModel.isBlank() ? null : fallbackModel.trim();
    }

    @Override
    public ChatModelId id() {
        return primary.id();
    }

    @Override
    public ModelCapabilities capabilities() {
        ModelCapabilities primaryCapabilities = primary.capabilities();
        ModelCapabilities fallbackCapabilities = fallback.capabilities();
        return new ModelCapabilities(
                primaryCapabilities.streaming() || fallbackCapabilities.streaming(),
                primaryCapabilities.toolCalling() || fallbackCapabilities.toolCalling(),
                primaryCapabilities.vision() || fallbackCapabilities.vision());
    }

    @Override
    public ChatResponse chat(Prompt prompt) {
        String capabilityFallback = capabilityFallback(prompt, false);
        if (capabilityFallback != null) {
            recordFailover(capabilityFallback);
            return fallback.chat(fallbackPrompt(prompt));
        }
        try {
            return primary.chat(prompt);
        } catch (ModelProviderException exception) {
            if (!exception.retryable()) {
                throw exception;
            }
            recordFailover(exception);
            return fallback.chat(fallbackPrompt(prompt));
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String capabilityFallback = capabilityFallback(prompt, true);
        if (capabilityFallback != null) {
            recordFailover(capabilityFallback);
            return fallback.stream(fallbackPrompt(prompt));
        }
        return primary.stream(prompt).switchOnFirst((signal, primaryFlux) -> {
            if (!signal.isOnError() || !(signal.getThrowable() instanceof ModelProviderException exception)
                    || !exception.retryable()) {
                return primaryFlux;
            }
            recordFailover(exception);
            return fallback.stream(fallbackPrompt(prompt));
        });
    }

    private Prompt fallbackPrompt(Prompt prompt) {
        ChatOptions source = prompt.getOptions();
        if (fallback.id() != ChatModelId.EXISTING || source == null || source instanceof OllamaChatOptions) {
            return prompt;
        }
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                .model(fallbackModel)
                .frequencyPenalty(source.getFrequencyPenalty())
                .maxTokens(source.getMaxTokens())
                .presencePenalty(source.getPresencePenalty())
                .stopSequences(source.getStopSequences())
                .temperature(source.getTemperature())
                .topK(source.getTopK())
                .topP(source.getTopP());
        if (source instanceof ToolCallingChatOptions toolOptions) {
            builder.toolCallbacks(toolOptions.getToolCallbacks())
                    .toolContext(toolOptions.getToolContext());
        }
        return new Prompt(prompt.getInstructions(), builder.build());
    }

    private String capabilityFallback(Prompt prompt, boolean streamingRequest) {
        ModelCapabilities primaryCapabilities = primary.capabilities();
        ModelCapabilities fallbackCapabilities = fallback.capabilities();
        if (streamingRequest && !primaryCapabilities.streaming() && fallbackCapabilities.streaming()) {
            return "streaming_unsupported";
        }
        boolean visionRequest = prompt.getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(message -> !message.getMedia().isEmpty());
        if (visionRequest && !primaryCapabilities.vision() && fallbackCapabilities.vision()) {
            return "vision_unsupported";
        }
        ChatOptions options = prompt.getOptions();
        boolean toolRequest = options instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null
                && !toolOptions.getToolCallbacks().isEmpty();
        if (toolRequest && !primaryCapabilities.toolCalling() && fallbackCapabilities.toolCalling()) {
            return "tool_calling_unsupported";
        }
        return null;
    }

    private void recordFailover(ModelProviderException exception) {
        recordFailover(exception.getMessage());
    }

    private void recordFailover(String reason) {
        LOGGER.warn("process=model_failover primary={} fallback={} reason={}",
                primary.id(), fallback.id(), reason);
        if (metrics != null) {
            metrics.record("model_failover", System.nanoTime(), "activated");
        }
    }
}
