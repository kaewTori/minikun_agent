package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

class ChatModelGatewayTest {
    @Test
    void failedToolLoopStopsWithoutRetryingAMaybeMutatingAction() {
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        SpringAiToolCallingRuntime tools = mock(SpringAiToolCallingRuntime.class);
        when(tools.call(any(Prompt.class), any(ConversationId.class), any(String.class)))
                .thenThrow(new IllegalStateException("tool failed after execution"));
        ChatModelGateway gateway = new ChatModelGateway(active, null, tools, null, true);

        var response = gateway.reviewToolRuntimeDraft(
                new Prompt("test"), new ConversationId("tool-recovery"), "default");

        assertTrue(response.getResult().getOutput().getText().contains("ไม่ให้ทำรายการซ้ำ"));
    }
}
