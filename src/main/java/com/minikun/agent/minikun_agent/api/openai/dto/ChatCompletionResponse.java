package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
                Usage usage,
                List<ChatAttachment> attachments
) {
        public ChatCompletionResponse(
                        String id,
                        String object,
                        long created,
                        String model,
                        List<Choice> choices,
                        Usage usage) {
                this(id, object, created, model, choices, usage, List.of());
        }

        public ChatCompletionResponse {
                attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

    public record Choice(int index, Message message, String finish_reason) {}

    public record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {}

    public record StreamChunk(
            String id,
            String object,
            long created,
            String model,
            java.util.List<StreamChoice> choices
    ) {}

    public record StreamChoice(int index, Delta delta, String finish_reason) {}

    public record Delta(String role, String content) {}
}
