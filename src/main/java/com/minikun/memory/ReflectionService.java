package com.minikun.memory;

import java.time.Clock;
import com.minikun.memory.model.CompletedConversation;
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
    private final ReflectionDecisionService decisionService;
    private final MemoryRepository repository;
    private final Clock clock;

    public ReflectionService(ReflectionPromptBuilder promptBuilder, ReflectionClient client,
            ReflectionParser parser, MemoryRepository repository, Clock clock) {
            this(promptBuilder, client, parser, new ReflectionDecisionService(), repository, clock);
            }

            public ReflectionService(ReflectionPromptBuilder promptBuilder, ReflectionClient client,
                ReflectionParser parser, ReflectionDecisionService decisionService,
                MemoryRepository repository, Clock clock) {
        this.promptBuilder = promptBuilder;
        this.client = client;
        this.parser = parser;
        this.decisionService = decisionService;
        this.repository = repository;
        this.clock = clock;
    }

    public void reflect(CompletedConversation conversation) {
        try {
            ReflectionPrompt prompt = promptBuilder.build(conversation, java.time.LocalDate.now(clock));
            var candidates = parser.parse(client.reflect(prompt), conversation.conversationId());
            var accepted = decisionService.decide(candidates);
            for (var memory : accepted) {
                repository.persist(memory);
            }
            log.info("memory_reflection conversation_id={} parsed_count={} success=true",
                    conversation.conversationId(), candidates.size());
        } catch (RuntimeException exception) {
            log.warn("memory_reflection conversation_id={} success=false", conversation.conversationId(), exception);
        }
    }
}