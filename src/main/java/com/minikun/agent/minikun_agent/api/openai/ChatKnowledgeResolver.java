package com.minikun.agent.minikun_agent.api.openai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.browser.BrowserContentException;
import com.minikun.browser.BrowserContentService;
import com.minikun.browser.BrowserReadResult;
import com.minikun.knowledge.PersonalKnowledgeService;
import com.minikun.knowledge.acquisition.AcquiredKnowledgeIndex;
import com.minikun.memory.LongTermMemoryScope;
import com.minikun.memory.MemoryRecallService;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeConsolidationService;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSelectionService;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.research.ResearchIntentDetector;
import com.minikun.research.AutonomousResearchRequest;
import com.minikun.research.AutonomousResearchResult;
import com.minikun.research.AutonomousResearchService;
import com.minikun.research.ResearchTrace;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.SearchSelectionSignalMapper;
import com.minikun.search.SearchService;
import com.minikun.search.internal.ExternalContextPlanner;
import com.minikun.search.model.ExternalContextAction;
import com.minikun.search.model.ExternalContextDecision;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchQueryPlan;
import com.minikun.search.model.SearchRequest;

import lombok.extern.slf4j.Slf4j;

/** Resolves all request-time knowledge without coupling prompt composition to retrieval mechanics. */
@Slf4j
final class ChatKnowledgeResolver {
    private final ObjectProvider<MemoryRecallService> memoryRecallService;
    private final PersonalKnowledgeService personalKnowledgeService;
    private final AcquiredKnowledgeIndex acquiredKnowledgeIndex;
    private final SearchService searchService;
    private final SearchDecisionService searchDecisionService;
    private final SearchQueryPlanningService searchQueryPlanningService;
    private final SearchContextAwarenessService searchContextAwarenessService;
    private final KnowledgeSelectionService knowledgeSelectionService;
    private final KnowledgeConsolidationService knowledgeConsolidationService;
    private final SearchSelectionSignalMapper searchSelectionSignalMapper;
    private final BrowserContentService browserContentService;
    private final AutonomousResearchService autonomousResearchService;
    private final ChatPerformanceMetrics performanceMetrics;
    private final Configuration configuration;
    private final ExternalContextPlanner externalContextPlanner = new ExternalContextPlanner();
    private final ResearchIntentDetector researchIntentDetector = new ResearchIntentDetector();

    ChatKnowledgeResolver(
            ObjectProvider<MemoryRecallService> memoryRecallService,
            PersonalKnowledgeService personalKnowledgeService,
            SearchService searchService,
            SearchDecisionService searchDecisionService,
            SearchQueryPlanningService searchQueryPlanningService,
            SearchContextAwarenessService searchContextAwarenessService,
            KnowledgeSelectionService knowledgeSelectionService,
            KnowledgeConsolidationService knowledgeConsolidationService,
            SearchSelectionSignalMapper searchSelectionSignalMapper,
            BrowserContentService browserContentService,
            AutonomousResearchService autonomousResearchService,
            ChatPerformanceMetrics performanceMetrics,
            Configuration configuration) {
        this(memoryRecallService, personalKnowledgeService, null, searchService, searchDecisionService,
                searchQueryPlanningService, searchContextAwarenessService, knowledgeSelectionService,
                knowledgeConsolidationService, searchSelectionSignalMapper, browserContentService,
                autonomousResearchService, performanceMetrics, configuration);
    }

    ChatKnowledgeResolver(
            ObjectProvider<MemoryRecallService> memoryRecallService,
            PersonalKnowledgeService personalKnowledgeService,
            AcquiredKnowledgeIndex acquiredKnowledgeIndex,
            SearchService searchService,
            SearchDecisionService searchDecisionService,
            SearchQueryPlanningService searchQueryPlanningService,
            SearchContextAwarenessService searchContextAwarenessService,
            KnowledgeSelectionService knowledgeSelectionService,
            KnowledgeConsolidationService knowledgeConsolidationService,
            SearchSelectionSignalMapper searchSelectionSignalMapper,
            BrowserContentService browserContentService,
            AutonomousResearchService autonomousResearchService,
            ChatPerformanceMetrics performanceMetrics,
            Configuration configuration) {
        this.memoryRecallService = memoryRecallService;
        this.personalKnowledgeService = personalKnowledgeService;
        this.acquiredKnowledgeIndex = acquiredKnowledgeIndex;
        this.searchService = searchService;
        this.searchDecisionService = searchDecisionService;
        this.searchQueryPlanningService = searchQueryPlanningService;
        this.searchContextAwarenessService = searchContextAwarenessService;
        this.knowledgeSelectionService = knowledgeSelectionService;
        this.knowledgeConsolidationService = knowledgeConsolidationService;
        this.searchSelectionSignalMapper = searchSelectionSignalMapper;
        this.browserContentService = browserContentService;
        this.autonomousResearchService = autonomousResearchService;
        this.performanceMetrics = performanceMetrics;
        this.configuration = configuration;
    }

