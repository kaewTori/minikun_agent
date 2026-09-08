package com.minikun.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultChatModelProviderRegistryTest {
    @Test
    void registersAndLooksUpProviderById() {
        ChatModelProvider existing = provider(ChatModelId.EXISTING);
        DefaultChatModelProviderRegistry registry = new DefaultChatModelProviderRegistry(List.of(existing));

        assertEquals(existing, registry.get(ChatModelId.EXISTING));
    }

    @Test
    void rejectsMissingProvider() {
        DefaultChatModelProviderRegistry registry = new DefaultChatModelProviderRegistry(List.of());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> registry.get(ChatModelId.EXISTING));

        assertEquals("No chat model provider registered for: EXISTING", exception.getMessage());
    }

    @Test
    void rejectsDuplicateProviderIds() {
        ChatModelProvider first = provider(ChatModelId.EXISTING);
        ChatModelProvider second = provider(ChatModelId.EXISTING);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new DefaultChatModelProviderRegistry(List.of(first, second)));

        assertEquals("duplicate chat model provider: EXISTING", exception.getMessage());
    }

    private ChatModelProvider provider(ChatModelId id) {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.id()).thenReturn(id);
        return provider;
    }
}
