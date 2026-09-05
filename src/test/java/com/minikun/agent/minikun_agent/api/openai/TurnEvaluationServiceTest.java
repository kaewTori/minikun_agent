package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minikun.model.CooperationRouter;
import com.minikun.personality.feedback.ChatFeedbackService;
import com.minikun.personality.feedback.InMemoryChatFeedbackStore;
import com.minikun.personality.learning.AdaptivePreferenceLearningService;
import com.minikun.personality.learning.InMemoryAdaptationSignalStore;
import com.minikun.personality.learning.ResponsePreferenceDetector;
import com.minikun.personality.preference.InMemoryPreferenceStore;
import com.minikun.personalloop.PersonalLoopModels.ExplainabilityTrace;
import com.minikun.personalloop.PersonalLoopStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnEvaluationServiceTest {
    private final TurnEvaluationService service = new TurnEvaluationService(
            new TurnPlanner(new CooperationRouter(), (TurnAmbiguityResolver) null));

    @Test
    void builtInRoutingRegressionPassesWithoutCallingToolsOrAnswerModel() {
        TurnEvaluationService.Report report = service.baseline();

        assertEquals(6, report.total());
        assertEquals(6, report.passed());
        assertEquals(100.0, report.scorePercent());
    }

    @Test
    void reportsOnlyTheFieldsThatDiffer() {
        TurnEvaluationService.Report report = service.evaluate(List.of(new TurnEvaluationService.Case(
                "wrong expectation", "สวัสดี", "", "balanced", false, true,
                new TurnEvaluationService.Expected("action", "direct_stream", false, false))));

        assertEquals(0, report.passed());
        assertEquals(List.of("intent"), report.results().getFirst().mismatches());
    }

    @Test
    void rejectsAnEmptySuite() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(List.of()));
    }

    @Test
    void qualityReportCorrelatesFeedbackWithSanitizedTurnTrace() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        PersonalLoopStore traces = new PersonalLoopStore(null, new ObjectMapper());
        traces.save(new ExplainabilityTrace(UUID.randomUUID(), "owner", "conversation", "response-1",
                "route only", List.of(), List.of(),
                Map.of("turn_intent", "search", "turn_execution", "direct_stream"), clock.instant()));
        ChatFeedbackService feedback = new ChatFeedbackService(new InMemoryChatFeedbackStore(),
                new AdaptivePreferenceLearningService(new InMemoryAdaptationSignalStore(),
                        new InMemoryPreferenceStore(), new ResponsePreferenceDetector(), clock,
                        true, 3, .6, Duration.ofDays(365)), clock);
        feedback.submit("owner", "conversation", "response-1", "down", null, "ผลค้นหาผิด");
        TurnEvaluationService observed = new TurnEvaluationService(
                new TurnPlanner(new CooperationRouter(), (TurnAmbiguityResolver) null), feedback, traces);

        TurnEvaluationService.QualityReport report = observed.quality("owner", 100);

        assertEquals(1, report.feedbackTotal());
        assertEquals(1, report.traceMatched());
        assertEquals(1, report.byIntent().get("search").down());
        assertEquals("search:direct_stream:search_wrong", report.shadowSignals().getFirst().routeAndCategory());
    }
}
