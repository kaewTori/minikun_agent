package com.minikun.agent.minikun_agent.api;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.conversation.ConversationId;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class ConversationIdResolver {

    public ConversationId resolve(ChatCompletionRequest request, HttpServletRequest httpRequest) {
        return resolveDetails(request, httpRequest).conversationId();
    }

    public Resolution resolveDetails(ChatCompletionRequest request, HttpServletRequest httpRequest) {
        Candidate[] candidates = {
                new Candidate(httpRequest.getHeader("X-Conversation-Id"), Source.EXPLICIT_HEADER),
                new Candidate(request.conversation_id(), Source.REQUEST_BODY),
                new Candidate(httpRequest.getHeader("X-OpenWebUI-Chat-Id"), Source.OPENWEBUI_HEADER),
                new Candidate(httpRequest.getHeader("X-Chat-Id"), Source.TRANSPORT_HEADER),
                new Candidate(httpRequest.getHeader("Chat-Id"), Source.TRANSPORT_HEADER)
        };
        for (Candidate candidate : candidates) {
            if (candidate.value() != null && !candidate.value().isBlank()) {
                return new Resolution(ConversationId.fromTransport(candidate.value()), candidate.source());
            }
        }
        return new Resolution(new ConversationId(UUID.randomUUID().toString()), Source.GENERATED);
    }

    public record Resolution(ConversationId conversationId, Source source) {
    }

    public enum Source {
        EXPLICIT_HEADER,
        REQUEST_BODY,
        OPENWEBUI_HEADER,
        TRANSPORT_HEADER,
        GENERATED
    }

    private record Candidate(String value, Source source) {
    }
}
