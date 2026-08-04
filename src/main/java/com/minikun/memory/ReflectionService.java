package com.minikun.memory;

import java.time.Clock;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionParser;
import com.minikun.memory.reflection.ReflectionPrompt;
import com.minikun.memory.reflection.ReflectionPromptBuilder;

public class ReflectionService {
    private final ReflectionPromptBuilder promptBuilder;
    private final ReflectionClient client;
    private final ReflectionParser parser;
    private final Clock clock;

    public ReflectionService(ReflectionPromptBuilder promptBuilder, ReflectionClient client,
            ReflectionParser parser, Clock clock) {
        this.promptBuilder = promptBuilder;
        this.client = client;
        this.parser = parser;
        this.clock = clock;
    }

    public void reflect(CompletedConversation conversation) {
        try {
            ReflectionPrompt prompt = promptBuilder.build(conversation, java.time.LocalDate.now(clock));
            String response = client.reflect(prompt);
            parser.parse(response, conversation);
        } catch (RuntimeException exception) {
        }
    }
}