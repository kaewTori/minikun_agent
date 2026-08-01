package com.minikun.agent.minikun_agent.api;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.conversation.ConversationId;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class ConversationIdResolver {

    public ConversationId resolve(ChatCompletionRequest request, HttpServletRequest httpRequest) {
        String identifier = firstText(
                httpRequest.getHeader("X-Conversation-Id"),
                request.conversation_id(),
                httpRequest.getHeader("X-OpenWebUI-Chat-Id"),
                httpRequest.getHeader("X-Chat-Id"));
        return new ConversationId(identifier == null ? UUID.randomUUID().toString() : identifier);
    }

    private String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }
}