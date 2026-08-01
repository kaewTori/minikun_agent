package com.minikun.pcs;

import com.minikun.pcs.model.UserMessage;
import com.minikun.pcs.model.Prompt;

public final class PromptComposer {
    public Prompt compose(PromptRequest request) {
        if (request == null) {
            throw new PromptException("request must not be null");
        }
        if (request.character() == null) {
            throw new PromptException("character must not be null");
        }
        if (request.runtime() == null || isBlank(request.runtime().content())) {
            throw new PromptException("runtime must not be blank");
        }
        UserMessage userMessage = request.userMessage();
        if (userMessage == null || isBlank(userMessage.content())) {
            throw new PromptException("user message must not be blank");
        }
        return PromptRenderer.render(request);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}