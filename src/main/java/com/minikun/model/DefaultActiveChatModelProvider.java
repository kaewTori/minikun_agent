package com.minikun.model;

import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class DefaultActiveChatModelProvider implements ActiveChatModelProvider {
    private final ChatModelProvider provider;

    public DefaultActiveChatModelProvider(
            ActiveModelConfiguration configuration,
            ChatModelProviderRegistry registry) {
        Objects.requireNonNull(configuration, "active model configuration must not be null");
        Objects.requireNonNull(registry, "chat model provider registry must not be null");
        this.provider = registry.get(configuration.active());
    }

    @Override
    public ChatModelProvider get() {
        return provider;
    }
}