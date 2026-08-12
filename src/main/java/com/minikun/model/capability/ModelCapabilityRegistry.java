package com.minikun.model.capability;

import com.minikun.model.ChatModelId;

public interface ModelCapabilityRegistry {
    ModelCapability get(ChatModelId modelId);
}
