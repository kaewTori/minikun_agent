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

    boolean hasConversationContext(List<ChatMessage> history, ChatCompletionRequest request, CommandCatalog commands) {
        boolean hasHistory = history.stream()
                .anyMatch(message -> !isCommand(message.content(), commands));
        long requestConversationMessages = request.messages().stream()
                .filter(message -> !"system".equals(message.role()))
                .filter(message -> hasText(message.content()))
                .count();
        return hasHistory || requestConversationMessages > 1;
    }

    List<ChatMessage> visualHistory(List<ChatMessage> history, ChatCompletionRequest request) {
        List<ChatMessage> requestHistory = request.messages().subList(0, lastUserMessageIndex(request.messages())).stream()
                .filter(message -> ("user".equals(message.role()) || "assistant".equals(message.role()))
                        && hasText(message.content()))
                .map(message -> new ChatMessage(message.role(), message.content())).toList();
        return requestHistory.stream().anyMatch(message -> "user".equals(message.role())) ? requestHistory : history;
    }

    String toolQuery(String message, ChatCompletionRequest request) {
        if (!hasText(message)) return message;
        var previousMessages = request.messages().subList(0, lastUserMessageIndex(request.messages()));
        boolean currentLocation = message.matches(
                "(?iu).*?(?:ที่(?:เรา|รา)อยู่|แถวนี้|ตรงนี้|ที่นี่|current location).*");
        boolean suppliedPlace = previousMessages.reversed().stream()
                .filter(previous -> "assistant".equals(previous.role()))
                .map(Message::content).filter(this::hasText).findFirst().orElse("")
                .startsWith("บอกชื่อเมืองหรือเปิด/อัปเดตตำแหน่ง")
                && message.length() <= 100
                && !message.matches("(?iu)^(?:ไม่|ยกเลิก|cancel|never mind).*");
        if (!currentLocation && !suppliedPlace) return message;
        String previousUserMessage = previousMessages.reversed().stream()
                .filter(previous -> "user".equals(previous.role()))
                .map(Message::content)
                .filter(this::hasText)
                .findFirst().orElse("");
        return previousUserMessage.matches("(?iu).*(?:อากาศ|พยากรณ์|ฝน|weather|forecast|rain).*")
                ? previousUserMessage + (currentLocation ? " " : " ที่ ") + message : message;
    }

    String classifierContext(List<ChatMessage> history, CommandCatalog commands) {
        if (history == null || history.isEmpty()) return "";
        return history.stream()
                .filter(message -> message != null && !isSystemMessage(message)
                        && hasText(message.content()) && !isCommand(message.content(), commands))
                .skip(Math.max(0, history.size() - 6L))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private boolean isInternalTitleRequest(String content) {
        if (!hasText(content)) return false;
        String normalized = content.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("generate a concise title summarizing the chat history")
                || normalized.contains("your entire response must consist solely of the json object")
                || normalized.contains("### task:\n") && normalized.contains("### chat history:");
    }
}
