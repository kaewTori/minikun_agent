package com.minikun.visual;

import java.util.Objects;

/** Bounded image bytes and the effective prompt returned by an image-generation provider. */
public record GeneratedImage(
        byte[] bytes,
        String provider,
        String effectivePrompt,
        String effectiveNegativePrompt) {

    public GeneratedImage(byte[] bytes, String provider) {
        this(bytes, provider, null, null);
    }

    public GeneratedImage {
        Objects.requireNonNull(bytes, "image bytes must not be null");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("image bytes must not be empty");
        }
        bytes = bytes.clone();
        provider = provider == null ? "" : provider.strip();
        effectivePrompt = effectivePrompt == null ? null : effectivePrompt.strip();
        effectiveNegativePrompt = effectiveNegativePrompt == null ? null : effectiveNegativePrompt.strip();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
