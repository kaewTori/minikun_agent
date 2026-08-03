package com.minikun.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.Memory;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.memory.reflection.ReflectionPromptBuilder;

class ReflectionServiceTest {
    private static final CompletedConversation CONVERSATION = new CompletedConversation(
            "conversation-1", List.of(new CompletedConversation.Message("user", "I use macOS")));

    @Test
    void attemptsPersistenceForEveryParsedMemory() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("response");
        when(parser.parse("response")).thenReturn(List.of(
                new ReflectionParser.ReflectionMemory(
                        com.minikun.memory.model.MemoryCategory.PROFILE, "Uses macOS", 0.9, "User stated it"),
                new ReflectionParser.ReflectionMemory(
                        com.minikun.memory.model.MemoryCategory.SKILL, "Writes Java", 0.8, "User stated it")));

        new ReflectionService(builder, client, parser, repository,
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC)).reflect(CONVERSATION);

        verify(repository, org.mockito.Mockito.times(2))
                .persist(any(Memory.class), org.mockito.ArgumentMatchers.eq("conversation-1"));
    }

    @Test
    void doesNotPersistWhenParsingFails() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        MemoryRepository repository = mock(MemoryRepository.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("invalid");
        when(parser.parse("invalid")).thenThrow(new MemoryException("invalid response"));

        new ReflectionService(builder, client, parser, repository, Clock.systemUTC()).reflect(CONVERSATION);

        verify(repository, never()).persist(any(Memory.class), any());
    }
}