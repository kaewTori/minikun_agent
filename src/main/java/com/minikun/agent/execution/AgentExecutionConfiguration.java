package com.minikun.agent.execution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({AgentPlanningService.class, JdbcAgentExecutionStore.class, AgentExecutionService.class,
        AgentResumeService.class, AgentExecutionController.class, AgentActionConfiguration.class})
public class AgentExecutionConfiguration {
    @Bean
    @ConditionalOnMissingBean(AgentExecutionTracker.class)
    AgentExecutionTracker noOpAgentExecutionTracker() {
        return AgentExecutionTracker.noop();
    }
}
