package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.model.ChatModelProviderRegistry;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ActiveModelConfiguration;
import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.DefaultChatModelProviderRegistry;
import com.minikun.model.DefaultActiveChatModelProvider;
import com.minikun.model.ModelCapabilities;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.model.task.title.TitleGenerationProvider;
import com.minikun.model.task.title.TitleGenerationService;
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
import com.minikun.tools.DefaultToolExecutor;
import com.minikun.tools.DefaultToolRegistry;
import com.minikun.tools.ToolEvidence;
import com.minikun.tools.ToolRequestRouter;
import com.minikun.tools.WeatherForecastTool;
import com.minikun.tools.WeatherToolRouter;
import com.minikun.weather.WeatherReport;
import com.minikun.vision.VisionInputService;

import reactor.core.publisher.Flux;

class ChatServiceChatOrchestrationTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void attachesVisionMediaToCurrentPromptWithoutPersistingImageBytes() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("เห็นภาพแล้วครับ"));
        ChatService service = service(chatModel, conversation);
        setField(service, "visionInputService", new VisionInputService(
                true, 3, 1024, true, false, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        ChatCompletionRequest request = new ObjectMapper().readValue("""
                {"model":"mini-kun","conversation_id":"vision-test","stream":false,"messages":[
                  {"role":"user","content":[
                    {"type":"text","text":"อธิบายภาพนี้"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}
                  ]}
                ]}
                """, ChatCompletionRequest.class);

        service.chatCompletion(request, new ConversationId("vision-test"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertEquals(1, prompt.getValue().getUserMessage().getMedia().size());
        assertEquals("image/png", prompt.getValue().getUserMessage().getMedia().getFirst()
                .getMimeType().toString());
        assertEquals(false, ((OllamaChatOptions) prompt.getValue().getOptions())
                .getThinkOption().toJsonValue());
        assertTrue(promptText(prompt.getValue()).contains("User-provided Images"));

        ArgumentCaptor<ChatMessage> persisted = ArgumentCaptor.forClass(ChatMessage.class);
        verify(conversation, org.mockito.Mockito.times(2)).append(any(), persisted.capture());
        assertEquals("อธิบายภาพนี้", persisted.getAllValues().getFirst().content());
        assertFalse(persisted.getAllValues().stream()
                .anyMatch(message -> message.content().contains("iVBORw0KGgo")));
    }

    @Test
    void verifiedWeatherResultIsAnsweredThroughMcsPrompt() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of(
                new ChatMessage("assistant", "พี่สาวครับ ตอนนี้มินิคุงยังไม่สามารถตรวจสอบข้อมูลสภาพอากาศได้ครับ")));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("พรุ่งนี้มีฝนครับ พี่สาวควรพกร่มนะครับ"));

        WeatherForecastTool weatherTool = new WeatherForecastTool(request ->
                new WeatherReport("กรุงเทพมหานคร", "Thailand", 13.75, 100.50, "Asia/Bangkok",
                        "2026-08-19", 30.0, 34.0, 1.0, 12.0, 61, "ฝนตก",
                        28.0, 35.0, 70, 4.0, "06:00", "18:40", java.time.Instant.now(), "test"));
        WeatherToolRouter router = new WeatherToolRouter(new DefaultToolExecutor(
                new DefaultToolRegistry(List.of(weatherTool))));
        ChatService service = service(chatModel, conversation);
        setField(service, "toolsEnabled", true);
        setField(service, "toolRequestRouters", List.of(router));

        ChatCompletionResponse result = service.chatCompletion(
                new ChatCompletionRequest(
                        "test-model",
                        List.of(new Message("user", "แล้ว พรุ่งนี้กรุงเทพอากาศเป็นอย่างไร")),
                        "weather-mcs", false, null, null, null, null),
                new ConversationId("weather-mcs"));

        assertEquals("พรุ่งนี้มีฝนครับ พี่สาวควรพกร่มนะครับ", result.choices().get(0).message().content());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("[Character]"));
        assertTrue(text.contains("[Capabilities]"));
        assertTrue(text.contains("Verified tool result"));
        assertTrue(text.contains("กรุงเทพมหานคร"));
        assertTrue(text.contains("70%"));
        assertTrue(text.contains("Keep the identity, language, tone, and response style from MCS"));
        assertTrue(!text.contains("ยังไม่สามารถตรวจสอบข้อมูลสภาพอากาศได้"));
    }

    @Test
    void deterministicToolResultBypassesKnowledgeAndModelForBlockingAndStreaming() throws Exception {
        ChatModel blockingModel = mock(ChatModel.class);
        ChatModel streamingModel = mock(ChatModel.class);
        ConversationMemoryService blockingConversation = mock(ConversationMemoryService.class);
        ConversationMemoryService streamingConversation = mock(ConversationMemoryService.class);
        ToolRequestRouter router = (text, conversationId) -> java.util.Optional.of(
                ToolEvidence.finalVerified("homelab.guardian", "verified guardian status"));
        ChatService blockingService = service(blockingModel, blockingConversation);
        ChatService streamingService = service(streamingModel, streamingConversation);
        setField(blockingService, "toolsEnabled", true);
        setField(streamingService, "toolsEnabled", true);
        setField(blockingService, "toolRequestRouters", List.of(router));
        setField(streamingService, "toolRequestRouters", List.of(router));

        ChatCompletionResponse blocking = blockingService.chatCompletion(
                request(), new ConversationId("guardian-blocking"));
        List<String> streaming = streamingService.chatCompletionStream(
                request(), new ConversationId("guardian-streaming")).collectList().block();

        assertEquals("verified guardian status", blocking.choices().getFirst().message().content());
        assertTrue(streaming.stream().anyMatch(chunk -> chunk.contains("verified guardian status")));
        verify(blockingModel, never()).call(any(Prompt.class));
        verify(blockingModel, never()).stream(any(Prompt.class));
        verify(streamingModel, never()).call(any(Prompt.class));
        verify(streamingModel, never()).stream(any(Prompt.class));
        verify(blockingConversation, never()).load(any());
        verify(streamingConversation, never()).load(any());
        verify(blockingConversation, org.mockito.Mockito.times(2)).append(any(), any());
        verify(streamingConversation, org.mockito.Mockito.times(2)).append(any(), any());
    }

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
    void blankModelResponseReturnsSafeMessageInsteadOfHttpFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response(""));

        ChatCompletionResponse result = service(chatModel, conversation)
                .chatCompletion(request(), new ConversationId("blank-response"));

        assertTrue(result.choices().getFirst().message().content().contains("ยังไม่ได้ส่งคำตอบที่สมบูรณ์"));
        verify(conversation, org.mockito.Mockito.times(2)).append(any(), any());
    }

    @Test
    void blockingUsesConfiguredTinyGradProvider() {
        ChatModelProvider tinyGradProvider = mock(ChatModelProvider.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(tinyGradProvider.id()).thenReturn(ChatModelId.TINYGRAD);
        when(tinyGradProvider.capabilities()).thenReturn(new ModelCapabilities(true, false, false));
        when(tinyGradProvider.chat(any(Prompt.class))).thenReturn(response("tinygrad answer"));
        when(conversation.load(any())).thenReturn(List.of());

        ChatService service = service(tinyGradProvider, conversation);

        var result = service.chatCompletion(request(), new ConversationId("tinygrad"));

        assertEquals("tinygrad answer", result.choices().get(0).message().content());
        verify(tinyGradProvider).chat(any(Prompt.class));
    }

        @Test
        void propagatesGenerationOptionsAndMapsModelUsage() {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
            List.of(new Generation(new AssistantMessage("answer"))),
            ChatResponseMetadata.builder().usage(new DefaultUsage(12, 5, 17, null)).build()));
        ChatCompletionRequest request = new ChatCompletionRequest(
            "test-model",
            List.of(new Message("user", "Explain this.")),
            "options",
            false,
            0.7,
            100,
            200,
            List.of("END"),
            null);

        ChatCompletionResponse response = service(chatModel, conversation)
            .chatCompletion(request, new ConversationId("options"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertTrue(prompt.getValue().getOptions() instanceof OllamaChatOptions);
        assertEquals("chat",
            prompt.getValue().getOptions().getModel());
        assertEquals(0.7, prompt.getValue().getOptions().getTemperature());
        assertEquals(200, prompt.getValue().getOptions().getMaxTokens());
        assertEquals(List.of("END"), prompt.getValue().getOptions().getStopSequences());
        assertEquals(16_384, ((OllamaChatOptions) prompt.getValue().getOptions()).getNumCtx());
        assertEquals(new ChatCompletionResponse.Usage(12, 5, 17), response.usage());
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
    void titleRequestUsesTitleServiceWithoutCallingActiveProvider() {
        ChatModelProvider activeProvider = mock(ChatModelProvider.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        TitleGenerationService titleService = mock(TitleGenerationService.class);
        when(activeProvider.id()).thenReturn(ChatModelId.TINYGRAD);
        when(activeProvider.capabilities()).thenReturn(new ModelCapabilities(true, false, false));
        when(titleService.generateTitle(anyList())).thenReturn("Postgres Setup");

        ChatCompletionResponse response = service(activeProvider, conversation, titleService)
                .chatCompletion(titleRequest(), new ConversationId("title-isolation"));

        assertEquals("Postgres Setup", response.choices().get(0).message().content());
        verify(titleService).generateTitle(anyList());
        verify(activeProvider, never()).chat(any(Prompt.class));
        verify(activeProvider, never()).stream(any(Prompt.class));
        verify(conversation, never()).load(any());
        verify(conversation, never()).append(any(), any());
    }

    @Test
    void titleFailureReturnsFallbackAndNormalChatStillUsesActiveProvider() {
        ChatModelProvider activeProvider = mock(ChatModelProvider.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        TitleGenerationProvider failingProvider = messages -> {
            throw new IllegalStateException("Ollama timeout");
        };
        when(activeProvider.id()).thenReturn(ChatModelId.TINYGRAD);
        when(activeProvider.capabilities()).thenReturn(new ModelCapabilities(true, false, false));
        when(activeProvider.chat(any(Prompt.class))).thenReturn(response("normal answer"));
        when(conversation.load(any())).thenReturn(List.of());

        ChatService service = service(activeProvider, conversation, new TitleGenerationService(failingProvider));
        ChatCompletionResponse title = service.chatCompletion(
                titleRequest(), new ConversationId("title-failure"));
        ChatCompletionResponse normal = service.chatCompletion(
                request(), new ConversationId("normal-after-title-failure"));

        assertEquals(TitleGenerationService.FALLBACK_TITLE, title.choices().get(0).message().content());
        assertEquals("normal answer", normal.choices().get(0).message().content());
        verify(activeProvider).chat(any(Prompt.class));
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
            ImageSource first = new ImageSource(
                    "https://example.com/first.jpg", "First", "https://source.example/first", "first description");
            ImageSource second = new ImageSource(
                    "https://example.com/second.jpg", "Second", "https://source.example/second", "second description");
            ImageSource third = new ImageSource(
                    "https://example.com/third.jpg", "Third", "https://source.example/third", "third description");
            when(searchService.search(any())).thenReturn(new KnowledgeContext(
                "search text",
                List.of(new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH, "search text", 0)),
                List.of(first, second, third)));

            ChatService service = service(chatModel, conversation, searchService, decisionService);
            setField(service, "searchEnabled", true);
            setField(service, "searchTimeout", Duration.ofSeconds(10));
            setField(service, "searchQueryPlanningEnabled", false);

            var response = service.chatCompletion(request(), new ConversationId("images"));

            assertEquals(List.of(
                new com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment("image", first.url(), first.title()),
                new com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment("image", second.url(), second.title()),
                new com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment("image", third.url(), third.title())),
                response.attachments());
            assertEquals("answer", response.choices().get(0).message().content());
            ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
            verify(chatModel).call(prompt.capture());
            String promptText = promptText(prompt.getValue());
            assertTrue(promptText.contains("[Capabilities]"));
            assertTrue(promptText.contains("Retrieved Images"));
            assertTrue(promptText.contains("will be available to the user as response attachments"));
            assertTrue(promptText.contains("Count: 3"));
            assertTrue(promptText.contains("cannot see, inspect, or analyze their visual contents"));
            assertFalse(promptText.contains(first.url()));
            assertFalse(promptText.contains(first.title()));
            assertFalse(promptText.contains(second.url()));
            assertFalse(promptText.contains(second.title()));
            assertFalse(promptText.contains(third.url()));
            assertFalse(promptText.contains(third.title()));
            assertFalse(promptText.contains(first.sourceUrl()));
            assertFalse(promptText.contains(second.sourceUrl()));
            assertFalse(promptText.contains(third.sourceUrl()));
            assertFalse(promptText.contains(first.description()));
            assertFalse(promptText.contains(second.description()));
            assertFalse(promptText.contains(third.description()));
            assertFalse(promptText.contains("ChatAttachment"));
            verify(searchService).search(any());
            verify(chatModel, org.mockito.Mockito.times(1)).call(any(Prompt.class));

            JsonNode serialized = new ObjectMapper().readTree(
                    new ObjectMapper().writeValueAsString(response));
            JsonNode attachment = serialized.get("attachments").get(0);
            assertEquals(3, serialized.get("attachments").size());
            assertEquals("image", attachment.get("type").asText());
            assertEquals(first.url(), attachment.get("url").asText());
            assertEquals(first.title(), attachment.get("title").asText());
            assertFalse(attachment.has("sourceUrl"));
            assertFalse(attachment.has("description"));
            }

    @Test
    void textOnlyPromptDoesNotAddImageAwareness() {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("answer"));

        ChatService service = service(chatModel, conversation);

        service.chatCompletion(request(), new ConversationId("text-only"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String promptText = promptText(prompt.getValue());
        assertFalse(promptText.contains("Retrieved Images"));
        assertFalse(promptText.contains("response attachments"));
    }

    @Test
    void streamingPromptReceivesImageAwarenessWithoutChangingDoneContract() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        SearchService searchService = mock(SearchService.class);
        SearchDecisionService decisionService = mock(SearchDecisionService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("streaming answer")));
        when(decisionService.decide(any())).thenReturn(new SearchDecision(true, "image query"));
        ImageSource image = new ImageSource("https://example.com/image.jpg", "Image", "source", "description");
        when(searchService.search(any())).thenReturn(new KnowledgeContext("search text", List.of(), List.of(image)));

        ChatService service = service(chatModel, conversation, searchService, decisionService);
        setField(service, "searchEnabled", true);
        setField(service, "searchTimeout", Duration.ofSeconds(10));
        setField(service, "searchQueryPlanningEnabled", false);

        List<String> chunks = service.chatCompletionStream(request(), new ConversationId("stream-images"))
                .collectList().block();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        assertTrue(promptText(prompt.getValue()).contains("Count: 1"));
        assertTrue(chunks.get(chunks.size() - 1).equals("[DONE]"));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("streaming answer")));
    }

    private ChatService service(ChatModel chatModel, ConversationMemoryService conversation) {
        return service(chatModel, conversation, mock(SearchService.class), mock(SearchDecisionService.class));
        }

    private ChatService service(ChatModelProvider provider, ConversationMemoryService conversation) {
        return service(provider, conversation, mock(SearchService.class), mock(SearchDecisionService.class));
    }

    private ChatService service(
            ChatModelProvider provider,
            ConversationMemoryService conversation,
            TitleGenerationService titleGenerationService) {
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        ObjectProvider<?> buildProperties = mock(ObjectProvider.class);
        return new ChatService(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(provider.id()),
                        new DefaultChatModelProviderRegistry(List.of(provider))),
                mock(EmbeddingModel.class),
                new ChatTransactionLogger(),
                conversation,
                mock(ObjectProvider.class),
                character,
                new PromptComposer(),
                mock(SearchService.class),
                mock(SearchDecisionService.class),
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
                mock(ObjectProvider.class),
                titleGenerationService);
    }

        private ChatService service(
            ChatModel chatModel,
            ConversationMemoryService conversation,
            SearchService searchService,
            SearchDecisionService decisionService) {
        return service(new ExistingChatModelProvider(chatModel), conversation, searchService, decisionService);
    }

        private ChatService service(
            ChatModelProvider provider,
            ConversationMemoryService conversation,
            SearchService searchService,
            SearchDecisionService decisionService) {
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        ObjectProvider<?> buildProperties = mock(ObjectProvider.class);
        return new ChatService(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(provider.id()),
                    new DefaultChatModelProviderRegistry(List.of(provider))),
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

    private ChatCompletionRequest titleRequest() {
        return new ChatCompletionRequest(
                "test-model",
                List.of(new Message("user", "Generate a concise title summarizing the chat history.")),
                "title",
                false,
                null,
                null,
                null);
    }
}
