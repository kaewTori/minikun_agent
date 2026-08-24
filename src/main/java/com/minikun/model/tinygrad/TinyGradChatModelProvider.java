package com.minikun.model.tinygrad;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;

import reactor.core.publisher.Flux;

@Component
public final class TinyGradChatModelProvider implements ChatModelProvider {
    private final TinyGradClient client;
    private final ModelCapabilities capabilities;

    public TinyGradChatModelProvider(TinyGradClient client) {
        this(client, false);
    }

    @Autowired
    public TinyGradChatModelProvider(
            TinyGradClient client,
            @Value("${minikun.model.tinygrad.tools-enabled:true}") boolean toolsEnabled) {
        this.client = Objects.requireNonNull(client, "TinyGrad client must not be null");
        this.capabilities = new ModelCapabilities(true, toolsEnabled, false);
    }

    @Override
    public ChatModelId id() {
        return ChatModelId.TINYGRAD;
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
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
