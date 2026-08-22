package com.minikun.agent.minikun_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MinikunAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinikunAgentApplication.class, args);
	}

}
