package com.minikun.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.computer.LocalComputerService;
import com.minikun.tools.ToolExecutor;
import com.minikun.tools.ToolRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.agent.execution.enabled", havingValue = "true", matchIfMissing = true)
@Import({AgentActionController.class, AgentActionTool.class, AgentActionConfirmationRouter.class})
public class AgentActionConfiguration {
    @Bean AgentActionStore agentActionStore(JdbcTemplate jdbc, ObjectMapper mapper) { return new AgentActionStore(jdbc, mapper); }
    @Bean AgentActionRuntime agentActionRuntime(AgentActionStore store, AgentExecutionService executions,
            ObjectProvider<ToolExecutor> executor, ObjectProvider<ToolRegistry> registry,
            ObjectProvider<LocalComputerService> computer, ObjectMapper mapper, Clock clock) {
        return new AgentActionRuntime(store, executions, executor, registry, computer, mapper, clock);
    }
}
