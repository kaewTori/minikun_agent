package com.minikun.agent.minikun_agent.conversation;

import java.util.Optional;

public interface ConversationSummaryStore {
    Optional<ConversationSummary> find(String ownerId, ConversationId conversationId);

    void save(ConversationSummary summary);

    boolean delete(String ownerId, ConversationId conversationId);
}
