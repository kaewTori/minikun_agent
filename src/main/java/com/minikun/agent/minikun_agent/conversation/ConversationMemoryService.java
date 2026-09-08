package com.minikun.agent.minikun_agent.conversation;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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

    private static final Duration DEFAULT_RECOVERY_PROBE_INTERVAL = Duration.ofSeconds(5);

    private final ChatMemory chatMemory;
    private final ChatMemory fallback;
    private final boolean recoveryEnabled;
    private final long recoveryProbeIntervalNanos;
    // ponytail: one lock keeps replay ordered; shard by conversation if throughput matters.
    private final Object stateLock = new Object();
    private final Deque<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final AtomicBoolean degraded;
    private volatile String degradedReason;
    private volatile long nextRecoveryProbeNanos;

    public ConversationMemoryService(ChatMemory chatMemory) {
        this(chatMemory, 20, true, DEFAULT_RECOVERY_PROBE_INTERVAL);
    }

    @Autowired
    ConversationMemoryService(
            ChatMemory chatMemory,
            @Value("${spring.ai.chat.memory.max-messages:20}") int maximumMessages,
            @Value("${minikun.database.enabled:true}") boolean databaseEnabled) {
        this(chatMemory, maximumMessages, databaseEnabled, DEFAULT_RECOVERY_PROBE_INTERVAL);
    }

    ConversationMemoryService(
            ChatMemory chatMemory,
            int maximumMessages,
            boolean databaseEnabled,
            Duration recoveryProbeInterval) {
        this.chatMemory = java.util.Objects.requireNonNull(chatMemory, "chat memory must not be null");
        if (recoveryProbeInterval.isNegative()) {
            throw new IllegalArgumentException("recovery probe interval must not be negative");
        }
        this.fallback = databaseEnabled
                ? MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .maxMessages(maximumMessages)
                        .build()
                : chatMemory;
        this.recoveryEnabled = databaseEnabled;
        this.recoveryProbeIntervalNanos = recoveryProbeInterval.toNanos();
        this.degraded = new AtomicBoolean(!databaseEnabled);
        this.degradedReason = databaseEnabled ? "" : "database_disabled";
        this.nextRecoveryProbeNanos = databaseEnabled ? 0 : Long.MAX_VALUE;
    }

    public List<ChatMessage> load(ConversationId conversationId) {
        String conversationKey = conversationId.value();
        synchronized (stateLock) {
            if (persistentReady(conversationKey)) {
                try {
                    List<Message> messages = chatMemory.get(conversationKey);
                    mirrorSnapshot(conversationKey, messages);
                    return toChatMessages(messages);
                } catch (RuntimeException exception) {
                    degrade(exception);
                }
            }
            return toChatMessages(fallback.get(conversationKey));
        }
    }

    public void append(ConversationId conversationId, ChatMessage message) {
        Message springMessage = toSpringMessage(message);
        write(conversationId, memory -> memory.add(conversationId.value(), springMessage));
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
        write(conversationId, memory -> memory.add(conversationId.value(), messages));
    }

    public void clear(ConversationId conversationId) {
        write(conversationId, memory -> memory.clear(conversationId.value()));
    }

    boolean degraded() {
        return degraded.get();
    }

    String degradedReason() {
        return degradedReason;
    }

    private List<ChatMessage> toChatMessages(List<Message> messages) {
        return messages == null ? List.of() : messages.stream().map(this::toChatMessage).toList();
    }

    private void write(ConversationId conversationId, Consumer<ChatMemory> action) {
        String conversationKey = conversationId.value();
        synchronized (stateLock) {
            if (!persistentReady(conversationKey)) {
                if (recoveryEnabled) {
                    pendingWrites.addLast(new PendingWrite(action));
                }
                action.accept(fallback);
                return;
            }
            try {
                action.accept(chatMemory);
                mirrorWrite(action);
            } catch (RuntimeException exception) {
                degrade(exception);
                if (recoveryEnabled) {
                    pendingWrites.addLast(new PendingWrite(action));
                }
                action.accept(fallback);
            }
        }
    }

    private boolean persistentReady(String conversationKey) {
        if (!degraded()) {
            return true;
        }
        if (!recoveryEnabled || System.nanoTime() < nextRecoveryProbeNanos) {
            return false;
        }
        try {
            chatMemory.get(conversationKey);
            int replayed = 0;
            while (!pendingWrites.isEmpty()) {
                PendingWrite pending = pendingWrites.peekFirst();
                pending.action().accept(chatMemory);
                pendingWrites.removeFirst();
                replayed++;
            }
            degradedReason = "";
            nextRecoveryProbeNanos = 0;
            degraded.set(false);
            log.info("process=conversation_persistence event=recovered replayed_writes={}", replayed);
            return true;
        } catch (RuntimeException exception) {
            degrade(exception);
            return false;
        }
    }

    private void mirrorSnapshot(String conversationKey, List<Message> messages) {
        if (fallback == chatMemory) {
            return;
        }
        try {
            fallback.clear(conversationKey);
            if (messages != null && !messages.isEmpty()) {
                fallback.add(conversationKey, messages);
            }
        } catch (RuntimeException exception) {
            log.debug("process=conversation_persistence event=fallback_mirror_failed reason={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void mirrorWrite(Consumer<ChatMemory> action) {
        if (fallback == chatMemory) {
            return;
        }
        try {
            action.accept(fallback);
        } catch (RuntimeException exception) {
            log.debug("process=conversation_persistence event=fallback_mirror_failed reason={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void degrade(RuntimeException exception) {
        degradedReason = exception.getClass().getSimpleName();
        nextRecoveryProbeNanos = System.nanoTime() + recoveryProbeIntervalNanos;
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

    private record PendingWrite(Consumer<ChatMemory> action) { }
}
