package com.minikun.vision;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.content.Media;

public record VisionInput(List<Media> media, List<Image> images) {
    public static final VisionInput EMPTY = new VisionInput(List.of(), List.of());

    public VisionInput(List<Media> media) {
        this(media, List.of());
    }

    public VisionInput {
        media = media == null ? List.of() : List.copyOf(media);
        images = images == null ? List.of() : List.copyOf(images);
    }

    public boolean hasImages() {
        return !media.isEmpty() || !images.isEmpty();
    }

    public int imageCount() {
        return Math.max(media.size(), images.size());
    }

    public record Image(String mimeType, byte[] bytes) {
        public Image {
            mimeType = Objects.requireNonNull(mimeType, "image media type must not be null");
            bytes = Objects.requireNonNull(bytes, "image bytes must not be null").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
