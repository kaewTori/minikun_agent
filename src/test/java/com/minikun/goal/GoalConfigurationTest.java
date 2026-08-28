package com.minikun.goal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.minikun.personalcare.PersonalCareConfiguration;
import com.minikun.personalcare.PersonalCareStatusController;
import com.minikun.task.TaskService;

class GoalConfigurationTest {
    @Test
    void exposesGoalCapabilityWhenJdbcIsAvailable() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("minikun.goal.enabled=true", "minikun.goal.review.enabled=false")
                    .applyTo(context);
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.getBeanFactory().registerSingleton("taskService", mock(TaskService.class));
            context.register(GoalConfiguration.class, PersonalCareConfiguration.class);
            context.refresh();

            assertNotNull(context.getBean(GoalStore.class));
            assertNotNull(context.getBean(GoalService.class));
            assertNotNull(context.getBean(GoalController.class));
            assertNotNull(context.getBean(NextActionService.class));
            assertNotNull(context.getBean(NextActionController.class));
            assertNotNull(context.getBean(PersonalCareStatusController.class));
        }
    }
}
