package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeSelectionPolicyTest {
    @Test
    void equalPoliciesHaveEqualValuesAndHashCodes() {
        KnowledgeSelectionPolicy first = new KnowledgeSelectionPolicy(
                new KnowledgeSelectionPolicy.SourcePolicy(true, 2, 10),
                new KnowledgeSelectionPolicy.SourcePolicy(false, 0, 0));
        KnowledgeSelectionPolicy second = new KnowledgeSelectionPolicy(
                new KnowledgeSelectionPolicy.SourcePolicy(true, 2, 10),
                new KnowledgeSelectionPolicy.SourcePolicy(false, 0, 0));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, KnowledgeSelectionPolicy.DEFAULT);
    }

    @Test
    void zeroAndUnboundedLimitsAreValid() {
        KnowledgeSelectionPolicy.SourcePolicy policy =
                new KnowledgeSelectionPolicy.SourcePolicy(true, 0, 0);

        assertEquals(0, policy.maxCandidates());
        assertEquals(0, policy.maxCharacters());
        assertEquals(KnowledgeSelectionPolicy.UNBOUNDED,
                KnowledgeSelectionPolicy.DEFAULT.memory().maxCandidates());
        assertEquals(KnowledgeSelectionPolicy.UNBOUNDED,
                KnowledgeSelectionPolicy.DEFAULT.memory().maxCharacters());
    }

    @Test
    void negativeLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeSelectionPolicy.SourcePolicy(true, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeSelectionPolicy.SourcePolicy(true, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeSelectionPolicy(-1, 1));
    }

    @Test
    void legacyConstructorKeepsTopKAndUnboundedCharacters() {
        KnowledgeSelectionPolicy policy = new KnowledgeSelectionPolicy(2, 3);

        assertEquals(2, policy.memoryTopK());
        assertEquals(3, policy.searchTopK());
        assertEquals(KnowledgeSelectionPolicy.UNBOUNDED, policy.memory().maxCharacters());
        assertEquals(KnowledgeSelectionPolicy.UNBOUNDED, policy.search().maxCharacters());
    }
}