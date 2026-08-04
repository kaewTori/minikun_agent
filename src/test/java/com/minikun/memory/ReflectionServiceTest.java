package com.minikun.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.reflection.ReflectionClient;
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
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(CONVERSATION, LocalDate.of(2026, 8, 5))).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("opaque raw output");
        when(parser.parse("opaque raw output", CONVERSATION)).thenReturn(List.of());

        new ReflectionService(builder, client, parser, CLOCK).reflect(CONVERSATION);

        verify(builder).build(CONVERSATION, LocalDate.of(2026, 8, 5));
        verify(client).reflect(prompt);
        verify(parser).parse("opaque raw output", CONVERSATION);
    }

    @Test
    void discardsRawClientOutputWithoutInterpretation() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenReturn("not JSON and intentionally opaque");
        when(parser.parse("not JSON and intentionally opaque", CONVERSATION))
            .thenThrow(new MemoryException("invalid reflection"));

        new ReflectionService(builder, client, parser, CLOCK).reflect(CONVERSATION);

        verify(client).reflect(prompt);
        verify(parser).parse("not JSON and intentionally opaque", CONVERSATION);
    }

    @Test
    void doesNotPropagateClientFailure() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        ReflectionPrompt prompt = new ReflectionPrompt(CONVERSATION, "prompt");
        when(builder.build(any(), any())).thenReturn(prompt);
        when(client.reflect(prompt)).thenThrow(new MemoryException("transport unavailable"));

        new ReflectionService(builder, client, parser, CLOCK).reflect(CONVERSATION);

        verify(client).reflect(prompt);
        verifyNoInteractions(parser);
    }

    @Test
    void doesNotInvokeClientWhenPromptConstructionFails() {
        ReflectionPromptBuilder builder = mock(ReflectionPromptBuilder.class);
        ReflectionClient client = mock(ReflectionClient.class);
        ReflectionParser parser = mock(ReflectionParser.class);
        when(builder.build(any(), any())).thenThrow(new IllegalStateException("prompt failure"));

        new ReflectionService(builder, client, parser, CLOCK).reflect(CONVERSATION);

        verifyNoInteractions(client);
        verifyNoInteractions(parser);
    }
}
