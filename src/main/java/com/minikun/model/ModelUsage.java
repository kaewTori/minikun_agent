package com.minikun.model;

public record ModelUsage(int promptTokens, int completionTokens) {
    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public static ModelUsage empty() {
        return new ModelUsage(0, 0);
    }
}
