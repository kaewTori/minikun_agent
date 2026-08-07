package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.memory.MemoryAnalyzer;
import com.minikun.memory.MemoryRecallService;
import com.minikun.memory.MemoryService;
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

class ChatServiceRuntimeCommandTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void runtimeCommandSkipsChatPipelineInBothResponseModes() {
        ChatModel chatModel = mock(ChatModel.class);
        ConversationMemoryService conversation = mock(ConversationMemoryService.class);
        MemoryAnalyzer memoryAnalyzer = mock(MemoryAnalyzer.class);
        ObjectProvider<MemoryService> memoryService = mock(ObjectProvider.class);
        ObjectProvider<MemoryRecallService> memoryRecall = mock(ObjectProvider.class);
        SearchService search = mock(SearchService.class);
        SearchDecisionService decision = mock(SearchDecisionService.class);
        ChatTransactionLogger transactions = mock(ChatTransactionLogger.class);
        ChatService service = service(chatModel, conversation, memoryAnalyzer, memoryService,
                memoryRecall, search, decision, transactions);
        ChatCompletionRequest request = request("/models");

        var response = service.chatCompletion(request, new ConversationId("runtime"));
        var stream = service.chatCompletionStream(request, new ConversationId("runtime"))
                .collectList().block();

        assertTrue(response.choices().getFirst().message().content().startsWith("Models\n"));
        assertTrue(stream.stream().anyMatch(chunk -> chunk.contains("Models\\n")));
        verifyNoInteractions(chatModel, conversation, memoryAnalyzer, memoryService, memoryRecall,
                search, decision, transactions);
    }

    private ChatService service(
            ChatModel chatModel,
            ConversationMemoryService conversation,
            MemoryAnalyzer memoryAnalyzer,
            ObjectProvider<MemoryService> memoryService,
            ObjectProvider<MemoryRecallService> memoryRecall,
            SearchService search,
            SearchDecisionService decision,
            ChatTransactionLogger transactions) {
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        ObjectProvider<?> buildProperties = mock(ObjectProvider.class);
        return new ChatService(
                chatModel,
                mock(EmbeddingModel.class),
                transactions,
                conversation,
                memoryRecall,
                character,
                new PromptComposer(),
                search,
                decision,
                new com.minikun.search.SearchSelectionSignalMapper(),
                mock(DiagnosticsService.class),
                new DiagnosticsFormatter(),
                new DiagnosticsPromptBuilder(new MinikunPersonaProvider(character)),
                new CommandCatalog(),
                new CommandFormatter(),
                new VersionService((ObjectProvider) buildProperties, "1.0.0"),
                new VersionFormatter(),
                new ModelsService("chat", "embedding", "memory", "llama", ""),
                new ModelsFormatter(),
                new CacheService("true", "valkey", Duration.ofMinutes(5)),
                new CacheFormatter(),
                mock(ObjectProvider.class));
    }

    private ChatCompletionRequest request(String command) {
        return new ChatCompletionRequest(
                "test-model", List.of(new Message("user", command)), "test", false, null, null, null);
    }
}
