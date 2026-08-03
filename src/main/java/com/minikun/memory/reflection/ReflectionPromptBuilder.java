package com.minikun.memory.reflection;

import java.time.LocalDate;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.pcs.MinikunPersonaProvider;

public final class ReflectionPromptBuilder {
    private final ObjectMapper objectMapper;
    private final MinikunPersonaProvider personaProvider;

    public ReflectionPromptBuilder(ObjectMapper objectMapper, MinikunPersonaProvider personaProvider) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.personaProvider = Objects.requireNonNull(personaProvider, "personaProvider must not be null");
    }

    public ReflectionPrompt build(CompletedConversation conversation, LocalDate currentDate) {
        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        try {
            String messages = objectMapper.writeValueAsString(conversation.messages());
            String content = """
                    [Character]
                    %s

                    [Reflection instructions]
                    Extract only durable, user-confirmed memories from the conversation.
                    Return one JSON object with exactly the top-level key `memories`.
                    Each memory must contain exactly `category`, `content`, `confidence`, and `reason`.
                    Do not infer facts, inspect stored memories, or include assistant claims as user facts.
                    If there are no durable memories, return {"memories":[]}.

                    Current date: %s

                    [Conversation snapshot]
                    %s
                    """.formatted(personaProvider.fragment().content(), currentDate, messages).trim();
            return new ReflectionPrompt(conversation, content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize reflection conversation", exception);
        }
    }
}