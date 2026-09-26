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
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import com.minikun.agent.minikun_agent.conversation.ConversationSummaryService;
import com.minikun.agent.minikun_agent.conversation.ConversationSummary;
import com.minikun.model.ChatModelProviderRegistry;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ActiveModelConfiguration;
import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.CooperationRouter;
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
import com.minikun.personality.companion.CompanionModeService;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchRequest;
import com.minikun.tools.DefaultToolExecutor;
import com.minikun.tools.DefaultToolRegistry;
import com.minikun.tools.ToolEvidence;
import com.minikun.tools.ToolRequestRouter;
import com.minikun.tools.WeatherForecastTool;
import com.minikun.tools.WeatherToolRouter;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import com.minikun.weather.WeatherReport;
import com.minikun.vision.VisionInputService;
import com.minikun.visual.GeneratedImage;
import com.minikun.visual.GeneratedImageStore;
import com.minikun.visual.ImageGenerationTool;
import com.minikun.visual.InMemoryCharacterVisualMemory;
import com.minikun.visual.StoryIllustrationService;
import com.minikun.visual.StorySceneSpec;
import com.minikun.visual.StoryVisualPlan;
import com.minikun.visual.StoryVisualPlanGenerator;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class ChatServiceChatOrchestrationTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");
    @TempDir Path temporaryDirectory;

    @Test
    void sendsAMixedLanguageImageRequestToTheImageProvider() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ภาพแมวดำในหอดูดาวครับ"));
        ChatService service = service(chatModel, conversation);
        var imageTool = new ImageGenerationTool(prompt -> new GeneratedImage(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1},
                "test-image-model"),
                new GeneratedImageStore(temporaryDirectory, 1024, Clock.systemUTC()),
                8000, 4_194_304L);
        setField(service, "storyIllustrationService", new StoryIllustrationService(
                imageTool, true, 2000, visualPlan(),
                new InMemoryCharacterVisualMemory(), 3));

        ChatCompletionResponse result = service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ช่วย gen รูปแมวดำในหอดูดาวให้หน่อย")),
                "story-image", false, null, null, null), new ConversationId("story-image"));

        assertEquals(1, result.attachments().size());
        assertEquals("generated", result.attachments().getFirst().origin());
        assertEquals("test-image-model", result.attachments().getFirst().provider());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertTrue(promptText(prompt.getValue()).contains("Generated story illustration"));
    }

    @Test
    void voiceTurnsSkipIllustrationsAndAddSpokenResponseGuidance() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("สรุปให้สั้น ๆ ครับ"));
        ChatService service = service(chatModel, conversation);
        AtomicBoolean imageGenerated = new AtomicBoolean();
        var imageTool = new ImageGenerationTool(prompt -> {
            imageGenerated.set(true);
            return new GeneratedImage(new byte[] {1}, "test-image-model");
        }, new GeneratedImageStore(temporaryDirectory, 1024, Clock.systemUTC()), 8000, 4_194_304L);
        setField(service, "storyIllustrationService", new StoryIllustrationService(
                imageTool, true, 2000, visualPlan(),
                new InMemoryCharacterVisualMemory(), 3));

        ChatCompletionResponse result = service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ช่วยสร้างภาพเมืองลอยฟ้าให้หน่อย")),
                "voice-image", false, null, null, null, null, "owner", "voice"),
                new ConversationId("voice-image"));

        assertTrue(result.attachments().isEmpty());
        assertFalse(imageGenerated.get());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("Voice response"));
        assertFalse(text.contains("Generated story illustration"));
    }

    @Test
    void streamingBlankImageResponseStillPreparesIllustrationOffTheReactorThread() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("")));
        ChatService service = service(chatModel, conversation);
        AtomicBoolean transformedOffReactor = new AtomicBoolean();
        var imageTool = new ImageGenerationTool(prompt -> new GeneratedImage(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1},
                "test-image-model"),
                new GeneratedImageStore(temporaryDirectory, 1024, Clock.systemUTC()),
                8000, 4_194_304L);
        StoryVisualPlanGenerator planner = (user, story, mode, memory, maximum) -> {
            transformedOffReactor.set(!Schedulers.isInNonBlockingThread());
            return visualPlan().generate(user, story, mode, memory, maximum);
        };
        setField(service, "storyIllustrationService", new StoryIllustrationService(
                imageTool, true, 2000, planner,
                new InMemoryCharacterVisualMemory(), 3));

        List<String> chunks = service.chatCompletionStream(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ช่วย gen รูปแมวดำในหอดูดาวให้หน่อย")),
                "stream-image", true, null, null, null), new ConversationId("stream-image"))
                .subscribeOn(Schedulers.parallel()).collectList().block();

        assertTrue(transformedOffReactor.get());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("โมเดลยังไม่ได้ส่งคำตอบ")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("\"image_status\":\"running\"")));
    }

    @Test
    void anchorsAmbiguousVisualFollowUpAndAddsRepairGuardToPrompt() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of(
                new ChatMessage("user", "ช่วยค้นหาข้อมูลของนักวาดที่ชื่อ RenaRaziel หน่อย"),
                new ChatMessage("assistant", "RenaRaziel เป็นนักวาดที่น่าสนใจครับ")));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ได้เลยครับ"));
        ChatService service = service(chatModel, conversation);

        service.chatCompletion(new ChatCompletionRequest(
                "mini-kun",
                List.of(new Message("user", "มีอะไรที่น่าสนใจอีกไหม คืนรูปที่ค้นหามาด้วย")),
                "continuity-visual", false, null, null, null),
                new ConversationId("continuity-visual"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("Turn continuity"));
        assertTrue(text.contains("RenaRaziel"));
        assertTrue(text.contains("Conversation repair"));
        assertTrue(text.contains("Do not switch to a different person"));
    }

    @Test
    void injectsNaturalConversationAndEmotionalGuidanceIntoOrdinaryTurns() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of(
                new ChatMessage("assistant", "เมื่อวานพี่สาวทำงานดึกครับ")));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("วันนี้พักก่อนสักนิดนะครับ"));
        ChatService service = service(chatModel, conversation);

        service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "วันนี้เหนื่อยและเครียดมากเลย")),
                "natural-style", false, null, null, null), new ConversationId("natural-style"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(prompt.getValue().getInstructions().stream()
                .anyMatch(message -> message instanceof org.springframework.ai.chat.messages.AssistantMessage
                        && message.getText().contains("เมื่อวานพี่สาวทำงานดึกครับ")));
        assertTrue(text.contains("Natural conversation"));
        assertTrue(text.contains("Use conversation history to resolve references and implied follow-ups"));
        assertTrue(text.contains("acknowledge the specific feeling"));
        assertTrue(text.contains("one small, practical next step"));
        assertTrue(text.contains("เมื่อวานพี่สาวทำงานดึกครับ"));
    }

    @Test
    void injectsConversationScopedCompanionModeIntoPrompt() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("อยู่ตรงนี้กับพี่สาวครับ"));
        ChatService service = service(chatModel, conversation);
        setField(service, "companionModeService", new CompanionModeService(true, 100));
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 2_048);

        service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "วันนี้ขอคุยแบบคู่หูนะ")),
                "companion-mode", false, null, null, null), new ConversationId("companion-mode"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("Interaction mode: COMPANION"));
        assertTrue(text.contains("โหมดปัจจุบันคือ COMPANION"));
        assertTrue(text.contains("ห้ามเสนอหลายทางเลือก"));
        assertEquals(384, prompt.getValue().getOptions().getMaxTokens());
    }

    @Test
    void attachesVisionMediaToCurrentPromptWithoutPersistingImageBytes() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("เห็นภาพแล้วครับ"));
        ChatService service = service(chatModel, conversation);
        setField(service, "visionInputService", new VisionInputService(
                true, 3, 1024, true, false, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new GeneratedImageStore(temporaryDirectory, 1024, Clock.systemUTC())));
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
        assertEquals("chat", prompt.getValue().getOptions().getModel());
        assertEquals(false, ((OllamaChatOptions) prompt.getValue().getOptions())
                .getThinkOption().toJsonValue());
        assertTrue(promptText(prompt.getValue()).contains("User-provided Images"));

        ArgumentCaptor<ChatMessage> persistedUser = ArgumentCaptor.forClass(ChatMessage.class);
        ArgumentCaptor<ChatMessage> persistedAssistant = ArgumentCaptor.forClass(ChatMessage.class);
        verify(conversation).appendTurn(any(), persistedUser.capture(), persistedAssistant.capture());
        assertEquals("อธิบายภาพนี้", persistedUser.getValue().content());
        assertFalse(persistedUser.getValue().content().contains("iVBORw0KGgo"));
        assertFalse(persistedAssistant.getValue().content().contains("iVBORw0KGgo"));
    }

    @Test
    void describesAttachedImageBeforeSearchingTextImageResults() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        SearchService searchService = mock(SearchService.class);
        SearchDecisionService decisionService = mock(SearchDecisionService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("cinematic black cat neon observatory"), response("image search answer"));
        when(decisionService.decide(any())).thenReturn(new SearchDecision(false, "image request"));
        ImageSource image = new ImageSource(
                "https://example.com/cat.jpg", "Cat", "https://source.example/cat", "black cat");
        when(searchService.search(any())).thenAnswer(invocation -> {
            SearchRequest searchRequest = invocation.getArgument(0);
            return SearchOptions.IMAGE_CATEGORY.equals(searchRequest.options().category())
                    ? new KnowledgeContext("", List.of(), List.of(image)) : KnowledgeContext.empty();
        });

        ChatService service = service(chatModel, conversation, searchService, decisionService);
        setField(service, "visionInputService", new VisionInputService(
                true, 3, 1024, true, false, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new GeneratedImageStore(temporaryDirectory, 1024, Clock.systemUTC())));
        setField(service, "searchEnabled", true);
        setField(service, "searchTimeout", Duration.ofSeconds(10));
        setField(service, "searchQueryPlanningEnabled", false);

        ChatCompletionRequest request = new ObjectMapper().readValue("""
                {"model":"mini-kun","conversation_id":"vision-search","stream":false,"messages":[
                  {"role":"user","content":[
                    {"type":"text","text":"ช่วยค้นหารูปที่มีสไตล์หรือบรรยากาศคล้าย reference นี้"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}
                  ]}
                ]}
                """, ChatCompletionRequest.class);

        service.chatCompletion(request, new ConversationId("vision-search"));

        ArgumentCaptor<SearchRequest> searchRequest = ArgumentCaptor.forClass(SearchRequest.class);
        verify(searchService, org.mockito.Mockito.atLeastOnce()).search(searchRequest.capture());
        SearchRequest imageRequest = searchRequest.getAllValues().stream()
                .filter(value -> SearchOptions.IMAGE_CATEGORY.equals(value.options().category()))
                .findFirst().orElseThrow();
        assertEquals("cinematic black cat neon observatory", imageRequest.query());

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.times(2)).call(prompts.capture());
        assertTrue(promptText(prompts.getAllValues().get(0)).contains("visual search query writer"));
        assertEquals(1, prompts.getAllValues().get(0).getUserMessage().getMedia().size());
        assertTrue(promptText(prompts.getAllValues().get(1)).contains("Retrieved Images"));
    }

    @Test
    void includesVisualHandoffWhenGeneratingFromAReferenceImage() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ผมจะออกแบบภาพใหม่จาก reference ให้ต่างจากต้นฉบับ"));
        ChatService service = service(chatModel, conversation);
        var imageTool = new ImageGenerationTool(prompt -> new GeneratedImage(
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1},
                "test-image-model"),
                new GeneratedImageStore(temporaryDirectory, 1024, Clock.systemUTC()),
                8000, 4_194_304L);
        setField(service, "storyIllustrationService", new StoryIllustrationService(
                imageTool, true, 2000, visualPlan(),
                new InMemoryCharacterVisualMemory(), 3));
        setField(service, "visionInputService", new VisionInputService(
                true, 3, 1024, true, false, Duration.ofSeconds(1), Duration.ofSeconds(1),
                new GeneratedImageStore(temporaryDirectory, 1024, Clock.systemUTC())));

        ChatCompletionRequest request = new ObjectMapper().readValue("""
                {"model":"mini-kun","conversation_id":"reference-image","stream":false,"messages":[
                  {"role":"user","content":[
                    {"type":"text","text":"ใช้ภาพ reference ที่แนบมานี้เป็นทิศทาง แล้วช่วยออกแบบภาพใหม่ให้ต่างจากต้นฉบับอย่างชัดเจน"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}
                  ]}
                ]}
                """, ChatCompletionRequest.class);

        service.chatCompletion(request, new ConversationId("reference-image"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String promptText = promptText(prompt.getValue());
        assertTrue(promptText.contains("Visual generation handoff"));
        assertTrue(promptText.contains("composition, pose, setting, palette"));
    }

    @Test
    void verifiedWeatherResultIsAnsweredThroughMcsPrompt() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of(
                new ChatMessage("assistant", "พี่สาววางแผนจะออกจากบ้านพรุ่งนี้เช้าครับ")));
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
        assertTrue(text.contains("พี่สาววางแผนจะออกจากบ้านพรุ่งนี้เช้าครับ"));
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16, 32})
    void longConversationUsesAvailablePromptSpaceBeforeDroppingHistory(int turns) {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        List<ChatMessage> history = new java.util.ArrayList<>();
        for (int turn = 0; turn < turns; turn++) {
            history.add(new ChatMessage("user", "question-" + turn + "-" + "ก".repeat(500)));
            history.add(new ChatMessage("assistant", "answer-" + turn + "-" + "ข".repeat(500)));
        }
        when(conversation.load(any())).thenReturn(List.copyOf(history));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ต่อเนื่องจากคำตอบล่าสุดครับ"));
        ChatService service = service(chatModel, conversation);

        service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ขยายคำตอบล่าสุดอีกนิด")),
                "long-conversation", false, null, null, null),
                new ConversationId("long-conversation"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("answer-" + (turns - 1) + "-"));
        assertTrue(text.contains("question-" + (turns - 1) + "-"));
        if (turns <= 16) {
            assertTrue(text.contains("question-0-"));
            assertFalse(text.contains("Earlier conversation omitted"));
        } else {
            assertFalse(text.contains("question-0-"));
            assertTrue(text.contains("Earlier conversation omitted"));
        }
    }

    @Test
    void creativeStoryTurnsReceiveLargerHistoryAndOutputBudgets() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        String previousStory = "story-start-" + "ก".repeat(8_500) + "-story-end";
        when(conversation.load(any())).thenReturn(List.of(
                new ChatMessage("user", "ช่วยแต่งเรื่องของริน"),
                new ChatMessage("assistant", previousStory)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ตอนต่อไปครับ"));
        ChatService service = service(chatModel, conversation);
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 4_096);

        service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ต่อจากตรงนั้นจนจบ")),
                "creative-context", false, null, null, null),
                new ConversationId("creative-context"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("story-start-"));
        assertTrue(text.contains("-story-end"));
        assertFalse(text.contains("message shortened"));
        assertEquals(4_096, prompt.getValue().getOptions().getMaxTokens());
    }

    @Test
    void lengthLimitedCreativeResponseIsCompletedAndPersistedAsOneNaturalTurn() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("รินผลักประตู", "length"),
                response("ประตูและพบแสงเช้า", "stop"));
        ChatService service = service(chatModel, conversation);
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 4_096);

        ChatCompletionResponse result = service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ช่วยแต่งเรื่องสั้นของริน")),
                "creative-auto-continuation", false, null, null, null),
                new ConversationId("creative-auto-continuation"));

        assertEquals("รินผลักประตูและพบแสงเช้า", result.choices().get(0).message().content());
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.times(2)).call(prompts.capture());
        assertEquals(1_024, prompts.getAllValues().get(1).getOptions().getMaxTokens());
        verify(conversation).appendTurn(
                new ConversationId("creative-auto-continuation"),
                new ChatMessage("user", "ช่วยแต่งเรื่องสั้นของริน"),
                new ChatMessage("assistant", "รินผลักประตูและพบแสงเช้า"));
    }

    @Test
    void lengthLimitedGeneralResponseGetsOneBoundedContinuationAndPreservesFinalReason() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("คำตอบส่วนแรก", "length"),
                response("ส่วนแรกและปิดคำตอบ", "length"));
        ChatService service = service(chatModel, conversation);
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 4_096);

        ChatCompletionResponse result = service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "สรุปหัวข้อนี้ให้ครบ")),
                "general-auto-continuation", false, null, null, null),
                new ConversationId("general-auto-continuation"));

        assertEquals("คำตอบส่วนแรกและปิดคำตอบ", result.choices().getFirst().message().content());
        assertEquals("length", result.choices().getFirst().finish_reason());
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.times(2)).call(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).getContents().contains("finish it concisely"));
        assertEquals(1_024, prompts.getAllValues().get(1).getOptions().getMaxTokens());
    }

    @Test
    void emptyContinuationKeepsTheResponseMarkedAsLengthLimited() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response("คำตอบยังไม่จบ", "length"),
                response("", "stop"));
        ChatService service = service(chatModel, conversation);

        ChatCompletionResponse result = service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "อธิบายให้ครบ")),
                "empty-continuation", false, null, null, null),
                new ConversationId("empty-continuation"));

        assertEquals("คำตอบยังไม่จบ", result.choices().getFirst().message().content());
        assertEquals("length", result.choices().getFirst().finish_reason());
        verify(chatModel, org.mockito.Mockito.times(2)).call(any(Prompt.class));
    }

    @Test
    void streamingCreativeContinuationFinishesBeforeTheStopChunk() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(response("รินผลักประตู", "length")),
                Flux.just(response("ประตูและพบแสงเช้า", "stop")));
        ChatService service = service(chatModel, conversation);
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 4_096);
        ChatCompletionRequest request = new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ช่วยแต่งเรื่องสั้นของริน")),
                "creative-stream-continuation", true, null, null, null);

        List<String> chunks = service.chatCompletionStream(
                request, new ConversationId("creative-stream-continuation"))
                .collectList().block();

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("รินผลักประตู")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("และพบแสงเช้า")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("\"finish_reason\":\"stop\"")));
        assertEquals("[DONE]", chunks.get(chunks.size() - 1));
        verify(conversation).appendTurn(
                new ConversationId("creative-stream-continuation"),
                new ChatMessage("user", "ช่วยแต่งเรื่องสั้นของริน"),
                new ChatMessage("assistant", "รินผลักประตูและพบแสงเช้า"));
    }

    @Test
    void rollingSummaryIsCombinedWithOnlyTheNewestVerbatimTurns() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        ConversationSummaryService summaries = mock(ConversationSummaryService.class);
        ConversationId conversationId = new ConversationId("summary-conversation");
        when(conversation.load(any())).thenReturn(List.of(
                new ChatMessage("user", "old-question"),
                new ChatMessage("assistant", "old-answer"),
                new ChatMessage("user", "recent-question"),
                new ChatMessage("assistant", "recent-answer")));
        when(summaries.snapshot("default", conversationId)).thenReturn(Optional.of(
                new ConversationSummary("default", conversationId,
                        "- ก่อนหน้านี้พี่เลือกแนวทาง A", List.of(), 2, java.time.Instant.now())));
        when(summaries.recentMessageLimit(any(), anyList())).thenReturn(2);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("ต่อจากแนวทาง A ครับ"));
        ChatService service = service(chatModel, conversation);
        setField(service, "conversationSummaryService", summaries);

        service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ทำต่อเลย")),
                conversationId.value(), false, null, null, null), conversationId);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("Rolling summary"));
        assertTrue(text.contains("ก่อนหน้านี้พี่เลือกแนวทาง A"));
        assertTrue(text.contains("recent-question"));
        assertTrue(text.contains("recent-answer"));
        assertFalse(text.contains("old-question"));
        verify(summaries).schedule(any(), any(), any());
    }

    @Test
    void editedClientTranscriptDoesNotInheritStoredRollingSummary() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        ConversationSummaryService summaries = mock(ConversationSummaryService.class);
        ConversationId conversationId = new ConversationId("edited-conversation");
        when(conversation.load(any())).thenReturn(List.of(
                new ChatMessage("user", "old path"),
                new ChatMessage("assistant", "old choice")));
        when(summaries.snapshot("default", conversationId)).thenReturn(Optional.of(
                new ConversationSummary("default", conversationId,
                        "- old choice is final", List.of(), 2, java.time.Instant.now())));
        when(summaries.recentMessageLimit(any(), anyList())).thenReturn(2);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("new answer"));
        ChatService service = service(chatModel, conversation);
        setField(service, "conversationSummaryService", summaries);

        service.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(
                        new Message("user", "new path"),
                        new Message("assistant", "new choice"),
                        new Message("user", "continue")),
                conversationId.value(), false, null, null, null), conversationId);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("new path"));
        assertTrue(text.contains("new choice"));
        assertFalse(text.contains("old choice"));
        assertFalse(text.contains("Rolling summary"));
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
        verify(blockingConversation).appendTurn(any(), any(), any());
        verify(streamingConversation).appendTurn(any(), any(), any());
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
        assertEquals("chat", blockingPrompt.getValue().getOptions().getModel());
        assertEquals("chat", streamingPrompt.getValue().getOptions().getModel());
        assertTrue(stream.stream().anyMatch(chunk -> chunk.contains("streaming answer")));

        verify(blockingConversation).load(conversationId);
        verify(streamingConversation).load(conversationId);
        verify(blockingConversation).appendTurn(any(), any(), any());
        verify(streamingConversation).appendTurn(any(), any(), any());
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
        verify(conversation).appendTurn(any(), any(), any());
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
        assertEquals(false, ((OllamaChatOptions) prompt.getValue().getOptions()).getThinkOption().toJsonValue());
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

        verify(conversation, never()).appendTurn(any(), any(), any());
    }

    @Test
    void blockingModelFailureDoesNotPersistAnIncompleteTurn() {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("model failed"));
        ChatService service = service(chatModel, conversation);

        assertThrows(IllegalStateException.class, () -> service.chatCompletion(
                request(), new ConversationId("blocking-error")));

        verify(conversation, never()).appendTurn(any(), any(), any());
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

        verify(conversation, never()).appendTurn(any(), any(), any());
    }

    @Test
    void streamingRecordsUserAndModelTimeToFirstToken() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage("first token"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 2, 12, null)).build())));
        ChatService service = service(chatModel, conversation);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        setField(service, "performanceMetrics", new ChatPerformanceMetrics(registry));

        service.chatCompletionStream(request(), new ConversationId("stream-ttft"))
                .collectList().block();

        assertEquals(1L, registry.get(ChatPerformanceMetrics.STAGE_DURATION)
                .tags("stage", "ttft", "result", "success").timer().count());
        assertEquals(1L, registry.get(ChatPerformanceMetrics.STAGE_DURATION)
                .tags("stage", "model_ttft", "result", "success").timer().count());
        assertEquals(1L, registry.get(ChatPerformanceMetrics.TOKENS_PER_SECOND).summary().count());
    }

    @Test
    void generalStreamingBypassesBlockingToolRuntime() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        SpringAiToolCallingRuntime toolRuntime = mock(SpringAiToolCallingRuntime.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("streamed answer")));
        ChatService service = service(chatModel, conversation);
        setField(service, "toolsEnabled", true);
        setField(service, "toolCallingRuntime", toolRuntime);
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 2_048);

        service.chatCompletionStream(request(), new ConversationId("general-direct-stream"))
                .collectList().block();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(prompt.capture());
        assertFalse(promptText(prompt.getValue()).contains("Native tools"));
        verify(toolRuntime, never()).call(any(Prompt.class), any(ConversationId.class), any(String.class));
    }

    @Test
    void technicalStreamingUsesDirectModel() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        SpringAiToolCallingRuntime toolRuntime = mock(SpringAiToolCallingRuntime.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("technical answer")));
        when(toolRuntime.call(any(Prompt.class), any(ConversationId.class), any(String.class)))
                .thenReturn(response("technical answer"));
        ChatService service = service(chatModel, conversation);
        setField(service, "toolsEnabled", true);
        setField(service, "toolCallingRuntime", toolRuntime);
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 2_048);
        ChatCompletionRequest technical = new ChatCompletionRequest(
                "test-model", List.of(new Message("user", "ช่วย debug Spring Boot API นี้")),
                "technical-stream", true, null, null, null);

        service.chatCompletionStream(technical, new ConversationId("technical-stream"))
                .collectList().block();

        verify(toolRuntime, never()).call(any(Prompt.class), any(ConversationId.class), any(String.class));
        verify(chatModel).stream(any(Prompt.class));
    }

    @Test
    void titleRequestUsesTitleServiceWithoutCallingActiveProvider() {
        ChatModelProvider activeProvider = mock(ChatModelProvider.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        TitleGenerationService titleService = mock(TitleGenerationService.class);
        when(activeProvider.id()).thenReturn(ChatModelId.EXISTING);
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
        verify(conversation, never()).appendTurn(any(), any(), any());
    }

    @Test
    void titleFailureReturnsFallbackAndNormalChatStillUsesActiveProvider() {
        ChatModelProvider activeProvider = mock(ChatModelProvider.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        TitleGenerationProvider failingProvider = messages -> {
            throw new IllegalStateException("Ollama timeout");
        };
        when(activeProvider.id()).thenReturn(ChatModelId.EXISTING);
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
            when(decisionService.decide(any())).thenReturn(new SearchDecision(
                    true, "image query", SearchDecisionReason.IMAGE_REQUEST));
            ImageSource first = new ImageSource(
                    "https://example.com/first.jpg", "First", "https://source.example/first", "first description");
            ImageSource second = new ImageSource(
                    "https://example.com/second.jpg", "Second", "https://source.example/second", "second description");
            ImageSource third = new ImageSource(
                    "https://example.com/third.jpg", "Third", "https://source.example/third", "third description");
            when(searchService.search(any())).thenAnswer(invocation -> {
                SearchRequest searchRequest = invocation.getArgument(0);
                if (SearchOptions.IMAGE_CATEGORY.equals(searchRequest.options().category())) {
                    return new KnowledgeContext("", List.of(), List.of(first, second, third));
                }
                return new KnowledgeContext("search text",
                        List.of(new KnowledgeCandidate("search-1", KnowledgeSource.SEARCH, "search text", 0)),
                        List.of());
            });

            ChatService service = service(chatModel, conversation, searchService, decisionService);
            setField(service, "searchEnabled", true);
            setField(service, "searchTimeout", Duration.ofSeconds(10));
            setField(service, "searchQueryPlanningEnabled", false);
            setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                    new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
            setField(service, "configuredGenerationMaxTokens", 4_096);

            var response = service.chatCompletion(request(), new ConversationId("images"));

            assertEquals(ImageAttachmentMapper.map(List.of(first, second, third)), response.attachments());
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
            assertEquals(3_072, prompt.getValue().getOptions().getMaxTokens());
            verify(searchService, org.mockito.Mockito.times(2)).search(any());
            verify(chatModel, org.mockito.Mockito.times(1)).call(any(Prompt.class));

            JsonNode serialized = new ObjectMapper().readTree(
                    new ObjectMapper().writeValueAsString(response));
            JsonNode attachment = serialized.get("attachments").get(0);
            assertEquals(3, serialized.get("attachments").size());
            assertEquals("image", attachment.get("type").asText());
            assertEquals("/v1/images/proxy?url=https%3A%2F%2Fexample.com%2Ffirst.jpg", attachment.get("url").asText());
            assertEquals(first.title(), attachment.get("title").asText());
            assertEquals(first.sourceUrl(), attachment.get("source_url").asText());
            assertEquals(first.description(), attachment.get("description").asText());
            assertEquals(first.url(), attachment.get("original_url").asText());
            assertEquals("web", attachment.get("origin").asText());
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
    void researchStorytellingContractReachesTheFinalModelPrompt() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        SearchService searchService = mock(SearchService.class);
        SearchDecisionService decisionService = mock(SearchDecisionService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("research answer"));
        when(decisionService.decide(any())).thenReturn(new SearchDecision(
                true, "ระบบพลังงาน", com.minikun.search.model.SearchDecisionReason.FACT_LOOKUP));
        String sourceUrl = "https://official.example/energy-report";
        when(searchService.search(any())).thenReturn(new KnowledgeContext(
                "Energy report (" + sourceUrl + "): evidence about ระบบพลังงาน",
                List.of(new KnowledgeCandidate(
                        "search-0", KnowledgeSource.SEARCH,
                        "Energy report (" + sourceUrl + "): evidence about ระบบพลังงาน",
                        0, sourceUrl))));
        ChatService service = service(chatModel, conversation, searchService, decisionService);
        setField(service, "searchEnabled", true);
        setField(service, "searchTimeout", Duration.ofSeconds(10));
        setField(service, "searchQueryPlanningEnabled", false);
        setField(service, "generationProfileSelector", new ChatGenerationProfileSelector(
                new CooperationRouter(), true, 384, 512, 768, 1_536, 3_072, 4_096, 2_048, 4_096));
        setField(service, "configuredGenerationMaxTokens", 4_096);
        setField(service, "autonomousResearchService",
                (com.minikun.research.AutonomousResearchService) researchRequest ->
                        new com.minikun.research.AutonomousResearchResult(
                                new KnowledgeContext(
                                        "Energy report (" + sourceUrl + "): evidence about ระบบพลังงาน",
                                        List.of(new KnowledgeCandidate(
                                                "research-search-0", KnowledgeSource.SEARCH,
                                                "Energy report (" + sourceUrl + "): evidence about ระบบพลังงาน",
                                                0, sourceUrl))),
                                List.of(),
                                new com.minikun.research.ResearchTrace(
                                        "research energy", List.of("primary evidence"),
                                        List.of("energy official", "energy independent"), 2,
                                        com.minikun.research.ResearchStopReason.SUFFICIENT,
                                        List.of(), true)));
        ChatCompletionRequest request = new ChatCompletionRequest(
                "mini-kun",
                List.of(new Message("user", "ช่วยค้นคว้าระบบพลังงานแล้วเล่าเป็นเรื่องให้เข้าใจง่าย")),
                "research-story", false, null, null, null);

        service.chatCompletion(request, new ConversationId("research-story"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        String text = promptText(prompt.getValue());
        assertTrue(text.contains("Deep research workflow"));
        assertTrue(text.contains("Evidence and citations"));
        assertTrue(text.contains("Autonomous research loop result"));
        assertTrue(text.contains("Autonomous research iterations: 2"));
        assertTrue(text.contains("Narrative craft: STORY"));
        assertTrue(text.contains(sourceUrl));
        assertTrue(text.contains("Never invent, repair, or guess a citation"));
        assertEquals(4_096, prompt.getValue().getOptions().getMaxTokens());
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

    private StoryVisualPlanGenerator visualPlan() {
        return (user, story, mode, memory, maximum) -> new StoryVisualPlan(
                mode, List.of(), List.of(new StorySceneSpec(
                        "Star cat", 1, List.of(), "black cat watching the stars", "", List.of(),
                        "old observatory", "night", "", "calm", "quiet", "starlight", "navy",
                        "cinematic composition", "eye level", "medium shot", "sharp focus",
                        List.of("black cat"), List.of(), List.of())));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatResponse response(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
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
