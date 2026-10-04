package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.ChatExplainabilitySink;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.goal.GoalService;
import com.minikun.goal.GoalStatus;
import com.minikun.goal.GoalStore;
import com.minikun.goal.NextActionService;
import com.minikun.goal.PersonalGoal;
import com.minikun.guardian.GuardianFinding;
import com.minikun.guardian.GuardianReport;
import com.minikun.guardian.GuardianSeverity;
import com.minikun.guardian.HomelabGuardianService;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.systemhealth.SystemHealthReport;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskService;
import com.minikun.task.TaskStatus;
import com.minikun.task.TaskStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

class PersonalLoopServicesTest {
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
    private Clock clock;
    private PersonalLoopStore store;
    private PersonalTimelineRecorder timeline;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new PersonalLoopStore(null, new ObjectMapper());
        timeline = new PersonalTimelineRecorder(store, clock);
    }

    @Test
    void personalExperimentSupportsClassBasedProxyingRequiredByTransactionalMethods() {
        OutcomeLearningService outcomes = new OutcomeLearningService(store, timeline, clock);
        PersonalExperimentService service = new PersonalExperimentService(store, outcomes, timeline, clock);
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);

        assertDoesNotThrow(() -> {
            proxyFactory.getProxy();
        });
    }

    @Test
    void learnsOnlyFromExplicitOutcomeTransitions() {
        OutcomeLearningService service = new OutcomeLearningService(store, timeline, clock);
        Outcome outcome = service.propose("owner-a", "home", "focus", "ทำ 15 นาที", "WEEKLY_REVIEW", "r1");

        service.accept("owner-a", outcome.id());
        service.start("owner-a", outcome.id());
        service.complete("owner-a", outcome.id(), "ทำเสร็จ");
        Outcome evaluated = service.evaluate("owner-a", outcome.id(), 5, "ช่วยได้มาก");

        assertEquals(OutcomeStatus.EVALUATED, evaluated.status());
        assertEquals(5, evaluated.score());
        assertEquals(1, service.insights("owner-a").get("total"));
        assertTrue(service.list("owner-b", 10).isEmpty());
    }

    @Test
    void universalInboxPreviewsBeforeCommitting() {
        UniversalInboxService service = new UniversalInboxService(store, timeline,
                null, null, null, null, null, clock);

        InboxItem item = service.capture("owner-a", "home", "TEXT", "บันทึกไอเดียเรื่องสวน", "", null, Map.of());
        assertEquals(InboxClassification.NOTE, item.classification());
        assertEquals(InboxStatus.PREVIEW, item.status());

        InboxItem committed = service.commit("owner-a", item.id());
        assertEquals(InboxStatus.COMMITTED, committed.status());
        assertEquals("NOTE", committed.targetType());
        assertThrows(IllegalStateException.class, () -> service.dismiss("owner-a", item.id()));
    }

    @Test
    void weeklyReviewCreatesConfirmableNextActionAndStartsTaskOnlyAfterApproval() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        TaskService tasks = new TaskService(taskStore, clock);
        InMemoryGoalStore goalStore = new InMemoryGoalStore();
        GoalService goals = new GoalService(goalStore, clock, taskStore);
        PersonalGoal goal = goals.create("owner-a", "home", "ออกกำลังกาย", "", "ครั้ง", 0, 3, 0, null, "Asia/Bangkok");
        PersonalTask task = tasks.create("owner-a", "home", "TASK", "เดิน", "", "", "Asia/Bangkok",
                "เดิน 15 นาที", "", "", "", goal.id().toString());
        OutcomeLearningService outcomes = new OutcomeLearningService(store, timeline, clock);
        WeeklyReviewService service = new WeeklyReviewService(store, outcomes, timeline, tasks, goals,
                new NextActionService(goals, tasks, clock), null, clock);

        WeeklyReview review = service.generate("owner-a", "home", NOW.minusSeconds(604800), NOW);
        @SuppressWarnings("unchecked")
        List<ReviewProposal> proposals = (List<ReviewProposal>) service.details("owner-a", review.id()).get("proposals");
        assertEquals(1, proposals.size());
        assertEquals(TaskStatus.OPEN, tasks.find("owner-a", task.id()).status());

        ReviewProposal applied = service.decide("owner-a", proposals.getFirst().id(), true);
        assertEquals(ProposalStatus.APPLIED, applied.status());
        assertEquals(TaskStatus.IN_PROGRESS, tasks.find("owner-a", task.id()).status());
        assertEquals(ReviewStatus.COMPLETED, service.list("owner-a", 10).getFirst().status());
    }

    @Test
    void automationComputesRiskServerSideAndRequiresConfirmationForTaskCreation() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        TaskService tasks = new TaskService(taskStore, clock);
        NotificationDispatcher notifications = mock(NotificationDispatcher.class);
        SafeAutomationService service = new SafeAutomationService(store, timeline, null, tasks, null,
                notifications, clock, ZoneId.of("Asia/Bangkok"));
        AutomationRecipe notify = service.create("owner-a", "heartbeat", "INTERVAL",
                Map.of("interval_minutes", 60), "NOTIFY", Map.of("message", "พร้อมใช้งาน"), true);

        AutomationRun notificationRun = service.evaluate("owner-a").getFirst();
        assertEquals(RiskLevel.LOW, notify.riskLevel());
        assertEquals(AutomationRunStatus.COMPLETED, notificationRun.status());
        verify(notifications).publish(org.mockito.ArgumentMatchers.any());

        AutomationRecipe createTask = service.create("owner-a", "capture", "INTERVAL",
                Map.of("interval_minutes", 60), "CREATE_TASK", Map.of("title", "ตรวจ backup"), true);
        AutomationRun waiting = service.evaluate("owner-a").stream()
                .filter(run -> run.recipeId().equals(createTask.id())).findFirst().orElseThrow();
        assertEquals(RiskLevel.MEDIUM, createTask.riskLevel());
        assertEquals(AutomationRunStatus.WAITING_CONFIRMATION, waiting.status());
        assertTrue(tasks.list("owner-a", null).isEmpty());

        AutomationRun completed = service.decide("owner-a", waiting.id(), true);
        assertEquals(AutomationRunStatus.COMPLETED, completed.status());
        assertEquals(1, tasks.list("owner-a", null).size());
    }

    @Test
    void incidentCommanderCorrelatesAndResolvesGuardianEvidence() {
        HomelabGuardianService guardian = mock(HomelabGuardianService.class);
        SystemHealthReport system = new SystemHealthReport("WARNING", false, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of());
        GuardianFinding finding = new GuardianFinding("DEPENDENCY_DOWN", GuardianSeverity.CRITICAL, "postgres",
                "postgres is unreachable", "status=DOWN", "inspect postgres logs");
        when(guardian.inspect()).thenReturn(new GuardianReport(NOW, "CRITICAL", false, system,
                List.of(finding), List.of(), List.of()));
        IncidentCommanderService service = new IncidentCommanderService(guardian, store, timeline, clock);

        Incident opened = service.inspect("owner-a").incident();
        assertEquals(IncidentStatus.OPEN, opened.status());
        assertTrue(opened.probableCause().contains("postgres"));

        when(guardian.inspect()).thenReturn(new GuardianReport(NOW, "UP", true,
                new SystemHealthReport("UP", true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()),
                List.of(), List.of(), List.of()));
        assertEquals(1, service.inspect("owner-a").resolved().size());
        assertEquals(IncidentStatus.RESOLVED, service.list("owner-a", 10).getFirst().status());
    }

    @Test
    void explainabilityStoresProvenanceWithoutResponseContent() {
        ExplainabilityService service = new ExplainabilityService(store, timeline, clock);
        String browserId = "web-5bd3be5d-9548-460c-a4fd-2ab4d9d67ad3";
        service.record(new ChatExplainabilitySink.Event("owner-a", ConversationId.fromTransport(browserId).value(), "chatcmpl-1",
                List.of("PERSONAL:notes/plan.md"), List.of("knowledge.personal"),
                Map.of("search_attempted", false, "confirmation_required", false), NOW));

        ExplainabilityTrace trace = service.find("owner-a", "chatcmpl-1");
        assertEquals(List.of("PERSONAL:notes/plan.md"), trace.sources());
        assertFalse(trace.decisions().containsKey("prompt"));
        assertTrue(trace.summary().contains("1"));
        assertEquals(List.of(trace), service.list("owner-a", browserId, 10));
    }

    @Test
    void personalExperimentClosesTheLearningLoopFromDraftToEvaluation() {
        OutcomeLearningService outcomes = new OutcomeLearningService(store, timeline, clock);
        PersonalExperimentService service = new PersonalExperimentService(store, outcomes, timeline, clock);

        var created = service.create("owner-a", "cockpit", "โฟกัสก่อนเปิดแชต",
                "การทำงานเงียบ 30 นาทีช่วยให้งานสำคัญคืบหน้า", "ทำงานสำคัญก่อนเปิดแชตทุกเช้า",
                "นาทีโฟกัส", "นาที", MetricDirection.INCREASE, 20, 45, 7);

        assertEquals(ExperimentStatus.DRAFT, created.experiment().status());
        assertEquals(OutcomeStatus.PROPOSED, created.outcome().status());
        assertThrows(IllegalStateException.class,
                () -> service.checkIn("owner-a", created.experiment().id(), 30, "เร็วเกินไป", null));

        var started = service.start("owner-a", created.experiment().id());
        assertEquals(ExperimentStatus.RUNNING, started.experiment().status());
        assertEquals(OutcomeStatus.IN_PROGRESS, started.outcome().status());
        assertEquals(7, java.time.Duration.between(started.experiment().startedAt(),
                started.experiment().plannedEndAt()).toDays());

        service.checkIn("owner-a", created.experiment().id(), 30, "วันแรก", NOW);
        var checkedIn = service.checkIn("owner-a", created.experiment().id(), 40, "ดีขึ้น", NOW.plusSeconds(60));
        assertEquals(2, checkedIn.analysis().checkInCount());
        assertEquals(80, checkedIn.analysis().progressPercent());
        assertEquals("IMPROVING", checkedIn.analysis().trend());

        var completed = service.complete("owner-a", created.experiment().id(), "ทำได้สม่ำเสมอ");
        assertEquals(ExperimentStatus.COMPLETED, completed.experiment().status());
        assertEquals("MADE_PROGRESS", completed.analysis().conclusion());
        assertEquals(OutcomeStatus.COMPLETED, completed.outcome().status());

        var evaluated = service.evaluate("owner-a", created.experiment().id(), 5, "อยากทำต่อ");
        assertEquals(OutcomeStatus.EVALUATED, evaluated.outcome().status());
        assertEquals(5, evaluated.outcome().score());
        assertThrows(IllegalArgumentException.class,
                () -> service.details("owner-b", created.experiment().id()));
    }

    @Test
    void personalExperimentSupportsDecreasingMetricsAndExplicitAbandonment() {
        OutcomeLearningService outcomes = new OutcomeLearningService(store, timeline, clock);
        PersonalExperimentService service = new PersonalExperimentService(store, outcomes, timeline, clock);
        var created = service.create("owner-a", "cockpit", "ลดเวลาหน้าจอก่อนนอน",
                "ลดหน้าจอจะช่วยให้พักผ่อนได้ตรงเวลา", "งดหน้าจอช่วงสุดท้ายของวัน",
                "นาทีหน้าจอ", "นาที", MetricDirection.DECREASE, 90, 30, 14);

        service.start("owner-a", created.experiment().id());
        var checkedIn = service.checkIn("owner-a", created.experiment().id(), 45, "ลดลงแล้ว", NOW);
        assertEquals(75, checkedIn.analysis().progressPercent());
        assertEquals(-45d, checkedIn.analysis().deltaFromBaseline());

        var abandoned = service.abandon("owner-a", created.experiment().id(), "ช่วงนี้ตารางไม่นิ่ง");
        assertEquals(ExperimentStatus.ABANDONED, abandoned.experiment().status());
        assertEquals(OutcomeStatus.ABANDONED, abandoned.outcome().status());
        assertThrows(IllegalStateException.class,
                () -> service.start("owner-a", created.experiment().id()));
    }

    private static final class InMemoryTaskStore implements TaskStore {
        private final List<PersonalTask> values = new ArrayList<>();
        public PersonalTask create(PersonalTask value) { values.add(value); return value; }
        public Optional<PersonalTask> find(UUID id, String owner) { return values.stream().filter(v -> v.id().equals(id) && v.ownerId().equals(owner)).findFirst(); }
        public List<PersonalTask> list(String owner, TaskStatus status) { return values.stream().filter(v -> v.ownerId().equals(owner) && (status == null || v.status() == status)).toList(); }
        public List<PersonalTask> findDueFollowUps(Instant now) { return List.of(); }
        public PersonalTask update(PersonalTask value) { values.replaceAll(v -> v.id().equals(value.id()) ? value : v); return value; }
        public boolean delete(UUID id, String owner, Instant at) { return false; }
        public void markFollowedUp(PersonalTask task, Instant now) { }
    }

    private static final class InMemoryGoalStore implements GoalStore {
        private final List<PersonalGoal> values = new ArrayList<>();
        public PersonalGoal create(PersonalGoal value) { values.add(value); return value; }
        public Optional<PersonalGoal> find(UUID id, String owner) { return values.stream().filter(v -> v.id().equals(id) && v.ownerId().equals(owner)).findFirst(); }
        public List<PersonalGoal> list(String owner, GoalStatus status) { return values.stream().filter(v -> v.ownerId().equals(owner) && (status == null || v.status() == status)).toList(); }
        public List<PersonalGoal> dueForReview(String owner, Instant now, int limit) { return values.stream().filter(v -> v.ownerId().equals(owner) && v.nextReviewAt() != null && !v.nextReviewAt().isAfter(now)).limit(limit).toList(); }
        public PersonalGoal update(PersonalGoal value) { values.replaceAll(v -> v.id().equals(value.id()) ? value : v); return value; }
    }
}
