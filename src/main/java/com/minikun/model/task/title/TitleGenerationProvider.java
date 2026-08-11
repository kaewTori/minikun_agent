package com.minikun.model.task.title;

import java.util.List;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;

public interface TitleGenerationProvider {
    String generateTitle(List<ChatMessage> messages);
}
