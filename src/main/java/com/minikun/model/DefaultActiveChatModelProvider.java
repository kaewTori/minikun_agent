package com.minikun.model;

import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class DefaultActiveChatModelProvider implements ActiveChatModelProvider {
    private final ChatModelProvider provider;

    public DefaultActiveChatModelProvider(
            ActiveModelConfiguration configuration,
            ChatModelProviderRegistry registry) {
        this(configuration, registry, false, (ModelPerformanceMetrics) null);
    }

    @Autowired
    public DefaultActiveChatModelProvider(
            ActiveModelConfiguration configuration,
            ChatModelProviderRegistry registry,
            @Value("${minikun.model.tinygrad.failover.enabled:true}") boolean tinyGradFailoverEnabled,
            @Value("${spring.ai.ollama.chat.options.model:}") String ollamaFallbackModel,
            ObjectProvider<ModelPerformanceMetrics> metricsProvider) {
        this(configuration, registry, tinyGradFailoverEnabled, ollamaFallbackModel,
                metricsProvider == null ? null : metricsProvider.getIfAvailable());
    }

    DefaultActiveChatModelProvider(
            ActiveModelConfiguration configuration,
            ChatModelProviderRegistry registry,
            boolean tinyGradFailoverEnabled,
            ModelPerformanceMetrics metrics) {
        this(configuration, registry, tinyGradFailoverEnabled, null, metrics);
    }

    DefaultActiveChatModelProvider(
            ActiveModelConfiguration configuration,
            ChatModelProviderRegistry registry,
            boolean tinyGradFailoverEnabled,
            String ollamaFallbackModel,
            ModelPerformanceMetrics metrics) {
        Objects.requireNonNull(configuration, "active model configuration must not be null");
        Objects.requireNonNull(registry, "chat model provider registry must not be null");
        ChatModelProvider active = registry.get(configuration.active());
        this.provider = configuration.active() == ChatModelId.TINYGRAD && tinyGradFailoverEnabled
                ? new FailoverChatModelProvider(
                        active, registry.get(ChatModelId.EXISTING), metrics, ollamaFallbackModel)
                : active;
    }

    @Override
    public ChatModelProvider get() {
        return provider;
    }
}
