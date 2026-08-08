package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultKnowledgeConsolidationServiceTest {
    private final KnowledgeConsolidationService service = new DefaultKnowledgeConsolidationService();

    @Test
    void emptyInputProducesEmptyObservation() {
        assertEquals(KnowledgeConsolidation.EMPTY, service.consolidate(List.of()));
        assertEquals(KnowledgeConsolidation.EMPTY, service.consolidate(null));
    }

    @Test
    void preservesCandidatesOrderAndProvenance() {
        List<KnowledgeCandidate> candidates = List.of(
                candidate("m1", KnowledgeSource.MEMORY, "memory", 7),
                candidate("s1", KnowledgeSource.SEARCH, "search", 3));

        KnowledgeConsolidation consolidation = service.consolidate(candidates);

        assertEquals(candidates, consolidation.selectedCandidates());
        assertEquals(List.of(
                new KnowledgeProvenance("m1", KnowledgeSource.MEMORY, 7, 0),
                new KnowledgeProvenance("s1", KnowledgeSource.SEARCH, 3, 1)),
                consolidation.provenance());
    }

    @Test
    void reportsExactDuplicatesWithoutRemovingCandidates() {
        List<KnowledgeCandidate> candidates = List.of(
                candidate("m1", KnowledgeSource.MEMORY, "same content", 0),
                candidate("s1", KnowledgeSource.SEARCH, "same content", 0));

        KnowledgeConsolidation consolidation = service.consolidate(candidates);

        assertEquals(candidates, consolidation.selectedCandidates());
        assertEquals(List.of(new KnowledgeRelation(
                consolidation.provenance().get(0),
                consolidation.provenance().get(1),
                KnowledgeRelation.RelationType.DUPLICATE)), consolidation.relations());
        assertEquals(List.of(), consolidation.conflicts());
    }

    @Test
    void differingContentIsUnknownAndNeverConflict() {
        KnowledgeConsolidation consolidation = service.consolidate(List.of(
                candidate("m1", KnowledgeSource.MEMORY, "one", 0),
                candidate("s1", KnowledgeSource.SEARCH, "two", 0)));

        assertEquals(KnowledgeRelation.RelationType.UNKNOWN,
                consolidation.relations().get(0).type());
        assertEquals(List.of(), consolidation.conflicts());
    }

    @Test
    void outputCollectionsAreDefensiveAndInputIsNotMutated() {
        List<KnowledgeCandidate> candidates = new ArrayList<>(List.of(
                candidate("m1", KnowledgeSource.MEMORY, "content", 0)));
        KnowledgeConsolidation consolidation = service.consolidate(candidates);
        candidates.clear();

        assertEquals(1, consolidation.selectedCandidates().size());
        assertThrows(UnsupportedOperationException.class,
                () -> consolidation.selectedCandidates().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> consolidation.provenance().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> consolidation.relations().clear());
    }

    @Test
    void repeatedExecutionIsValueEqual() {
        List<KnowledgeCandidate> candidates = List.of(
                candidate("m1", KnowledgeSource.MEMORY, "same", 0),
                candidate("m2", KnowledgeSource.MEMORY, "same", 1),
                candidate("s1", KnowledgeSource.SEARCH, "other", 0));

        assertEquals(service.consolidate(candidates), service.consolidate(List.copyOf(candidates)));
    }

    @Test
    void rejectsNullCandidate() {
        assertThrows(NullPointerException.class, () -> service.consolidate(List.of((KnowledgeCandidate) null)));
    }

    private KnowledgeCandidate candidate(String id, KnowledgeSource source, String content, int position) {
        return new KnowledgeCandidate(id, source, content, position);
    }
}