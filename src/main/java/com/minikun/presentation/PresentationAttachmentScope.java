package com.minikun.presentation;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Carries only attachments produced by verified presentation tool calls to the chat response.
 * ponytail: synchronous tool callbacks; use a request-scoped context if the runtime parallelizes calls.
 */
public final class PresentationAttachmentScope implements AutoCloseable {
    public static final String METADATA_KEY = "minikun.presentation.attachments";
    private static final ThreadLocal<PresentationAttachmentScope> CURRENT = new ThreadLocal<>();
    private final PresentationAttachmentScope previous;
    private final List<ChatAttachment> attachments = new ArrayList<>();

    private PresentationAttachmentScope() {
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static PresentationAttachmentScope open() { return new PresentationAttachmentScope(); }

    static void add(ChatAttachment attachment) {
        PresentationAttachmentScope scope = CURRENT.get();
        if (scope != null && attachment != null
                && scope.attachments.stream().noneMatch(item -> item.artifactId().equals(attachment.artifactId()))) {
            scope.attachments.add(attachment);
        }
    }

    public ChatResponse attach(ChatResponse response) {
        return attach(response, List.copyOf(attachments));
    }

    public static ChatResponse carry(ChatResponse response, ChatResponse source) {
        List<ChatAttachment> existing = attachments(source);
        return existing.isEmpty() ? response : attach(response, existing);
    }

    public static List<ChatAttachment> attachments(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of();
        }
        Object value = response.getResult().getOutput().getMetadata().get(METADATA_KEY);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(ChatAttachment.class::isInstance).map(ChatAttachment.class::cast).toList();
    }

    private static ChatResponse attach(ChatResponse response, List<ChatAttachment> attachments) {
        if (response == null || attachments.isEmpty()) return response;
        List<Generation> generations = response.getResults().stream().map(generation -> {
            AssistantMessage output = generation.getOutput();
            Map<String, Object> metadata = new java.util.LinkedHashMap<>(output.getMetadata());
            metadata.put(METADATA_KEY, attachments);
            AssistantMessage enriched = AssistantMessage.builder().content(output.getText())
                    .properties(metadata).toolCalls(output.getToolCalls()).media(output.getMedia()).build();
            ChatGenerationMetadata generationMetadata = generation.getMetadata();
            return generationMetadata == null || generationMetadata == ChatGenerationMetadata.NULL
                    ? new Generation(enriched) : new Generation(enriched, generationMetadata);
        }).toList();
        return new ChatResponse(generations, response.getMetadata());
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
