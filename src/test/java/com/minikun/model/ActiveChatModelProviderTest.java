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
    void rejectsRemovedTinyGradModel() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ActiveModelConfiguration.parse("tinygrad"));

        assertEquals(true, exception.getMessage().contains("expected existing"));
    }

    @Test
    void resolvesConfiguredProviderThroughRegistry() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        org.mockito.Mockito.when(provider.id()).thenReturn(ChatModelId.EXISTING);
        ChatModelProviderRegistry registry = new DefaultChatModelProviderRegistry(List.of(provider));

        ActiveChatModelProvider activeProvider = new DefaultActiveChatModelProvider(
                ActiveModelConfiguration.parse("existing"), registry);

        assertEquals(ChatModelId.EXISTING, activeProvider.get().id());
    }

    @Test
    void failsWhenConfiguredProviderIsNotRegistered() {
        ChatModelProviderRegistry registry = new DefaultChatModelProviderRegistry(List.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new DefaultActiveChatModelProvider(
                        ActiveModelConfiguration.parse("existing"), registry));

        assertEquals(true, exception.getMessage().contains("EXISTING"));
    }
}
