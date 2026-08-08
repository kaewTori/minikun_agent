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
    private final KnowledgeRelevanceService relevanceService;

    public DefaultKnowledgeSelectionService() {
        this(new DefaultKnowledgeRankingService(), KnowledgeSelectionPolicy.DEFAULT,
                new DefaultKnowledgeRelevanceService());
    }

    public DefaultKnowledgeSelectionService(KnowledgeRankingService rankingService) {
        this(rankingService, KnowledgeSelectionPolicy.DEFAULT, new DefaultKnowledgeRelevanceService());
    }

    public DefaultKnowledgeSelectionService(
            KnowledgeRankingService rankingService,
            KnowledgeSelectionPolicy policy) {
        this(rankingService, policy, new DefaultKnowledgeRelevanceService());
    }

    public DefaultKnowledgeSelectionService(
            KnowledgeRankingService rankingService,
            KnowledgeSelectionPolicy policy,
            KnowledgeRelevanceService relevanceService) {
        this.rankingService = Objects.requireNonNull(rankingService, "ranking service must not be null");
        this.policy = Objects.requireNonNull(policy, "selection policy must not be null");
        this.relevanceService = Objects.requireNonNull(
                relevanceService, "relevance service must not be null");
    }

    @Override
    public KnowledgeSelection select(
            String userRequest,
            List<KnowledgeCandidate> memoryCandidates,
            List<KnowledgeCandidate> searchCandidates) {
        return select(userRequest, memoryCandidates, searchCandidates, List.of());
    }

    @Override
    public KnowledgeSelection select(
            String userRequest,
            List<KnowledgeCandidate> memoryCandidates,
            List<KnowledgeCandidate> searchCandidates,
            List<KnowledgeCandidate> browserCandidates) {
        String normalizedRequest = Objects.requireNonNullElse(userRequest, "");
        SourceSelection memory = selectSource(
                normalizedRequest, copyCandidates(memoryCandidates), KnowledgeSource.MEMORY,
                policy.memory());
        SourceSelection search = selectSource(
                normalizedRequest, copyCandidates(searchCandidates), KnowledgeSource.SEARCH,
                policy.search());
        // Browser content was explicitly supplied by the user. Keep it all (within its policy)
        // rather than allowing relevance scoring to omit an input link.
        SourceSelection browser = new SourceSelection(
                applyPolicy(copyCandidates(browserCandidates), policy.browser()), false);

        List<KnowledgeCandidate> selected = new ArrayList<>(
                memory.candidates().size() + search.candidates().size() + browser.candidates().size());
        selected.addAll(memory.candidates());
        selected.addAll(search.candidates());
        selected.addAll(browser.candidates());
        return new KnowledgeSelection(List.copyOf(selected),
                memory.fallback() || search.fallback() || browser.fallback());
    }

    private SourceSelection selectSource(
            String userRequest,
            List<KnowledgeCandidate> candidates,
            KnowledgeSource source,
            KnowledgeSelectionPolicy.SourcePolicy sourcePolicy) {
        if (!sourcePolicy.enabled() || candidates.isEmpty()) {
            return new SourceSelection(List.of(), false);
        }
        if (!hasValidSourceCandidates(candidates, source)) {
            return new SourceSelection(applyPolicy(candidates, sourcePolicy), true);
        }

        try {
            List<KnowledgeRanking> rankings = rankingService.rank(userRequest, candidates);
            List<RankedCandidate> ranked = validateAndPair(rankings, candidates);
            ranked.sort(HIGHEST_SCORE_FIRST);
            List<KnowledgeCandidate> relevant = applyRelevance(userRequest, ranked, source);
            return new SourceSelection(
                    applyPolicy(relevant, sourcePolicy), false);
        } catch (RuntimeException exception) {
            return new SourceSelection(applyPolicy(candidates, sourcePolicy), true);
        }
    }

    private List<KnowledgeCandidate> applyRelevance(
            String userRequest,
            List<RankedCandidate> ranked,
            KnowledgeSource source) {
        List<KnowledgeCandidate> candidates = ranked.stream()
                .map(RankedCandidate::candidate)
                .toList();
        try {
            List<KnowledgeRelevance> relevance = relevanceService.evaluate(userRequest, candidates);
            return validateAndFilterRelevance(relevance, candidates, source);
        } catch (RuntimeException exception) {
            return candidates;
        }
    }

    private List<KnowledgeCandidate> validateAndFilterRelevance(
            List<KnowledgeRelevance> relevance,
            List<KnowledgeCandidate> candidates,
            KnowledgeSource source) {
        if (relevance == null || relevance.size() != candidates.size()) {
            throw new IllegalArgumentException("relevance must contain exactly all candidates");
        }
        Map<String, KnowledgeCandidate> candidatesById = new HashMap<>();
        for (KnowledgeCandidate candidate : candidates) {
            if (candidate.source() != source || candidatesById.put(candidate.candidateId(), candidate) != null) {
                throw new IllegalArgumentException("relevance candidates must belong to one source");
            }
        }

        Set<String> seenIds = new HashSet<>();
        Map<String, KnowledgeRelevance> relevanceById = new HashMap<>();
        for (KnowledgeRelevance item : relevance) {
            if (item == null || !Double.isFinite(item.score())
                    || item.score() < 0.0d || item.score() > 1.0d
                    || !seenIds.add(item.candidateId())) {
                throw new IllegalArgumentException("relevance contains an invalid candidate");
            }
            if (!candidatesById.containsKey(item.candidateId())) {
                throw new IllegalArgumentException("relevance contains an unknown candidate");
            }
            relevanceById.put(item.candidateId(), item);
        }
        if (seenIds.size() != candidatesById.size()) {
            throw new IllegalArgumentException("relevance is missing a candidate");
        }
        return candidates.stream()
                .filter(candidate -> relevanceById.get(candidate.candidateId()).decision()
                        == KnowledgeRelevanceDecision.RELEVANT)
                .toList();
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

    private List<KnowledgeCandidate> applyPolicy(
            List<KnowledgeCandidate> candidates,
            KnowledgeSelectionPolicy.SourcePolicy sourcePolicy) {
        if (sourcePolicy.maxCandidates() == 0 || sourcePolicy.maxCharacters() == 0) {
            return List.of();
        }
        List<KnowledgeCandidate> selected = new ArrayList<>();
        boolean unboundedCharacters = sourcePolicy.maxCharacters()
            == KnowledgeSelectionPolicy.UNBOUNDED;
        long remainingCharacters = sourcePolicy.maxCharacters();
        for (KnowledgeCandidate candidate : candidates) {
            if (selected.size() >= sourcePolicy.maxCandidates()) {
                break;
            }
            int contentLength = candidate.content().length();
            if (unboundedCharacters || contentLength <= remainingCharacters) {
                selected.add(candidate);
                if (!unboundedCharacters) {
                    remainingCharacters -= contentLength;
                }
                if (!unboundedCharacters && remainingCharacters == 0) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private record RankedCandidate(KnowledgeCandidate candidate, double score) {
    }

    private record SourceSelection(List<KnowledgeCandidate> candidates, boolean fallback) {
    }
}
