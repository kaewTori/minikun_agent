package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

class ActiveChatModelProviderTest {
    @Test
    void parsesExistingByDefault() {
        assertEquals(ChatModelId.EXISTING, ActiveModelConfiguration.parse("existing").active());
    }

    @Test
    void parsesTinyGrad() {
        assertEquals(ChatModelId.TINYGRAD, ActiveModelConfiguration.parse("tinygrad").active());
    }

    @Test
    void rejectsUnknownModel() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ActiveModelConfiguration.parse("unknown"));

        assertEquals(true, exception.getMessage().contains("minikun.model.active"));
    }

    @Test
    void resolvesConfiguredProviderThroughRegistry() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        org.mockito.Mockito.when(provider.id()).thenReturn(ChatModelId.TINYGRAD);
        ChatModelProviderRegistry registry = new DefaultChatModelProviderRegistry(List.of(provider));

        ActiveChatModelProvider activeProvider = new DefaultActiveChatModelProvider(
                ActiveModelConfiguration.parse("tinygrad"), registry);

        assertEquals(ChatModelId.TINYGRAD, activeProvider.get().id());
    }

    @Test
    void failsWhenConfiguredProviderIsNotRegistered() {
        ChatModelProviderRegistry registry = new DefaultChatModelProviderRegistry(List.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new DefaultActiveChatModelProvider(
                        ActiveModelConfiguration.parse("tinygrad"), registry));

        assertEquals(true, exception.getMessage().contains("TINYGRAD"));
    }
}