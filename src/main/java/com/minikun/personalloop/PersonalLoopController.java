package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Management API for the closed-loop personal agent. Every mutation is explicit and owner-scoped. */
@RestController
@RequestMapping("/v1/personal")
public final class PersonalLoopController {
    private final WeeklyReviewService reviews;
    private final OutcomeLearningService outcomes;
    private final UniversalInboxService inbox;
    private final SafeAutomationService automations;
    private final IncidentCommanderService incidents;
    private final ExplainabilityService explanations;
    private final PersonalTimelineService timeline;
    private final PersonalExperimentService experiments;
    @Value("${minikun.personal-loop.management.token:${minikun.memory.management.token:}}")
    private String configuredToken;

    public PersonalLoopController(WeeklyReviewService reviews, OutcomeLearningService outcomes,
            UniversalInboxService inbox, SafeAutomationService automations, IncidentCommanderService incidents,
            ExplainabilityService explanations, PersonalTimelineService timeline,
            PersonalExperimentService experiments) {
        this.reviews = reviews; this.outcomes = outcomes; this.inbox = inbox; this.automations = automations;
        this.incidents = incidents; this.explanations = explanations; this.timeline = timeline;
        this.experiments = experiments;
    }

    @PostMapping("/weekly-reviews")
    public WeeklyReview createReview(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody(required = false) ReviewRequest request) {
        authorize(token); ReviewRequest value = request == null ? new ReviewRequest("weekly-review", null, null) : request;
        return reviews.generate(ownerId, blank(value.conversationId(), "weekly-review"), parse(value.periodStart()), parse(value.periodEnd()));
    }

    @GetMapping("/weekly-reviews")
    public List<WeeklyReview> reviews(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return reviews.list(ownerId, limit); }

    @GetMapping("/weekly-reviews/{id}")
    public Map<String,Object> review(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return reviews.details(ownerId, id); }

    @PostMapping("/weekly-reviews/proposals/{id}/decision")
    public ReviewProposal decideProposal(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody DecisionRequest request) { authorize(token); return reviews.decide(ownerId, id, request.approve()); }

    @GetMapping("/outcomes")
    public List<Outcome> outcomes(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return outcomes.list(ownerId, limit); }

    @GetMapping("/outcomes/insights")
    public Map<String,Object> outcomeInsights(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return outcomes.insights(ownerId); }

    @PostMapping("/outcomes/{id}/transition")
    public Outcome transitionOutcome(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody OutcomeRequest request) {
        authorize(token); String action = blank(request.action(), "").toUpperCase();
        return switch (action) {
            case "ACCEPT" -> outcomes.accept(ownerId, id); case "REJECT" -> outcomes.reject(ownerId, id, request.note());
            case "START" -> outcomes.start(ownerId, id); case "COMPLETE" -> outcomes.complete(ownerId, id, request.note());
            case "ABANDON" -> outcomes.abandon(ownerId, id, request.note());
            case "EVALUATE" -> outcomes.evaluate(ownerId, id, Objects.requireNonNull(request.score(), "score is required"), request.note());
            default -> throw new IllegalArgumentException("action must be ACCEPT, REJECT, START, COMPLETE, ABANDON, or EVALUATE");
        };
    }

    @PostMapping("/inbox")
    public InboxItem capture(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody InboxRequest request) { authorize(token); return inbox.capture(ownerId,
                    blank(request.conversationId(), "inbox"), blank(request.inputType(), "TEXT"), request.content(),
                    request.sourceRef(), request.classification(), request.metadata()); }

    @GetMapping("/inbox")
    public List<InboxItem> inbox(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return inbox.list(ownerId, limit); }

    @PatchMapping("/inbox/{id}")
    public InboxItem reviseInbox(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody InboxRevision request) { authorize(token); return inbox.revise(ownerId, id, request.classification(), request.preview()); }

    @PostMapping("/inbox/{id}/commit")
    public InboxItem commitInbox(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return inbox.commit(ownerId, id); }

    @PostMapping("/inbox/{id}/dismiss")
    public InboxItem dismissInbox(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return inbox.dismiss(ownerId, id); }

    @PostMapping("/automations")
    public AutomationRecipe createAutomation(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody AutomationRequest request) { authorize(token); return automations.create(ownerId, request.name(),
                    request.triggerType(), request.trigger(), request.actionType(), request.action(), request.enabled()); }

    @GetMapping("/automations")
    public List<AutomationRecipe> automations(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return automations.recipes(ownerId); }

    @PatchMapping("/automations/{id}")
    public AutomationRecipe toggleAutomation(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody EnabledRequest request) { authorize(token); return automations.setEnabled(ownerId, id, request.enabled()); }

    @PostMapping("/automations/evaluate")
    public List<AutomationRun> evaluateAutomations(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return automations.evaluate(ownerId); }

