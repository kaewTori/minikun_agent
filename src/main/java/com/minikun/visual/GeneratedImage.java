package com.minikun.visual;

import java.util.Objects;

/** Bounded image bytes returned by an image-generation provider. */
public record GeneratedImage(byte[] bytes, String provider) {
    public GeneratedImage {
        Objects.requireNonNull(bytes, "image bytes must not be null");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("image bytes must not be empty");
        }
        bytes = bytes.clone();
        provider = provider == null ? "" : provider.strip();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
