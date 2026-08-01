package com.minikun.agent.minikun_agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration",
	"minikun.memory.persistence.enabled=false"
})
@Import(TestChatMemoryConfiguration.class)
class MinikunAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
