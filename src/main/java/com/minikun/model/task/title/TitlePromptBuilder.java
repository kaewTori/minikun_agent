package com.minikun.model.task.title;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;

public final class TitlePromptBuilder {
    private static final int MAX_CONTEXT_CHARACTERS = 2_048;
    private static final String INSTRUCTIONS = "Generate a short conversation title.\n"
            + "Rules:\n"
            + "- 3-8 words\n"
            + "- no explanation\n"
            + "- no quotation marks\n"
            + "- use the same language as the conversation\n"
            + "- describe the main topic\n\n"
            + "Conversation:\n";

    public String build(List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        List<ChatMessage> selected = select(messages);
        StringBuilder context = new StringBuilder();
        for (ChatMessage message : selected) {
            if (!context.isEmpty()) {
                context.append('\n');
            }
            context.append(message.role()).append(": ").append(message.content());
        }
        String boundedContext = context.length() <= MAX_CONTEXT_CHARACTERS
                ? context.toString()
                : context.substring(context.length() - MAX_CONTEXT_CHARACTERS);
        return INSTRUCTIONS + boundedContext;
    }

    private List<ChatMessage> select(List<ChatMessage> messages) {
        List<ChatMessage> selected = new ArrayList<>(2);
        for (int index = messages.size() - 1; index >= 0 && selected.size() < 2; index--) {
            ChatMessage message = messages.get(index);
            if (("user".equals(message.role()) || "assistant".equals(message.role()))
                    && !message.content().isBlank()) {
                selected.add(0, message);
            }
        }
        return selected;
    }
}
