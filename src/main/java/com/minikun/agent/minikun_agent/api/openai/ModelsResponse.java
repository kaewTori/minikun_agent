package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;

public record ModelsResponse(String object, List<Model> data) {
    public record Model(String id, String object, long created, String owned_by) {}
}