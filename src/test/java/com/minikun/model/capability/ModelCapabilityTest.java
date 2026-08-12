package com.minikun.model.capability;

import com.minikun.model.ChatModelId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCapabilityTest {
    @Test
    void storesCapabilityValues() {
        ModelCapability capability = new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT, 8192, 2048);

        assertEquals(ChatModelId.TINYGRAD, capability.modelId());
        assertEquals(ModelRole.CHAT, capability.role());
        assertEquals(8192, capability.contextWindowTokens());
        assertEquals(2048, capability.maxOutputTokens());
    }

    @Test
    void acceptsZeroValues() {
        ModelCapability capability = new ModelCapability(ChatModelId.EXISTING, ModelRole.TASK, 0, 0);

        assertEquals(0, capability.contextWindowTokens());
        assertEquals(0, capability.maxOutputTokens());
    }

    @Test
    void rejectsNullReferences() {
        assertThrows(NullPointerException.class,
                () -> new ModelCapability(null, ModelRole.CHAT, 1, 1));
        assertThrows(NullPointerException.class,
                () -> new ModelCapability(ChatModelId.TINYGRAD, null, 1, 1));
    }

    @Test
    void rejectsNegativeTokenLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelCapability(ChatModelId.TINYGRAD, ModelRole.CHAT, 1, -1));
    }
}
