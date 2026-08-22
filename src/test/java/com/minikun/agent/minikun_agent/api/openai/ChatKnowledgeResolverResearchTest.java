package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minikun.browser.BrowserContent;
import com.minikun.browser.BrowserContentService;
import com.minikun.memory.MemoryRecallService;
import com.minikun.pcs.DefaultKnowledgeConsolidationService;
import com.minikun.pcs.DefaultKnowledgeSelectionService;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchSelectionSignalMapper;
import com.minikun.search.internal.DefaultSearchContextAwarenessService;
import com.minikun.search.internal.DefaultSearchQueryPlanningService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.research.AutonomousResearchResult;
import com.minikun.research.ResearchStopReason;
import com.minikun.research.ResearchTrace;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ChatKnowledgeResolverResearchTest {
    @Test
    void deepResearchDelegatesToAutonomousLoopAndPropagatesTrace() {
        KnowledgeCandidate evidence = searchCandidate(0, "https://official.example/autonomous");
        var autonomous = (com.minikun.research.AutonomousResearchService) request ->
                new AutonomousResearchResult(
                        new KnowledgeContext(evidence.content(), List.of(evidence)), List.of(),
                        new ResearchTrace(request.userQuery(), List.of("subquestion"),
                                List.of("query one", "gap query"), 2,
                                ResearchStopReason.SUFFICIENT, List.of(), true));
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        com.minikun.search.SearchService deterministicSearch = request -> {
            throw new AssertionError("deterministic search must not run after autonomous success");
        };
        ChatKnowledgeResolver resolver = new ChatKnowledgeResolver(
                memory, null, deterministicSearch,
                query -> new SearchDecision(true, query, SearchDecisionReason.FACT_LOOKUP),
                new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), null, autonomous, null,
                new ChatKnowledgeResolver.Configuration(
                        true, Duration.ofSeconds(5), true, true, 8, 5, 5, 3,
                        Duration.ofSeconds(30)));

        ChatKnowledgeSelection result = resolver.resolve(new ChatKnowledgeResolver.Request(
                "ช่วยค้นคว้าเรื่องระบบพลังงานแบบเจาะลึก", "request-auto", null,
                "default", false, ""));

        assertTrue(result.researchTrace().autonomous());
        assertEquals(2, result.researchTrace().iterations());
        assertEquals(ResearchStopReason.SUFFICIENT, result.researchTrace().stopReason());
        assertTrue(result.selection().selectedCandidates().stream()
                .anyMatch(candidate -> candidate.provenance().contains("autonomous")));
    }

    @Test
    void deepResearchReadsTopSearchSourcesAndKeepsTheirProvenance() {
        AtomicInteger renders = new AtomicInteger();
        BrowserContentService browser = new BrowserContentService(url -> {
            renders.incrementAndGet();
            return new BrowserContent(url,
                    "A complete primary source page with enough evidence for the research answer.",
                    "text/html", false);
        }, true, 5);
        KnowledgeContext searchKnowledge = new KnowledgeContext("search snippets", List.of(
                searchCandidate(0, "https://official.example/report"),
                searchCandidate(1, "https://independent.example/analysis")));
        ChatKnowledgeResolver resolver = resolver(browser, searchKnowledge, 2);

        ChatKnowledgeSelection result = resolver.resolve(new ChatKnowledgeResolver.Request(
                "ช่วยค้นคว้าเรื่องระบบพลังงานนี้แบบเจาะลึก", "request-1", null,
                "default", false, ""));

        List<KnowledgeCandidate> browserCandidates = result.selection().selectedCandidates().stream()
                .filter(candidate -> candidate.source() == KnowledgeSource.BROWSER)
                .toList();
        assertEquals(2, renders.get());
        assertEquals(2, browserCandidates.size());
        assertEquals("https://official.example/report", browserCandidates.getFirst().provenance());
        assertTrue(browserCandidates.getFirst().content().contains("Rendered page content"));
    }

    @Test
    void ordinaryLookupKeepsSnippetFastPathWithoutRenderingSearchResults() {
        AtomicInteger renders = new AtomicInteger();
        BrowserContentService browser = new BrowserContentService(url -> {
            renders.incrementAndGet();
            return new BrowserContent(url, "content", "text/html", false);
        }, true, 5);
        ChatKnowledgeResolver resolver = resolver(browser, new KnowledgeContext("snippet", List.of(
                searchCandidate(0, "https://example.org/fact"))), 3);

        ChatKnowledgeSelection result = resolver.resolve(new ChatKnowledgeResolver.Request(
                "ค้นหาข้อมูลรุ่นล่าสุด", "request-2", null, "default", false, ""));

        assertEquals(0, renders.get());
        assertFalse(result.selection().selectedCandidates().stream()
                .anyMatch(candidate -> candidate.source() == KnowledgeSource.BROWSER));
    }

    @SuppressWarnings("unchecked")
    private ChatKnowledgeResolver resolver(
            BrowserContentService browser,
            KnowledgeContext searchKnowledge,
            int sourceReadLimit) {
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        return new ChatKnowledgeResolver(
                memory,
                null,
                request -> searchKnowledge,
                query -> new SearchDecision(true, query, SearchDecisionReason.FACT_LOOKUP),
                new DefaultSearchQueryPlanningService(),
                new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(),
                new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(),
                browser,
                null,
                null,
                new ChatKnowledgeResolver.Configuration(
                        true, Duration.ofSeconds(5), true, true, 8, 5, 5, sourceReadLimit,
                        Duration.ofSeconds(30)));
    }

    private KnowledgeCandidate searchCandidate(int index, String url) {
        return new KnowledgeCandidate(
                "search-" + index, KnowledgeSource.SEARCH,
                "ระบบพลังงาน report (" + url + "): relevant supported evidence", index, url);
    }
}
