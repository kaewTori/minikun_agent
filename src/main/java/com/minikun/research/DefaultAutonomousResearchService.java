package com.minikun.research;

import com.minikun.browser.BrowserContentService;
import com.minikun.browser.BrowserReadResult;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchRequest;
import java.time.Instant;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/** Bounded plan-search-read-evaluate loop for explicit deep-research requests. */
@Slf4j
public final class DefaultAutonomousResearchService implements AutonomousResearchService {
    private static final int QUERIES_PER_ITERATION = 3;

    private final SearchService searchService;
    private final BrowserContentService browserContentService;
    private final ResearchReasoningProvider reasoningProvider;
    private final boolean enabled;
    private final int maximumIterations;
    private final int maximumQueries;
    private final int maximumFollowUpQueries;
    private final int maximumEvidenceCharacters;

    public DefaultAutonomousResearchService(
            SearchService searchService,
            BrowserContentService browserContentService,
            ResearchReasoningProvider reasoningProvider,
            boolean enabled,
            int maximumIterations,
            int maximumQueries,
            int maximumFollowUpQueries,
            int maximumEvidenceCharacters) {
        this.searchService = Objects.requireNonNull(searchService, "search service must not be null");
        this.browserContentService = browserContentService;
        this.reasoningProvider = Objects.requireNonNull(reasoningProvider, "reasoning provider must not be null");
        if (maximumIterations < 1 || maximumIterations > 5) {
            throw new IllegalArgumentException("maximum research iterations must be between 1 and 5");
        }
        if (maximumQueries < 1 || maximumQueries > 20) {
            throw new IllegalArgumentException("maximum research queries must be between 1 and 20");
        }
        if (maximumFollowUpQueries < 1 || maximumFollowUpQueries > 4) {
            throw new IllegalArgumentException("maximum follow-up queries must be between 1 and 4");
        }
        if (maximumEvidenceCharacters < 1_000 || maximumEvidenceCharacters > 50_000) {
            throw new IllegalArgumentException("maximum evaluation evidence characters must be between 1000 and 50000");
        }
        this.enabled = enabled;
        this.maximumIterations = maximumIterations;
        this.maximumQueries = maximumQueries;
        this.maximumFollowUpQueries = maximumFollowUpQueries;
        this.maximumEvidenceCharacters = maximumEvidenceCharacters;
    }

