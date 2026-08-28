package com.minikun.relationship;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationThreadStore {
    ConversationThread save(ConversationThread value);
    Optional<ConversationThread> find(String ownerId, UUID id);
    Optional<ConversationThread> findOpenByFingerprint(String ownerId, String fingerprint);
    List<ConversationThread> list(String ownerId, ConversationThreadStatus status, int limit);
    List<ConversationThread> due(Instant now, int limit);
}
