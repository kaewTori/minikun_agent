package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.agent.minikun_agent.conversation.ConversationSummaryService;
import com.minikun.memory.DeferredReflectionService;
import com.minikun.memory.ReflectionService;
import com.minikun.memory.event.MinikunEvent;
import com.minikun.memory.event.NoOpObservationPublisher;
import com.minikun.memory.event.Observation;
import com.minikun.memory.event.ObservationPublisher;
import com.minikun.memory.event.ObservationSource;
import com.minikun.memory.event.ObservationType;
import com.minikun.memory.event.SafeObservationPublisher;
import com.minikun.memory.model.CompletedConversation;

import lombok.extern.slf4j.Slf4j;

/** Owns successful-turn persistence, completion events, and optional reflection. */
@Slf4j
final class ChatTurnFinalizer {
    private final ConversationMemoryService conversationMemoryService;
    private final ObjectProvider<ReflectionService> reflectionService;
    private final DeferredReflectionService deferredReflectionService;
    private final ObservationPublisher observationPublisher;
    private final ConversationSummaryService conversationSummaryService;
    private final boolean reflectionEnabled;

    ChatTurnFinalizer(
            ConversationMemoryService conversationMemoryService,
            ObjectProvider<ReflectionService> reflectionService,
            DeferredReflectionService deferredReflectionService,
            ObservationPublisher observationPublisher,
            ConversationSummaryService conversationSummaryService,
            boolean reflectionEnabled) {
        this.conversationMemoryService = conversationMemoryService;
        this.reflectionService = reflectionService;
        this.deferredReflectionService = deferredReflectionService;
        this.observationPublisher = observationPublisher;
        this.conversationSummaryService = conversationSummaryService;
        this.reflectionEnabled = reflectionEnabled;
    }

    void complete(
            ConversationId conversationId,
            ChatMessage userMessage,
            String ownerId,
            String requestId,
            String assistantContent,
            boolean persistConversation,
            boolean streaming) {
        if (!persistConversation) {
            return;
        }
        conversationMemoryService.appendTurn(
                conversationId, userMessage, new ChatMessage("assistant", assistantContent));
        log.info("process=conversation event=completed_turn_persisted{}",
                streaming ? " stream=true" : "");
        publishTurnCompleted(ownerId, conversationId, requestId);
        updateConversationSummary(ownerId, conversationId);
        reflectOnCompletedConversation(ownerId, conversationId);
    }

    void persistDeterministic(
            ConversationId conversationId,
            ChatMessage userMessage,
            String assistantContent,
            String ownerId,
            String requestId,
            boolean persistConversation,
            boolean streaming) {
        if (!persistConversation) {
            return;
        }
        conversationMemoryService.appendTurn(
                conversationId, userMessage, new ChatMessage("assistant", assistantContent));
        log.info("process=conversation event=deterministic_tool_turn_persisted{}",
                streaming ? " stream=true" : "");
        publishTurnCompleted(ownerId, conversationId, requestId);
        updateConversationSummary(ownerId, conversationId);
        // Operational snapshots are intentionally excluded from long-term reflection.
    }

    Optional<CompletedConversation> completedConversation(
            String ownerId,
            ConversationId conversationId) {
        List<ChatMessage> messages = conversationMemoryService.load(conversationId);
        int assistantIndex = latestAssistantIndex(messages);
        if (assistantIndex < 0 || hasConversationMessageAfter(messages, assistantIndex)) {
            return Optional.empty();
        }

        int firstUserIndex = assistantIndex - 1;
        while (firstUserIndex >= 0 && isUserMessage(messages.get(firstUserIndex))) {
            firstUserIndex--;
        }
        firstUserIndex++;
        if (firstUserIndex > assistantIndex - 1) {
            return Optional.empty();
        }

        List<CompletedConversation.Message> snapshot = messages.subList(firstUserIndex, assistantIndex + 1).stream()
                .filter(message -> !isSystemMessage(message))
                .map(message -> new CompletedConversation.Message(message.role(), message.content()))
                .toList();
        return Optional.of(new CompletedConversation(ownerId, conversationId.value(), snapshot));
    }

    private int latestAssistantIndex(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (isAssistantMessage(messages.get(index))) {
                return index;
            }
            if (isUserMessage(messages.get(index))) {
                return -1;
            }
        }
        return -1;
    }

    private boolean hasConversationMessageAfter(List<ChatMessage> messages, int assistantIndex) {
        for (int index = assistantIndex + 1; index < messages.size(); index++) {
            if (!isSystemMessage(messages.get(index))) {
                return true;
            }
        }
        return false;
    }

    private boolean isUserMessage(ChatMessage message) {
        return "user".equalsIgnoreCase(message.role());
    }

    private boolean isAssistantMessage(ChatMessage message) {
        return "assistant".equalsIgnoreCase(message.role());
    }

    private boolean isSystemMessage(ChatMessage message) {
        return "system".equalsIgnoreCase(message.role());
    }

    private void reflectOnCompletedConversation(String ownerId, ConversationId conversationId) {
        if (!reflectionEnabled) {
            return;
        }
        try {
            ReflectionService service = reflectionService.getIfAvailable();
            if (service == null) {
                return;
            }
            if (deferredReflectionService != null) {
                boolean submitted = deferredReflectionService.submit(
                        () -> runReflection(service, ownerId, conversationId));
                if (!submitted) {
                    log.warn("memory_reflection conversation_id={} success=false reason=queue_full action=dropped",
                            conversationId.value());
                }
                return;
            }
            runReflection(service, ownerId, conversationId);
        } catch (RuntimeException exception) {
            log.warn("memory_reflection conversation_id={} success=false", conversationId.value(), exception);
        }
    }

    private void runReflection(ReflectionService service, String ownerId, ConversationId conversationId) {
        try {
            completedConversation(ownerId, conversationId).ifPresent(service::reflect);
        } catch (RuntimeException exception) {
            log.warn("memory_reflection conversation_id={} success=false", conversationId.value(), exception);
        }
    }

    private void publishTurnCompleted(String ownerId, ConversationId conversationId, String requestId) {
        ObservationPublisher delegate = observationPublisher == null
                ? new NoOpObservationPublisher()
                : observationPublisher;
        new SafeObservationPublisher(delegate).publish(MinikunEvent.from(new Observation(
                ObservationType.TURN_COMPLETED,
                ObservationSource.CHAT,
                ownerId,
                conversationId == null ? null : conversationId.value(),
                Instant.now(),
                Map.of("request_id", requestId == null ? "" : requestId))));
    }

    private void updateConversationSummary(String ownerId, ConversationId conversationId) {
        if (conversationSummaryService == null) {
            return;
        }
        conversationSummaryService.schedule(
                ownerId, conversationId, () -> conversationMemoryService.load(conversationId));
    }
}
