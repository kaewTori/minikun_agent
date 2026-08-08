package com.minikun.pcs;

import java.util.Objects;

public record ContextCompressionResult(
        ContextItem originalItem,
        ContextItem compressedItem) {
    public ContextCompressionResult {
        Objects.requireNonNull(originalItem, "original item must not be null");
        Objects.requireNonNull(compressedItem, "compressed item must not be null");
        if (originalItem.section() != compressedItem.section()
                || originalItem.priority() != compressedItem.priority()
                || originalItem.required() != compressedItem.required()) {
            throw new IllegalArgumentException("compression must preserve item metadata");
        }
        if (compressedItem.size() > originalItem.size()) {
            throw new IllegalArgumentException("compressed content must not be larger than original content");
        }
    }

    public long originalSize() {
        return originalItem.content().length();
    }

    public long compressedSize() {
        return compressedItem.content().length();
    }

    public long charactersSaved() {
        return Math.max(0L, originalSize() - compressedSize());
    }

    public boolean compressionApplied() {
        return !originalItem.content().equals(compressedItem.content());
    }
}