    ChatKnowledgeSelection resolve(Request request) {
        Map<String, String> previous = null;
        boolean scoped = false;
        try {
            previous = MDC.getCopyOfContextMap();
            if (request.requestId() != null) {
                MDC.put("request_id", request.requestId());
            }
            if (request.conversationId() != null) {
                MDC.put("conversation_id", request.conversationId().value());
            }
            scoped = true;
        } catch (RuntimeException ignored) {
            restoreMdc(previous);
        }
        try {
            return resolveScoped(request);
        } finally {
            if (scoped) {
                restoreMdc(previous);
            }
        }
    }

    private ChatKnowledgeSelection resolveScoped(Request request) {
        String query = request.query();
        boolean deepResearch = request.turnPlan() == null
                ? researchIntentDetector.detect(query).deepResearch() : request.turnPlan().deepResearch();
        log.info("process=knowledge_pipeline event=start");
        long memoryStarted = System.nanoTime();
        CompletableFuture<KnowledgeContext> memoryFuture =
                request.turnPlan() != null && !request.turnPlan().needsMemory()
                        ? CompletableFuture.completedFuture(KnowledgeContext.empty())
                        : CompletableFuture.supplyAsync(() -> recallMemory(
                                query, request.conversationId(), request.ownerId()));
        CompletableFuture<KnowledgeContext> personalFuture =
                request.turnPlan() != null && !request.turnPlan().needsPersonalKnowledge()
                        ? CompletableFuture.completedFuture(KnowledgeContext.empty())
                        : CompletableFuture.supplyAsync(() -> recallPersonal(query, request.ownerId()));
        CompletableFuture<SearchDecision> decisionFuture = configuration.searchEnabled()
                && !isInternalTitleRequest(query)
                ? CompletableFuture.supplyAsync(() -> decideSearch(query, request.classifierContext()))
                : null;
        KnowledgeContext memoryKnowledge;
        KnowledgeContext personalKnowledge;
        try {
            memoryKnowledge = joinKnowledge(memoryFuture, "memory");
            personalKnowledge = joinKnowledge(personalFuture, "personal");
        } finally {
            recordStage("memory", memoryStarted, "success");
        }

        KnowledgeContext localKnowledge = combine(memoryKnowledge, personalKnowledge);
        long searchStarted = System.nanoTime();
        if (!configuration.searchEnabled() || isInternalTitleRequest(query)) {
            log.info("process=search event=skipped enabled={} internal_request={}",
                    configuration.searchEnabled(), isInternalTitleRequest(query));
            List<KnowledgeCandidate> browserCandidates = readBrowserCandidates(query);
            recordStage("search", searchStarted, "skipped");
            return selection(
                    request, memoryKnowledge, personalKnowledge, null, false, null,
                    browserCandidates, SearchSelectionSignals.EMPTY);
        }

        SearchDecision decision = decisionFuture == null
                ? new SearchDecision(false, query)
                : joinSearchDecision(decisionFuture, query);
        log.info("process=search_decision event=completed should_search={} reason={}",
                decision.shouldSearch(), decision.reason());

        boolean explicitUrl = browserContentService != null && !browserContentService.urlsIn(query).isEmpty();
        ExternalContextDecision externalDecision = externalContextPlanner.plan(
                decision,
                explicitUrl,
                request.conversationContextAvailable(),
                !localKnowledge.content().isBlank());
        log.info("process=external_context event=planned action={} confidence={} reason={}",
                externalDecision.action(), externalDecision.confidence(), externalDecision.reason());

        boolean shouldOpenBrowser = externalDecision.action() == ExternalContextAction.OPEN_EXPLICIT_URL
                || externalDecision.action() == ExternalContextAction.SEARCH_THEN_OPEN;
        CompletableFuture<List<KnowledgeCandidate>> browserFuture = shouldOpenBrowser
                ? CompletableFuture.supplyAsync(() -> readBrowserCandidates(query))
                : CompletableFuture.completedFuture(List.of());
        SearchQueryPlan plan = configuration.queryPlanningEnabled()
                ? searchQueryPlanningService.plan(query, decision, request.classifierContext())
                : new SearchQueryPlan(
                        decision.shouldSearch(), query, decision.shouldSearch() ? decision.query() : "",
                        List.of(), List.of(), "all", "general", "", 1.0, "query_planning_disabled");
        SearchDecision plannedDecision = plan.shouldSearch()
                ? new SearchDecision(true, plan.primaryQuery(), decision.reason(),
                        new com.minikun.search.model.SearchPlanHints(
                                plan.intent(), plan.confidence(), plan.primaryQuery(), plan.alternateQueries(),
                                plan.evidenceNeeds(), plan.location()))
                : decision;
        log.info("process=search_query_plan event=completed should_search={} primary_query={} alternates={} "
                        + "core_terms={} language={} intent={} time_range={} confidence={} reason={} evidence={} location={}",
                plan.shouldSearch(), plan.primaryQuery(), plan.alternateQueries(), plan.coreTerms(),
                plan.language(), plan.intent(), plan.timeRange(), plan.confidence(), plan.reason(),
                plan.evidenceNeeds(), plan.location());
        SearchSelectionSignals searchSignals = searchSelectionSignalMapper.map(decision);
        if (!plan.shouldSearch()) {
            fastPath("no_search");
            log.info("process=search event=skipped reason=query_plan");
            List<KnowledgeCandidate> browserCandidates = joinBrowser(browserFuture);
            recordStage("search", searchStarted, "skipped");
            return selection(
                    request, memoryKnowledge, personalKnowledge, plannedDecision, false, null,
                    browserCandidates, searchSignals);
        }

        if (deepResearch && autonomousResearchService != null) {
            try {
                AutonomousResearchResult research = autonomousResearchService.research(
                        new AutonomousResearchRequest(
                                query,
                                request.classifierContext(),
                                plan.primaryQuery(),
                                plan.alternateQueries(),
                                plan.language(),
                                plan.timeRange(),
                                configuration.safeSearch(),
                                Math.max(1, Math.min(100, configuration.searchResultLimit())),
                                configuration.researchSourceReadLimit(),
                                List.of(),
                                Instant.now().plus(configuration.autonomousResearchTimeout())));
                if (research.trace().autonomous()) {
                    List<KnowledgeCandidate> explicitBrowserCandidates = joinBrowser(browserFuture);
                    List<KnowledgeCandidate> allBrowserCandidates = mergeBrowserCandidates(
                            explicitBrowserCandidates, research.browserCandidates());
                    recordStage("search", searchStarted, "autonomous_success");
                    return selection(
                            request, memoryKnowledge, personalKnowledge, plannedDecision, true,
                            research.searchKnowledge(), allBrowserCandidates, searchSignals, research.trace());
                }
            } catch (RuntimeException exception) {
                log.warn("Autonomous research failed; using deterministic research fallback", exception);
            }
        }

        List<KnowledgeCandidate> browserCandidates = List.of();
        try {
            boolean imageRequest = SearchOptions.IMAGE_CATEGORY.equals(
                    categoryFor(plannedDecision, plan.intent()));
            SearchRequest searchRequest = new SearchRequest(
                    UUID.randomUUID(),
                    plan.primaryQuery(),
                    Math.max(1, Math.min(100, configuration.searchResultLimit())),
                    Instant.now().plus(configuration.searchTimeout()),
                    new SearchOptions(
                            plan.language(), categoryFor(plannedDecision, plan.intent()),
                            plan.timeRange(), configuration.safeSearch()),
                    plan.alternateQueries());
            CompletableFuture<KnowledgeContext> searchFuture =
                    CompletableFuture.supplyAsync(() -> searchService.search(searchRequest));
            CompletableFuture<KnowledgeContext> evidenceFuture = imageRequest
                    ? CompletableFuture.supplyAsync(() -> searchService.search(new SearchRequest(
                            UUID.randomUUID(), plan.primaryQuery(),
                            Math.max(1, Math.min(100, configuration.searchResultLimit())),
                            Instant.now().plus(configuration.searchTimeout()),
                            new SearchOptions(plan.language(), "", plan.timeRange(), configuration.safeSearch()),
                            plan.alternateQueries())))
                    : CompletableFuture.completedFuture(KnowledgeContext.empty());
            browserCandidates = joinBrowser(browserFuture);
            KnowledgeContext searchKnowledge = imageRequest
                    ? combine(joinKnowledge(searchFuture, "image"), joinKnowledge(evidenceFuture, "image_evidence"))
                    : searchFuture.join();
            searchKnowledge = ensureRecommendationEvidence(plan, searchKnowledge);
            if (deepResearch) {
                browserCandidates = mergeBrowserCandidates(
                        browserCandidates, readResearchSourceCandidates(searchKnowledge));
            }
            log.info("Search completed knowledgeCharacters={}",
                    searchKnowledge == null ? 0 : searchKnowledge.content().length());
            recordStage("search", searchStarted, "success");
            return selection(
                    request, memoryKnowledge, personalKnowledge, plannedDecision, true, searchKnowledge,
                    browserCandidates, searchSignals);
        } catch (RuntimeException exception) {
            log.warn("Search failed; continuing without search knowledge", exception);
            recordStage("search", searchStarted, "error");
            return selection(
                    request, memoryKnowledge, personalKnowledge, plannedDecision, true, null,
                    browserCandidates, searchSignals);
        }
    }

