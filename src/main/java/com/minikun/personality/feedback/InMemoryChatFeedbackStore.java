package com.minikun.personality.feedback;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryChatFeedbackStore implements ChatFeedbackStore {
    private final Map<String, ChatFeedback> values = new ConcurrentHashMap<>();
    @Override public ChatFeedback save(ChatFeedback feedback) {
        values.put(feedback.ownerId() + "\u0000" + feedback.conversationId() + "\u0000" + feedback.messageId(), feedback);
        return feedback;
    }
    @Override public List<ChatFeedback> list(String ownerId, int limit) {
        return values.values().stream().filter(value -> value.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(ChatFeedback::createdAt).reversed()).limit(limit).toList();
    }
    @Override public boolean delete(String ownerId, String conversationId, String messageId) {
        return values.remove(ownerId + "\u0000" + conversationId + "\u0000" + messageId) != null;
    }
}
