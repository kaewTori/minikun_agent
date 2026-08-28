package com.minikun.personality.feedback;

import java.util.List;

public interface ChatFeedbackStore {
    ChatFeedback save(ChatFeedback feedback);
    List<ChatFeedback> list(String ownerId, int limit);
    boolean delete(String ownerId, String conversationId, String messageId);
}
