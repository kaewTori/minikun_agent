package com.minikun.visual;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Reproducible metadata for one locally generated image. */
public record ImageGenerationHistory(
        UUID id,
        String ownerId,
        String conversationId,
        String origin,
        String illustrationMode,
        String sceneTitle,
        String prompt,
        String negativePrompt,
        long seed,
        String provider,
        Integer width,
        Integer height,
        Integer steps,
        String imageUrl,
        Instant createdAt) {

    public ImageGenerationHistory {
        Objects.requireNonNull(id, "history id must not be null");
        ownerId = required(ownerId, "owner id");
        conversationId = required(conversationId, "conversation id");
        origin = optional(origin);
        illustrationMode = optional(illustrationMode);
        sceneTitle = optional(sceneTitle);
        prompt = required(prompt, "prompt");
        negativePrompt = optional(negativePrompt);
        provider = optional(provider);
        imageUrl = required(imageUrl, "image URL");
        Objects.requireNonNull(createdAt, "created at must not be null");
    }

    private static String required(String value, String name) {
        String result = optional(value);
        if (result.isBlank()) throw new IllegalArgumentException(name + " is required");
        return result;
    }

    private static String optional(String value) {
        return value == null ? "" : value.strip();
    }
}
