package com.minikun.agent.minikun_agent.api.openai;

record ImageAwareness(int count) {
    ImageAwareness {
        if (count < 0) {
            throw new IllegalArgumentException("image awareness count must not be negative");
        }
    }

    boolean hasImages() {
        return count > 0;
    }
}
