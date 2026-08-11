package com.minikun.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class DefaultChatModelProviderRegistry implements ChatModelProviderRegistry {
    private final Map<ChatModelId, ChatModelProvider> providers;

    public DefaultChatModelProviderRegistry(List<ChatModelProvider> providers) {
        Objects.requireNonNull(providers, "providers must not be null");
        EnumMap<ChatModelId, ChatModelProvider> registered = new EnumMap<>(ChatModelId.class);
        for (ChatModelProvider provider : providers) {
            Objects.requireNonNull(provider, "provider must not be null");
            ChatModelProvider previous = registered.put(provider.id(), provider);
            if (previous != null) {
                throw new IllegalStateException("duplicate chat model provider: " + provider.id());
            }
        }
        this.providers = Map.copyOf(registered);
    }

    @Override
    public ChatModelProvider get(ChatModelId id) {
        Objects.requireNonNull(id, "model id must not be null");
        ChatModelProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalStateException("No chat model provider registered for: " + id);
        }
        return provider;
    }
}