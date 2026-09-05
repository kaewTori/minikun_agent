package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.personality.companion.CompanionMode;
import com.minikun.personality.companion.CompanionModeContext;
import com.minikun.personality.feedback.ChatFeedback;
import com.minikun.personality.feedback.ChatFeedbackCategory;
import com.minikun.personality.feedback.ChatFeedbackService;
import com.minikun.personalloop.PersonalLoopStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Runs bounded routing regressions without invoking the answer model or executing tools. */
@Service
public final class TurnEvaluationService {
    private static final int MAX_CASES = 200;
    private final TurnPlanner planner;
    private final ChatFeedbackService feedback;
    private final PersonalLoopStore traces;

    TurnEvaluationService(TurnPlanner planner) {
        this(planner, (ChatFeedbackService) null, (PersonalLoopStore) null);
    }

    @Autowired
    TurnEvaluationService(TurnPlanner planner, ObjectProvider<ChatFeedbackService> feedback,
            ObjectProvider<PersonalLoopStore> traces) {
        this(planner, feedback == null ? null : feedback.getIfAvailable(),
                traces == null ? null : traces.getIfAvailable());
    }

    TurnEvaluationService(TurnPlanner planner, ChatFeedbackService feedback, PersonalLoopStore traces) {
        this.planner = Objects.requireNonNull(planner);
        this.feedback = feedback;
        this.traces = traces;
    }

    public Report baseline() {
        return evaluate(List.of(
                new Case("casual", "สวัสดี", "", "balanced", false, true,
                        new Expected("companion", "direct_stream", false, false)),
                new Case("action", "ช่วยสร้างงานเตือนให้หน่อย", "", "balanced", false, true,
                        new Expected("action", "tool_loop", true, false)),
                new Case("research", "ช่วยทำ deep research เรื่องพลังงานแสงอาทิตย์", "", "work", false, true,
                        new Expected("research", "background", null, null)),
                new Case("creative", "ช่วยแต่งเรื่องสั้นเกี่ยวกับแมวบนดวงจันทร์", "", "balanced", false, true,
                        new Expected("creative", "direct_stream", false, false)),
                new Case("technical", "ช่วยออกแบบ Spring Boot API ให้หน่อย", "", "work", false, true,
                        new Expected("technical", "direct_stream", false, true)),
                new Case("vision", "ช่วยดูภาพนี้", "", "balanced", true, true,
                        new Expected("vision", "direct_stream", false, false))));
    }

    public Report evaluate(List<Case> cases) {
        if (cases == null || cases.isEmpty() || cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("eval suite must contain between 1 and 200 cases");
        }
        List<Result> results = cases.stream().map(this::evaluate).toList();
        long passed = results.stream().filter(Result::passed).count();
        return new Report(results.size(), Math.toIntExact(passed),
                Math.round(passed * 10_000.0 / results.size()) / 100.0, results);
    }

    /** Aggregates explicit feedback by the route that produced it; no prompt or response text is returned. */
    public QualityReport quality(String ownerId, int limit) {
        String owner = required(ownerId, "owner id", 255);
        if (limit < 1 || limit > MAX_CASES) throw new IllegalArgumentException("limit must be between 1 and 200");
        if (feedback == null || traces == null) return QualityReport.empty();
        List<ChatFeedback> values = feedback.list(owner, limit);
        Map<String, MutableScore> intents = new TreeMap<>();
        Map<String, MutableScore> executions = new TreeMap<>();
        Map<String, Long> categories = new TreeMap<>();
        Map<String, Long> shadow = new TreeMap<>();
        int matched = 0;
        int up = 0;
        for (ChatFeedback value : values) {
            categories.merge(value.category().name().toLowerCase(Locale.ROOT), 1L, Long::sum);
            if ("UP".equals(value.rating())) up++;
            var trace = traces.trace(owner, value.messageId());
            if (trace.isEmpty()) continue;
            matched++;
            String intent = decision(trace.get().decisions(), "turn_intent");
            String execution = decision(trace.get().decisions(), "turn_execution");
            score(intents, intent, value.rating());
            score(executions, execution, value.rating());
            if ("DOWN".equals(value.rating()) && routingFailure(value.category())) {
                shadow.merge(intent + ":" + execution + ":"
                        + value.category().name().toLowerCase(Locale.ROOT), 1L, Long::sum);
            }
        }
        int down = values.size() - up;
        return new QualityReport(values.size(), matched, up, down, percent(up, values.size()),
                freeze(intents), freeze(executions), Map.copyOf(categories),
                shadow.entrySet().stream().map(entry -> new ShadowSignal(entry.getKey(), entry.getValue())).toList());
    }

