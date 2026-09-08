package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/** Central model boundary for provider and tool-runtime execution. */
@Slf4j
final class ChatModelGateway {
    private final ActiveChatModelProvider activeChatModelProvider;
    private final SpringAiToolCallingRuntime toolCallingRuntime;
    private final ChatPerformanceMetrics performanceMetrics;
    private final boolean toolsEnabled;

    ChatModelGateway(
            ActiveChatModelProvider activeChatModelProvider,
            SpringAiToolCallingRuntime toolCallingRuntime,
            ChatPerformanceMetrics performanceMetrics,
            boolean toolsEnabled) {
        this.activeChatModelProvider = activeChatModelProvider;
        this.toolCallingRuntime = toolCallingRuntime;
        this.performanceMetrics = performanceMetrics;
        this.toolsEnabled = toolsEnabled;
    }

    ChatResponse chat(Prompt prompt, String process, String requestId, ConversationId conversationId) {
        long started = System.nanoTime();
        String result = "success";
        try {
            return provider().chat(prompt);
        } catch (RuntimeException exception) {
            result = "error";
            throw exception;
        } finally {
            logDuration(process, started, requestId);
            recordStage(started, result);
        }
    }

    Flux<ChatResponse> stream(Prompt prompt, ConversationId conversationId) {
        return provider().stream(prompt);
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
        return toolsEnabled && toolCallingRuntime != null;
    }

    ChatResponse reviewToolRuntimeDraft(
            Prompt prompt,
            ConversationId conversationId,
            String ownerId) {
        ChatResponse draft;
        long started = System.nanoTime();
        try {
            draft = toolCallingRuntime.call(prompt, conversationId, ownerId);
        } catch (RuntimeException exception) {
            log.warn("process=tool_calling event=failed conversation_id={} reason={}",
                    conversationValue(conversationId), exception.getClass().getSimpleName());
            if (performanceMetrics != null) performanceMetrics.record("tool", started, "error");
            // Never retry a failed tool loop here: a mutating tool may already have committed its side effect.
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "ขออภัยครับ การใช้เครื่องมือในรอบนี้ไม่สำเร็จ มินิคุงจึงหยุดไว้ก่อนเพื่อไม่ให้ทำรายการซ้ำครับ"))));
        }
        if (performanceMetrics != null) performanceMetrics.record("tool", started, "success");
        return draft;
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
        if (performanceMetrics != null) {
            performanceMetrics.record("model", started, result);
        }
    }
}
