package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.model.ModelUsage;

/**
 * Owns the OpenAI-compatible response envelope and SSE payload mapping.
 *
 * <p>The chat orchestration layer supplies domain values and model output; this
 * class is responsible only for the transport representation.</p>
 */
final class OpenAiChatResponseFactory {
    private static final String COMPLETION_OBJECT = "chat.completion";
    private static final String CHUNK_OBJECT = "chat.completion.chunk";

    private final ObjectMapper objectMapper;

    OpenAiChatResponseFactory() {
        this(new ObjectMapper());
    }

    OpenAiChatResponseFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ChatCompletionResponse completion(
            String id,
            long created,
            String model,
            String content,
            ModelUsage usage,
            List<ChatAttachment> attachments) {
        return new ChatCompletionResponse(
                id,
                COMPLETION_OBJECT,
                created,
                model,
                List.of(new ChatCompletionResponse.Choice(
                        0, new Message("assistant", content), "stop")),
                usage(usage),
                attachments);
    }

    ChatCompletionResponse contentCompletion(String model, String content) {
        return completion(
                "chatcmpl-" + UUID.randomUUID(),
                Instant.now().getEpochSecond(),
                model,
                content,
                ModelUsage.empty(),
                List.of());
    }

    List<String> contentStream(String model, String content) {
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        return List.of(
                data(new ChatCompletionResponse.StreamChunk(
                        id,
                        CHUNK_OBJECT,
                        created,
                        model,
                        List.of(new ChatCompletionResponse.StreamChoice(
                                0, new ChatCompletionResponse.Delta("assistant", content), null)))),
                stopChunk(id, created, model),
                "[DONE]");
    }

    String initialChunk(
            String id,
            long created,
            String model,
            List<ChatAttachment> attachments) {
        return data(new ChatCompletionResponse.StreamChunk(
                id,
                CHUNK_OBJECT,
                created,
                model,
                List.of(new ChatCompletionResponse.StreamChoice(
                        0,
                        new ChatCompletionResponse.Delta(
                                "assistant", "", imageDeltas(attachments)),
                        null)),
                attachments));
    }

    String contentChunk(ChatResponse response, String id, long created, String model) {
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isEmpty()) {
            return "";
        }
        return data(new ChatCompletionResponse.StreamChunk(
                id,
                CHUNK_OBJECT,
                created,
                model,
                List.of(new ChatCompletionResponse.StreamChoice(
                        0, new ChatCompletionResponse.Delta(null, content), null))));
    }

    String usageChunk(ModelUsage modelUsage, String id, long created, String model) {
        if (modelUsage == null || modelUsage.equals(ModelUsage.empty())) {
            return "";
        }
        return data(new ChatCompletionResponse.StreamChunk(
                id,
                CHUNK_OBJECT,
                created,
                model,
                List.of(),
                List.of(),
                usage(modelUsage)));
    }

    String stopChunk(String id, long created, String model) {
        return data(new ChatCompletionResponse.StreamChunk(
                id,
                CHUNK_OBJECT,
                created,
                model,
                List.of(new ChatCompletionResponse.StreamChoice(
                        0, new ChatCompletionResponse.Delta(null, null), "stop"))));
    }

    ModelUsage modelUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return ModelUsage.empty();
        }
        var usage = response.getMetadata().getUsage();
        return new ModelUsage(
                nonNegative(usage.getPromptTokens()),
                nonNegative(usage.getCompletionTokens()));
    }

    ChatCompletionResponse.Usage usage(ModelUsage modelUsage) {
        ModelUsage safeUsage = modelUsage == null ? ModelUsage.empty() : modelUsage;
        return new ChatCompletionResponse.Usage(
                safeUsage.promptTokens(), safeUsage.completionTokens(), safeUsage.totalTokens());
    }

    private List<ChatCompletionResponse.Image> imageDeltas(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        return attachments.stream()
                .map(attachment -> new ChatCompletionResponse.Image(
                        "image_url", new ChatCompletionResponse.ImageUrl(attachment.url())))
                .toList();
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String data(ChatCompletionResponse.StreamChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize streaming response", exception);
        }
    }
}
