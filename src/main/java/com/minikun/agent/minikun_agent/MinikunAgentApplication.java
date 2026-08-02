package com.minikun.agent.minikun_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.minikun.memory.internal.MemoryConfiguration;
import com.minikun.search.internal.SearchConfiguration;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.diagnostics.MeterRegistryMetricsReader;

@SpringBootApplication
@Import({MemoryConfiguration.class, SearchConfiguration.class, MeterRegistryMetricsReader.class,
		DiagnosticsService.class, DiagnosticsFormatter.class, CommandCatalog.class, CommandFormatter.class})
public class MinikunAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinikunAgentApplication.class, args);
	}

}
