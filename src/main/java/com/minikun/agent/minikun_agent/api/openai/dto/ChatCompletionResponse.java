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
            java.util.List<StreamChoice> choices,
            java.util.List<ChatAttachment> attachments,
            Usage usage
    ) {
        public StreamChunk(
                String id,
                String object,
                long created,
                String model,
                java.util.List<StreamChoice> choices) {
            this(id, object, created, model, choices, java.util.List.of(), null);
        }

        public StreamChunk(
                String id,
                String object,
                long created,
                String model,
                java.util.List<StreamChoice> choices,
                java.util.List<ChatAttachment> attachments) {
            this(id, object, created, model, choices, attachments, null);
        }

        public StreamChunk {
            attachments = attachments == null ? java.util.List.of() : java.util.List.copyOf(attachments);
        }
    }

    public record StreamChoice(int index, Delta delta, String finish_reason) {}

    public record Delta(
            String role,
            String content,
            java.util.List<Image> images) {
        public Delta(String role, String content) {
            this(role, content, java.util.List.of());
        }

        public Delta {
            images = images == null ? java.util.List.of() : java.util.List.copyOf(images);
        }
    }

    public record Image(String type, ImageUrl image_url) {}

    public record ImageUrl(String url) {}
}
