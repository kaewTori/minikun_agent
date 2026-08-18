package com.minikun.agent.minikun_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

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
import com.minikun.tools.CalculatorAddTool;
import com.minikun.tools.CurrentTimeTool;
import com.minikun.tools.CurrentTimeToolRouter;
import com.minikun.tools.DefaultToolExecutor;
import com.minikun.tools.DefaultToolRegistry;
import com.minikun.tools.WeatherForecastTool;
import com.minikun.tools.WeatherToolRouter;
import com.minikun.tools.WebOpenUrlTool;
import com.minikun.tools.WebSearchTool;
import com.minikun.tools.PlannerManageTool;
import com.minikun.tools.PlannerConfirmationRouter;
import com.minikun.tools.RelativeReminderToolRouter;
import com.minikun.tools.AbsoluteReminderToolRouter;
import com.minikun.tools.ReminderContextToolRouter;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import com.minikun.notification.NtfyNotificationService;
import com.minikun.planner.JdbcPlannerStore;
import com.minikun.planner.JdbcPlannerConfirmationStore;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerNotificationScheduler;
import com.minikun.planner.PlannerService;
import com.minikun.weather.DailyWeatherNotificationScheduler;
import com.minikun.weather.WeatherConfiguration;

@SpringBootApplication
@EnableScheduling
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
		MemoryManagementService.class, MemoryManagementController.class,
		WeatherConfiguration.class,
		CurrentTimeToolRouter.class,
		DefaultToolRegistry.class, DefaultToolExecutor.class,
		CalculatorAddTool.class, WeatherForecastTool.class, CurrentTimeTool.class,
		WebSearchTool.class, WebOpenUrlTool.class, WeatherToolRouter.class, PlannerManageTool.class,
		PlannerConfirmationRouter.class, RelativeReminderToolRouter.class, AbsoluteReminderToolRouter.class,
		ReminderContextToolRouter.class,
		JdbcPlannerConfirmationStore.class, PlannerConfirmationService.class,
		NtfyNotificationService.class, JdbcPlannerStore.class, PlannerService.class,
		PlannerNotificationScheduler.class, DailyWeatherNotificationScheduler.class,
		SpringAiToolCallingRuntime.class})
public class MinikunAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(MinikunAgentApplication.class, args);
	}

}
