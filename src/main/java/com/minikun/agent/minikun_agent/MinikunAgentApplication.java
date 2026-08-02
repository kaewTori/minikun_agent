package com.minikun.agent.minikun_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.minikun.memory.internal.MemoryConfiguration;
import com.minikun.search.internal.SearchConfiguration;

@SpringBootApplication
@Import({MemoryConfiguration.class, SearchConfiguration.class})
public class MinikunAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinikunAgentApplication.class, args);
	}

}
