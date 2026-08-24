package com.minikun.pcs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiKnowledgeRelevanceService implements KnowledgeRelevanceService {
    private static final TypeReference<List<RelevanceScore>> RELEVANCE_TYPE = new TypeReference<>() {
    };

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final KnowledgeRelevancePolicy policy;

    public AiKnowledgeRelevanceService(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            KnowledgeRelevancePolicy policy) {
        this.chatModel = Objects.requireNonNull(chatModel, "chat model must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.policy = Objects.requireNonNull(policy, "relevance policy must not be null");
    }

    @Override
    public List<KnowledgeRelevance> evaluate(String userRequest, List<KnowledgeCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        String prompt = buildPrompt(Objects.requireNonNullElse(userRequest, ""), candidates);
        var response = chatModel.call(new Prompt(new org.springframework.ai.chat.messages.UserMessage(prompt)));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("relevance model returned no response");
        }
        try {
            return objectMapper.readValue(response.getResult().getOutput().getText(), RELEVANCE_TYPE)
                    .stream()
                    .map(item -> new KnowledgeRelevance(
                            item.candidateId(), item.score(), decisionFor(item.score())))
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("relevance model returned malformed output", exception);
        }
    }

    private KnowledgeRelevanceDecision decisionFor(double score) {
        return policy.accepts(score)
                ? KnowledgeRelevanceDecision.RELEVANT
                : KnowledgeRelevanceDecision.IRRELEVANT;
    }

    private String buildPrompt(String userRequest, List<KnowledgeCandidate> candidates) {
        StringBuilder prompt = new StringBuilder()
                .append("Evaluate each existing knowledge candidate for relevance to the user request. ")
                .append("Return only a JSON array of objects with candidateId and score. ")
                .append("Return every candidate exactly once. Scores must be between 0.0 and 1.0. ")
                .append("Do not create or rewrite knowledge.\n")
                .append("User request: ").append(userRequest).append("\nCandidates:\n");
        for (KnowledgeCandidate candidate : candidates) {
            prompt.append(candidate.candidateId()).append(": ").append(candidate.content()).append('\n');
        }
        return prompt.toString();
    }

    private record RelevanceScore(String candidateId, double score) {
    }
}