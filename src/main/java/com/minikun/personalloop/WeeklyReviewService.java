package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import com.minikun.agent.execution.AgentExecutionService;
import com.minikun.agent.execution.AgentRunStatus;
import com.minikun.goal.GoalService;
import com.minikun.goal.NextActionRecommendation;
import com.minikun.goal.NextActionService;
import com.minikun.goal.PersonalGoal;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskPatch;
import com.minikun.task.TaskService;
import com.minikun.task.TaskStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deterministic weekly reflection that creates bounded, individually confirmable proposals. */
public final class WeeklyReviewService {
    private final PersonalLoopStore store;
    private final OutcomeLearningService outcomes;
    private final PersonalTimelineRecorder timeline;
    private final TaskService tasks;
    private final GoalService goals;
    private final NextActionService nextActions;
    private final AgentExecutionService executions;
    private final Clock clock;

    public WeeklyReviewService(PersonalLoopStore store, OutcomeLearningService outcomes,
            PersonalTimelineRecorder timeline, TaskService tasks, GoalService goals,
            NextActionService nextActions, AgentExecutionService executions, Clock clock) {
        this.store = Objects.requireNonNull(store); this.outcomes = Objects.requireNonNull(outcomes);
        this.timeline = Objects.requireNonNull(timeline); this.tasks = tasks; this.goals = goals;
        this.nextActions = nextActions; this.executions = executions; this.clock = Objects.requireNonNull(clock);
    }

    public WeeklyReview generate(String ownerId, String conversationId, Instant periodStart, Instant periodEnd) {
        String owner = PersonalLoopModels.owner(ownerId);
        String conversation = PersonalLoopModels.text(conversationId, "conversation id");
        Instant end = periodEnd == null ? clock.instant() : periodEnd;
        Instant start = periodStart == null ? end.minus(Duration.ofDays(7)) : periodStart;
        var existing = store.reviewForPeriod(owner, start, end);
        if (existing.isPresent()) return existing.orElseThrow();

        List<PersonalTask> ownerTasks = tasks == null ? List.of() : tasks.list(owner, null);
        Instant now = clock.instant();
        List<String> wins = ownerTasks.stream().filter(task -> task.status() == TaskStatus.DONE)
                .filter(task -> task.completedAt() != null && !task.completedAt().isBefore(start) && task.completedAt().isBefore(end.plusMillis(1)))
                .map(PersonalTask::title).limit(20).toList();
        List<PersonalTask> active = ownerTasks.stream().filter(PersonalTask::active).toList();
        List<PersonalTask> overdue = active.stream().filter(task -> task.dueAt() != null && !task.dueAt().isAfter(now)).toList();
        List<PersonalTask> blocked = active.stream().filter(task -> task.status() == TaskStatus.BLOCKED).toList();
        List<PersonalGoal> activeGoals = goals == null ? List.of() : goals.syncOpenProgress(owner, ownerTasks);
        long dueGoals = activeGoals.stream().filter(goal -> goal.nextReviewAt() != null && !goal.nextReviewAt().isAfter(now)).count();
        int waiting = executions == null ? 0 : executions.list(owner, AgentRunStatus.WAITING_CONFIRMATION, 100).size();
        List<String> risks = new ArrayList<>();
        overdue.stream().limit(10).forEach(task -> risks.add("งานเลยกำหนด: " + task.title()));
        blocked.stream().limit(10).forEach(task -> risks.add("งานติดขัด: " + task.title()));
        if (dueGoals > 0) risks.add("มีเป้าหมายถึงรอบทบทวน " + dueGoals + " รายการ");
        if (waiting > 0) risks.add("มี agent run รอยืนยัน " + waiting + " รายการ");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("wins", wins); summary.put("risks", risks); summary.put("completed_tasks", wins.size());
        summary.put("active_tasks", active.size()); summary.put("overdue_tasks", overdue.size());
        summary.put("blocked_tasks", blocked.size()); summary.put("active_goals", activeGoals.size());
        summary.put("goals_due_for_review", dueGoals); summary.put("agent_waiting_confirmation", waiting);
        summary.put("attention_required", !risks.isEmpty());
        WeeklyReview review = store.save(new WeeklyReview(UUID.randomUUID(), owner, conversation, start, end,
                ReviewStatus.DRAFT, summary, now, null));
        createProposals(review);
        timeline.record(owner, "WEEKLY_REVIEW_CREATED", "WEEKLY_REVIEW", review.id().toString(),
                "สรุปทบทวนประจำสัปดาห์", risks.isEmpty() ? "ไม่มีรายการเสี่ยงเร่งด่วน" : risks.getFirst(),
                Map.of("period_start", start.toString(), "period_end", end.toString()), now);
        return review;
    }

    public Map<String, Object> details(String ownerId, UUID id) {
        WeeklyReview review = find(ownerId, id);
        return Map.of("review", review, "proposals", store.proposals(id, review.ownerId()));
    }

