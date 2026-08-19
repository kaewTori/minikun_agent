package com.minikun.vision;

import java.util.List;

import org.springframework.ai.content.Media;

public record VisionInput(List<Media> media) {
    public static final VisionInput EMPTY = new VisionInput(List.of());

    public VisionInput {
        media = media == null ? List.of() : List.copyOf(media);
    }

    public boolean hasImages() {
        return !media.isEmpty();
    }

    public int imageCount() {
        return media.size();
    }
}
