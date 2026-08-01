package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.List;

public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        String conversation_id,
        Boolean stream,
        Double temperature,
        Integer max_tokens,
        Integer max_completion_tokens
) {}
