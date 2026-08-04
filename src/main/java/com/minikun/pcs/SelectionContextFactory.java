package com.minikun.pcs;

import java.util.Objects;

public final class SelectionContextFactory {
    public McsSelectionContext create(String currentUserMessage, String conversationHistory) {
        return new McsSelectionContext(
                Objects.requireNonNullElse(currentUserMessage, ""),
                Objects.requireNonNullElse(conversationHistory, ""),
                ConversationAttributes.EMPTY,
                RuntimeAttributes.EMPTY,
                MemorySelectionSignals.EMPTY);
    }
}
