package com.minikun.pcs;

import java.util.Objects;

public record McsSelectionContext(String currentUserMessage, String conversationHistory) {
    public McsSelectionContext {
        currentUserMessage = Objects.requireNonNullElse(currentUserMessage, "");
        conversationHistory = Objects.requireNonNullElse(conversationHistory, "");
    }
}
