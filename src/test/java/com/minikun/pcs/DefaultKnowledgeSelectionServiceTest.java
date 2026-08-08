package com.minikun.pcs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultKnowledgeSelectionServiceTest {
    @Test
    void ranksEachSourceIndependentlyAndKeepsMemoryFirst() {
        List<KnowledgeSource> calls = new ArrayList<>();
        KnowledgeRankingService ranker = (request, candidates) -> {
            calls.add(candidates.get(0).source());
            return candidates.stream()
                    .map(candidate -> new KnowledgeRanking(candidate.candidateId(),
                            candidate.candidateId().endsWith("2") ? 10.0 : 1.0))
                    .toList();
        };
        DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(ranker);

        KnowledgeSelection selection = service.select("request",
                List.of(memory("M1", 0), memory("M2", 1)),
                List.of(search("S1", 0), search("S2", 1)));

        assertEquals(List.of(KnowledgeSource.MEMORY, KnowledgeSource.SEARCH), calls);
        assertEquals(List.of("M2", "M1", "S2", "S1"), ids(selection));
    }

    @Test
    void invalidRankingFallsBackAsAWhole() {
        KnowledgeRankingService ranker = (request, candidates) -> List.of(
                new KnowledgeRanking("M2", 10.0),
                new KnowledgeRanking("unknown", 1.0));
        DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(ranker);

        KnowledgeSelection selection = service.select("request",
                List.of(memory("M1", 0), memory("M2", 1)), List.of());

        assertEquals(List.of("M1", "M2"), ids(selection));
        assertEquals(true, selection.rankingFallback());
    }

    @Test
    void missingRankingFallsBackAsAWhole() {
        KnowledgeRankingService ranker = (request, candidates) -> List.of(
                new KnowledgeRanking("M1", 10.0));
        DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(ranker);

        KnowledgeSelection selection = service.select("request",
                List.of(memory("M1", 0), memory("M2", 1)), List.of());

        assertEquals(List.of("M1", "M2"), ids(selection));
        assertEquals(true, selection.rankingFallback());
    }

    @Test
    void tiesPreserveOriginalCandidateOrderAndTopKIsSourceLocal() {
        AtomicInteger calls = new AtomicInteger();
        KnowledgeRankingService ranker = (request, candidates) -> {
            calls.incrementAndGet();
            return candidates.stream()
                    .map(candidate -> new KnowledgeRanking(candidate.candidateId(), 5.0))
                    .toList();
        };
        DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(
                ranker, new KnowledgeSelectionPolicy(2, 1));

        KnowledgeSelection selection = service.select("request",
                List.of(memory("M1", 0), memory("M2", 1), memory("M3", 2)),
                List.of(search("S1", 0), search("S2", 1)));

        assertEquals(2, calls.get());
        assertEquals(List.of("M1", "M2", "S1"), ids(selection));
    }

    @Test
    void populatedSourcesAreTheOnlySourcesRanked() {
        AtomicInteger calls = new AtomicInteger();
        KnowledgeRankingService ranker = (request, candidates) -> {
            calls.incrementAndGet();
            return candidates.stream()
                    .map(candidate -> new KnowledgeRanking(candidate.candidateId(), 1.0))
                    .toList();
        };
        DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(ranker);

        KnowledgeSelection selection = service.select("request", List.of(), List.of(search("S1", 0)));

        assertEquals(1, calls.get());
        assertEquals(List.of("S1"), ids(selection));
    }

    @Test
    void selectionCopiesInputAndOutputLists() {
        List<KnowledgeCandidate> input = new ArrayList<>(List.of(memory("M1", 0)));
        KnowledgeSelection selection = new DefaultKnowledgeSelectionService()
                .select("request", input, List.of());
        input.clear();

        assertEquals(List.of("M1"), ids(selection));
        assertThrows(UnsupportedOperationException.class,
                () -> selection.selectedCandidates().clear());
    }

    private List<String> ids(KnowledgeSelection selection) {
        return selection.selectedCandidates().stream().map(KnowledgeCandidate::candidateId).toList();
    }

    private KnowledgeCandidate memory(String id, int position) {
        return new KnowledgeCandidate(id, KnowledgeSource.MEMORY, id + " content", position);
    }

    private KnowledgeCandidate search(String id, int position) {
        return new KnowledgeCandidate(id, KnowledgeSource.SEARCH, id + " content", position);
    }
}
