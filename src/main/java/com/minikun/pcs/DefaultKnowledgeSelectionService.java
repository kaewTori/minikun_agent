package com.minikun.pcs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DefaultKnowledgeSelectionService implements KnowledgeSelectionService {
    private static final Comparator<RankedCandidate> HIGHEST_SCORE_FIRST =
            Comparator.comparingDouble(RankedCandidate::score).reversed();

    private final KnowledgeRankingService rankingService;
    private final KnowledgeSelectionPolicy policy;

    public DefaultKnowledgeSelectionService() {
        this(new DefaultKnowledgeRankingService(), KnowledgeSelectionPolicy.DEFAULT);
    }

    public DefaultKnowledgeSelectionService(KnowledgeRankingService rankingService) {
        this(rankingService, KnowledgeSelectionPolicy.DEFAULT);
    }

    public DefaultKnowledgeSelectionService(
            KnowledgeRankingService rankingService,
            KnowledgeSelectionPolicy policy) {
        this.rankingService = Objects.requireNonNull(rankingService, "ranking service must not be null");
        this.policy = Objects.requireNonNull(policy, "selection policy must not be null");
    }

    @Override
    public KnowledgeSelection select(
            String userRequest,
            List<KnowledgeCandidate> memoryCandidates,
            List<KnowledgeCandidate> searchCandidates) {
        String normalizedRequest = Objects.requireNonNullElse(userRequest, "");
        SourceSelection memory = selectSource(
                normalizedRequest, copyCandidates(memoryCandidates), KnowledgeSource.MEMORY,
                policy.memoryTopK());
        SourceSelection search = selectSource(
                normalizedRequest, copyCandidates(searchCandidates), KnowledgeSource.SEARCH,
                policy.searchTopK());

        List<KnowledgeCandidate> selected = new ArrayList<>(memory.candidates().size() + search.candidates().size());
        selected.addAll(memory.candidates());
        selected.addAll(search.candidates());
        return new KnowledgeSelection(List.copyOf(selected), memory.fallback() || search.fallback());
    }

    private SourceSelection selectSource(
            String userRequest,
            List<KnowledgeCandidate> candidates,
            KnowledgeSource source,
            int topK) {
        if (candidates.isEmpty()) {
            return new SourceSelection(List.of(), false);
        }
        if (!hasValidSourceCandidates(candidates, source)) {
            return new SourceSelection(limit(candidates, topK), true);
        }

        try {
            List<KnowledgeRanking> rankings = rankingService.rank(userRequest, candidates);
            List<RankedCandidate> ranked = validateAndPair(rankings, candidates);
            ranked.sort(HIGHEST_SCORE_FIRST);
            return new SourceSelection(
                    limit(ranked.stream().map(RankedCandidate::candidate).toList(), topK), false);
        } catch (RuntimeException exception) {
            return new SourceSelection(limit(candidates, topK), true);
        }
    }

    private List<RankedCandidate> validateAndPair(
            List<KnowledgeRanking> rankings,
            List<KnowledgeCandidate> candidates) {
        if (rankings == null || rankings.size() != candidates.size()) {
            throw new IllegalArgumentException("ranking must contain exactly all candidates");
        }
        Map<String, KnowledgeCandidate> candidatesById = new HashMap<>();
        for (KnowledgeCandidate candidate : candidates) {
            if (candidatesById.put(candidate.candidateId(), candidate) != null) {
                throw new IllegalArgumentException("candidate IDs must be unique");
            }
        }

        Set<String> seenIds = new HashSet<>();
        List<RankedCandidate> paired = new ArrayList<>(rankings.size());
        for (KnowledgeRanking ranking : rankings) {
            if (ranking == null || !Double.isFinite(ranking.score())
                    || !seenIds.add(ranking.candidateId())) {
                throw new IllegalArgumentException("ranking contains an invalid candidate");
            }
            KnowledgeCandidate candidate = candidatesById.get(ranking.candidateId());
            if (candidate == null) {
                throw new IllegalArgumentException("ranking contains an unknown candidate");
            }
            paired.add(new RankedCandidate(candidate, ranking.score()));
        }
        if (seenIds.size() != candidatesById.size()) {
            throw new IllegalArgumentException("ranking is missing a candidate");
        }
        return paired;
    }

    private boolean hasValidSourceCandidates(
            List<KnowledgeCandidate> candidates,
            KnowledgeSource source) {
        Set<String> ids = new HashSet<>();
        return candidates.stream().allMatch(candidate -> candidate != null
                && candidate.source() == source
                && ids.add(candidate.candidateId()));
    }

    private List<KnowledgeCandidate> copyCandidates(List<KnowledgeCandidate> candidates) {
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    private List<KnowledgeCandidate> limit(List<KnowledgeCandidate> candidates, int topK) {
        return candidates.subList(0, Math.min(topK, candidates.size()));
    }

    private record RankedCandidate(KnowledgeCandidate candidate, double score) {
    }

    private record SourceSelection(List<KnowledgeCandidate> candidates, boolean fallback) {
    }
}
