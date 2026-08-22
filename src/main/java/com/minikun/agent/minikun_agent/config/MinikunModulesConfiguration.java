package com.minikun.agent.minikun_agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.minikun.agent.execution.AgentExecutionConfiguration;
import com.minikun.agent.minikun_agent.conversation.ConversationSummaryConfiguration;
import com.minikun.browser.BrowserConfiguration;
import com.minikun.calendar.ExternalCalendarConfiguration;
import com.minikun.calendar.ExternalCalendarController;
import com.minikun.calendar.ExternalCalendarReminderScheduler;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.communication.CommunicationConfiguration;
import com.minikun.computer.ComputerConfiguration;
import com.minikun.context.config.ContextRuntimeConfiguration;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.diagnostics.MeterRegistryMetricsReader;
import com.minikun.guardian.GuardianConfiguration;
import com.minikun.guardian.HomelabGuardianScheduler;
import com.minikun.investment.InvestmentService;
import com.minikun.investment.JdbcInvestmentStore;
import com.minikun.knowledge.PersonalKnowledgeConfiguration;
import com.minikun.memory.internal.MemoryConfiguration;
import com.minikun.memory.management.MemoryManagementController;
import com.minikun.memory.management.MemoryManagementService;
import com.minikun.model.ActiveModelConfigurationSetup;
import com.minikun.model.CooperationRouter;
import com.minikun.model.CooperativeChatModelService;
import com.minikun.model.CooperativeQualityGate;
import com.minikun.model.CooperativeReviewStore;
import com.minikun.model.DefaultActiveChatModelProvider;
import com.minikun.model.DefaultChatModelProviderRegistry;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.model.task.TaskModelConfiguration;
import com.minikun.model.task.title.TitleGenerationConfiguration;
import com.minikun.model.task.title.TitleGenerationService;
import com.minikun.model.tinygrad.TinyGradChatModelProvider;
import com.minikun.model.tinygrad.TinyGradConfiguration;
import com.minikun.notification.JdbcNotificationDeliveryStore;
import com.minikun.notification.NotificationDeliveryController;
import com.minikun.notification.NotificationDeliveryService;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.notification.NtfyNotificationService;
import com.minikun.pcs.KnowledgeSelectionConfiguration;
import com.minikun.pcs.MinikunPersonaProvider;
import com.minikun.personality.config.PersonalityConfiguration;
import com.minikun.planner.JdbcPlannerConfirmationStore;
import com.minikun.planner.JdbcPlannerStore;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.planner.PlannerNotificationScheduler;
import com.minikun.planner.PlannerService;
import com.minikun.planner.ReminderActionController;
import com.minikun.proactive.DailyBriefingScheduler;
import com.minikun.proactive.ProactiveNotificationPolicy;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;
import com.minikun.search.internal.SearchConfiguration;
import com.minikun.systemhealth.SystemHealthConfiguration;
import com.minikun.task.JdbcTaskStore;
import com.minikun.task.TaskController;
import com.minikun.task.TaskFollowUpScheduler;
import com.minikun.task.TaskService;
import com.minikun.tokenbudget.runtime.TokenBudgetRuntimeConfiguration;
import com.minikun.tools.AbsoluteReminderToolRouter;
import com.minikun.tools.CalculatorAddTool;
import com.minikun.tools.CalendarManageTool;
import com.minikun.tools.ComputerConfirmationRouter;
import com.minikun.tools.CurrentTimeTool;
import com.minikun.tools.CurrentTimeToolRouter;
import com.minikun.tools.DefaultToolExecutor;
import com.minikun.tools.DefaultToolRegistry;
import com.minikun.tools.GuardianConfirmationRouter;
import com.minikun.tools.HomelabGuardianRouter;
import com.minikun.tools.HomelabGuardianTool;
import com.minikun.tools.InvestmentAnalyzeTool;
import com.minikun.tools.InvestmentConfirmationRouter;
import com.minikun.tools.InvestmentManageTool;
import com.minikun.tools.LocalComputerRouter;
import com.minikun.tools.LocalComputerTool;
import com.minikun.tools.LocationResolveTool;
import com.minikun.tools.MemoryManageTool;
import com.minikun.tools.PlannerConfirmationRouter;
import com.minikun.tools.PlannerManageTool;
import com.minikun.tools.RelativeReminderToolRouter;
import com.minikun.tools.ReminderContextToolRouter;
import com.minikun.tools.ServiceHealthTool;
import com.minikun.tools.SystemHealthTool;
import com.minikun.tools.TaskCaptureToolRouter;
import com.minikun.tools.TaskConfirmationRouter;
import com.minikun.tools.TaskManageTool;
import com.minikun.tools.WeatherForecastTool;
import com.minikun.tools.WeatherToolRouter;
import com.minikun.tools.WebOpenUrlTool;
import com.minikun.tools.WebSearchTool;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import com.minikun.vision.VisionInputService;
import com.minikun.voice.VoiceConfiguration;
import com.minikun.voice.VoiceController;
import com.minikun.voice.VoiceExceptionHandler;
import com.minikun.weather.DailyWeatherNotificationScheduler;
import com.minikun.weather.WeatherConfiguration;

