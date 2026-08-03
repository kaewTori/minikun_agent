package com.minikun.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class ModelsService {
    private final String chatModel;
    private final String embeddingModel;
    private final String memoryModel;
    private final String memoryLlamaCppModel;
    private final String searchDecisionModel;

    public ModelsService(
            @Value("${spring.ai.ollama.chat.options.model:}") String chatModel,
            @Value("${spring.ai.embedding.options.model:}") String embeddingModel,
            @Value("${minikun.memory.model:}") String memoryModel,
            @Value("${minikun.memory.llama-cpp.model:}") String memoryLlamaCppModel,
            @Value("${minikun.search.decision.model:}") String searchDecisionModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.memoryModel = memoryModel;
        this.memoryLlamaCppModel = memoryLlamaCppModel;
        this.searchDecisionModel = searchDecisionModel;
    }

    public ModelsInfo snapshot() {
        return new ModelsInfo(
                configured(chatModel),
                configured(embeddingModel),
                configured(memoryModel),
                configured(memoryLlamaCppModel),
                configured(searchDecisionModel));
    }

    private RuntimeValue configured(String value) {
        return value == null || value.isBlank()
                ? RuntimeValue.notConfigured()
                : RuntimeValue.configured(value);
    }
}
