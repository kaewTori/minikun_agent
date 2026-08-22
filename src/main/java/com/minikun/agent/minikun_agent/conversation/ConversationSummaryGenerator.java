package com.minikun.agent.minikun_agent.conversation;

import java.util.List;

@FunctionalInterface
public interface ConversationSummaryGenerator {
    String update(String existingSummary, List<ChatMessage> newOlderMessages);
}
