package com.minikun.pcs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;

import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiKnowledgeRankingService implements KnowledgeRankingService {
    private static final TypeReference<List<KnowledgeRanking>> RANKING_TYPE = new TypeReference<>() {
    };

    private final TaskModelProvider taskModelProvider;
    private final ObjectMapper objectMapper;

    public AiKnowledgeRankingService(TaskModelProvider taskModelProvider) {
        this(taskModelProvider, new ObjectMapper());
    }

    public AiKnowledgeRankingService(TaskModelProvider taskModelProvider, ObjectMapper objectMapper) {
        this.taskModelProvider = Objects.requireNonNull(taskModelProvider, "task model provider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public List<KnowledgeRanking> rank(String userRequest, List<KnowledgeCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        String prompt = buildPrompt(Objects.requireNonNullElse(userRequest, ""), candidates);
        long started = System.nanoTime();
        String responseText = taskModelProvider.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("user", prompt)), 256, 0.0,
                TaskModelRequest.ResponseFormat.TEXT));
        log.info("model_call=knowledge_ranking request_id=- duration_ms={}",
            (System.nanoTime() - started) / 1_000_000);
        if (responseText == null) {
            throw new IllegalStateException("ranking model returned no response");
        }
        try {
            return objectMapper.readValue(responseText, RANKING_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("ranking model returned malformed output", exception);
        }
    }

    private String buildPrompt(String userRequest, List<KnowledgeCandidate> candidates) {
        StringBuilder prompt = new StringBuilder()
                .append("Rank the existing knowledge candidates for relevance to the user request. ")
                .append("Return only a JSON array of objects with candidateId and score. ")
                .append("Return every candidate exactly once. Do not create or rewrite knowledge.\n")
                .append("User request: ").append(userRequest).append("\nCandidates:\n");
        for (KnowledgeCandidate candidate : candidates) {
            prompt.append(candidate.candidateId()).append(": ").append(candidate.content()).append('\n');
        }
        return prompt.toString();
    }
}
