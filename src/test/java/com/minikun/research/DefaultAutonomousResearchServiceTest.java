package com.minikun.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.browser.BrowserContent;
import com.minikun.browser.BrowserContentService;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultAutonomousResearchServiceTest {
    @Test
    void iteratesFromPlanThroughCoverageGapToFollowUpSearch() {
        List<String> searched = new ArrayList<>();
        SearchService search = request -> {
            searched.add(request.query());
            String url = "https://evidence.example/" + searched.size();
            KnowledgeCandidate candidate = new KnowledgeCandidate(
                    "search-" + searched.size(), KnowledgeSource.SEARCH,
                    "Evidence for " + request.query() + " (" + url + "): supported detail", 0, url);
            return new KnowledgeContext(candidate.content(), List.of(candidate));
        };
        AtomicInteger evaluations = new AtomicInteger();
        ResearchReasoningProvider reasoning = new ResearchReasoningProvider() {
            @Override
            public ResearchPlan plan(String userQuery, String context, int maximumQuestions) {
                return new ResearchPlan(userQuery, List.of(
                        new ResearchQuestion("q1", "battery safety evidence", "establish baseline")));
            }

            @Override
            public ResearchEvaluation evaluate(
                    ResearchPlan plan, List<String> queries, String evidence, int maximumFollowUps) {
                if (evaluations.getAndIncrement() == 0) {
                    return new ResearchEvaluation(
                            false, List.of("independent incident data is missing"),
                            List.of("battery independent incident statistics"));
                }
                return new ResearchEvaluation(true, List.of(), List.of());
            }
        };
        BrowserContentService browser = new BrowserContentService(url -> new BrowserContent(
                url, "Rendered primary evidence with enough content for evaluation.", "text/html", false), true, 5);
        DefaultAutonomousResearchService service = new DefaultAutonomousResearchService(
                search, browser, reasoning, true, 3, 8, 3, 12_000);

        AutonomousResearchResult result = service.research(request("battery safety evidence", 3));

        assertEquals(List.of("battery safety evidence", "battery independent incident statistics"), searched);
        assertEquals(2, result.trace().iterations());
        assertEquals(ResearchStopReason.SUFFICIENT, result.trace().stopReason());
        assertEquals(2, result.searchKnowledge().candidates().size());
        assertEquals(2, result.browserCandidates().size());
        assertTrue(result.trace().unresolvedGaps().isEmpty());
    }

    @Test
    void enforcesIterationBoundWhenEvaluatorKeepsRequestingNewEvidence() {
        AtomicInteger searches = new AtomicInteger();
        SearchService search = request -> {
            int index = searches.incrementAndGet();
            String url = "https://example.org/" + index;
            KnowledgeCandidate candidate = new KnowledgeCandidate(
                    "s" + index, KnowledgeSource.SEARCH, "Evidence " + index, 0, url);
            return new KnowledgeContext(candidate.content(), List.of(candidate));
        };
        AtomicInteger followUp = new AtomicInteger();
        ResearchReasoningProvider reasoning = new ResearchReasoningProvider() {
            @Override
            public ResearchPlan plan(String query, String context, int max) {
                return ResearchPlan.fallback(query);
            }

            @Override
            public ResearchEvaluation evaluate(ResearchPlan plan, List<String> queries, String evidence, int max) {
                return new ResearchEvaluation(false, List.of("still incomplete"),
                        List.of("new gap query " + followUp.incrementAndGet()));
            }
        };
        DefaultAutonomousResearchService service = new DefaultAutonomousResearchService(
                search, null, reasoning, true, 2, 8, 2, 4_000);

        AutonomousResearchResult result = service.research(request("initial query", 0));

        assertEquals(2, result.trace().iterations());
        assertEquals(ResearchStopReason.MAX_ITERATIONS, result.trace().stopReason());
        assertEquals(List.of("still incomplete"), result.trace().unresolvedGaps());
    }

    @Test
    void plannerFailureFallsBackToSeedAndEvaluatorFailureReturnsGatheredEvidence() {
        SearchService search = request -> new KnowledgeContext("fallback evidence");
        ResearchReasoningProvider reasoning = new ResearchReasoningProvider() {
            @Override
            public ResearchPlan plan(String query, String context, int max) {
                throw new IllegalStateException("planner unavailable");
            }

            @Override
            public ResearchEvaluation evaluate(ResearchPlan plan, List<String> queries, String evidence, int max) {
                throw new IllegalStateException("evaluator unavailable");
            }
        };
        DefaultAutonomousResearchService service = new DefaultAutonomousResearchService(
                search, null, reasoning, true, 3, 8, 3, 4_000);

        AutonomousResearchResult result = service.research(request("fallback query", 0));

        assertEquals(1, result.trace().iterations());
        assertEquals(ResearchStopReason.EVALUATION_FAILED, result.trace().stopReason());
        assertEquals(1, result.searchKnowledge().candidates().size());
        assertTrue(result.trace().autonomous());
    }

    @Test
    void readsPreferredDomainBeforeEarlierUntrustedSearchResults() {
        SearchService search = request -> {
            KnowledgeCandidate untrusted = new KnowledgeCandidate("untrusted", KnowledgeSource.SEARCH,
                    "Third-party result", 0, "https://example.com/spring-ai");
            KnowledgeCandidate official = new KnowledgeCandidate("official", KnowledgeSource.SEARCH,
                    "Official result", 1, "https://docs.spring.io/spring-ai/reference/api/index.html");
            return KnowledgeContext.fromCandidates(List.of(untrusted, official));
        };
        List<String> rendered = new ArrayList<>();
        BrowserContentService browser = new BrowserContentService(url -> {
            rendered.add(url);
            return new BrowserContent(url, "Rendered official evidence with enough detail.", "text/html", false);
        }, true, 1);
        ResearchReasoningProvider reasoning = new ResearchReasoningProvider() {
            @Override
            public ResearchPlan plan(String query, String context, int max) {
                return ResearchPlan.fallback(query);
            }

            @Override
            public ResearchEvaluation evaluate(ResearchPlan plan, List<String> queries, String evidence, int max) {
                return new ResearchEvaluation(true, List.of(), List.of());
            }
        };
        DefaultAutonomousResearchService service = new DefaultAutonomousResearchService(
                search, browser, reasoning, true, 1, 3, 1, 4_000);
        AutonomousResearchRequest request = new AutonomousResearchRequest(
                "Spring AI", "", "Spring AI", List.of(), "en", "", true, 8, 1,
                List.of("docs.spring.io"), Instant.now().plusSeconds(30));

        service.research(request);

        assertEquals(List.of("https://docs.spring.io/spring-ai/reference/api/index.html"), rendered);
    }

    private AutonomousResearchRequest request(String query, int sourceReadLimit) {
        return new AutonomousResearchRequest(
                query, "", query, List.of(), "en", "", true, 8,
                sourceReadLimit, List.of(), Instant.now().plusSeconds(30));
    }
}
