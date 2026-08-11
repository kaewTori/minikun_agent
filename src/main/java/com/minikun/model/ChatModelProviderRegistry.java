package com.minikun.model;

public interface ChatModelProviderRegistry {
    ChatModelProvider get(ChatModelId id);
}