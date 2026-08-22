package com.minikun.agent.minikun_agent.conversation;

import java.time.Instant;

/** Owner-scoped operational view; raw transcript content is never exposed here. */
public record ConversationSummaryStatus(
        String ownerId,
        String conversationId,
        boolean enabled,
        boolean present,
        String content,
        int summarizedMessages,
        Instant updatedAt,
        long ageSeconds,
        int historyMessages,
        boolean updatePending) {
}
