package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.List;

public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        String conversation_id,
        Boolean stream,
        Double temperature,
        Integer max_tokens,
        Integer max_completion_tokens,
        String owner_id
) {
    public ChatCompletionRequest(String model, List<Message> messages, String conversationId,
            Boolean stream, Double temperature, Integer maxTokens, Integer maxCompletionTokens) {
        this(model, messages, conversationId, stream, temperature, maxTokens, maxCompletionTokens, null);
    }
}
