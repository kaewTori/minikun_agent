package com.minikun.model;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

public interface ChatModelProvider {
    ChatModelId id();

    ModelCapabilities capabilities();

    ChatResponse chat(Prompt prompt);

    Flux<ChatResponse> stream(Prompt prompt);
}