    @GetMapping("/automations/runs")
    public List<AutomationRun> automationRuns(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return automations.runs(ownerId, limit); }

    @PostMapping("/automations/runs/{id}/decision")
    public AutomationRun decideAutomation(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody DecisionRequest request) { authorize(token); return automations.decide(ownerId, id, request.approve()); }

    @PostMapping("/incidents/inspect")
    public IncidentCommanderService.Inspection inspect(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return incidents.inspect(ownerId); }

    @GetMapping("/incidents")
    public List<Incident> incidents(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return incidents.list(ownerId, limit); }

    @PostMapping("/incidents/{id}/resolve")
    public Incident resolveIncident(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody(required = false) NoteRequest request) { authorize(token); return incidents.resolve(ownerId, id, request == null ? "manual resolution" : request.note()); }

    @GetMapping("/explanations")
    public Object explanations(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(name = "conversation_id", required = false) String conversationId,
            @RequestParam(name = "response_id", required = false) String responseId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return responseId == null || responseId.isBlank() ? explanations.list(ownerId, conversationId, limit) : explanations.find(ownerId, responseId); }

    @GetMapping("/timeline")
    public List<TimelineEvent> timeline(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(required = false) String since, @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) { authorize(token); return timeline.list(ownerId, parse(since), eventType, limit); }

    @PostMapping("/experiments")
    public PersonalExperimentService.ExperimentDetails createExperiment(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody ExperimentCreateRequest request) {
        authorize(token);
        return experiments.create(ownerId, blank(request.conversationId(), "cockpit"), request.title(),
                request.hypothesis(), request.protocol(), request.metricName(), request.metricUnit(),
                MetricDirection.valueOf(blank(request.direction(), "INCREASE").toUpperCase()),
                request.baselineValue(), request.targetValue(), request.durationDays());
    }

    @GetMapping("/experiments")
    public List<PersonalExperimentService.ExperimentOverview> experiments(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) {
        authorize(token);
        ExperimentStatus parsed = status == null || status.isBlank() ? null : ExperimentStatus.valueOf(status.toUpperCase());
        return experiments.list(ownerId, parsed, limit);
    }

    @GetMapping("/experiments/{id}")
    public PersonalExperimentService.ExperimentDetails experiment(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) {
        authorize(token); return experiments.details(ownerId, id);
    }

    @PostMapping("/experiments/{id}/transition")
    public PersonalExperimentService.ExperimentDetails transitionExperiment(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody ExperimentTransitionRequest request) {
        authorize(token);
        return switch (blank(request.action(), "").toUpperCase()) {
            case "START" -> experiments.start(ownerId, id);
            case "PAUSE" -> experiments.pause(ownerId, id);
            case "RESUME" -> experiments.resume(ownerId, id);
            case "COMPLETE" -> experiments.complete(ownerId, id, request.note());
            case "ABANDON" -> experiments.abandon(ownerId, id, request.note());
            default -> throw new IllegalArgumentException("action must be START, PAUSE, RESUME, COMPLETE, or ABANDON");
        };
    }

    @PostMapping("/experiments/{id}/check-ins")
    public PersonalExperimentService.ExperimentDetails checkInExperiment(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody ExperimentCheckInRequest request) {
        authorize(token); return experiments.checkIn(ownerId, id, request.value(), request.note(), parse(request.observedAt()));
    }

    @PostMapping("/experiments/{id}/evaluate")
    public PersonalExperimentService.ExperimentDetails evaluateExperiment(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token,
            @RequestBody ExperimentEvaluationRequest request) {
        authorize(token); return experiments.evaluate(ownerId, id, request.score(), request.note());
    }

    private void authorize(String token) { if (configuredToken != null && !configuredToken.isBlank() && !Objects.equals(configuredToken, token)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "personal loop token is invalid"); }
    private Instant parse(String value) { return value == null || value.isBlank() ? null : Instant.parse(value); }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }

    public record ReviewRequest(String conversationId, String periodStart, String periodEnd) { }
    public record DecisionRequest(boolean approve) { }
    public record OutcomeRequest(String action, String note, Integer score) { }
    public record InboxRequest(String conversationId, String inputType, String content, String sourceRef, String classification, Map<String,Object> metadata) { }
    public record InboxRevision(String classification, Map<String,Object> preview) { }
    public record AutomationRequest(String name, String triggerType, Map<String,Object> trigger, String actionType, Map<String,Object> action, boolean enabled) { }
    public record EnabledRequest(boolean enabled) { }
    public record NoteRequest(String note) { }
    public record ExperimentCreateRequest(String conversationId, String title, String hypothesis, String protocol,
            String metricName, String metricUnit, String direction, double baselineValue, double targetValue,
            int durationDays) { }
    public record ExperimentTransitionRequest(String action, String note) { }
    public record ExperimentCheckInRequest(double value, String note, String observedAt) { }
    public record ExperimentEvaluationRequest(int score, String note) { }
}
