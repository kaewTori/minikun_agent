package com.minikun.agent.minikun_agent.conversation;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final ChatMemory chatMemory;

    public List<ChatMessage> load(ConversationId conversationId) {
        return chatMemory.get(conversationId.value()).stream()
                .map(this::toChatMessage)
                .toList();
    }

    public void append(ConversationId conversationId, ChatMessage message) {
        chatMemory.add(conversationId.value(), toSpringMessage(message));
    }

    public void clear(ConversationId conversationId) {
        chatMemory.clear(conversationId.value());
    }

    private ChatMessage toChatMessage(Message message) {
        return new ChatMessage(message.getMessageType().getValue(), message.getText());
    }

    private Message toSpringMessage(ChatMessage message) {
        return switch (MessageType.valueOf(message.role().toUpperCase(Locale.ROOT))) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
            case SYSTEM -> new SystemMessage(message.content());
            case TOOL -> throw new IllegalArgumentException("tool messages are not supported");
        };
    }
}
