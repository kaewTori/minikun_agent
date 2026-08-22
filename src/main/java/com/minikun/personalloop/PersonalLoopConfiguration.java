package com.minikun.personalloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.execution.AgentExecutionService;
import com.minikun.goal.GoalService;
import com.minikun.goal.NextActionService;
import com.minikun.guardian.HomelabGuardianService;
import com.minikun.investment.InvestmentService;
import com.minikun.knowledge.PersonalKnowledgeService;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.planner.PlannerService;
import com.minikun.task.TaskService;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.personal-loop.enabled", havingValue = "true", matchIfMissing = true)
public class PersonalLoopConfiguration {
    @Bean PersonalLoopStore personalLoopStore(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json) { return new PersonalLoopStore(jdbc.getIfAvailable(), json); }
    @Bean PersonalTimelineRecorder personalTimelineRecorder(PersonalLoopStore store, Clock clock) { return new PersonalTimelineRecorder(store, clock); }
    @Bean OutcomeLearningService outcomeLearningService(PersonalLoopStore store, PersonalTimelineRecorder timeline, Clock clock) { return new OutcomeLearningService(store, timeline, clock); }
    @Bean PersonalExperimentService personalExperimentService(PersonalLoopStore store, OutcomeLearningService outcomes,
            PersonalTimelineRecorder timeline, Clock clock) {
        return new PersonalExperimentService(store, outcomes, timeline, clock);
    }
    @Bean WeeklyReviewService weeklyReviewService(PersonalLoopStore store, OutcomeLearningService outcomes,
            PersonalTimelineRecorder timeline, ObjectProvider<TaskService> tasks, ObjectProvider<GoalService> goals,
            ObjectProvider<NextActionService> nextActions, ObjectProvider<AgentExecutionService> executions, Clock clock) {
        return new WeeklyReviewService(store, outcomes, timeline, tasks.getIfAvailable(), goals.getIfAvailable(),
                nextActions.getIfAvailable(), executions.getIfAvailable(), clock);
    }
    @Bean UniversalInboxService universalInboxService(PersonalLoopStore store, PersonalTimelineRecorder timeline,
            ObjectProvider<TaskService> tasks, ObjectProvider<GoalService> goals, ObjectProvider<PlannerService> planner,
            ObjectProvider<PersonalKnowledgeService> knowledge, ObjectProvider<InvestmentService> investment, Clock clock) {
        return new UniversalInboxService(store, timeline, tasks.getIfAvailable(), goals.getIfAvailable(),
                planner.getIfAvailable(), knowledge.getIfAvailable(), investment.getIfAvailable(), clock);
    }
    @Bean ExplainabilityService explainabilityService(PersonalLoopStore store, PersonalTimelineRecorder timeline, Clock clock) { return new ExplainabilityService(store, timeline, clock); }
    @Bean IncidentCommanderService incidentCommanderService(HomelabGuardianService guardian, PersonalLoopStore store, PersonalTimelineRecorder timeline, Clock clock) { return new IncidentCommanderService(guardian, store, timeline, clock); }
    @Bean SafeAutomationService safeAutomationService(PersonalLoopStore store, PersonalTimelineRecorder timeline,
            WeeklyReviewService reviews, ObjectProvider<TaskService> tasks, ObjectProvider<GoalService> goals,
            ObjectProvider<NotificationDispatcher> notifications, Clock clock,
            @Value("${minikun.personal-loop.timezone:Asia/Bangkok}") String zone) {
        return new SafeAutomationService(store, timeline, reviews, tasks.getIfAvailable(), goals.getIfAvailable(),
                notifications.getIfAvailable(), clock, ZoneId.of(zone));
    }
    @Bean PersonalTimelineService personalTimelineService(PersonalLoopStore store, ObjectProvider<TaskService> tasks,
            ObjectProvider<GoalService> goals, ObjectProvider<AgentExecutionService> executions, Clock clock) {
        return new PersonalTimelineService(store, tasks.getIfAvailable(), goals.getIfAvailable(), executions.getIfAvailable(), clock);
    }
    @Bean
    @ConditionalOnProperty(name = "minikun.personal-loop.weekly-review.enabled", havingValue = "true", matchIfMissing = true)
    WeeklyReviewScheduler weeklyReviewScheduler(WeeklyReviewService service, Clock clock,
            @Value("${minikun.personal-loop.owner-id:default}") String owner,
            @Value("${minikun.personal-loop.timezone:Asia/Bangkok}") String zone,
            @Value("${minikun.personal-loop.weekly-review.day:SUNDAY}") String day,
            @Value("${minikun.personal-loop.weekly-review.time:19:00}") String time) {
        return new WeeklyReviewScheduler(service, clock, owner, zone, day, time);
    }
    @Bean
    @ConditionalOnProperty(name = "minikun.personal-loop.automation.enabled", havingValue = "true", matchIfMissing = true)
    AutomationScheduler automationScheduler(SafeAutomationService service,
            @Value("${minikun.personal-loop.owner-id:default}") String owner) { return new AutomationScheduler(service, owner); }
    @Bean
    @ConditionalOnProperty(name = "minikun.personal-loop.incident.enabled", havingValue = "true", matchIfMissing = true)
    IncidentCommanderScheduler incidentCommanderScheduler(IncidentCommanderService service,
            @Value("${minikun.personal-loop.owner-id:default}") String owner) { return new IncidentCommanderScheduler(service, owner); }
    @Bean PersonalLoopController personalLoopController(WeeklyReviewService reviews, OutcomeLearningService outcomes,
            UniversalInboxService inbox, SafeAutomationService automations, IncidentCommanderService incidents,
            ExplainabilityService explanations, PersonalTimelineService timeline, PersonalExperimentService experiments) {
        return new PersonalLoopController(reviews, outcomes, inbox, automations, incidents, explanations, timeline, experiments);
    }
    @Bean PersonalLoopTool personalLoopTool(WeeklyReviewService reviews, OutcomeLearningService outcomes,
            UniversalInboxService inbox, SafeAutomationService automations, IncidentCommanderService incidents,
            PersonalTimelineService timeline) {
        return new PersonalLoopTool(reviews, outcomes, inbox, automations, incidents, timeline);
    }
    @Bean PersonalLoopExceptionHandler personalLoopExceptionHandler() { return new PersonalLoopExceptionHandler(); }
}