    private Result evaluate(Case testCase) {
        if (testCase == null) throw new IllegalArgumentException("eval case must not be null");
        String id = required(testCase.id(), "case id", 120);
        String message = required(testCase.message(), "message", 2_000);
        String context = bounded(testCase.context(), 4_000);
        TurnPlan plan = planner.plan(message, context, mode(testCase.mode()), testCase.hasVision(), null,
                testCase.toolsAvailable());
        Actual actual = new Actual(plan.intentTag(), plan.executionTag(), plan.needsTools(),
                plan.cooperation().needsExpert(), plan.ambiguous(), plan.confidence(), plan.reason());
        List<String> mismatches = mismatches(testCase.expected(), actual);
        return new Result(id, mismatches.isEmpty(), actual, mismatches);
    }

    private List<String> mismatches(Expected expected, Actual actual) {
        if (expected == null) return List.of();
        List<String> values = new ArrayList<>();
        compare(values, "intent", normalized(expected.intent()), actual.intent());
        compare(values, "execution", normalized(expected.execution()), actual.execution());
        if (expected.tools() != null && expected.tools() != actual.tools()) values.add("tools");
        if (expected.expertReview() != null && expected.expertReview() != actual.expertReview()) {
            values.add("expert_review");
        }
        return List.copyOf(values);
    }

    private void score(Map<String, MutableScore> scores, String key, String rating) {
        MutableScore score = scores.computeIfAbsent(key, ignored -> new MutableScore());
        score.total++;
        if ("UP".equals(rating)) score.up++;
    }

    private Map<String, Score> freeze(Map<String, MutableScore> values) {
        Map<String, Score> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key,
                new Score(value.total, value.up, value.total - value.up, percent(value.up, value.total))));
        return Map.copyOf(result);
    }

    private String decision(Map<String, Object> decisions, String key) {
        String value = String.valueOf(decisions.getOrDefault(key, "unknown")).trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "unknown" : bounded(value, 80);
    }

    private boolean routingFailure(ChatFeedbackCategory category) {
        return category == ChatFeedbackCategory.CONTEXT_WRONG || category == ChatFeedbackCategory.SEARCH_WRONG
                || category == ChatFeedbackCategory.TOOL_WRONG || category == ChatFeedbackCategory.SHOULD_HAVE_ACTED;
    }

    private double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : Math.round(numerator * 10_000.0 / denominator) / 100.0;
    }

    private void compare(List<String> mismatches, String field, String expected, String actual) {
        if (expected != null && !expected.equals(actual)) mismatches.add(field);
    }

    private CompanionModeContext mode(String value) {
        String normalized = normalized(value);
        CompanionMode selected = normalized == null ? CompanionMode.BALANCED
                : CompanionMode.valueOf(normalized.toUpperCase(Locale.ROOT));
        return new CompanionModeContext(selected, false, "Evaluation mode only.");
    }

    private String required(String value, String field, int maximum) {
        String result = bounded(value, maximum);
        if (result.isBlank() || "*".equals(result)) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }

    private String bounded(String value, int maximum) {
        String result = Objects.requireNonNullElse(value, "").trim();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }

    private String normalized(String value) {
        String result = Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
        return result.isBlank() ? null : result;
    }

    public record Case(String id, String message, String context, String mode,
            boolean hasVision, boolean toolsAvailable, Expected expected) { }
    public record Expected(String intent, String execution, Boolean tools, Boolean expertReview) { }
    public record Actual(String intent, String execution, boolean tools, boolean expertReview,
            boolean ambiguous, double confidence, String reason) { }
    public record Result(String id, boolean passed, Actual actual, List<String> mismatches) { }
    public record Report(int total, int passed, double scorePercent, List<Result> results) { }
    public record Score(int total, int up, int down, double approvalPercent) { }
    public record ShadowSignal(String routeAndCategory, long occurrences) { }
    public record QualityReport(int feedbackTotal, int traceMatched, int up, int down, double approvalPercent,
            Map<String, Score> byIntent, Map<String, Score> byExecution, Map<String, Long> categories,
            List<ShadowSignal> shadowSignals) {
        static QualityReport empty() {
            return new QualityReport(0, 0, 0, 0, 0.0, Map.of(), Map.of(), Map.of(), List.of());
        }
    }

    private static final class MutableScore { private int total; private int up; }
}
