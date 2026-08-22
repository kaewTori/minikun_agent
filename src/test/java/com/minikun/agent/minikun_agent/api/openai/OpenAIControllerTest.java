package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.minikun.agent.minikun_agent.api.ConversationIdResolver;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;

class OpenAIControllerTest {
    @Test
    void exposesStableConversationIdentityAndItsSourceToBrowserClients() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.chatCompletion(any(), any())).thenReturn(new ChatCompletionResponse(
                "chatcmpl-id", "chat.completion", 1L, "mini-kun", List.of(),
                new ChatCompletionResponse.Usage(0, 0, 0)));
        OpenAIController controller = new OpenAIController(chatService, new ConversationIdResolver());
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Conversation-Id", "conversation-1");

        var response = controller.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "hello")), null,
                false, null, null, null), http);

        assertEquals("conversation-1", response.getHeaders().getFirst("X-Conversation-Id"));
        assertEquals("explicit_header", response.getHeaders().getFirst("X-Conversation-Id-Source"));
        assertTrue(response.getHeaders().getFirst("Access-Control-Expose-Headers")
                .contains("X-Conversation-Id"));
    }
}
