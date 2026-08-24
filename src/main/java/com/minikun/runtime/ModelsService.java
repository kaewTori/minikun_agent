package com.minikun.runtime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class ModelsService {
    private final String chatModel;
    private final String embeddingModel;
    private final String memoryModel;
    private final String searchDecisionModel;

    @Autowired
    public ModelsService(
            @Value("${minikun.model.active:existing}") String activeProvider,
            @Value("${spring.ai.ollama.chat.options.model:}") String ollamaChatModel,
            @Value("${minikun.model.tinygrad.model:}") String tinyGradChatModel,
            @Value("${spring.ai.embedding.options.model:${spring.ai.ollama.embedding.options.model:}}") String embeddingModel,
            @Value("${minikun.memory.model:}") String memoryModel,
            @Value("${minikun.search.decision.model:}") String searchDecisionModel) {
        this.chatModel = "tinygrad".equalsIgnoreCase(activeProvider) ? tinyGradChatModel : ollamaChatModel;
        this.embeddingModel = embeddingModel;
        this.memoryModel = memoryModel;
        this.searchDecisionModel = searchDecisionModel;
    }

    public ModelsService(String chatModel, String embeddingModel, String memoryModel, String searchDecisionModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.memoryModel = memoryModel;
        this.searchDecisionModel = searchDecisionModel;
    }

    public ModelsInfo snapshot() {
        return new ModelsInfo(
                configured(chatModel),
                configured(embeddingModel),
                configured(memoryModel),
                configured(searchDecisionModel));
    }

    public String chatModel() {
        return chatModel;
    }

    private RuntimeValue configured(String value) {
        return value == null || value.isBlank()
                ? RuntimeValue.notConfigured()
                : RuntimeValue.configured(value);
    }
}
