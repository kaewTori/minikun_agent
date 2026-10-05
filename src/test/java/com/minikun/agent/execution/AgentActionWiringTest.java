package com.minikun.agent.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.tools.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.aop.support.AopUtils;

class AgentActionWiringTest {
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    @Import({AgentExecutionConfiguration.class, DefaultToolExecutor.class, DefaultToolRegistry.class})
    static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
    }
    @Test void operationalModuleLoadsWithLazyRegistryAndTransactionalCheckpoints() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            assertNotNull(context.getBean(AgentActionController.class));
            assertTrue(context.getBean(ToolRegistry.class).find("agent.action").isPresent());
            assertTrue(AopUtils.isAopProxy(context.getBean(AgentActionRuntime.class)));
        }
    }
}
