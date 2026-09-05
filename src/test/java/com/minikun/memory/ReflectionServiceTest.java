package com.minikun.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemorySource;
import com.minikun.memory.reflection.ReflectionProvider;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.memory.reflection.ReflectionPromptBuilder;

class ReflectionServiceTest {
    private static final CompletedConversation CONVERSATION = new CompletedConversation(
            "conversation-1", List.of(new CompletedConversation.Message("user", "I use macOS")));
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void buildsPromptAndInvokesClientExactlyOnce() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionProvider client = mock(ReflectionProvider.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(CONVERSATION, LocalDate.of(2026, 8, 5))).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("opaque raw output");
        when(parser.parse("opaque raw output", CONVERSATION)).thenReturn(List.of());
        when(decisionService.decide(List.of())).thenReturn(List.of());

        new ReflectionService(builder, client, parser, decisionService, repository, CLOCK).reflect(CONVERSATION);

        verify(builder).build(CONVERSATION, LocalDate.of(2026, 8, 5));
        verify(client).reflect(prompt);
        verify(parser).parse("opaque raw output", CONVERSATION);
        verify(decisionService).decide(List.of());
    }

    @Test
    void discardsRawClientOutputWithoutInterpretation() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionProvider client = mock(ReflectionProvider.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("not JSON and intentionally opaque");
        when(parser.parse("not JSON and intentionally opaque", CONVERSATION))
            .thenThrow(new MemoryException("invalid reflection"));

        new ReflectionService(builder, client, parser, decisionService, repository, CLOCK).reflect(CONVERSATION);

        verify(client).reflect(prompt);
        verify(parser).parse("not JSON and intentionally opaque", CONVERSATION);
        verifyNoInteractions(decisionService, repository);
    }

    @Test
    void doesNotPropagateClientFailure() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionProvider client = mock(ReflectionProvider.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenThrow(new MemoryException("transport unavailable"));

        new ReflectionService(builder, client, parser, decisionService, repository, CLOCK).reflect(CONVERSATION);

        verify(client).reflect(prompt);
        verifyNoInteractions(parser);
        verifyNoInteractions(decisionService, repository);
    }

    @Test
    void doesNotInvokeClientWhenPromptConstructionFails() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionProvider client = mock(ReflectionProvider.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        when(builder.build(any(), any())).thenThrow(new IllegalStateException("prompt failure"));

        new ReflectionService(builder, client, parser, decisionService, repository, CLOCK).reflect(CONVERSATION);

        verifyNoInteractions(client);
        verifyNoInteractions(parser);
        verifyNoInteractions(decisionService, repository);
    }

        @Test
        void persistsAcceptedInstancesInOrderAndWithoutReplacement() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionProvider client = mock(ReflectionProvider.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        AcceptedMemory first = new AcceptedMemory("conversation-1", MemoryCategory.PROFILE,
            "Uses macOS", 0.9, "stated", MemorySource.LLM_EXTRACTION);
        AcceptedMemory second = new AcceptedMemory("conversation-1", MemoryCategory.SKILL,
            "Writes Java", 0.8, "stated", MemorySource.LLM_EXTRACTION);
        List<AcceptedMemory> accepted = List.of(first, second);
        when(builder.build(CONVERSATION, LocalDate.of(2026, 8, 5))).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("raw");
        when(parser.parse("raw", CONVERSATION)).thenReturn(List.of());
        when(decisionService.decide(List.of())).thenReturn(accepted);

        new ReflectionService(builder, client, parser, decisionService, repository, CLOCK).reflect(CONVERSATION);

        var inOrder = org.mockito.Mockito.inOrder(repository);
        inOrder.verify(repository).save(first);
        inOrder.verify(repository).save(second);
        var captor = org.mockito.ArgumentCaptor.forClass(AcceptedMemory.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertSame(first, captor.getAllValues().getFirst());
        }

        @Test
        void repositoryFailureDoesNotPreventLaterPersistenceAttempts() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionProvider client = mock(ReflectionProvider.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        AcceptedMemory first = new AcceptedMemory("conversation-1", MemoryCategory.PROFILE,
            "Uses macOS", 0.9, "stated", MemorySource.LLM_EXTRACTION);
        AcceptedMemory second = new AcceptedMemory("conversation-1", MemoryCategory.SKILL,
            "Writes Java", 0.8, "stated", MemorySource.LLM_EXTRACTION);
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("raw");
        when(parser.parse("raw", CONVERSATION)).thenReturn(List.of());
        when(decisionService.decide(List.of())).thenReturn(List.of(first, second));
        doThrow(new MemoryException("repository unavailable")).when(repository).save(first);

        new ReflectionService(builder, client, parser, decisionService, repository, CLOCK).reflect(CONVERSATION);

        verify(repository).save(first);
        verify(repository).save(second);
        }

        @Test
        void repositoryFailureIsStickyAndTimerStopsOnce() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionProvider client = mock(ReflectionProvider.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionDecisionService decisionService = mock(ReflectionDecisionService.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        var first = new AcceptedMemory("conversation-1", MemoryCategory.PROFILE,
            "Uses macOS", 0.9, "stated", MemorySource.LLM_EXTRACTION);
        var second = new AcceptedMemory("conversation-1", MemoryCategory.SKILL,
            "Writes Java", 0.8, "stated", MemorySource.LLM_EXTRACTION);
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("raw");
        when(parser.parse("raw", CONVERSATION)).thenReturn(List.of());
        when(decisionService.decide(List.of())).thenReturn(List.of(first, second));
        doThrow(new MemoryException("repository unavailable")).when(repository).save(first);
        when(repository.save(second)).thenReturn(true);

        new ReflectionService(builder, client, parser, decisionService, repository, CLOCK, registry)
            .reflect(CONVERSATION);

        verify(repository).save(first);
        verify(repository).save(second);
        assertEquals(1.0, registry.get("minikun.memory.reflection.requests").counter().count());
        assertEquals(1.0, registry.get("minikun.memory.reflection.failures")
            .tag("failure_type", "repository").counter().count());
        assertEquals(1.0, registry.get("minikun.memory.reflection.outcomes")
            .tag("result", "persistence_failure").counter().count());
        assertEquals(0, registry.find("minikun.memory.reflection.outcomes")
            .tag("result", "success").counters().size());
        assertEquals(1L, registry.get("minikun.memory.reflection.duration")
            .tag("result", "persistence_failure").timer().count());
        }
}
