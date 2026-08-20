package com.minikun.communication;

/** Input shared by the HTTP API and the owner-scoped native tool. */
public record CommunicationRequest(
        String ownerId,
        String action,
        String content,
        String context,
        String goal,
        String audience,
        String channel,
        String tone,
        String language,
        Integer maxLength) {
}
