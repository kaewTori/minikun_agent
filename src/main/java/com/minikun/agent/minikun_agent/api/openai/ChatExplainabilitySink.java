package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Optional audit sink for visible input provenance and deterministic routing decisions. */
public interface ChatExplainabilitySink {
    void record(Event event);

    record Event(
            String ownerId,
            String conversationId,
            String responseId,
            List<String> sources,
            List<String> tools,
            Map<String, Object> decisions,
            Instant createdAt) {
        public Event {
            sources = sources == null ? List.of() : List.copyOf(sources);
            tools = tools == null ? List.of() : List.copyOf(tools);
            decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
    }
}
