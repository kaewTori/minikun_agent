package com.minikun.model.task.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;

class OllamaTitleGenerationProviderTest {
    @Test
    void mapsConfiguredModelAndNonStreamingRequest() {
        OllamaApi ollamaApi = mock(OllamaApi.class);
        OllamaApi.ChatResponse response = mock(OllamaApi.ChatResponse.class);
        OllamaApi.Message message = mock(OllamaApi.Message.class);
        when(ollamaApi.chat(any())).thenReturn(response);
        when(response.message()).thenReturn(message);
        when(message.content()).thenReturn("Postgres Setup");

        OllamaTitleGenerationProvider provider = new OllamaTitleGenerationProvider(
                ollamaApi, "qwen3.5:0.6b", Duration.ofSeconds(2), new TitlePromptBuilder());

        assertEquals("Postgres Setup", provider.generateTitle(
                List.of(new ChatMessage("user", "How do I configure PostgreSQL?"))));
        verify(ollamaApi).chat(any(OllamaApi.ChatRequest.class));
    }

    @Test
    void malformedOrEmptyResponseFailsForServiceFallback() {
        OllamaApi ollamaApi = mock(OllamaApi.class);
        OllamaApi.ChatResponse response = mock(OllamaApi.ChatResponse.class);
        OllamaApi.Message message = mock(OllamaApi.Message.class);
        when(ollamaApi.chat(any())).thenReturn(response);
        when(response.message()).thenReturn(message);
        when(message.content()).thenReturn(" ");

        OllamaTitleGenerationProvider provider = new OllamaTitleGenerationProvider(
                ollamaApi, "qwen3.5:0.6b", Duration.ofSeconds(2), new TitlePromptBuilder());

        assertThrows(IllegalStateException.class, () -> provider.generateTitle(
                List.of(new ChatMessage("user", "Question"))));
    }
}
