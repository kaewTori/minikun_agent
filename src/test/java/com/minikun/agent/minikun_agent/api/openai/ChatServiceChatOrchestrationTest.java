package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.memory.MemoryRecallService;
import com.minikun.pcs.MinikunPersonaProvider;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.ImageSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchDecision;

import reactor.core.publisher.Flux;

class ChatServiceChatOrchestrationTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void blockingAndStreamingUseEquivalentPreparationOncePerRequest() {
        ChatModel blockingModel = mock(ChatModel.class);
        ChatModel streamingModel = mock(ChatModel.class);
        ConversationMemoryService blockingConversation = mock(ConversationMemoryService.class);
        ConversationMemoryService streamingConversation = mock(ConversationMemoryService.class);
        ChatResponse blockingResponse = response("blocking answer");
        ChatResponse streamingResponse = response("streaming answer");
        List<ChatMessage> history = List.of(new ChatMessage("assistant", "previous answer"));
        when(blockingConversation.load(any())).thenReturn(history);
        when(streamingConversation.load(any())).thenReturn(history);
        when(blockingModel.call(any(Prompt.class))).thenReturn(blockingResponse);
        when(streamingModel.stream(any(Prompt.class))).thenReturn(Flux.just(streamingResponse));

        ChatCompletionRequest request = request();
        ConversationId conversationId = new ConversationId("orchestration");
        ChatService blockingService = service(blockingModel, blockingConversation);
        ChatService streamingService = service(streamingModel, streamingConversation);

        blockingService.chatCompletion(request, conversationId);
        List<String> stream = streamingService.chatCompletionStream(request, conversationId)
                .collectList()
                .block();

        ArgumentCaptor<Prompt> blockingPrompt = ArgumentCaptor.forClass(Prompt.class);
        ArgumentCaptor<Prompt> streamingPrompt = ArgumentCaptor.forClass(Prompt.class);
        verify(blockingModel).call(blockingPrompt.capture());
        verify(streamingModel).stream(streamingPrompt.capture());
        assertEquals(promptText(blockingPrompt.getValue()), promptText(streamingPrompt.getValue()));
        assertTrue(stream.stream().anyMatch(chunk -> chunk.contains("streaming answer")));

        verify(blockingConversation).load(conversationId);
        verify(streamingConversation).load(conversationId);
        verify(blockingConversation, org.mockito.Mockito.times(2)).append(any(), any());
        verify(streamingConversation, org.mockito.Mockito.times(2)).append(any(), any());
        verify(blockingModel, never()).stream(any(Prompt.class));
        verify(streamingModel, never()).call(any(Prompt.class));
    }

    @Test
    void streamingErrorDoesNotPersistAssistantResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("model failed")));
        ChatService service = service(chatModel, conversation);

        assertThrows(IllegalStateException.class, () -> service.chatCompletionStream(request(),
                new ConversationId("stream-error")).collectList().block());

        verify(conversation, org.mockito.Mockito.times(1)).append(any(), any());
    }

    @Test
    void streamingCancellationDoesNotPersistAssistantResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());
        ChatService service = service(chatModel, conversation);

        service.chatCompletionStream(request(), new ConversationId("stream-cancel"))
                .take(1)
                .blockLast();

        verify(conversation, org.mockito.Mockito.times(1)).append(any(), any());
    }

            @Test
            void blockingResponseDeliversImagesWithoutAddingThemToPrompt() throws Exception {
            ChatModel chatModel = mock(ChatModel.class);
            ConversationMemoryService conversation = mock(ConversationMemoryService.class);
            SearchService searchService = mock(SearchService.class);
            SearchDecisionService decisionService = mock(SearchDecisionService.class);
            when(conversation.load(any())).thenReturn(List.of());
            when(chatModel.call(any(Prompt.class))).thenReturn(response("answer"));
            when(decisionService.decide(any())).thenReturn(new SearchDecision(true, "image query"));
            ImageSource first = new ImageSource("https://example.com/first.jpg", "First", null, null);
            ImageSource second = new ImageSource("https://example.com/second.jpg", "Second", null, null);
            when(searchService.search(any())).thenReturn(new KnowledgeContext(
                "search text",
                List.of(new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH, "search text", 0)),
                List.of(first, second)));

            ChatService service = service(chatModel, conversation, searchService, decisionService);
            setField(service, "searchEnabled", true);
            setField(service, "searchTimeout", Duration.ofSeconds(10));
            setField(service, "searchQueryPlanningEnabled", false);

            var response = service.chatCompletion(request(), new ConversationId("images"));

            assertEquals(List.of(
                new com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment("image", first.url(), first.title()),
                new com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment("image", second.url(), second.title())),
                response.attachments());
            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(prompt.capture());
            assertFalse(promptText(prompt.getValue()).contains(first.url()));
            verify(searchService).search(any());
            }

    private ChatService service(ChatModel chatModel, ConversationMemoryService conversation) {
        return service(chatModel, conversation, mock(SearchService.class), mock(SearchDecisionService.class));
        }

        private ChatService service(
            ChatModel chatModel,
            ConversationMemoryService conversation,
            SearchService searchService,
            SearchDecisionService decisionService) {
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        ObjectProvider<?> buildProperties = mock(ObjectProvider.class);
        return new ChatService(
                chatModel,
                mock(EmbeddingModel.class),
                new ChatTransactionLogger(),
                conversation,
                mock(ObjectProvider.class),
                character,
                new PromptComposer(),
                searchService,
                decisionService,
                new com.minikun.search.SearchSelectionSignalMapper(),
                mock(DiagnosticsService.class),
                new DiagnosticsFormatter(),
                new DiagnosticsPromptBuilder(new MinikunPersonaProvider(character)),
                new CommandCatalog(),
                new CommandFormatter(),
                new VersionService((ObjectProvider) buildProperties, "1.0.0"),
                new VersionFormatter(),
                new ModelsService("chat", "embedding", "memory", ""),
                new ModelsFormatter(),
                new CacheService("true", "valkey", Duration.ofMinutes(5)),
                new CacheFormatter(),
                mock(ObjectProvider.class));
    }

    private void setField(ChatService service, String fieldName, Object value) throws Exception {
        var field = ChatService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String promptText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                + "\n" + prompt.getUserMessage().getText();
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model",
                List.of(new Message("user", "Explain the previous answer.")),
                "orchestration",
                false,
                null,
                null,
                null);
    }
}
