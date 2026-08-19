package com.minikun.model.existing;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;

import reactor.core.publisher.Flux;

@Component
public final class ExistingChatModelProvider implements ChatModelProvider {
    private static final ModelCapabilities CAPABILITIES = new ModelCapabilities(true, true, true);

    private final ChatModel chatModel;

    public ExistingChatModelProvider(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chat model must not be null");
    }

    @Override
    public ChatModelId id() {
        return ChatModelId.EXISTING;
    }

    @Override
    public ModelCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ChatResponse chat(Prompt prompt) {
        return chatModel.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return chatModel.stream(prompt);
    }
}
