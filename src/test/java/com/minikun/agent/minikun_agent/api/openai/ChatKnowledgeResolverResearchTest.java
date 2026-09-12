package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.browser.BrowserContent;
import com.minikun.browser.BrowserContentService;
import com.minikun.memory.MemoryRecallService;
import com.minikun.knowledge.acquisition.AcquiredKnowledgeIndex;
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
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchPlanHints;
import com.minikun.search.model.SearchRequest;
import com.minikun.research.AutonomousResearchResult;
import com.minikun.research.ResearchStopReason;
import com.minikun.research.ResearchTrace;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import com.minikun.vision.VisionInput;

class ChatKnowledgeResolverResearchTest {
    @Test
    void casualGreetingDoesNotSpendASearchDecisionOrRetrievalCall() {
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        com.minikun.search.SearchDecisionService decisions = mock(com.minikun.search.SearchDecisionService.class);
        var resolver = new ChatKnowledgeResolver(memory, null, request -> { throw new AssertionError("unexpected search"); },
                decisions, new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), null, null, null,
                new ChatKnowledgeResolver.Configuration(true, Duration.ofSeconds(10), true, true, 8, 5, 5, 3,
                        Duration.ofSeconds(30)));
        var plan = new TurnPlanner(new com.minikun.model.CooperationRouter(), (TurnAmbiguityResolver) null)
                .plan("สวัสดี", "", null, false, null, true);
        var result = resolver.resolve(new ChatKnowledgeResolver.Request("สวัสดี", "greeting", null, "owner", false, "", plan));
        assertFalse(result.searchContext().searchAttempted());
        org.mockito.Mockito.verifyNoInteractions(memory, decisions);
    }

    @Test
    void routesAnAttachedImageRequestThroughVisionToTextSearch() {
        java.util.concurrent.atomic.AtomicReference<SearchRequest> imageSearch = new java.util.concurrent.atomic.AtomicReference<>();
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        ChatKnowledgeResolver resolver = new ChatKnowledgeResolver(
                memory, null, null,
                request -> {
                    if (SearchOptions.IMAGE_CATEGORY.equals(request.options().category())) {
                        imageSearch.set(request);
                    }
                    return KnowledgeContext.empty();
                },
                query -> new SearchDecision(false, query),
                new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), null, null, null,
                new ChatKnowledgeResolver.Configuration(
                        true, Duration.ofSeconds(5), true, true, 8, 5, 5, 3,
                        Duration.ofSeconds(30)));
        Media media = new Media(MimeTypeUtils.parseMimeType("image/png"), new ByteArrayResource(new byte[] {1}));
        VisionInput visionInput = new VisionInput(
                List.of(media), List.of(new VisionInput.Image("image/png", new byte[] {1, 2, 3})));

        ChatKnowledgeSelection result = resolver.resolve(new ChatKnowledgeResolver.Request(
                "ช่วยค้นหารูปที่มีสไตล์คล้าย reference นี้", "image-search", null,
                "default", false, "", null, 0, visionInput,
                "cinematic anime girl neon city",
                List.of("anime girl neon city", "cinematic neon city artwork")));

        assertEquals("cinematic anime girl neon city", imageSearch.get().query());
        assertEquals(List.of("anime girl neon city", "cinematic neon city artwork"),
                imageSearch.get().alternateQueries());
        assertEquals(SearchOptions.IMAGE_CATEGORY, imageSearch.get().options().category());
        assertTrue(result.searchContext().searchAttempted());
    }

    @Test
    void continuesWhenTheSearchWorkerStopsResponding() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        com.minikun.search.SearchService search = request -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return KnowledgeContext.empty();
        };
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        ChatKnowledgeResolver resolver = new ChatKnowledgeResolver(
                memory, null, search,
                query -> new SearchDecision(true, query, SearchDecisionReason.FACT_LOOKUP),
                new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), null, null, null,
                new ChatKnowledgeResolver.Configuration(
                        true, Duration.ofMillis(100), true, true, 8, 5, 5, 3,
                        Duration.ofSeconds(30)));

        resolver.resolve(new ChatKnowledgeResolver.Request(
                "ค้นข้อมูลระบบพลังงาน", "request-search-timeout", null, "default", false, ""));

        release.countDown();
        assertTrue(started.await(2, TimeUnit.SECONDS));
    }

    @Test
    void recallsPublishedAcquiredKnowledgeThroughThePersonalKnowledgeLane() {
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        AcquiredKnowledgeIndex acquired = mock(AcquiredKnowledgeIndex.class);
        KnowledgeCandidate learned = new KnowledgeCandidate("acquired-1", KnowledgeSource.PERSONAL,
                "[Verified acquired knowledge] Spring AI supports portable model APIs.", 0,
                "https://docs.spring.io/spring-ai/reference/");
        when(acquired.recall("default", "Spring AI model APIs", 5))
                .thenReturn(KnowledgeContext.fromCandidates(List.of(learned)));
        ChatKnowledgeResolver resolver = new ChatKnowledgeResolver(
                memory, null, acquired, request -> KnowledgeContext.empty(),
                query -> new SearchDecision(false, query), new DefaultSearchQueryPlanningService(),
                new DefaultSearchContextAwarenessService(), new DefaultKnowledgeSelectionService(),
                new DefaultKnowledgeConsolidationService(), new SearchSelectionSignalMapper(), null,
                null, null, new ChatKnowledgeResolver.Configuration(
                        false, Duration.ofSeconds(5), true, true, 8, 5, 5, 3,
                        Duration.ofSeconds(30)));

        ChatKnowledgeSelection result = resolver.resolve(new ChatKnowledgeResolver.Request(
                "Spring AI model APIs", "request-learned", null, "default", false, ""));

        assertTrue(result.selection().selectedCandidates().stream()
                .anyMatch(candidate -> candidate.candidateId().equals("acquired-1")));
    }

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

    @Test
    void retriesLocalDiscoveryOnceWhenEvidenceIsThin() {
        AtomicInteger searches = new AtomicInteger();
        com.minikun.search.SearchService search = request -> {
            int attempt = searches.incrementAndGet();
            if (attempt == 1) {
                return KnowledgeContext.fromCandidates(List.of(localCandidate(0, "https://one.example", "ร้านข้าว")));
            }
            return KnowledgeContext.fromCandidates(List.of(
                    localCandidate(0, "https://two.example", "ร้านข้าว รีวิว 4.8 ดาว เปิดถึง 20:00"),
                    localCandidate(1, "https://three.example", "ร้านข้าว ราคา 80 บาท ใกล้สถานี"),
                    localCandidate(2, "https://four.example", "ร้านอาหารแถวไฟฉาย ที่อยู่และแผนที่")));
        };
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        SearchPlanHints hints = new SearchPlanHints(
                "local_discovery", 0.96, "ร้านข้าว MRT ไฟฉาย", List.of(),
                List.of("opening_hours", "rating", "location", "price"), "MRT ไฟฉาย");
        ChatKnowledgeResolver resolver = new ChatKnowledgeResolver(
                memory, null, search,
                query -> new SearchDecision(true, query, SearchDecisionReason.EXTERNAL_RESOURCE, hints),
                new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), null, null, null,
                new ChatKnowledgeResolver.Configuration(
                        true, Duration.ofSeconds(5), true, true, 8, 5, 5, 3,
                        Duration.ofSeconds(30)));

        resolver.resolve(new ChatKnowledgeResolver.Request(
                "ช่วยแนะนำร้านข้าวแถว MRT ไฟฉาย", "request-local", null, "default", false, ""));

        assertEquals(2, searches.get());
    }

    @Test
    void doesNotRetryLocalDiscoveryWhenEvidenceIsAlreadyUseful() {
        AtomicInteger searches = new AtomicInteger();
        com.minikun.search.SearchService search = request -> {
            searches.incrementAndGet();
            return KnowledgeContext.fromCandidates(List.of(
                    localCandidate(0, "https://one.example", "ร้านข้าว รีวิว 4.8 ดาว เปิดถึง 20:00"),
                    localCandidate(1, "https://two.example", "ร้านข้าว ราคา 80 บาท ใกล้สถานี"),
                    localCandidate(2, "https://three.example", "ร้านอาหารแถวไฟฉาย ที่อยู่และแผนที่")));
        };
        ObjectProvider<MemoryRecallService> memory = mock(ObjectProvider.class);
        ChatKnowledgeResolver resolver = new ChatKnowledgeResolver(
                memory, null, search,
                query -> new SearchDecision(true, query, SearchDecisionReason.EXTERNAL_RESOURCE),
                new DefaultSearchQueryPlanningService(), new DefaultSearchContextAwarenessService(),
                new DefaultKnowledgeSelectionService(), new DefaultKnowledgeConsolidationService(),
                new SearchSelectionSignalMapper(), null, null, null,
                new ChatKnowledgeResolver.Configuration(
                        true, Duration.ofSeconds(5), true, true, 8, 5, 5, 3,
                        Duration.ofSeconds(30)));

        resolver.resolve(new ChatKnowledgeResolver.Request(
                "ช่วยแนะนำร้านข้าวแถว MRT ไฟฉาย", "request-local-good", null, "default", false, ""));

        assertEquals(1, searches.get());
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

    private KnowledgeCandidate localCandidate(int index, String url, String content) {
        return new KnowledgeCandidate("local-" + index, KnowledgeSource.SEARCH,
                content + " (" + url + ")", index, url);
    }
}