    public List<WeeklyReview> list(String ownerId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return store.reviews(PersonalLoopModels.owner(ownerId), limit);
    }

    public ReviewProposal decide(String ownerId, UUID proposalId, boolean accept) {
        String owner = PersonalLoopModels.owner(ownerId);
        ReviewProposal proposal = store.proposal(Objects.requireNonNull(proposalId), owner)
                .orElseThrow(() -> new IllegalArgumentException("review proposal was not found"));
        if (proposal.status() != ProposalStatus.PENDING) throw new IllegalStateException("review proposal was already decided");
        Instant now = clock.instant();
        UUID outcomeId = UUID.fromString(proposal.payload().get("outcome_id").toString());
        if (!accept) {
            outcomes.reject(owner, outcomeId, "weekly review proposal rejected");
            ReviewProposal rejected = store.save(copy(proposal, ProposalStatus.REJECTED, now));
            finishReviewIfDecided(rejected.reviewId(), owner);
            return rejected;
        }
        outcomes.accept(owner, outcomeId);
        ReviewProposal accepted = store.save(copy(proposal, ProposalStatus.ACCEPTED, now));
        try {
            apply(accepted);
            outcomes.start(owner, outcomeId);
            ReviewProposal applied = store.save(copy(accepted, ProposalStatus.APPLIED, now));
            finishReviewIfDecided(applied.reviewId(), owner);
            return applied;
        } catch (RuntimeException exception) {
            ReviewProposal failed = store.save(copy(accepted, ProposalStatus.FAILED, now));
            finishReviewIfDecided(failed.reviewId(), owner);
            throw exception;
        }
    }

    private void createProposals(WeeklyReview review) {
        if (nextActions == null) return;
        for (NextActionRecommendation recommendation : nextActions.recommend(review.ownerId(), 3)) {
            String type = recommendation.taskId() == null ? "CREATE_TASK" : "START_OR_FOLLOW_UP_TASK";
            Outcome outcome = outcomes.propose(review.ownerId(), review.conversationId(), "next_action",
                    recommendation.action(), "WEEKLY_REVIEW", review.id().toString());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("goal_id", recommendation.goalId().toString());
            if (recommendation.taskId() != null) payload.put("task_id", recommendation.taskId().toString());
            payload.put("action", recommendation.action()); payload.put("priority", recommendation.priority());
            payload.put("outcome_id", outcome.id().toString());
            store.save(new ReviewProposal(UUID.randomUUID(), review.id(), review.ownerId(), type,
                    recommendation.taskId() == null ? recommendation.goalId().toString() : recommendation.taskId().toString(),
                    recommendation.action(), recommendation.reason(), payload, ProposalStatus.PENDING,
                    clock.instant(), null));
        }
    }

    private void apply(ReviewProposal proposal) {
        if (tasks == null) throw new IllegalStateException("task service is unavailable");
        if ("CREATE_TASK".equals(proposal.type())) {
            tasks.create(proposal.ownerId(), find(proposal.ownerId(), proposal.reviewId()).conversationId(), "TASK",
                    proposal.title(), proposal.reason(), "", "Asia/Bangkok", proposal.title(), "", "", "",
                    proposal.payload().get("goal_id").toString());
            return;
        }
        UUID taskId = UUID.fromString(proposal.payload().get("task_id").toString());
        PersonalTask task = tasks.find(proposal.ownerId(), taskId);
        if (task.status() == TaskStatus.OPEN) {
            tasks.update(proposal.ownerId(), taskId, new TaskPatch(null, null, TaskStatus.IN_PROGRESS,
                    null, null, null, null, null));
        }
    }

    private void finishReviewIfDecided(UUID reviewId, String owner) {
        List<ReviewProposal> values = store.proposals(reviewId, owner);
        if (values.stream().anyMatch(value -> value.status() == ProposalStatus.PENDING)) return;
        WeeklyReview review = find(owner, reviewId);
        Instant now = clock.instant();
        store.save(new WeeklyReview(review.id(), review.ownerId(), review.conversationId(), review.periodStart(),
                review.periodEnd(), ReviewStatus.COMPLETED, review.summary(), review.createdAt(), now));
        timeline.record(owner, "WEEKLY_REVIEW_COMPLETED", "WEEKLY_REVIEW", reviewId.toString(),
                "ทบทวนประจำสัปดาห์เสร็จแล้ว", "ข้อเสนอทั้งหมดได้รับการตัดสินใจแล้ว", Map.of(), now);
    }

    private WeeklyReview find(String ownerId, UUID id) { return store.review(Objects.requireNonNull(id), PersonalLoopModels.owner(ownerId)).orElseThrow(() -> new IllegalArgumentException("weekly review was not found")); }
    private ReviewProposal copy(ReviewProposal value, ProposalStatus status, Instant decidedAt) { return new ReviewProposal(value.id(), value.reviewId(), value.ownerId(), value.type(), value.targetId(), value.title(), value.reason(), value.payload(), status, value.createdAt(), decidedAt); }
}
