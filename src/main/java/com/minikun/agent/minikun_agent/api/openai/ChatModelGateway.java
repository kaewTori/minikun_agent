package com.minikun.agent.minikun_agent.api.openai;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.CooperativeChatModelService;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/** Central model boundary for provider, cooperative-review, and tool-runtime execution. */
@Slf4j
final class ChatModelGateway {
    private final ActiveChatModelProvider activeChatModelProvider;
    private final CooperativeChatModelService cooperativeChatModelService;
    private final SpringAiToolCallingRuntime toolCallingRuntime;
    private final ChatPerformanceMetrics performanceMetrics;
    private final boolean toolsEnabled;

    ChatModelGateway(
            ActiveChatModelProvider activeChatModelProvider,
            CooperativeChatModelService cooperativeChatModelService,
            SpringAiToolCallingRuntime toolCallingRuntime,
            ChatPerformanceMetrics performanceMetrics,
            boolean toolsEnabled) {
        this.activeChatModelProvider = activeChatModelProvider;
        this.cooperativeChatModelService = cooperativeChatModelService;
        this.toolCallingRuntime = toolCallingRuntime;
        this.performanceMetrics = performanceMetrics;
        this.toolsEnabled = toolsEnabled;
    }

    ChatResponse chat(Prompt prompt, String process, String requestId, ConversationId conversationId) {
        long started = System.nanoTime();
        String result = "success";
        try {
            return cooperativeChatModelService == null
                    ? provider().chat(prompt)
                    : cooperativeChatModelService.chat(
                            provider(), prompt, conversationValue(conversationId));
        } catch (RuntimeException exception) {
            result = "error";
            throw exception;
        } finally {
            logDuration(process, started, requestId);
            recordStage(started, result);
        }
    }

    Flux<ChatResponse> stream(Prompt prompt, ConversationId conversationId) {
        long started = System.nanoTime();
        AtomicBoolean firstChunk = new AtomicBoolean();
        Flux<ChatResponse> response = cooperativeChatModelService == null
                ? provider().stream(prompt)
                : cooperativeChatModelService.stream(
                        provider(), prompt, conversationValue(conversationId));
        return response
                .doOnNext(ignored -> {
                    if (firstChunk.compareAndSet(false, true)) {
                        recordProviderStage("ttft", started, "success");
                    }
                })
                .doFinally(signal -> recordProviderStage("stream", started, signalResult(signal)));
    }

    ChatResponse chatWithTools(
            Prompt prompt,
            ConversationId conversationId,
            String ownerId,
            String requestId) {
        if (!toolsAvailable()) {
            return chat(prompt, "chat_model", requestId, conversationId);
        }
        long started = System.nanoTime();
        String result = "success";
        try {
            return reviewToolRuntimeDraft(prompt, conversationId, ownerId);
        } catch (RuntimeException exception) {
            result = "error";
            throw exception;
        } finally {
            logDuration("chat_model", started, requestId);
            recordStage(started, result);
        }
    }

    boolean toolsAvailable() {
        return toolsEnabled && toolCallingRuntime != null && provider().capabilities().toolCalling();
    }

    ChatResponse reviewToolRuntimeDraft(
            Prompt prompt,
            ConversationId conversationId,
            String ownerId) {
        ChatResponse draft = toolCallingRuntime.call(prompt, conversationId, ownerId);
        return cooperativeChatModelService == null
                ? draft
                : cooperativeChatModelService.reviewDraft(
                        provider(), prompt, draft, conversationValue(conversationId));
    }

    private ChatModelProvider provider() {
        return activeChatModelProvider.get();
    }

    private String conversationValue(ConversationId conversationId) {
        return conversationId == null ? "unknown" : conversationId.value();
    }

    private void logDuration(String process, long started, String requestId) {
        log.info("model_call={} request_id={} duration_ms={}",
                process,
                requestId == null ? "-" : requestId,
                (System.nanoTime() - started) / 1_000_000);
    }

    private void recordStage(long started, String result) {
        recordProviderStage("call", started, result);
    }

    private void recordProviderStage(String operation, long started, String result) {
        if (performanceMetrics == null) return;
        String provider = provider().id().name().toLowerCase(Locale.ROOT);
        performanceMetrics.record("model_" + provider + "_" + operation, started, result);
    }

    private String signalResult(reactor.core.publisher.SignalType signal) {
        if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) return "success";
        if (signal == reactor.core.publisher.SignalType.CANCEL) return "cancelled";
        return "error";
    }
}
