package com.minikun.search.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A validated image payload for a reverse-image search provider. */
public record ImageSearchRequest(
        UUID requestId,
        byte[] bytes,
        String mimeType,
        int resultLimit,
        Instant deadline) {
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    public ImageSearchRequest {
        Objects.requireNonNull(requestId, "request id must not be null");
        Objects.requireNonNull(bytes, "image bytes must not be null");
        Objects.requireNonNull(mimeType, "image media type must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        bytes = bytes.clone();
        mimeType = mimeType.trim().toLowerCase(Locale.ROOT);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("image bytes must not be empty");
        }
        if (!IMAGE_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("unsupported image media type");
        }
        if (resultLimit < 1 || resultLimit > SearchRequest.MAX_RESULT_LIMIT) {
            throw new IllegalArgumentException(
                    "result limit must be between 1 and " + SearchRequest.MAX_RESULT_LIMIT);
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
