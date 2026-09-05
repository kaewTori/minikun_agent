package com.minikun.runtime;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public final class ModelsService {
    private volatile String chatModel;
    private final String embeddingModel;
    private final String memoryModel;
    private final String searchDecisionModel;
    private final RestClient ollama;

    @Autowired
    public ModelsService(
            @Value("${spring.ai.ollama.chat.options.model:}") String chatModel,
            @Value("${spring.ai.ollama.embedding.options.model:}") String embeddingModel,
            @Value("${minikun.memory.model:}") String memoryModel,
            @Value("${minikun.search.decision.ollama.model:}") String searchDecisionModel,
            @Value("${spring.ai.ollama.base-url:http://127.0.0.1:11434}") String ollamaBaseUrl) {
        this(chatModel, embeddingModel, memoryModel, searchDecisionModel,
                RestClient.create(ollamaBaseUrl.replaceAll("/+$", "")));
    }

    public ModelsService(String chatModel, String embeddingModel, String memoryModel, String searchDecisionModel) {
        this(chatModel, embeddingModel, memoryModel, searchDecisionModel, (RestClient) null);
    }

    ModelsService(String chatModel, String embeddingModel, String memoryModel, String searchDecisionModel,
            RestClient ollama) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.memoryModel = memoryModel;
        this.searchDecisionModel = searchDecisionModel;
        this.ollama = ollama;
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

    public Catalog catalog() {
        List<String> models = installedChatModels();
        return new Catalog(chatModel(), models);
    }

    public Catalog activate(String requestedModel) {
        return activate(requestedModel, installedChatModels());
    }

    Catalog activate(String requestedModel, List<String> installedModels) {
        String model = requestedModel == null ? "" : requestedModel.trim();
        if (model.isEmpty() || !installedModels.contains(model)) {
            throw new IllegalArgumentException("model is not installed in Ollama: " + model);
        }
        chatModel = model;
        return new Catalog(model, installedModels);
    }

    private List<String> installedChatModels() {
        if (ollama == null) {
            throw new IllegalStateException("Ollama model catalog is unavailable");
        }
        OllamaTags response = ollama.get().uri("/api/tags").retrieve().body(OllamaTags.class);
        return response == null || response.models() == null ? List.of() : response.models().stream()
                .map(OllamaModel::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private RuntimeValue configured(String value) {
        return value == null || value.isBlank()
                ? RuntimeValue.notConfigured()
                : RuntimeValue.configured(value);
    }

    public record Catalog(String active, List<String> models) {
        public Catalog {
            models = List.copyOf(models);
        }
    }

    private record OllamaTags(List<OllamaModel> models) {}

    private record OllamaModel(String name) {}
}
