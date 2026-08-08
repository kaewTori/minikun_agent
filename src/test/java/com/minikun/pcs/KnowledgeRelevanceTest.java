package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeRelevanceTest {
    @Test
    void equalFreshInstancesHaveStableEqualityAndHashCode() {
        KnowledgeRelevance first = new KnowledgeRelevance(
                "candidate", 0.75d, KnowledgeRelevanceDecision.RELEVANT);
        KnowledgeRelevance second = new KnowledgeRelevance(
                "candidate", 0.75d, KnowledgeRelevanceDecision.RELEVANT);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals("candidate", first.candidateId());
        assertEquals(0.75d, first.score());
    }

    @Test
    void rejectsInvalidScores() {
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeRelevance("candidate", -0.01d,
                        KnowledgeRelevanceDecision.RELEVANT));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeRelevance("candidate", 1.01d,
                        KnowledgeRelevanceDecision.RELEVANT));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeRelevance("candidate", Double.NaN,
                        KnowledgeRelevanceDecision.RELEVANT));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeRelevance("candidate", Double.POSITIVE_INFINITY,
                        KnowledgeRelevanceDecision.RELEVANT));
    }

    @Test
    void thresholdUsesInclusiveBoundaryAndValidatesItsRange() {
        KnowledgeRelevancePolicy policy = new KnowledgeRelevancePolicy(true, 0.5d);

        assertEquals(true, policy.accepts(0.5d));
        assertEquals(false, policy.accepts(0.499d));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeRelevancePolicy(true, Double.NaN));
    }
}