    @Override
    public AutonomousResearchResult research(AutonomousResearchRequest request) {
        Objects.requireNonNull(request, "research request must not be null");
        if (!enabled) {
            return new AutonomousResearchResult(
                    KnowledgeContext.empty(), List.of(), ResearchTrace.EMPTY);
        }

        ResearchPlan plan;
        boolean plannerFallback = false;
        try {
            plan = reasoningProvider.plan(request.userQuery(), request.conversationContext(),
                    Math.min(maximumQueries, 6));
        } catch (RuntimeException exception) {
            plannerFallback = true;
            plan = ResearchPlan.fallback(request.primaryQuery());
            log.warn("Autonomous research planning failed; using deterministic seed", exception);
        }

        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> knownQueries = new LinkedHashSet<>();
        enqueue(pending, knownQueries, request.primaryQuery());
        request.seedQueries().forEach(query -> enqueue(pending, knownQueries, query));
        plan.questions().forEach(question -> enqueue(pending, knownQueries, question.query()));

        List<String> executedQueries = new ArrayList<>();
        LinkedHashMap<String, KnowledgeCandidate> searchEvidence = new LinkedHashMap<>();
        LinkedHashMap<String, KnowledgeCandidate> browserEvidence = new LinkedHashMap<>();
        Set<String> readUrls = new LinkedHashSet<>();
        ResearchEvaluation evaluation = ResearchEvaluation.incomplete("evidence has not been evaluated");
        ResearchStopReason stopReason = plannerFallback
                ? ResearchStopReason.PLANNER_FALLBACK : ResearchStopReason.MAX_ITERATIONS;
        int iterations = 0;

        while (iterations < maximumIterations && executedQueries.size() < maximumQueries) {
            if (!Instant.now().isBefore(request.deadline())) {
                stopReason = ResearchStopReason.DEADLINE;
                break;
            }
            List<String> batch = nextBatch(pending, executedQueries.size());
            if (batch.isEmpty()) {
                stopReason = executedQueries.size() >= maximumQueries
                        ? ResearchStopReason.MAX_QUERIES : ResearchStopReason.NO_FOLLOW_UPS;
                break;
            }
            iterations++;
            executedQueries.addAll(batch);
            KnowledgeContext found = executeSearch(request, batch);
            addSearchEvidence(searchEvidence, found);
            readSources(request, found, browserEvidence, readUrls);

            try {
                evaluation = reasoningProvider.evaluate(
                        plan,
                        List.copyOf(executedQueries),
                        evidenceDigest(searchEvidence, browserEvidence),
                        maximumFollowUpQueries);
            } catch (RuntimeException exception) {
                stopReason = ResearchStopReason.EVALUATION_FAILED;
                log.warn("Autonomous research coverage evaluation failed; returning gathered evidence", exception);
                break;
            }
            if (evaluation.sufficient()) {
                stopReason = ResearchStopReason.SUFFICIENT;
                break;
            }
            int before = pending.size();
            evaluation.followUpQueries().forEach(query -> enqueue(pending, knownQueries, query));
            if (pending.size() == before && pending.isEmpty()) {
                stopReason = ResearchStopReason.NO_FOLLOW_UPS;
                break;
            }
            if (executedQueries.size() >= maximumQueries) {
                stopReason = ResearchStopReason.MAX_QUERIES;
                break;
            }
        }

        if (iterations >= maximumIterations && stopReason != ResearchStopReason.SUFFICIENT
                && stopReason != ResearchStopReason.EVALUATION_FAILED) {
            stopReason = ResearchStopReason.MAX_ITERATIONS;
        }
        List<KnowledgeCandidate> searchCandidates = reindex(searchEvidence.values(), KnowledgeSource.SEARCH);
        List<KnowledgeCandidate> browserCandidates = reindex(browserEvidence.values(), KnowledgeSource.BROWSER);
        ResearchTrace trace = new ResearchTrace(
                plan.objective(),
                plan.questions().stream().map(ResearchQuestion::query).toList(),
                executedQueries,
                iterations,
                stopReason,
                evaluation.unresolvedGaps(),
                true);
        log.info("process=autonomous_research event=completed iterations={} queries={} search_sources={} "
                        + "browser_sources={} stop_reason={} unresolved_gaps={}",
                iterations, executedQueries.size(), searchCandidates.size(), browserCandidates.size(),
                stopReason, evaluation.unresolvedGaps().size());
        return new AutonomousResearchResult(
                KnowledgeContext.fromCandidates(searchCandidates), browserCandidates, trace);
    }

    private KnowledgeContext executeSearch(AutonomousResearchRequest request, List<String> batch) {
        try {
            return searchService.search(new SearchRequest(
                    UUID.randomUUID(), batch.getFirst(), request.resultLimit(), request.deadline(),
                    new SearchOptions(request.language(), category(request), request.timeRange(), request.safeSearch()),
                    batch.stream().skip(1).toList()));
        } catch (RuntimeException exception) {
            log.warn("Autonomous research search iteration failed; continuing to coverage evaluation", exception);
            return KnowledgeContext.empty();
        }
    }

    private void addSearchEvidence(
            LinkedHashMap<String, KnowledgeCandidate> ledger,
            KnowledgeContext knowledge) {
        if (knowledge == null) {
            return;
        }
        if (knowledge.candidates().isEmpty() && !knowledge.content().isBlank()) {
            KnowledgeCandidate legacy = new KnowledgeCandidate(
                    "research-search-legacy-" + ledger.size(), KnowledgeSource.SEARCH,
                    knowledge.content(), ledger.size());
            ledger.putIfAbsent(legacy.content(), legacy);
            return;
        }
        knowledge.candidates().forEach(candidate -> ledger.putIfAbsent(key(candidate), candidate));
    }

