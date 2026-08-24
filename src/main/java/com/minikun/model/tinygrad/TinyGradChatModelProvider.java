package com.minikun.model.tinygrad;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;

import reactor.core.publisher.Flux;

@Component
public final class TinyGradChatModelProvider implements ChatModelProvider {
    private static final ModelCapabilities CAPABILITIES = new ModelCapabilities(true, false, false);

    private final TinyGradClient client;

    public TinyGradChatModelProvider(TinyGradClient client) {
        this.client = Objects.requireNonNull(client, "TinyGrad client must not be null");
    }

    @Override
    public ChatModelId id() {
        return ChatModelId.TINYGRAD;
    }

    @Override
    public ModelCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ChatResponse chat(Prompt prompt) {
        return client.chat(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return client.stream(prompt);
    }
}
