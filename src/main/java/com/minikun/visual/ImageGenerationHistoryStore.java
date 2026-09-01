package com.minikun.visual;

import java.util.List;

public interface ImageGenerationHistoryStore {
    void save(ImageGenerationHistory history);

    List<ImageGenerationHistory> find(String ownerId, String conversationId, int limit);

    static ImageGenerationHistoryStore noop() {
        return new ImageGenerationHistoryStore() {
            @Override public void save(ImageGenerationHistory history) { }
            @Override public List<ImageGenerationHistory> find(
                    String ownerId, String conversationId, int limit) {
                return List.of();
            }
        };
    }
}