    private SearchDecision decideSearch(String query, String classifierContext) {
        SearchDecision decision = searchDecisionService.decide(query, classifierContext);
        if (decision == null) decision = searchDecisionService.decide(query);
        return decision == null ? new SearchDecision(false, query) : decision;
    }

    private SearchDecision joinSearchDecision(
            CompletableFuture<SearchDecision> future, String query) {
        try {
            SearchDecision decision = future.join();
            return decision == null ? new SearchDecision(false, query) : decision;
        } catch (RuntimeException exception) {
            log.warn("Search decision failed; using no-search fallback", exception);
            return new SearchDecision(false, query);
        }
    }

    private KnowledgeContext recallMemory(String query, ConversationId conversationId, String ownerId) {
        if (conversationId == null || ownerId == null || ownerId.isBlank()) {
            return KnowledgeContext.empty();
        }
        MemoryRecallService service = memoryRecallService.getIfAvailable();
        if (service == null) {
            return KnowledgeContext.empty();
        }
        try {
            KnowledgeContext knowledge = service.recall(
                    new LongTermMemoryScope(ownerId), query, configuration.memoryRetrievalLimit());
            log.info("process=memory_recall event=completed candidates={}",
                    knowledge == null ? 0 : knowledge.candidates().size());
            return knowledge == null ? KnowledgeContext.empty() : knowledge;
        } catch (RuntimeException exception) {
            log.warn("Long-term memory recall failed; continuing without knowledge", exception);
            return KnowledgeContext.empty();
        }
    }

