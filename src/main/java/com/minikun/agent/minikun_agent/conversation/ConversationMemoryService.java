package com.minikun.agent.minikun_agent.conversation;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ConversationMemoryService {

    private final ChatMemory chatMemory;
    private final ChatMemory fallback;
    private final AtomicBoolean degraded;
    private volatile String degradedReason;

    public ConversationMemoryService(ChatMemory chatMemory) {
        this(chatMemory, 20, true);
    }

    @Autowired
    ConversationMemoryService(
            ChatMemory chatMemory,
            @Value("${spring.ai.chat.memory.max-messages:20}") int maximumMessages,
            @Value("${minikun.database.enabled:true}") boolean databaseEnabled) {
        this.chatMemory = java.util.Objects.requireNonNull(chatMemory, "chat memory must not be null");
        this.fallback = databaseEnabled
                ? MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .maxMessages(maximumMessages)
                        .build()
                : chatMemory;
        this.degraded = new AtomicBoolean(!databaseEnabled);
        this.degradedReason = databaseEnabled ? "" : "database_disabled";
    }

    public List<ChatMessage> load(ConversationId conversationId) {
        return read(memory -> memory.get(conversationId.value())).stream()
                .map(this::toChatMessage)
                .toList();
    }

    public void append(ConversationId conversationId, ChatMessage message) {
        Message springMessage = toSpringMessage(message);
        write(memory -> memory.add(conversationId.value(), springMessage));
    }

    /** Persists a completed user/assistant turn through one ChatMemory write boundary. */
    public void appendTurn(
            ConversationId conversationId,
            ChatMessage userMessage,
            ChatMessage assistantMessage) {
        if (!"user".equalsIgnoreCase(userMessage.role())) {
            throw new IllegalArgumentException("turn must start with a user message");
        }
        if (!"assistant".equalsIgnoreCase(assistantMessage.role())) {
            throw new IllegalArgumentException("turn must end with an assistant message");
        }
        List<Message> messages = List.of(toSpringMessage(userMessage), toSpringMessage(assistantMessage));
        write(memory -> memory.add(conversationId.value(), messages));
    }

    public void clear(ConversationId conversationId) {
        write(memory -> memory.clear(conversationId.value()));
    }

    boolean degraded() {
        return degraded.get();
    }

    String degradedReason() {
        return degradedReason;
    }

    private <T> T read(Function<ChatMemory, T> action) {
        if (degraded()) return action.apply(fallback);
        try {
            return action.apply(chatMemory);
        } catch (RuntimeException exception) {
            degrade(exception);
            return action.apply(fallback);
        }
    }

    private void write(Consumer<ChatMemory> action) {
        if (degraded()) {
            action.accept(fallback);
            return;
        }
        try {
            action.accept(chatMemory);
        } catch (RuntimeException exception) {
            degrade(exception);
            action.accept(fallback);
        }
    }

    private void degrade(RuntimeException exception) {
        degradedReason = exception.getClass().getSimpleName();
        if (degraded.compareAndSet(false, true)) {
            log.warn("process=conversation_persistence event=degraded mode=in_memory reason={}", degradedReason);
        }
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
