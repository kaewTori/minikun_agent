package com.minikun.runtime;

public record ModelsInfo(
        RuntimeValue chatModel,
        RuntimeValue embeddingModel,
        RuntimeValue memoryModel,
        RuntimeValue memoryLlamaCppModel,
        RuntimeValue searchDecisionModel) {
}
