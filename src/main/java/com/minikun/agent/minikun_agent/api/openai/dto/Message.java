package com.minikun.agent.minikun_agent.api.openai.dto;

public record Message(
        String role,
        String content
) {}
