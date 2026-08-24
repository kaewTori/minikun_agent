package com.minikun.pcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelRequest;

class AiKnowledgeTaskModelServiceTest {
    @Test
    void rankingUsesBackgroundTaskModelContract() {
        AtomicReference<TaskModelRequest> captured = new AtomicReference<>();
        AiKnowledgeRankingService service = new AiKnowledgeRankingService(request -> {
            captured.set(request);
            return "[{\"candidateId\":\"M1\",\"score\":0.9}]";
        }, new ObjectMapper());

        List<KnowledgeRanking> result = service.rank("what matters", List.of(candidate("M1")));

        assertEquals(List.of(new KnowledgeRanking("M1", 0.9)), result);
        assertEquals(256, captured.get().maxOutputTokens());
        assertEquals(TaskModelRequest.ResponseFormat.TEXT, captured.get().responseFormat());
        assertTrue(captured.get().messages().getFirst().content().contains("M1: M1 content"));
    }

    @Test
    void relevanceUsesTaskModelAndAppliesPolicy() {
        AtomicReference<TaskModelRequest> captured = new AtomicReference<>();
        AiKnowledgeRelevanceService service = new AiKnowledgeRelevanceService(request -> {
            captured.set(request);
            return "[{\"candidateId\":\"M1\",\"score\":0.4}]";
        }, new ObjectMapper(), new KnowledgeRelevancePolicy(true, 0.5));

        List<KnowledgeRelevance> result = service.evaluate("what matters", List.of(candidate("M1")));

        assertEquals(KnowledgeRelevanceDecision.IRRELEVANT, result.getFirst().decision());
        assertEquals(TaskModelRequest.ResponseFormat.TEXT, captured.get().responseFormat());
    }

    private KnowledgeCandidate candidate(String id) {
        return new KnowledgeCandidate(id, KnowledgeSource.MEMORY, id + " content", 0);
    }
}
