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

            @Test
            void relevanceFiltersWithoutReorderingTheRankedCandidates() {
            KnowledgeRelevanceService relevance = (request, candidates) -> List.of(
                new KnowledgeRelevance("M2", 0.9d, KnowledgeRelevanceDecision.RELEVANT),
                new KnowledgeRelevance("M1", 0.2d, KnowledgeRelevanceDecision.IRRELEVANT),
                new KnowledgeRelevance("M3", 0.5d, KnowledgeRelevanceDecision.RELEVANT));
            KnowledgeRankingService ranker = (request, candidates) -> List.of(
                new KnowledgeRanking("M2", 10.0d),
                new KnowledgeRanking("M1", 5.0d),
                new KnowledgeRanking("M3", 1.0d));
            DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(
                ranker, KnowledgeSelectionPolicy.DEFAULT, relevance);

            KnowledgeSelection selection = service.select("request",
                List.of(memory("M1", 0), memory("M2", 1), memory("M3", 2)), List.of());

            assertEquals(List.of("M2", "M3"), ids(selection));
            }

            @Test
            void relevanceExceptionKeepsValidatedRankedCandidates() {
            KnowledgeRankingService ranker = (request, candidates) -> List.of(
                new KnowledgeRanking("M2", 10.0d),
                new KnowledgeRanking("M1", 5.0d));
            KnowledgeRelevanceService relevance = (request, candidates) -> {
                throw new IllegalStateException("relevance unavailable");
            };
            DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(
                ranker, KnowledgeSelectionPolicy.DEFAULT, relevance);

            KnowledgeSelection selection = service.select("request",
                List.of(memory("M1", 0), memory("M2", 1)), List.of());

            assertEquals(List.of("M2", "M1"), ids(selection));
            assertEquals(false, selection.rankingFallback());
            }

            @Test
            void malformedRelevanceIsRejectedAsAWhole() {
            KnowledgeRelevanceService relevance = (request, candidates) -> List.of(
                new KnowledgeRelevance("M1", 0.9d, KnowledgeRelevanceDecision.RELEVANT),
                new KnowledgeRelevance("unknown", 0.1d, KnowledgeRelevanceDecision.RELEVANT));
            DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(
                new DefaultKnowledgeRankingService(), KnowledgeSelectionPolicy.DEFAULT, relevance);

            KnowledgeSelection selection = service.select("request",
                List.of(memory("M1", 0), memory("M2", 1)), List.of());

            assertEquals(List.of("M1", "M2"), ids(selection));
            }

            @Test
            void relevanceIsEvaluatedIndependentlyForEachSource() {
            List<KnowledgeSource> calls = new ArrayList<>();
            KnowledgeRelevanceService relevance = (request, candidates) -> {
                calls.add(candidates.get(0).source());
                return candidates.stream()
                    .map(candidate -> new KnowledgeRelevance(candidate.candidateId(), 1.0d,
                        KnowledgeRelevanceDecision.RELEVANT))
                    .toList();
            };
            DefaultKnowledgeSelectionService service = new DefaultKnowledgeSelectionService(
                new DefaultKnowledgeRankingService(), KnowledgeSelectionPolicy.DEFAULT, relevance);

            service.select("request", List.of(memory("M1", 0)), List.of(search("S1", 0)));

            assertEquals(List.of(KnowledgeSource.MEMORY, KnowledgeSource.SEARCH), calls);
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
