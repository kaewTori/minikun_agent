package com.minikun.visual;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCharacterVisualMemory implements CharacterVisualMemory {
    private final Map<String, List<CharacterVisualProfile>> values = new ConcurrentHashMap<>();

    @Override
    public List<CharacterVisualProfile> find(String ownerId, String conversationId) {
        return values.getOrDefault(scope(ownerId, conversationId), List.of());
    }

    @Override
    public void save(String ownerId, String conversationId, List<CharacterVisualProfile> profiles) {
        values.put(scope(ownerId, conversationId), profiles == null ? List.of() : List.copyOf(profiles));
    }

    private String scope(String ownerId, String conversationId) {
        return required(ownerId, "owner id") + "\u0000" + required(conversationId, "conversation id");
    }

    private String required(String value, String name) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank()) throw new IllegalArgumentException(name + " is required");
        return result;
    }
}
