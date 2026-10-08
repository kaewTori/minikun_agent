package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.model.DefaultChatModelProviderRegistry;
import com.minikun.model.ActiveModelConfiguration;
import com.minikun.model.ChatModelId;
import com.minikun.model.DefaultActiveChatModelProvider;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.diagnostics.DiagnosticsSummary;
import com.minikun.memory.MemoryRecallService;
import com.minikun.pcs.MinikunPersonaProvider;
import com.minikun.pcs.PromptComposer;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchService;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;

class ChatServiceDiagnosticsTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void disabledModeUsesFormatterWithoutCallingLlm() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        DiagnosticsService diagnosticsService = mock(DiagnosticsService.class);
        DiagnosticsSummary summary = summary();
        when(diagnosticsService.summarize()).thenReturn(summary);
        ChatService service = service(chatModel, diagnosticsService);
        setConversationalMode(service, false);

        String expected = new DiagnosticsFormatter().format(summary);
        var response = service.chatCompletion(request(), new ConversationId("test"));

        assertEquals(expected, response.choices().get(0).message().content());
        verify(diagnosticsService).summarize();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void llmFailureFallsBackToTheSameSummarySnapshot() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
        DiagnosticsService diagnosticsService = mock(DiagnosticsService.class);
        DiagnosticsSummary summary = summary();
        when(diagnosticsService.summarize()).thenReturn(summary);
        ChatService service = service(chatModel, diagnosticsService);
        setConversationalMode(service, true);

        String expected = new DiagnosticsFormatter().format(summary);
        var response = service.chatCompletion(request(), new ConversationId("test"));

        assertEquals(expected, response.choices().get(0).message().content());
        verify(diagnosticsService).summarize();
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void successfulConversationUsesTheRequiredPromptOrder() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Explained diagnostics")))));
        DiagnosticsService diagnosticsService = mock(DiagnosticsService.class);
        DiagnosticsSummary summary = summary();
        when(diagnosticsService.summarize()).thenReturn(summary);
        DiagnosticsPromptBuilder builder = spy(new DiagnosticsPromptBuilder(
                new MinikunPersonaProvider(new CharacterLoader(MCS_ROOT).load())));
        ChatService service = service(chatModel, diagnosticsService, builder);
        setConversationalMode(service, true);

        var response = service.chatCompletion(request(), new ConversationId("test"));
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        verify(builder).build(eq(summary), eq("/diagnostics"));

        String system = promptCaptor.getValue().getInstructions().get(0).getText();
        assertEquals("Explained diagnostics", response.choices().get(0).message().content());
        assertTrueInOrder(system, "[Character]", "[Diagnostics instructions]", "[DiagnosticsSummary]");
        assertEquals("/diagnostics", promptCaptor.getValue().getUserMessage().getText());
    }

    @Test
    void streamingDiagnosticsRemainDeterministicWhenConversationalModeIsEnabled() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        DiagnosticsService diagnosticsService = mock(DiagnosticsService.class);
        DiagnosticsSummary summary = summary();
        when(diagnosticsService.summarize()).thenReturn(summary);
        ChatService service = service(chatModel, diagnosticsService);
        setConversationalMode(service, true);

        String expected = new DiagnosticsFormatter().format(summary);
        List<String> chunks = service.chatCompletionStream(request(), new ConversationId("test"))
                .collectList().block();

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains(expected.replace("\n", "\\n"))));
        verify(diagnosticsService).summarize();
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    private ChatService service(ChatModel chatModel, DiagnosticsService diagnosticsService) {
        return service(chatModel, diagnosticsService, null);
    }

    @Test
    void embeddingsUseOneBatchReturnActualModelAndRejectWrongModelsOrInvalidInput() {
        String name = "embeddinggemma-2:270m-mxfp8-text";
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(List.of("first", "second"))).thenReturn(List.of(new float[] {1, 0}, new float[] {0, 1}));
        ChatService service = service(mock(ChatModel.class), mock(DiagnosticsService.class), null,
                model, new ModelsService("chat", name, "memory", "task"));
        var request = new com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest("mini-kun", List.of("first", "second"));
        var response = service.embeddings(request);
        assertEquals(name, response.model());
        assertEquals(2, response.data().size());
        assertEquals(1, response.data().get(1).index());
        verify(model).embed(List.of("first", "second"));
        assertThrows(IllegalArgumentException.class, () -> service.embeddings(
                new com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest("qwen3-embedding:0.6b", "first")));
        assertThrows(IllegalArgumentException.class, () -> new com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest(name, List.of(1)).texts());
        assertThrows(IllegalArgumentException.class, () -> new com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest(name, List.of()).texts());
        when(model.embed(List.of("first", "second"))).thenReturn(List.of(new float[] {1, 0}));
        assertThrows(IllegalStateException.class, () -> service.embeddings(request));
    }

    private ChatService service(
            ChatModel chatModel, DiagnosticsService diagnosticsService, DiagnosticsPromptBuilder promptBuilder) {
        return service(chatModel, diagnosticsService, promptBuilder, mock(EmbeddingModel.class), mock(ModelsService.class));
    }

    private ChatService service(ChatModel chatModel, DiagnosticsService diagnosticsService,
            DiagnosticsPromptBuilder promptBuilder, EmbeddingModel embeddingModel, ModelsService modelsService) {
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        DiagnosticsPromptBuilder builder = promptBuilder == null
                ? spy(new DiagnosticsPromptBuilder(new MinikunPersonaProvider(character)))
                : promptBuilder;
        return new ChatService(
                new DefaultActiveChatModelProvider(
                    new ActiveModelConfiguration(ChatModelId.EXISTING),
                    new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                embeddingModel,
                mock(ChatTransactionLogger.class),
                mock(ConversationMemoryService.class),
                mock(ObjectProvider.class),
                character,
                new PromptComposer(),
                mock(SearchService.class),
                mock(SearchDecisionService.class),
                new com.minikun.search.SearchSelectionSignalMapper(),
                diagnosticsService,
                new DiagnosticsFormatter(),
                builder,
                new CommandCatalog(),
                new CommandFormatter(),
                mock(VersionService.class),
                mock(VersionFormatter.class),
                modelsService,
                mock(ModelsFormatter.class),
                mock(CacheService.class),
                mock(CacheFormatter.class),
                mock(ObjectProvider.class));
    }

    private void assertTrueInOrder(String text, String... sections) {
        int previous = -1;
        for (String section : sections) {
            int current = text.indexOf(section);
            if (current <= previous) {
                throw new AssertionError("Expected section order: " + List.of(sections));
            }
            previous = current;
        }
    }

    private void setConversationalMode(ChatService service, boolean enabled) throws Exception {
        Field field = ChatService.class.getDeclaredField("diagnosticsConversationalEnabled");
        field.setAccessible(true);
        field.setBoolean(service, enabled);
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model",
                List.of(new Message("user", "/diagnostics")),
                "test",
                false,
                null,
                null,
                null);
    }

    private DiagnosticsSummary summary() {
        return new DiagnosticsSummary(
                com.minikun.diagnostics.MetricCount.present(3),
                new com.minikun.diagnostics.DurationSummary(
                        com.minikun.diagnostics.MetricPresence.PRESENT, 3, 150, 50),
                new DiagnosticsSummary.CacheSummary(
                        com.minikun.diagnostics.MetricCount.present(1),
                        com.minikun.diagnostics.MetricCount.present(2),
                        com.minikun.diagnostics.MetricCount.present(1)),
                com.minikun.diagnostics.MetricCount.present(0),
                com.minikun.diagnostics.MetricCount.present(1),
                new DiagnosticsSummary.QualitySummary(
                        com.minikun.diagnostics.MetricCount.present(1),
                        com.minikun.diagnostics.MetricCount.present(1),
                        com.minikun.diagnostics.MetricCount.present(1)));
    }
}