    private KnowledgeContext recallPersonal(String query, String ownerId) {
        if (ownerId == null || ownerId.isBlank()
                || query == null || query.isBlank() || configuration.personalKnowledgeLimit() < 1) {
            return KnowledgeContext.empty();
        }
        KnowledgeContext documents = KnowledgeContext.empty();
        KnowledgeContext acquired = KnowledgeContext.empty();
        if (personalKnowledgeService != null) {
            try {
                documents = personalKnowledgeService.recall(
                        ownerId, query, Math.min(20, configuration.personalKnowledgeLimit()));
                log.info("process=personal_knowledge event=recall_completed candidates={}",
                        documents.candidates().size());
            } catch (RuntimeException exception) {
                log.warn("Personal knowledge recall failed; continuing without document context", exception);
            }
        }
        if (acquiredKnowledgeIndex != null) {
            try {
                acquired = acquiredKnowledgeIndex.recall(
                        ownerId, query, Math.min(20, configuration.personalKnowledgeLimit()));
                log.info("process=acquired_knowledge event=recall_completed candidates={}",
                        acquired.candidates().size());
            } catch (RuntimeException exception) {
                log.warn("Acquired knowledge recall failed; continuing without learned context", exception);
            }
        }
        return combine(documents, acquired);
    }

    private ChatKnowledgeSelection selection(
            Request request,
            KnowledgeContext memoryKnowledge,
            KnowledgeContext personalKnowledge,
            SearchDecision decision,
            boolean searchAttempted,
            KnowledgeContext searchKnowledge,
            List<KnowledgeCandidate> browserCandidates,
            SearchSelectionSignals searchSignals) {
        return selection(request, memoryKnowledge, personalKnowledge, decision, searchAttempted,
                searchKnowledge, browserCandidates, searchSignals, ResearchTrace.EMPTY);
    }

