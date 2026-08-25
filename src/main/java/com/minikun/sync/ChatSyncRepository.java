package com.minikun.sync;

import java.util.List;

public interface ChatSyncRepository {
    List<ChatSyncConversation> list(String ownerId, int limit);

    void upsert(String ownerId, ChatSyncConversation conversation);

    boolean delete(String ownerId, String conversationId);

    void deleteAll(String ownerId);
}
