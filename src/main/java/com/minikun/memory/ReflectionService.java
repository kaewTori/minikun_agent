package com.minikun.memory;

import java.time.Clock;
import java.util.List;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.AcceptedMemory;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.memory.reflection.ReflectionPromptBuilder;

public class ReflectionService {
    private final ReflectionPromptBuilder promptBuilder;
    private final ReflectionClient client;
    private final ReflectionParser parser;
    private final ReflectionDecisionService decisionService;
    private final MemoryRepository repository;
    private final Clock clock;

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
            String response = client.reflect(prompt);
            List<AcceptedMemory> accepted = decisionService.decide(parser.parse(response, conversation));
            for (AcceptedMemory memory : accepted) {
                try {
                    repository.save(memory);
                } catch (RuntimeException exception) {
                }
            }
        } catch (RuntimeException exception) {
        }
    }
}