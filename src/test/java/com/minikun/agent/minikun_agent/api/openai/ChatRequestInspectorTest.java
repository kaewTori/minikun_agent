package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRequestInspectorTest {
    private final ChatRequestInspector inspector = new ChatRequestInspector();

    @Test
    void identifiesInternalTitleRequestsAndKeepsOnlyConversationMessages() {
        ChatCompletionRequest request = request(List.of(
                new Message("system", "Generate a concise title summarizing the chat history"),
                new Message("user", "สวัสดี"),
                new Message("assistant", "สวัสดีครับ")));

        assertTrue(inspector.isInternalTitleRequest(request));
        assertFalse(inspector.shouldPersist(request));
        assertEquals(List.of("user", "assistant"),
                inspector.titleMessages(request).stream().map(message -> message.role()).toList());
    }

    @Test
    void selectsTheLastNonBlankUserMessage() {
        List<Message> messages = List.of(
                new Message("user", "first"),
                new Message("assistant", "answer"),
                new Message("user", "latest"));

        assertEquals(2, inspector.lastUserMessageIndex(messages));
    }

    private ChatCompletionRequest request(List<Message> messages) {
        return new ChatCompletionRequest("mini-kun", messages, "conversation", false, null, null, null);
    }
}