    private void readSources(
            AutonomousResearchRequest request,
            KnowledgeContext found,
            LinkedHashMap<String, KnowledgeCandidate> browserEvidence,
            Set<String> readUrls) {
        if (browserContentService == null || request.sourceReadLimit() == 0
                || browserEvidence.size() >= request.sourceReadLimit() || found == null) {
            return;
        }
        int remaining = request.sourceReadLimit() - browserEvidence.size();
        List<String> urls = found.candidates().stream()
                .sorted(Comparator.comparingInt(candidate -> preferredRank(
                        candidate.provenance(), request.preferredDomains())))
                .map(KnowledgeCandidate::provenance)
                .filter(value -> value != null && !value.isBlank())
                .filter(readUrls::add)
                .limit(remaining)
                .toList();
        if (urls.isEmpty()) {
            return;
        }
        try {
            BrowserReadResult result = browserContentService.readUrls(urls, remaining);
            result.candidates().forEach(candidate -> browserEvidence.putIfAbsent(key(candidate), candidate));
            result.failures().forEach(failure -> log.warn(
                    "process=autonomous_research event=source_read_failed url={} reason={}",
                    failure.url(), failure.reason()));
        } catch (RuntimeException exception) {
            log.warn("Autonomous research source reading failed; retaining search evidence", exception);
        }
    }

    private int preferredRank(String url, List<String> preferredDomains) {
        if (preferredDomains == null || preferredDomains.isEmpty()) return 0;
        try {
            String host = URI.create(Objects.requireNonNullElse(url, "")).getHost();
            if (host == null) return 1;
            String normalized = host.toLowerCase(Locale.ROOT);
            return preferredDomains.stream().anyMatch(domain -> normalized.equals(domain)
                    || normalized.endsWith("." + domain)) ? 0 : 1;
        } catch (RuntimeException exception) {
            return 1;
        }
    }

    private String evidenceDigest(
            LinkedHashMap<String, KnowledgeCandidate> searchEvidence,
            LinkedHashMap<String, KnowledgeCandidate> browserEvidence) {
        StringBuilder digest = new StringBuilder();
        java.util.stream.Stream.concat(searchEvidence.values().stream(), browserEvidence.values().stream())
                .forEach(candidate -> {
                    if (digest.length() >= maximumEvidenceCharacters) {
                        return;
                    }
                    String provenance = candidate.provenance().isBlank() ? "no citation" : candidate.provenance();
                    String line = "[" + candidate.source() + " | " + provenance + "] "
                            + candidate.content().replaceAll("\\s+", " ").strip() + "\n";
                    int remaining = maximumEvidenceCharacters - digest.length();
                    digest.append(line, 0, Math.min(line.length(), remaining));
                });
        return digest.toString();
    }

    private List<String> nextBatch(ArrayDeque<String> pending, int executedCount) {
        List<String> batch = new ArrayList<>();
        int allowed = Math.min(QUERIES_PER_ITERATION, maximumQueries - executedCount);
        while (!pending.isEmpty() && batch.size() < allowed) {
            batch.add(pending.removeFirst());
        }
        return List.copyOf(batch);
    }

    private void enqueue(ArrayDeque<String> pending, Set<String> knownQueries, String rawQuery) {
        String query = Objects.requireNonNullElse(rawQuery, "").replaceAll("\\s+", " ").strip();
        if (query.isBlank() || query.length() > 500 || knownQueries.size() >= maximumQueries) {
            return;
        }
        String key = query.toLowerCase(Locale.ROOT);
        if (knownQueries.add(key)) {
            pending.addLast(query);
        }
    }

    private List<KnowledgeCandidate> reindex(
            java.util.Collection<KnowledgeCandidate> candidates,
            KnowledgeSource source) {
        List<KnowledgeCandidate> result = new ArrayList<>();
        for (KnowledgeCandidate candidate : candidates) {
            int index = result.size();
            result.add(new KnowledgeCandidate(
                    "research-" + source.name().toLowerCase(Locale.ROOT) + "-" + index,
                    source, candidate.content(), index, candidate.provenance()));
        }
        return List.copyOf(result);
    }

    private String key(KnowledgeCandidate candidate) {
        return candidate.provenance().isBlank() ? candidate.content() : candidate.provenance();
    }

    private String category(AutonomousResearchRequest request) {
        return request.timeRange().isBlank() ? "" : "news";
    }
}
