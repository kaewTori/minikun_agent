package com.minikun.visual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryImageGenerationHistoryStore implements ImageGenerationHistoryStore {
    private final List<ImageGenerationHistory> histories = new CopyOnWriteArrayList<>();

    @Override
    public void save(ImageGenerationHistory history) {
        histories.add(history);
    }

    @Override
    public List<ImageGenerationHistory> find(String ownerId, String conversationId, int limit) {
        int safeLimit = Math.max(0, Math.min(limit, 500));
        if (safeLimit == 0) return List.of();
        List<ImageGenerationHistory> result = new ArrayList<>();
        histories.stream()
                .filter(item -> item.ownerId().equals(ownerId) && item.conversationId().equals(conversationId))
                .sorted(Comparator.comparing(ImageGenerationHistory::createdAt).reversed())
                .limit(safeLimit)
                .forEach(result::add);
        return List.copyOf(result);
    }
}
