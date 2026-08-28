package com.minikun.goal;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.goal.enabled", havingValue = "true", matchIfMissing = true)
@Import({JdbcGoalStore.class, GoalService.class, GoalController.class, GoalReviewScheduler.class,
        NextActionService.class, NextActionController.class})
public class GoalConfiguration {
}
