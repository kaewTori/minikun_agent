package com.minikun.goal;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({JdbcGoalStore.class, GoalService.class, GoalController.class, GoalReviewScheduler.class,
        NextActionService.class, NextActionController.class})
public class GoalConfiguration {
}
