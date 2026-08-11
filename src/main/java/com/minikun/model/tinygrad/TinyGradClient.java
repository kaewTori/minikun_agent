package com.minikun.model.tinygrad;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

interface TinyGradClient {
    ChatResponse chat(Prompt prompt);

    Flux<ChatResponse> stream(Prompt prompt);
}
