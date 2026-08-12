package com.minikun.model.task.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;

class OllamaTitleGenerationProviderTest {
    @Test
    void mapsConfiguredModelAndNonStreamingRequest() {
        TaskModelProvider taskModelProvider = mock(TaskModelProvider.class);
        when(taskModelProvider.generate(any(TaskModelRequest.class))).thenReturn("Postgres Setup");

        OllamaTitleGenerationProvider provider = new OllamaTitleGenerationProvider(
                taskModelProvider, new TitlePromptBuilder());

        assertEquals("Postgres Setup", provider.generateTitle(
                List.of(new ChatMessage("user", "How do I configure PostgreSQL?"))));
        verify(taskModelProvider).generate(any(TaskModelRequest.class));
    }

    @Test
    void malformedOrEmptyResponseFailsForServiceFallback() {
        TaskModelProvider taskModelProvider = mock(TaskModelProvider.class);
        when(taskModelProvider.generate(any(TaskModelRequest.class))).thenReturn(" ");

        OllamaTitleGenerationProvider provider = new OllamaTitleGenerationProvider(
                taskModelProvider, new TitlePromptBuilder());

        assertThrows(IllegalStateException.class, () -> provider.generateTitle(
                List.of(new ChatMessage("user", "Question"))));
    }
}
