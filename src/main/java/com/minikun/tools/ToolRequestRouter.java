package com.minikun.tools;

import java.util.Optional;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.weather.DeviceLocation;

/** Routes an unambiguous user request directly to a tool before model generation. */
public interface ToolRequestRouter {
    Optional<ToolEvidence> route(String userText, ConversationId conversationId);

    /** Routes with the authenticated/request-scoped owner when a router needs owner isolation. */
    default Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId) {
        return route(userText, conversationId);
    }

    default Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId,
            DeviceLocation deviceLocation) {
        return route(userText, conversationId, ownerId);
    }
}
