package com.minikun.model.task.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;

class TitleGenerationServiceTest {
    @Test
    void returnsProviderTitle() {
        TitleGenerationProvider provider = mock(TitleGenerationProvider.class);
        when(provider.generateTitle(anyList())).thenReturn("PostgreSQL Configuration");

        String title = new TitleGenerationService(provider).generateTitle(
                List.of(new ChatMessage("user", "How do I configure PostgreSQL?")));

        assertEquals("PostgreSQL Configuration", title);
    }

    @Test
    void titleProviderFailureReturnsFallbackInsteadOfThrowing() {
        TitleGenerationProvider provider = mock(TitleGenerationProvider.class);
        when(provider.generateTitle(anyList())).thenThrow(new IllegalStateException("timeout"));

        String title = new TitleGenerationService(provider).generateTitle(
                List.of(new ChatMessage("user", "How do I configure PostgreSQL?")));

        assertEquals(TitleGenerationService.FALLBACK_TITLE, title);
    }
}
