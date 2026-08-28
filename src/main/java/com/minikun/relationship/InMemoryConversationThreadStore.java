package com.minikun.relationship;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryConversationThreadStore implements ConversationThreadStore {
    private final Map<UUID, ConversationThread> values = new ConcurrentHashMap<>();
    private final Map<UUID, String> fingerprints = new ConcurrentHashMap<>();

    @Override
    public ConversationThread save(ConversationThread value) {
        values.put(value.id(), value);
        fingerprints.put(value.id(), ConversationThreadService.fingerprint(value.topic()));
        return value;
    }

    @Override
    public Optional<ConversationThread> find(String ownerId, UUID id) {
        return Optional.ofNullable(values.get(id)).filter(value -> value.ownerId().equals(ownerId));
    }

    @Override
    public Optional<ConversationThread> findOpenByFingerprint(String ownerId, String fingerprint) {
        return values.values().stream().filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.status() == ConversationThreadStatus.OPEN)
                .filter(value -> fingerprint.equals(fingerprints.get(value.id()))).findFirst();
    }

    @Override
    public List<ConversationThread> list(String ownerId, ConversationThreadStatus status, int limit) {
        return values.values().stream().filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> status == null || value.status() == status)
                .sorted(Comparator.comparing(ConversationThread::updatedAt).reversed())
                .limit(Math.max(0, limit)).toList();
    }

    @Override
    public List<ConversationThread> due(Instant now, int limit) {
        return values.values().stream().filter(value -> value.dueAt(now))
                .sorted(Comparator.comparing(ConversationThread::checkInAt))
                .limit(Math.max(0, limit)).toList();
    }
}
