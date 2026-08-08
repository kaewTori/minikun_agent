package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

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
import com.minikun.memory.model.CompletedConversation;
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

class ChatServiceReflectionBoundaryTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");
    private static final ConversationId CONVERSATION_ID = new ConversationId("conversation-1");

    @Test
    void selectsLatestCompletedTurnAndPreservesConsecutiveUsers() throws Exception {
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "old user"),
                new ChatMessage("assistant", "old assistant"),
                new ChatMessage("user", "latest user A"),
                new ChatMessage("user", "latest user B"),
                new ChatMessage("assistant", "latest assistant"));
        ConversationMemoryService memory = mockMemory(messages);

        Optional<CompletedConversation> snapshot = select(memory);

        assertEquals(List.of(
                new CompletedConversation.Message("user", "latest user A"),
                new CompletedConversation.Message("user", "latest user B"),
                new CompletedConversation.Message("assistant", "latest assistant")),
                snapshot.orElseThrow().messages());
    }

    @Test
    void skipsPartialLatestTurnInsteadOfUsingHistoricalAssistant() throws Exception {
        ConversationMemoryService memory = mockMemory(List.of(
                new ChatMessage("user", "old user"),
                new ChatMessage("assistant", "old assistant"),
                new ChatMessage("user", "partial latest user")));

        assertTrue(select(memory).isEmpty());
    }

    @Test
    void excludesSystemMessagesAndDoesNotCrossSystemBoundary() throws Exception {
        ConversationMemoryService memory = mockMemory(List.of(
                new ChatMessage("user", "old user"),
                new ChatMessage("assistant", "old assistant"),
                new ChatMessage("system", "system boundary"),
                new ChatMessage("user", "latest user"),
                new ChatMessage("assistant", "latest assistant")));

        Optional<CompletedConversation> snapshot = select(memory);

        assertEquals(List.of(
                new CompletedConversation.Message("user", "latest user"),
                new CompletedConversation.Message("assistant", "latest assistant")),
                snapshot.orElseThrow().messages());
    }

    @Test
    void skipsEmptyAssistantOnlyAndUserOnlyHistories() throws Exception {
        assertTrue(select(mockMemory(List.of())).isEmpty());
        assertTrue(select(mockMemory(List.of(new ChatMessage("assistant", "assistant")))).isEmpty());
        assertTrue(select(mockMemory(List.of(new ChatMessage("user", "user")))).isEmpty());
        assertTrue(select(mockMemory(List.of(new ChatMessage("system", "system")))).isEmpty());
    }

    @Test
    void snapshotIsImmutableAndRepeatedSelectionIsDeterministic() throws Exception {
        List<ChatMessage> messages = new java.util.ArrayList<>(List.of(
                new ChatMessage("user", "user"),
                new ChatMessage("assistant", "assistant")));
        ConversationMemoryService memory = mockMemory(messages);

        Optional<CompletedConversation> first = select(memory);
        messages.set(0, new ChatMessage("user", "changed source"));
        Optional<CompletedConversation> second = select(mockMemory(List.of(
                new ChatMessage("user", "user"),
                new ChatMessage("assistant", "assistant"))));

        assertEquals(first, second);
        assertEquals("user", first.orElseThrow().messages().getFirst().content());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> first.orElseThrow().messages().add(null));
    }

    private ConversationMemoryService mockMemory(List<ChatMessage> messages) {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.load(CONVERSATION_ID)).thenReturn(messages);
        return memory;
    }

    @SuppressWarnings("unchecked")
    private Optional<CompletedConversation> select(ConversationMemoryService memory) throws Exception {
        ChatService service = service(memory);
        Method method = ChatService.class.getDeclaredMethod(
                "completedConversation", String.class, ConversationId.class);
        method.setAccessible(true);
        return (Optional<CompletedConversation>) method.invoke(service, "owner-1", CONVERSATION_ID);
    }

    private ChatService service(ConversationMemoryService memory) {
        CharacterSpecification character = new CharacterLoader(MCS_ROOT).load();
        return new ChatService(
                mock(ChatModel.class),
                mock(EmbeddingModel.class),
                mock(ChatTransactionLogger.class),
                memory,
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
                mock(VersionService.class),
                mock(VersionFormatter.class),
                mock(ModelsService.class),
                mock(ModelsFormatter.class),
                mock(CacheService.class),
                mock(CacheFormatter.class),
                mock(ObjectProvider.class));
    }
}