    private ChatKnowledgeSelection selection(
            Request request,
            KnowledgeContext memoryKnowledge,
            KnowledgeContext personalKnowledge,
            SearchDecision decision,
            boolean searchAttempted,
            KnowledgeContext searchKnowledge,
            List<KnowledgeCandidate> browserCandidates,
            SearchSelectionSignals searchSignals,
            ResearchTrace researchTrace) {
        KnowledgeSelection selected = knowledgeSelectionService.select(
                request.query(), memoryKnowledge, personalKnowledge, searchKnowledge, browserCandidates);
        log.info("process=knowledge_selection event=completed selected={} fallback={}",
                selected.selectedCandidates().size(), selected.rankingFallback());
        return new ChatKnowledgeSelection(
                selected,
                consolidate(selected),
                searchSignals,
                searchContextAwarenessService.observe(
                        request.query(), request.conversationContextAvailable(),
                        combine(memoryKnowledge, personalKnowledge), decision,
                        searchAttempted, searchKnowledge),
                researchTrace,
                decision == null ? new SearchDecision(false, request.query()) : decision);
    }

    private KnowledgeConsolidation consolidate(KnowledgeSelection selection) {
        try {
            return knowledgeConsolidationService.consolidate(selection.selectedCandidates());
        } catch (RuntimeException exception) {
            log.warn("Knowledge consolidation failed; continuing without metadata", exception);
            return KnowledgeConsolidation.EMPTY;
        }
    }

    private List<KnowledgeCandidate> readBrowserCandidates(String query) {
        if (browserContentService == null) {
            return List.of();
        }
        BrowserReadResult browserRead = browserContentService.readPartial(query);
        log.info("process=browser event=completed candidates={} failures={}",
                browserRead.candidates().size(), browserRead.failures().size());
        browserRead.failures().forEach(failure ->
                log.warn("process=browser event=url_failed url={} reason={}",
                        failure.url(), failure.reason()));
        return browserRead.candidates();
    }

