package com.minikun.sync;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryChatSyncRepository implements ChatSyncRepository {
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ChatSyncConversation>> owners =
            new ConcurrentHashMap<>();

    @Override public List<ChatSyncConversation> list(String ownerId, int limit) {
        return owners.getOrDefault(ownerId, new ConcurrentHashMap<>()).values().stream()
                .sorted((left, right) -> right.updatedAt().compareTo(left.updatedAt())).limit(limit).toList();
    }

    @Override public void upsert(String ownerId, ChatSyncConversation conversation) {
        owners.computeIfAbsent(ownerId, ignored -> new ConcurrentHashMap<>())
                .merge(conversation.id(), conversation, this::merge);
    }

    @Override public boolean delete(String ownerId, String conversationId) {
        ConcurrentHashMap<String, ChatSyncConversation> values = owners.get(ownerId);
        return values != null && values.remove(conversationId) != null;
    }

    @Override public void deleteAll(String ownerId) { owners.remove(ownerId); }

    private ChatSyncConversation merge(ChatSyncConversation current, ChatSyncConversation update) {
        boolean newer = !update.updatedAt().isBefore(current.updatedAt());
        return newer ? update : current;
    }
}
