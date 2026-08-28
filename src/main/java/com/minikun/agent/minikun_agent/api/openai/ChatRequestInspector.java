package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.commands.CommandCatalog;
import com.minikun.pcs.PromptException;
import java.util.List;

/** Pure request classification kept outside the chat orchestration path. */
final class ChatRequestInspector {
    private static final String PUBLIC_MODEL_NAME = "mini-kun";

    String publicModelName() {
        return PUBLIC_MODEL_NAME;
    }

    boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    boolean isCommand(String content, CommandCatalog commands) {
        return hasText(content) && commands.findExact(content.trim()).isPresent();
    }

    boolean shouldPersist(ChatCompletionRequest request) {
        return request.messages().stream()
                .map(Message::content)
                .filter(this::hasText)
                .noneMatch(this::isInternalTitleRequest);
    }

    boolean isInternalTitleRequest(ChatCompletionRequest request) {
        return request.messages().stream()
                .map(Message::content)
                .filter(this::hasText)
                .anyMatch(this::isInternalTitleRequest);
    }

    List<ChatMessage> titleMessages(ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> hasText(message.content()))
                .filter(message -> "user".equals(message.role()) || "assistant".equals(message.role()))
                .map(message -> new ChatMessage(message.role(), message.content()))
                .toList();
    }

    int lastUserMessageIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if ("user".equals(message.role()) && (hasText(message.content()) || message.hasImageContent())) {
                return index;
            }
        }
        throw new PromptException("chat request must contain user text or an image");
    }

    boolean isSystemMessage(ChatMessage message) {
        return "system".equalsIgnoreCase(message.role());
    }

    private boolean isInternalTitleRequest(String content) {
        if (!hasText(content)) return false;
        String normalized = content.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("generate a concise title summarizing the chat history")
                || normalized.contains("your entire response must consist solely of the json object")
                || normalized.contains("### task:\n") && normalized.contains("### chat history:");
    }
}