    private List<KnowledgeCandidate> joinBrowser(CompletableFuture<List<KnowledgeCandidate>> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof BrowserContentException browserException) {
                throw browserException;
            }
            throw exception;
        }
    }

    private KnowledgeContext joinKnowledge(CompletableFuture<KnowledgeContext> future, String source) {
        try {
            KnowledgeContext result = future.join();
            return result == null ? KnowledgeContext.empty() : result;
        } catch (RuntimeException exception) {
            log.warn("Visual companion search branch failed; continuing with remaining results source={}", source);
            return KnowledgeContext.empty();
        }
    }

    private List<KnowledgeCandidate> readResearchSourceCandidates(KnowledgeContext searchKnowledge) {
        if (browserContentService == null || configuration.researchSourceReadLimit() < 1
                || searchKnowledge == null || searchKnowledge.candidates().isEmpty()) {
            return List.of();
        }
        List<String> urls = searchKnowledge.candidates().stream()
                .filter(candidate -> candidate.source() == KnowledgeSource.SEARCH)
                .map(KnowledgeCandidate::provenance)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (urls.isEmpty()) {
            return List.of();
        }
        try {
            BrowserReadResult result = browserContentService.readUrls(
                    urls, configuration.researchSourceReadLimit());
            log.info("process=research_source_read event=completed requested={} candidates={} failures={}",
                    Math.min(urls.size(), configuration.researchSourceReadLimit()),
                    result.candidates().size(), result.failures().size());
            result.failures().forEach(failure ->
                    log.warn("process=research_source_read event=url_failed url={} reason={}",
                            failure.url(), failure.reason()));
            return result.candidates();
        } catch (RuntimeException exception) {
            log.warn("Research source reading failed; continuing with search snippets", exception);
            return List.of();
        }
    }

    private List<KnowledgeCandidate> mergeBrowserCandidates(
            List<KnowledgeCandidate> first,
            List<KnowledgeCandidate> second) {
        List<KnowledgeCandidate> merged = new java.util.ArrayList<>();
        java.util.Set<String> seenProvenance = new java.util.LinkedHashSet<>();
        java.util.stream.Stream.concat(first.stream(), second.stream()).forEach(candidate -> {
            String key = candidate.provenance().isBlank() ? candidate.content() : candidate.provenance();
            if (seenProvenance.add(key)) {
                int index = merged.size();
                merged.add(new KnowledgeCandidate(
                        "browser-" + index, KnowledgeSource.BROWSER, candidate.content(), index,
                        candidate.provenance()));
            }
        });
        return List.copyOf(merged);
    }

    private KnowledgeContext ensureRecommendationEvidence(
            SearchQueryPlan plan, KnowledgeContext firstAttempt) {
        if (!"recommendation".equals(plan.intent()) || recommendationEvidenceIsEnough(plan, firstAttempt)) {
            return firstAttempt;
        }
        String retryQuery = evidenceRetryQuery(plan);
        log.info("process=search_quality event=retry intent={} sources={} query={}",
                plan.intent(), usableSourceCount(firstAttempt), retryQuery);
        try {
            KnowledgeContext retry = searchService.search(new SearchRequest(
                    UUID.randomUUID(), retryQuery,
                    Math.max(1, Math.min(100, configuration.searchResultLimit())),
                    Instant.now().plus(configuration.searchTimeout()),
                    new SearchOptions(plan.language(), "", plan.timeRange(), configuration.safeSearch()),
                    List.of()));
            return mergeSearchKnowledge(firstAttempt, retry, configuration.searchResultLimit());
        } catch (RuntimeException exception) {
            log.warn("Recommendation evidence retry failed; keeping first search results", exception);
            return firstAttempt;
        }
    }

    private boolean recommendationEvidenceIsEnough(SearchQueryPlan plan, KnowledgeContext knowledge) {
        if (usableSourceCount(knowledge) < Math.min(3, configuration.searchResultLimit())) return false;
        if (plan.evidenceNeeds().isEmpty()) return true;
        String content = knowledge == null ? "" : knowledge.content().toLowerCase(java.util.Locale.ROOT);
        long covered = plan.evidenceNeeds().stream().filter(need -> evidencePresent(need, content, plan.location())).count();
        return covered >= Math.min(2, plan.evidenceNeeds().size());
    }

    private long usableSourceCount(KnowledgeContext knowledge) {
        if (knowledge == null) return 0;
        return knowledge.candidates().stream()
                .map(KnowledgeCandidate::provenance)
                .filter(this::isWebUrl)
                .distinct()
                .count();
    }

    private boolean evidencePresent(String need, String content, String location) {
        return switch (need) {
            case "opening_hours" -> containsAny(content, "เวลาเปิด", "เปิดถึง", "ปิด", "opening hours", "open until");
            case "rating" -> containsAny(content, "รีวิว", "คะแนน", "ดาว", "rating", "review");
            case "location" -> containsAny(content, "ที่อยู่", "ถนน", "สถานี", "แผนที่", "address", "map")
                    || !location.isBlank() && content.contains(location.toLowerCase(java.util.Locale.ROOT));
            case "price" -> containsAny(content, "ราคา", "บาท", "฿", "price");
            case "availability" -> containsAny(content, "ว่าง", "พร้อม", "available", "availability");
            case "transit_access" -> containsAny(content, "เดิน", "รถ", "mrt", "bts", "สถานี", "transit");
            case "official_source" -> containsAny(content, "เว็บไซต์ทางการ", "official");
            case "freshness" -> containsAny(content, "ล่าสุด", "วันนี้", "updated", "latest");
            default -> false;
        };
    }

    private String evidenceRetryQuery(SearchQueryPlan plan) {
        boolean thai = "th".equals(plan.language()) || "all".equals(plan.language());
        String suffix = plan.evidenceNeeds().stream().map(need -> switch (need) {
            case "opening_hours" -> thai ? "เวลาเปิดปิด" : "opening hours";
            case "rating" -> thai ? "รีวิวคะแนน" : "reviews rating";
            case "location" -> thai ? "ที่อยู่แผนที่" : "address map";
            case "price" -> thai ? "ราคา" : "price";
            case "availability" -> thai ? "เปิดให้บริการ" : "availability";
            case "transit_access" -> thai ? "การเดินทางจากสถานี" : "transit access from station";
            case "official_source" -> thai ? "เว็บไซต์ทางการ" : "official website";
            case "freshness" -> thai ? "ข้อมูลล่าสุด" : "latest information";
            default -> "";
        }).filter(value -> !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining(" "));
        return (plan.primaryQuery() + " " + suffix).trim();
    }

    private KnowledgeContext mergeSearchKnowledge(
            KnowledgeContext first, KnowledgeContext second, int resultLimit) {
        java.util.LinkedHashMap<String, KnowledgeCandidate> distinct = new java.util.LinkedHashMap<>();
        java.util.stream.Stream.of(first, second)
                .filter(java.util.Objects::nonNull)
                .flatMap(context -> context.candidates().stream())
                .forEach(candidate -> distinct.putIfAbsent(
                        candidate.provenance().isBlank() ? candidate.content() : candidate.provenance(), candidate));
        List<KnowledgeCandidate> candidates = new java.util.ArrayList<>();
        for (KnowledgeCandidate candidate : distinct.values()) {
            int index = candidates.size();
            candidates.add(new KnowledgeCandidate(
                    "search-quality-" + index, KnowledgeSource.SEARCH, candidate.content(), index,
                    candidate.provenance()));
            if (candidates.size() == Math.max(1, resultLimit)) break;
        }
        return KnowledgeContext.fromCandidates(candidates);
    }

    private boolean containsAny(String content, String... values) {
        for (String value : values) if (content.contains(value)) return true;
        return false;
    }

    private boolean isWebUrl(String value) {
        return value != null && (value.startsWith("https://") || value.startsWith("http://"));
    }

    private String categoryFor(SearchDecision decision, String intent) {
        if (decision.reason() == SearchDecisionReason.IMAGE_REQUEST || "images".equals(intent)) {
            return SearchOptions.IMAGE_CATEGORY;
        }
        return "current_information".equals(intent) ? "news" : "";
    }

    private KnowledgeContext combine(KnowledgeContext first, KnowledgeContext second) {
        KnowledgeContext left = first == null ? KnowledgeContext.empty() : first;
        KnowledgeContext right = second == null ? KnowledgeContext.empty() : second;
        String content = java.util.stream.Stream.of(left.content(), right.content())
                .filter(value -> value != null && !value.isBlank())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        List<KnowledgeCandidate> candidates = java.util.stream.Stream
                .concat(left.candidates().stream(), right.candidates().stream()).toList();
        List<com.minikun.pcs.model.ImageSource> images = java.util.stream.Stream
                .concat(left.images().stream(), right.images().stream()).toList();
        return new KnowledgeContext(content, candidates, images);
    }

    private boolean isInternalTitleRequest(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase();
        return normalized.contains("generate a concise title summarizing the chat history")
                || normalized.contains("your entire response must consist solely of the json object")
                || normalized.contains("### task:\n") && normalized.contains("### chat history:");
    }

    private void recordStage(String stage, long startedNanos, String result) {
        if (performanceMetrics != null) {
            performanceMetrics.record(stage, startedNanos, result);
        }
    }

    private void fastPath(String reason) {
        if (performanceMetrics != null) {
            performanceMetrics.fastPath(reason);
        }
    }

    private void restoreMdc(Map<String, String> previous) {
        try {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        } catch (RuntimeException ignored) {
            // MDC must not affect search execution.
        }
    }

    record Request(
            String query,
            String requestId,
            ConversationId conversationId,
            String ownerId,
            boolean conversationContextAvailable,
            String classifierContext,
            TurnPlan turnPlan) {

        Request(String query, String requestId, ConversationId conversationId, String ownerId,
                boolean conversationContextAvailable, String classifierContext) {
            this(query, requestId, conversationId, ownerId, conversationContextAvailable, classifierContext, null);
        }
    }

    record Configuration(
            boolean searchEnabled,
            Duration searchTimeout,
            boolean safeSearch,
            boolean queryPlanningEnabled,
            int searchResultLimit,
            int memoryRetrievalLimit,
            int personalKnowledgeLimit,
            int researchSourceReadLimit,
            Duration autonomousResearchTimeout) {
        Configuration {
            if (researchSourceReadLimit < 0 || researchSourceReadLimit > 10) {
                throw new IllegalArgumentException("research source read limit must be between 0 and 10");
            }
            if (autonomousResearchTimeout == null || autonomousResearchTimeout.isZero()
                    || autonomousResearchTimeout.isNegative()) {
                throw new IllegalArgumentException("autonomous research timeout must be positive");
            }
        }
    }
}
