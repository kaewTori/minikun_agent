package com.minikun.personalcare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.goal.GoalService;
import com.minikun.goal.GoalStatus;
import com.minikun.goal.JdbcGoalStore;
import com.minikun.goal.NextActionService;
import com.minikun.task.JdbcTaskStore;
import com.minikun.task.TaskService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Real-database verification of the personal Goal -> Task -> Next Action -> Status loop. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_POSTGRES_INTEGRATION", matches = "true")
class PersonalLoopPostgresIntegrationTest {
    @Test
    void completesGoalFromLinkedTaskAndRemovesItFromOpenStatus() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                environment("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/minikun"),
                environment("SPRING_DATASOURCE_USERNAME", "minikun"),
                environment("SPRING_DATASOURCE_PASSWORD", ""));
        new ResourceDatabasePopulator(new ClassPathResource("planner-schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Clock clock = Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneOffset.UTC);
        String owner = "integration-loop-" + UUID.randomUUID();
        GoalService goals = new GoalService(new JdbcGoalStore(jdbc), clock);
        TaskService tasks = new TaskService(new JdbcTaskStore(jdbc), clock);
        GoalService goalsWithTasks = new GoalService(new JdbcGoalStore(jdbc), clock, new JdbcTaskStore(jdbc));
        NextActionService nextActions = new NextActionService(goalsWithTasks, tasks, clock);
        PersonalCareStatusService status = new PersonalCareStatusService(tasks, goalsWithTasks, null, clock);

        try {
            var goal = goals.create(owner, "integration-conversation", "Ship personal loop",
                    "End-to-end functional closure", "completed tasks", 0, 1, 0,
                    clock.instant().plusSeconds(86_400).toString(), "Asia/Bangkok");
            var task = tasks.create(owner, "integration-conversation", "TASK", "Run closure test",
                    "Verify the complete loop", clock.instant().plusSeconds(3_600).toString(),
                    "Asia/Bangkok", "Run the integration test", "", "", "", goal.id().toString());

            var recommendations = nextActions.recommend(owner, 5);
            assertEquals(1, recommendations.size());
            assertEquals(goal.id(), recommendations.getFirst().goalId());
            assertEquals(task.id(), recommendations.getFirst().taskId());
            assertEquals("Run the integration test", recommendations.getFirst().action());
            assertStatus(status.snapshot(owner), 1, 1, false);

            tasks.complete(owner, task.id());

            assertStatus(status.snapshot(owner), 0, 0, false);
            var completed = goals.find(owner, goal.id());
            assertEquals(GoalStatus.COMPLETED, completed.status());
            assertEquals(100, completed.progressPercent());
            assertTrue(nextActions.recommend(owner, 5).isEmpty());
        } finally {
            jdbc.update("DELETE FROM minikun_goal_review_notification WHERE goal_id IN "
                    + "(SELECT id FROM minikun_goal WHERE owner_id = ?)", owner);
            jdbc.update("DELETE FROM minikun_task WHERE owner_id = ?", owner);
            jdbc.update("DELETE FROM minikun_goal WHERE owner_id = ?", owner);
        }
    }

    private void assertStatus(Map<String, Object> snapshot, int tasks, int goals, boolean attentionRequired) {
        assertEquals(tasks, snapshot.get("open_tasks"));
        assertEquals(goals, snapshot.get("open_goals"));
        assertEquals(attentionRequired, snapshot.get("attention_required"));
    }

    private String environment(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
