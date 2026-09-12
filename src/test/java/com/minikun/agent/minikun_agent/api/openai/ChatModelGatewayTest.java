package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.GenerationOptions;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

class ChatModelGatewayTest {
    @Test
    void failedToolLoopStopsWithoutRetryingAMaybeMutatingAction() {
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        SpringAiToolCallingRuntime tools = mock(SpringAiToolCallingRuntime.class);
        when(tools.call(any(Prompt.class), any(ConversationId.class), any(String.class)))
                .thenThrow(new IllegalStateException("tool failed after execution"));
        ChatModelGateway gateway = new ChatModelGateway(active, tools, null, true);

        var response = gateway.reviewToolRuntimeDraft(
                new Prompt("test"), new ConversationId("tool-recovery"), "default");

        assertTrue(response.getResult().getOutput().getText().contains("ไม่ให้ทำรายการซ้ำ"));
    }

    @Test
    void reasoningHandoffIsFinalizedByTheMainOllamaModel() {
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        when(provider.chat(any(Prompt.class))).thenReturn(response("final answer"));
        KimiK3ReasoningClient kimi = mock(KimiK3ReasoningClient.class);
        when(kimi.configured()).thenReturn(true);
        when(kimi.handoff(any(Prompt.class), eq(GenerationOptions.Reasoning.HIGH)))
                .thenReturn("private handoff");
        ChatModelGateway gateway = new ChatModelGateway(active, null, null, false, kimi, "main-model");

        Prompt prompt = new Prompt(List.of(new UserMessage("solve")),
                OllamaChatOptions.builder().model("local-reasoner").maxTokens(32).build());
        ChatResponse result = gateway.chat(
                prompt, GenerationOptions.Reasoning.HIGH, "chat_model", "request", new ConversationId("reasoning"));

        ArgumentCaptor<Prompt> captured = ArgumentCaptor.forClass(Prompt.class);
        verify(provider).chat(captured.capture());
        assertEquals("final answer", result.getResult().getOutput().getText());
        assertEquals("main-model", ((OllamaChatOptions) captured.getValue().getOptions()).getModel());
        assertTrue(captured.getValue().getUserMessage().getText().contains("private handoff"));
    }

    @Test
    void failedKimiReasoningFallsBackToTheOriginalOllamaPrompt() {
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        when(provider.chat(any(Prompt.class))).thenReturn(response("fallback answer"));
        KimiK3ReasoningClient kimi = mock(KimiK3ReasoningClient.class);
        when(kimi.configured()).thenReturn(true);
        when(kimi.handoff(any(Prompt.class), eq(GenerationOptions.Reasoning.HIGH)))
                .thenThrow(new IllegalStateException("NVIDIA unavailable"));
        ChatModelGateway gateway = new ChatModelGateway(active, null, null, false, kimi, "main-model");
        Prompt prompt = new Prompt("solve");

        assertEquals("fallback answer", gateway.chat(
                prompt, GenerationOptions.Reasoning.HIGH, "chat_model", "request", new ConversationId("fallback"))
                .getResult().getOutput().getText());
        verify(provider).chat(prompt);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
