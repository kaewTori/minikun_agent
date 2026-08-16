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
import com.minikun.model.CooperativeChatModelService;
import com.minikun.model.CooperativeReviewStore;
import com.minikun.model.CooperationRouter;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.model.tinygrad.TinyGradChatModelProvider;
import com.minikun.model.tinygrad.TinyGradConfiguration;
import com.minikun.model.task.title.TitleGenerationConfiguration;
import com.minikun.model.task.title.TitleGenerationService;
import com.minikun.model.task.TaskModelConfiguration;
import com.minikun.tokenbudget.runtime.TokenBudgetRuntimeConfiguration;
import com.minikun.context.config.ContextRuntimeConfiguration;
import com.minikun.memory.management.MemoryManagementController;
import com.minikun.memory.management.MemoryManagementService;

@SpringBootApplication
@Import({MemoryConfiguration.class, SearchConfiguration.class, BrowserConfiguration.class, MeterRegistryMetricsReader.class,
		DiagnosticsService.class, DiagnosticsFormatter.class, DiagnosticsPromptBuilder.class,
		MinikunPersonaProvider.class, KnowledgeSelectionConfiguration.class,
		CommandCatalog.class, CommandFormatter.class,
		VersionService.class, VersionFormatter.class, ModelsService.class, ModelsFormatter.class,
		CacheService.class, CacheFormatter.class,
		DefaultChatModelProviderRegistry.class, ActiveModelConfigurationSetup.class,
		DefaultActiveChatModelProvider.class, ExistingChatModelProvider.class,
		TinyGradChatModelProvider.class, TinyGradConfiguration.class, TaskModelConfiguration.class,
		CooperativeReviewStore.class, CooperationRouter.class, CooperativeChatModelService.class,
		TokenBudgetRuntimeConfiguration.class,
		TitleGenerationConfiguration.class, TitleGenerationService.class,
		ContextRuntimeConfiguration.class,
		MemoryManagementService.class, MemoryManagementController.class})
public class MinikunAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinikunAgentApplication.class, args);
	}

}
