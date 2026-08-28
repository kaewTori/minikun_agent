package com.minikun.personality.companion;

import java.util.Optional;

public interface CompanionModeStore {
    Optional<CompanionMode> find(String ownerId, String conversationId);
    void save(String ownerId, String conversationId, CompanionMode mode);
    void delete(String ownerId, String conversationId);
}
