package com.minikun.pcs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.pcs.model.KnowledgeContext;

class PersonalKnowledgeSelectionTest {
    @Test
    void selectsPersonalCandidatesThroughDedicatedPolicy() {
        KnowledgeCandidate personal = new KnowledgeCandidate(
                "personal-1", KnowledgeSource.PERSONAL, "personal document", 0, "knowledge://notes/a.md#chunk=0");
        KnowledgeSelection selection = new DefaultKnowledgeSelectionService().select(
                "document", KnowledgeContext.empty(), KnowledgeContext.fromCandidates(List.of(personal)),
                KnowledgeContext.empty(), List.of());

        assertEquals(List.of(personal), selection.selectedCandidates());
    }
}
