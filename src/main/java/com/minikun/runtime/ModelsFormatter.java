package com.minikun.runtime;

import org.springframework.stereotype.Component;

@Component
public final class ModelsFormatter {
    public String format(ModelsInfo info) {
        return "Models\n"
                + "Chat: " + RuntimeFormatter.value(info.chatModel()) + '\n'
                + "Embedding: " + RuntimeFormatter.value(info.embeddingModel()) + '\n'
                + "Memory: " + RuntimeFormatter.value(info.memoryModel()) + '\n'
                + "Memory llama.cpp: " + RuntimeFormatter.value(info.memoryLlamaCppModel()) + '\n'
                + "Search decision: " + RuntimeFormatter.value(info.searchDecisionModel()) + '\n';
    }
}