/** Explicit module graph kept separate from the executable application entry point. */
@Configuration(proxyBeanMethods = false)
@Import({
        MemoryConfiguration.class, ConversationSummaryConfiguration.class,
        SearchConfiguration.class, BrowserConfiguration.class,
        MeterRegistryMetricsReader.class, DiagnosticsService.class, DiagnosticsFormatter.class,
        DiagnosticsPromptBuilder.class, MinikunPersonaProvider.class, KnowledgeSelectionConfiguration.class,
        CommandCatalog.class, CommandFormatter.class, VersionService.class, VersionFormatter.class,
        ModelsService.class, ModelsFormatter.class, CacheService.class, CacheFormatter.class,
        DefaultChatModelProviderRegistry.class, ActiveModelConfigurationSetup.class,
        DefaultActiveChatModelProvider.class, ExistingChatModelProvider.class,
        TinyGradChatModelProvider.class, TinyGradConfiguration.class, TaskModelConfiguration.class,
        CooperativeReviewStore.class, CooperationRouter.class, CooperativeQualityGate.class,
        CooperativeChatModelService.class, TokenBudgetRuntimeConfiguration.class,
        TitleGenerationConfiguration.class, TitleGenerationService.class, ContextRuntimeConfiguration.class,
        VisionInputService.class, GuardianConfiguration.class, ComputerConfiguration.class,
        VoiceConfiguration.class, VoiceController.class, VoiceExceptionHandler.class,
        PersonalKnowledgeConfiguration.class, PersonalityConfiguration.class,
        CommunicationConfiguration.class, AgentExecutionConfiguration.class, HomelabGuardianScheduler.class,
        MemoryManagementService.class, MemoryManagementController.class, WeatherConfiguration.class,
        ExternalCalendarConfiguration.class, ExternalCalendarController.class,
        ExternalCalendarReminderScheduler.class, ProactiveNotificationPolicy.class,
        SystemHealthConfiguration.class, CurrentTimeToolRouter.class, DefaultToolRegistry.class,
        DefaultToolExecutor.class, CalculatorAddTool.class, WeatherForecastTool.class, CurrentTimeTool.class,
        WebSearchTool.class, WebOpenUrlTool.class, WeatherToolRouter.class, LocationResolveTool.class,
        PlannerManageTool.class, CalendarManageTool.class, MemoryManageTool.class, ServiceHealthTool.class,
        SystemHealthTool.class, HomelabGuardianTool.class, HomelabGuardianRouter.class,
        GuardianConfirmationRouter.class, LocalComputerTool.class, LocalComputerRouter.class,
        ComputerConfirmationRouter.class, PlannerConfirmationRouter.class, RelativeReminderToolRouter.class,
        AbsoluteReminderToolRouter.class, ReminderContextToolRouter.class, JdbcPlannerConfirmationStore.class,
        PlannerConfirmationService.class, NtfyNotificationService.class, JdbcNotificationDeliveryStore.class,
        NotificationDeliveryService.class, NotificationDeliveryController.class,
        NotificationSchedulerMonitor.class, JdbcPlannerStore.class, PlannerService.class,
        ReminderActionController.class, PlannerNotificationScheduler.class,
        DailyWeatherNotificationScheduler.class, JdbcTaskStore.class, TaskService.class,
        TaskManageTool.class, TaskConfirmationRouter.class, TaskCaptureToolRouter.class, TaskController.class,
        TaskFollowUpScheduler.class, DailyBriefingScheduler.class,
        JdbcInvestmentStore.class, InvestmentService.class, InvestmentManageTool.class,
        InvestmentAnalyzeTool.class, InvestmentConfirmationRouter.class, SpringAiToolCallingRuntime.class
})
public class MinikunModulesConfiguration {
}
