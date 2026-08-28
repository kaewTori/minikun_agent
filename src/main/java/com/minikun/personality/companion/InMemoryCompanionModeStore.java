package com.minikun.personality.companion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCompanionModeStore implements CompanionModeStore {
    private final Map<String, CompanionMode> values;
    public InMemoryCompanionModeStore(int maximumSessions) {
        int maximum = Math.max(10, Math.min(maximumSessions, 100_000));
        values = Collections.synchronizedMap(new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CompanionMode> eldest) {
                return size() > maximum;
            }
        });
    }
    private String key(String owner, String conversation) { return owner + "\u0000" + conversation; }
    @Override public Optional<CompanionMode> find(String ownerId, String conversationId) {
        return Optional.ofNullable(values.get(key(ownerId, conversationId)));
    }
    @Override public void save(String ownerId, String conversationId, CompanionMode mode) {
        values.put(key(ownerId, conversationId), mode);
    }
    @Override public void delete(String ownerId, String conversationId) {
        values.remove(key(ownerId, conversationId));
    }
}
