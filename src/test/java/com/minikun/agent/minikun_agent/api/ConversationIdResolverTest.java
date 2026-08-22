package com.minikun.agent.minikun_agent.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;

class ConversationIdResolverTest {
    private final ConversationIdResolver resolver = new ConversationIdResolver();

    @Test
    void explicitHeaderWinsAndSourceIsReported() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Conversation-Id", "header-id");
        http.addHeader("X-OpenWebUI-Chat-Id", "openwebui-id");

        var result = resolver.resolveDetails(request("body-id"), http);

        assertEquals("header-id", result.conversationId().value());
        assertEquals(ConversationIdResolver.Source.EXPLICIT_HEADER, result.source());
    }

    @Test
    void requestBodyPrecedesTransportIdentifiers() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-OpenWebUI-Chat-Id", "openwebui-id");

        var result = resolver.resolveDetails(request("body-id"), http);

        assertEquals("body-id", result.conversationId().value());
        assertEquals(ConversationIdResolver.Source.REQUEST_BODY, result.source());
    }

    @Test
    void oversizedTransportIdentifierIsMappedToAStableDatabaseSafeId() {
        String oversized = "chat-" + "a".repeat(100);
        MockHttpServletRequest first = new MockHttpServletRequest();
        MockHttpServletRequest second = new MockHttpServletRequest();
        first.addHeader("X-Chat-Id", oversized);
        second.addHeader("X-Chat-Id", oversized);

        String firstId = resolver.resolveDetails(request(null), first).conversationId().value();
        String secondId = resolver.resolveDetails(request(null), second).conversationId().value();

        assertEquals(firstId, secondId);
        assertEquals(36, firstId.length());
    }

    @Test
    void missingIdentifierGeneratesANewConversation() {
        var first = resolver.resolveDetails(request(null), new MockHttpServletRequest());
        var second = resolver.resolveDetails(request(null), new MockHttpServletRequest());

        assertEquals(ConversationIdResolver.Source.GENERATED, first.source());
        assertNotEquals(first.conversationId(), second.conversationId());
    }

    private ChatCompletionRequest request(String conversationId) {
        return new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "hello")), conversationId,
                false, null, null, null);
    }
}
