package com.minikun.pcs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;

public final class AiKnowledgeRankingService implements KnowledgeRankingService {
    private static final TypeReference<List<KnowledgeRanking>> RANKING_TYPE = new TypeReference<>() {
    };

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public AiKnowledgeRankingService(ChatModel chatModel) {
        this(chatModel, new ObjectMapper());
    }

    public AiKnowledgeRankingService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = Objects.requireNonNull(chatModel, "chat model must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public List<KnowledgeRanking> rank(String userRequest, List<KnowledgeCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        String prompt = buildPrompt(Objects.requireNonNullElse(userRequest, ""), candidates);
        var response = chatModel.call(new Prompt(new org.springframework.ai.chat.messages.UserMessage(prompt)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("ranking model returned no response");
        }
        try {
            return objectMapper.readValue(response.getResult().getOutput().getText(), RANKING_TYPE);
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
