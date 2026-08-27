package com.minikun.pcs.model;

import java.util.List;
import java.util.Objects;

/** Selection text plus provider-neutral, role-preserving recent conversation. */
public record ConversationContext(
        String content,
        String systemContent,
        List<PromptMessage> messages) {

    public ConversationContext(String content) {
        this(content, content, List.of());
    }

    public ConversationContext {
        content = Objects.requireNonNullElse(content, "");
        systemContent = Objects.requireNonNullElse(systemContent, "");
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("conversation messages must not contain null");
        }
        if (messages.stream().anyMatch(message -> message.role() == PromptRole.SYSTEM)) {
            throw new IllegalArgumentException("conversation messages must use user or assistant roles");
        }
    }
}
