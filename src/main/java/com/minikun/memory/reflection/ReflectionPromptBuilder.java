package com.minikun.memory.reflection;

import java.time.LocalDate;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.pcs.MinikunPersonaProvider;

public final class ReflectionPromptBuilder {
    private static final String NO_THINK_PREFIX = "/no_think\n";
    private final ObjectMapper objectMapper;

    public ReflectionPromptBuilder(ObjectMapper objectMapper, MinikunPersonaProvider personaProvider) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(personaProvider, "personaProvider must not be null");
    }

    public ReflectionPrompt build(CompletedConversation conversation, LocalDate currentDate) {
        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        try {
            String messages = objectMapper.writeValueAsString(conversation.messages());
                String content = NO_THINK_PREFIX + """
                    [Reflection instructions]
                    Extract only durable, user-confirmed memories from the conversation snapshot below.
                    Return exactly one JSON object with a `memories` array and no surrounding text.
                    Each array element must contain exactly `category`, `content`, `confidence`, and `reason`.
                    `category` must be one of `PREFERENCE`, `GOAL`, `PROFILE`, `SKILL`, or `PROJECT`.
                    `confidence` must be a finite number from 0.0 to 1.0.
                    Use only facts explicitly stated or confirmed by the user messages in this snapshot.
                    Do not use the assistant messages as evidence and do not extract facts about the assistant.
                    Do not infer facts, inspect stored memories, or include assistant claims as user facts.
                    If there are no durable memories, return {"memories":[]}.

                    Current date: %s

                    [Conversation snapshot]
                    %s
                    """.formatted(currentDate, messages).trim();
            return new ReflectionPrompt(conversation, content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize reflection conversation", exception);
        }
    }
}