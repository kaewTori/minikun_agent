package com.minikun.model.capability;

import com.minikun.model.ChatModelId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCapabilityRegistryTest {
    @Test
    void looksUpExistingModelCapability() {
        ModelCapability capability = capability(ChatModelId.EXISTING);
        ModelCapabilityRegistry registry = new DefaultModelCapabilityRegistry(
                Map.of(ChatModelId.EXISTING, capability));

        assertEquals(capability, registry.get(ChatModelId.EXISTING));
    }

    @Test
    void rejectsUnknownAndNullModels() {
        ModelCapabilityRegistry registry = new DefaultModelCapabilityRegistry(Map.of());

        assertThrows(IllegalArgumentException.class, () -> registry.get(ChatModelId.EXISTING));
        assertThrows(IllegalArgumentException.class, () -> registry.get(null));
    }

    @Test
    void snapshotsInputMap() {
        ModelCapability capability = capability(ChatModelId.EXISTING);
        Map<ChatModelId, ModelCapability> source = new HashMap<>();
        source.put(ChatModelId.EXISTING, capability);
        ModelCapabilityRegistry registry = new DefaultModelCapabilityRegistry(source);

        source.clear();

        assertEquals(capability, registry.get(ChatModelId.EXISTING));
    }

    @Test
    void rejectsNullEntries() {
        assertThrows(NullPointerException.class,
                () -> new DefaultModelCapabilityRegistry(Map.of(ChatModelId.EXISTING, null)));
    }

    private ModelCapability capability(ChatModelId modelId) {
        return new ModelCapability(modelId, ModelRole.CHAT, 8192, 2048);
    }
}
