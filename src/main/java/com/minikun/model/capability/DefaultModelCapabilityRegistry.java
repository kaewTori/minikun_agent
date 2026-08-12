package com.minikun.model.capability;

import com.minikun.model.ChatModelId;

import java.util.Map;
import java.util.Objects;

public final class DefaultModelCapabilityRegistry implements ModelCapabilityRegistry {
    private final Map<ChatModelId, ModelCapability> capabilities;

    public DefaultModelCapabilityRegistry(Map<ChatModelId, ModelCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        for (Map.Entry<ChatModelId, ModelCapability> entry : capabilities.entrySet()) {
            ChatModelId modelId = Objects.requireNonNull(entry.getKey(), "model id must not be null");
            ModelCapability capability = Objects.requireNonNull(entry.getValue(), "capability must not be null");
            if (capability.modelId() != modelId) {
                throw new IllegalArgumentException("capability model id must match registry key: " + modelId);
            }
        }
        this.capabilities = Map.copyOf(capabilities);
    }

    @Override
    public ModelCapability get(ChatModelId modelId) {
        if (modelId == null) {
            throw new IllegalArgumentException("model id must not be null");
        }
        ModelCapability capability = capabilities.get(modelId);
        if (capability == null) {
            throw new IllegalArgumentException("No model capability registered for: " + modelId);
        }
        return capability;
    }
}
