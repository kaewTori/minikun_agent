package com.minikun.tools;

import java.util.Optional;

import com.minikun.agent.minikun_agent.conversation.ConversationId;

/** Routes an unambiguous user request directly to a tool before model generation. */
public interface ToolRequestRouter {
    Optional<ToolEvidence> route(String userText, ConversationId conversationId);
}
