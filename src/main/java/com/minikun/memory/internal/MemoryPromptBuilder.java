package com.minikun.memory.internal;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;

final class MemoryPromptBuilder {
    static final String VERSION = "memory-v2-1";

    private final ObjectMapper objectMapper;

    MemoryPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String version() {
        return VERSION;
    }

    String build(CompletedConversation conversation, Instant now) {
        var request = new PromptRequest(
                VERSION,
                now.toString(),
            "Return JSON only with this schema: {\"memories\":[{\"category\":\"PREFERENCE|GOAL|PROFILE|SKILL|PROJECT\",\"content\":\"string\",\"confidence\":0.0,\"reason\":\"string\"}]}. Do not generate identifiers or persistence decisions.",
                conversation.messages().stream()
                        .map(message -> new PromptMessage(message.role(), message.content()))
                        .toList());
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to build memory extraction prompt", exception);
        }
    }

        private record PromptRequest(String promptVersion, String currentTimestamp, String outputSchema,
            java.util.List<PromptMessage> conversation) {
    }

    private record PromptMessage(String role, String content) {
    }
}
