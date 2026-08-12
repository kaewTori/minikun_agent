package com.minikun.agent.minikun_agent.api.openai;

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
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ActiveModelConfiguration;
import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.DefaultActiveChatModelProvider;
import com.minikun.model.DefaultChatModelProviderRegistry;
import com.minikun.model.GenerationOptions;
import com.minikun.model.ModelCapabilities;
import com.minikun.model.capability.DefaultModelCapabilityRegistry;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.model.capability.ModelRole;
import com.minikun.model.task.title.TitleGenerationService;
import com.minikun.pcs.MinikunPersonaProvider;
import com.minikun.pcs.PromptComposer;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchService;
import com.minikun.search.SearchSelectionSignalMapper;
import com.minikun.tokenbudget.domain.TokenBudgetAllocation;
import com.minikun.tokenbudget.integration.DefaultGenerationOptionsResolver;
import com.minikun.tokenbudget.planner.DynamicTokenPlanner;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceDynamicTokenBudgetTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");
    private static final ModelCapability CAPABILITY =
            new ModelCapability(ChatModelId.EXISTING, ModelRole.CHAT, 16_384, 4_096);

    @Test
    void disabledFlagPreservesConfiguredGenerationOptions() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        when(conversation.load(any())).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(response("answer"));
        ChatService service = service(chatModel, conversation);
        setField(service, "configuredGenerationMaxTokens", 2_048);
        setField(service, "dynamicTokenBudgetEnabled", false);
        setField(service, "dynamicGenerationOptionsFactory", failingFactory());

        service.chatCompletion(request(), new ConversationId("disabled"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertEquals(2_048, prompt.getValue().getOptions().getMaxTokens());
    }

    @Test
    void blockingAndStreamingUseTheSameDynamicPreparation() throws Exception {
        ChatModel blockingModel = mock(ChatModel.class);
        ChatModel streamingModel = mock(ChatModel.class);
        ConversationMemoryService blockingConversation = mock(ConversationMemoryService.class);
        ConversationMemoryService streamingConversation = mock(ConversationMemoryService.class);
        when(blockingConversation.load(any())).thenReturn(List.of());
        when(streamingConversation.load(any())).thenReturn(List.of());
        when(blockingModel.call(any(Prompt.class))).thenReturn(response("blocking"));
        when(streamingModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("streaming")));
        AtomicReference<String> blockingText = new AtomicReference<>();
        AtomicReference<String> streamingText = new AtomicReference<>();
        DynamicTokenPlanner blockingPlanner = (capability, budget, prompt) -> {
            blockingText.set(prompt);
            return new TokenBudgetAllocation(100, 777, false);
        };
        DynamicTokenPlanner streamingPlanner = (capability, budget, prompt) -> {
            streamingText.set(prompt);
            return new TokenBudgetAllocation(100, 777, false);
        };
        ChatService blockingService = configuredService(blockingModel, blockingConversation, blockingPlanner);
        ChatService streamingService = configuredService(streamingModel, streamingConversation, streamingPlanner);

        blockingService.chatCompletion(request(), new ConversationId("dynamic-blocking"));
        streamingService.chatCompletionStream(request(), new ConversationId("dynamic-streaming"))
                .collectList().block();

        ArgumentCaptor<Prompt> blockingPrompt = ArgumentCaptor.forClass(Prompt.class);
        ArgumentCaptor<Prompt> streamingPrompt = ArgumentCaptor.forClass(Prompt.class);
        verify(blockingModel).call(blockingPrompt.capture());
        verify(streamingModel).stream(streamingPrompt.capture());
        assertEquals(777, blockingPrompt.getValue().getOptions().getMaxTokens());
        assertEquals(777, streamingPrompt.getValue().getOptions().getMaxTokens());
        assertEquals(blockingText.get(), streamingText.get());
    }

    private DynamicGenerationOptionsFactory failingFactory() {
        return new DynamicGenerationOptionsFactory(
                (capability, budget, prompt) -> {
                    throw new AssertionError("dynamic planner must not run when disabled");
                }, new DefaultGenerationOptionsResolver());
    }

    private ChatService configuredService(
            ChatModel chatModel,
            ConversationMemoryService conversation,
            DynamicTokenPlanner planner) throws Exception {
        ChatService service = service(chatModel, conversation);
        ModelCapabilityRegistry registry = new DefaultModelCapabilityRegistry(
                java.util.Map.of(ChatModelId.EXISTING, CAPABILITY));
        setField(service, "modelCapabilityRegistry", registry);
        setField(service, "dynamicGenerationOptionsFactory",
                new DynamicGenerationOptionsFactory(planner, new DefaultGenerationOptionsResolver()));
        setField(service, "dynamicTokenBudgetEnabled", true);
        setField(service, "configuredGenerationMaxTokens", 2_048);
        return service;
    }

    private ChatService service(ChatModel chatModel, ConversationMemoryService conversation) {
        ChatModelProvider provider = new com.minikun.model.existing.ExistingChatModelProvider(chatModel);
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        ObjectProvider<?> buildProperties = mock(ObjectProvider.class);
        ActiveChatModelProvider activeProvider = new DefaultActiveChatModelProvider(
                new ActiveModelConfiguration(ChatModelId.EXISTING),
                new DefaultChatModelProviderRegistry(List.of(provider)));
        return new ChatService(
                activeProvider,
                mock(EmbeddingModel.class),
                new ChatTransactionLogger(),
                conversation,
                mock(ObjectProvider.class),
                character,
                new PromptComposer(),
                mock(SearchService.class),
                mock(SearchDecisionService.class),
                new SearchSelectionSignalMapper(),
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
                new TitleGenerationService(messages -> TitleGenerationService.FALLBACK_TITLE));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }

    private ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model", List.of(new Message("user", "Explain this.")),
                "dynamic", false, 0.7, null, null);
    }

    private void setField(ChatService service, String fieldName, Object value) throws Exception {
        Field field = ChatService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
