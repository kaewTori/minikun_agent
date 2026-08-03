package com.minikun.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryCandidate;
import com.minikun.memory.model.MemorySource;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.memory.reflection.ReflectionPromptBuilder;

class ReflectionServiceTest {
    private static final CompletedConversation CONVERSATION = new CompletedConversation(
            "conversation-1", List.of(new CompletedConversation.Message("user", "I use macOS")));

    @Test
    void persistsAcceptedCandidatesInOrderAndPreservesContext() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        MemoryCandidate first = new MemoryCandidate("conversation-1", MemoryCategory.PROFILE,
                "Uses macOS", 0.9, "User stated it");
        MemoryCandidate second = new MemoryCandidate("conversation-1", MemoryCategory.SKILL,
                "Writes Java", 0.8, "User stated it");
        AcceptedMemory acceptedFirst = new AcceptedMemory("conversation-1", MemoryCategory.PROFILE,
                MemorySource.LLM_EXTRACTION, "Uses macOS", 0.9, "User stated it");
        AcceptedMemory acceptedSecond = new AcceptedMemory("conversation-1", MemoryCategory.SKILL,
                MemorySource.LLM_EXTRACTION, "Writes Java", 0.8, "User stated it");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("response");
        when(parser.parse("response", "conversation-1")).thenReturn(List.of(first, second));
        when(decisionService.decide(List.of(first, second))).thenReturn(List.of(acceptedFirst, acceptedSecond));

        new ReflectionService(builder, client, parser, decisionService, repository, Clock.systemUTC())
                .reflect(CONVERSATION);

        var order = org.mockito.ArgumentCaptor.forClass(AcceptedMemory.class);
        verify(repository, org.mockito.Mockito.times(2)).persist(order.capture());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(acceptedFirst, acceptedSecond), order.getAllValues());
        org.junit.jupiter.api.Assertions.assertEquals("conversation-1", order.getAllValues().getFirst().conversationId());
    }

    @Test
    void skipsRejectedCandidates() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        MemoryCandidate candidate = new MemoryCandidate("conversation-1", MemoryCategory.PROFILE,
                "Uses macOS", 0.9, "User stated it");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("response");
        when(parser.parse("response", "conversation-1")).thenReturn(List.of(candidate));
        when(decisionService.decide(List.of(candidate))).thenReturn(List.of());

        new ReflectionService(builder, client, parser, decisionService, repository, Clock.systemUTC()).reflect(CONVERSATION);

        verify(repository, never()).persist(any(AcceptedMemory.class));
    }

    @Test
    void doesNotPersistWhenParsingFails() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("invalid");
        when(parser.parse("invalid", "conversation-1")).thenThrow(new MemoryException("invalid response"));

        new ReflectionService(builder, client, parser, decisionService, repository, Clock.systemUTC()).reflect(CONVERSATION);

        verify(repository, never()).persist(any(AcceptedMemory.class));
    }

    @Test
    void doesNotPropagateRepositoryFailures() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        MemoryCandidate candidate = new MemoryCandidate("conversation-1", MemoryCategory.PROFILE,
                "Uses macOS", 0.9, "User stated it");
        AcceptedMemory accepted = new AcceptedMemory("conversation-1", MemoryCategory.PROFILE,
                MemorySource.LLM_EXTRACTION, "Uses macOS", 0.9, "User stated it");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("response");
        when(parser.parse("response", "conversation-1")).thenReturn(List.of(candidate));
        when(decisionService.decide(List.of(candidate))).thenReturn(List.of(accepted));
        when(repository.persist(accepted)).thenThrow(new IllegalStateException("database unavailable"));

        new ReflectionService(builder, client, parser, decisionService, repository, Clock.systemUTC()).reflect(CONVERSATION);

        verify(repository).persist(accepted);
    }
}
