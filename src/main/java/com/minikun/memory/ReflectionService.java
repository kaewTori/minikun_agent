package com.minikun.memory;

import java.time.Clock;
import java.time.Instant;

import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPromptBuilder;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ReflectionService {
    private final ReflectionPromptBuilder promptBuilder;
    private final ReflectionClient client;
    private final ReflectionParser parser;
    private final MemoryRepository repository;
    private final Clock clock;

    public ReflectionService(ReflectionPromptBuilder promptBuilder, ReflectionClient client,
            ReflectionParser parser, MemoryRepository repository, Clock clock) {
        this.promptBuilder = promptBuilder;
        this.client = client;
        this.parser = parser;
        this.repository = repository;
        this.clock = clock;
    }

    public void reflect(CompletedConversation conversation) {
        try {
            ReflectionPrompt prompt = promptBuilder.build(conversation, java.time.LocalDate.now(clock));
            var memories = parser.parse(client.reflect(prompt));
            Instant createdAt = clock.instant();
            for (var parsed : memories) {
                Memory memory = new Memory(MemoryId.generate(), parsed.category(), MemorySource.LLM_EXTRACTION,
                        parsed.content().trim(), createdAt, parsed.confidence(), parsed.reason().trim());
                repository.persist(memory, conversation.conversationId());
            }
            log.info("memory_reflection conversation_id={} parsed_count={} success=true",
                    conversation.conversationId(), memories.size());
        } catch (RuntimeException exception) {
            log.warn("memory_reflection conversation_id={} success=false", conversation.conversationId(), exception);
        }
    }
}