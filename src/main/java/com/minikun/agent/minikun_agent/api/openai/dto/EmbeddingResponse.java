package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.List;

public record EmbeddingResponse(
        String object,
        List<Data> data,
        String model,
        Usage usage
) {
    public record Data(String object, List<Float> embedding, int index) {}

    public record Usage(int prompt_tokens, int total_tokens) {}
}