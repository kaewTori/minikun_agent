package com.minikun.agent.minikun_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.minikun.memory.internal.MemoryConfiguration;
import com.minikun.browser.BrowserConfiguration;
import com.minikun.search.internal.SearchConfiguration;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.diagnostics.MeterRegistryMetricsReader;
import com.minikun.pcs.MinikunPersonaProvider;
import com.minikun.pcs.KnowledgeSelectionConfiguration;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;
import com.minikun.model.DefaultChatModelProviderRegistry;
import com.minikun.model.ActiveModelConfigurationSetup;
import com.minikun.model.DefaultActiveChatModelProvider;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.model.tinygrad.TinyGradChatModelProvider;
import com.minikun.model.tinygrad.TinyGradConfiguration;

@SpringBootApplication
@Import({MemoryConfiguration.class, SearchConfiguration.class, BrowserConfiguration.class, MeterRegistryMetricsReader.class,
		DiagnosticsService.class, DiagnosticsFormatter.class, DiagnosticsPromptBuilder.class,
		MinikunPersonaProvider.class, KnowledgeSelectionConfiguration.class,
		CommandCatalog.class, CommandFormatter.class,
		VersionService.class, VersionFormatter.class, ModelsService.class, ModelsFormatter.class,
		CacheService.class, CacheFormatter.class,
		DefaultChatModelProviderRegistry.class, ActiveModelConfigurationSetup.class,
		DefaultActiveChatModelProvider.class, ExistingChatModelProvider.class,
		TinyGradChatModelProvider.class, TinyGradConfiguration.class})
public class MinikunAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinikunAgentApplication.class, args);
	}

}
