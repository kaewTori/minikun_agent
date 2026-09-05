package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.minikun.browser.BrowserContentService;
import com.minikun.agent.minikun_agent.conversation.ConversationSummaryService;
import com.minikun.knowledge.PersonalKnowledgeService;
import com.minikun.knowledge.acquisition.AcquiredKnowledgeIndex;
import com.minikun.memory.DeferredReflectionService;
import com.minikun.memory.event.ObservationPublisher;
import com.minikun.model.CooperativeChatModelService;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.personality.companion.CompanionModeService;
import com.minikun.personality.profile.UserModelService;
import com.minikun.personality.runtime.AdaptivePersonaService;
import com.minikun.research.AutonomousResearchService;
import com.minikun.relationship.ConversationThreadService;
import com.minikun.context.runtime.PersonalContextRuntime;
import com.minikun.tokenbudget.config.TokenBudgetProperties;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import com.minikun.tools.ToolRequestRouter;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import com.minikun.vision.VisionInputService;
import com.minikun.visual.StoryIllustrationService;

/** Resolves optional chat capabilities once at the composition boundary. */
@Component
final class ChatCollaborators {
    private final SpringAiToolCallingRuntime toolCallingRuntime;
    private final List<ToolRequestRouter> toolRequestRouters;
    private final DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory;
    private final ModelCapabilityRegistry modelCapabilityRegistry;
    private final TokenBudgetProperties tokenBudgetProperties;
    private final PersonalContextRuntime personalContextRuntime;
    private final AdaptivePersonaService adaptivePersonaService;
    private final CompanionModeService companionModeService;
    private final UserModelService userModelService;
    private final CooperativeChatModelService cooperativeChatModelService;
    private final DeferredReflectionService deferredReflectionService;
    private final ObservationPublisher observationPublisher;
    private final ChatPerformanceMetrics performanceMetrics;
    private final ChatGenerationProfileSelector generationProfileSelector;
    private final BrowserContentService browserContentService;
    private final VisionInputService visionInputService;
    private final PersonalKnowledgeService personalKnowledgeService;
    private final AcquiredKnowledgeIndex acquiredKnowledgeIndex;
    private final ConversationSummaryService conversationSummaryService;
    private final AutonomousResearchService autonomousResearchService;
    private final ChatExplainabilitySink explainabilitySink;
    private final ConversationThreadService conversationThreadService;
    private final StoryIllustrationService storyIllustrationService;
    private final TurnPlanner turnPlanner;

    ChatCollaborators(
            ObjectProvider<SpringAiToolCallingRuntime> toolCallingRuntime,
            ObjectProvider<ToolRequestRouter> toolRequestRouters,
            ObjectProvider<DynamicGenerationOptionsFactory> dynamicGenerationOptionsFactory,
            ObjectProvider<ModelCapabilityRegistry> modelCapabilityRegistry,
            ObjectProvider<TokenBudgetProperties> tokenBudgetProperties,
            ObjectProvider<PersonalContextRuntime> personalContextRuntime,
            ObjectProvider<AdaptivePersonaService> adaptivePersonaService,
            ObjectProvider<CompanionModeService> companionModeService,
            ObjectProvider<UserModelService> userModelService,
            ObjectProvider<CooperativeChatModelService> cooperativeChatModelService,
            ObjectProvider<DeferredReflectionService> deferredReflectionService,
            ObjectProvider<ObservationPublisher> observationPublisher,
            ObjectProvider<ChatPerformanceMetrics> performanceMetrics,
            ObjectProvider<ChatGenerationProfileSelector> generationProfileSelector,
            BrowserContentService browserContentService,
            ObjectProvider<VisionInputService> visionInputService,
            ObjectProvider<PersonalKnowledgeService> personalKnowledgeService,
            ObjectProvider<AcquiredKnowledgeIndex> acquiredKnowledgeIndex,
            ObjectProvider<ConversationSummaryService> conversationSummaryService,
            ObjectProvider<AutonomousResearchService> autonomousResearchService,
            ObjectProvider<ChatExplainabilitySink> explainabilitySink,
            ObjectProvider<ConversationThreadService> conversationThreadService,
            ObjectProvider<StoryIllustrationService> storyIllustrationService,
            ObjectProvider<TurnPlanner> turnPlanner) {
        this.toolCallingRuntime = toolCallingRuntime.getIfAvailable();
        this.toolRequestRouters = toolRequestRouters.orderedStream().toList();
        this.dynamicGenerationOptionsFactory = dynamicGenerationOptionsFactory.getIfAvailable();
        this.modelCapabilityRegistry = modelCapabilityRegistry.getIfAvailable();
        this.tokenBudgetProperties = tokenBudgetProperties.getIfAvailable();
        this.personalContextRuntime = personalContextRuntime.getIfAvailable();
        this.adaptivePersonaService = adaptivePersonaService.getIfAvailable();
        this.companionModeService = companionModeService.getIfAvailable();
        this.userModelService = userModelService.getIfAvailable();
        this.cooperativeChatModelService = cooperativeChatModelService.getIfAvailable();
        this.deferredReflectionService = deferredReflectionService.getIfAvailable();
        this.observationPublisher = observationPublisher.getIfAvailable();
        this.performanceMetrics = performanceMetrics.getIfAvailable();
        this.generationProfileSelector = generationProfileSelector.getIfAvailable();
        this.browserContentService = browserContentService;
        this.visionInputService = visionInputService.getIfAvailable();
        this.personalKnowledgeService = personalKnowledgeService.getIfAvailable();
        this.acquiredKnowledgeIndex = acquiredKnowledgeIndex.getIfAvailable();
        this.conversationSummaryService = conversationSummaryService.getIfAvailable();
        this.autonomousResearchService = autonomousResearchService.getIfAvailable();
        this.explainabilitySink = explainabilitySink.getIfAvailable();
        this.conversationThreadService = conversationThreadService.getIfAvailable();
        this.storyIllustrationService = storyIllustrationService.getIfAvailable();
        this.turnPlanner = turnPlanner.getIfAvailable();
    }

    SpringAiToolCallingRuntime toolCallingRuntime() { return toolCallingRuntime; }
    List<ToolRequestRouter> toolRequestRouters() { return toolRequestRouters; }
    DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory() { return dynamicGenerationOptionsFactory; }
    ModelCapabilityRegistry modelCapabilityRegistry() { return modelCapabilityRegistry; }
    TokenBudgetProperties tokenBudgetProperties() { return tokenBudgetProperties; }
    PersonalContextRuntime personalContextRuntime() { return personalContextRuntime; }
    AdaptivePersonaService adaptivePersonaService() { return adaptivePersonaService; }
    CompanionModeService companionModeService() { return companionModeService; }
    UserModelService userModelService() { return userModelService; }
    CooperativeChatModelService cooperativeChatModelService() { return cooperativeChatModelService; }
    DeferredReflectionService deferredReflectionService() { return deferredReflectionService; }
    ObservationPublisher observationPublisher() { return observationPublisher; }
    ChatPerformanceMetrics performanceMetrics() { return performanceMetrics; }
    ChatGenerationProfileSelector generationProfileSelector() { return generationProfileSelector; }
    BrowserContentService browserContentService() { return browserContentService; }
    VisionInputService visionInputService() { return visionInputService; }
    PersonalKnowledgeService personalKnowledgeService() { return personalKnowledgeService; }
    AcquiredKnowledgeIndex acquiredKnowledgeIndex() { return acquiredKnowledgeIndex; }
    ConversationSummaryService conversationSummaryService() { return conversationSummaryService; }
    AutonomousResearchService autonomousResearchService() { return autonomousResearchService; }
    ChatExplainabilitySink explainabilitySink() { return explainabilitySink; }
    ConversationThreadService conversationThreadService() { return conversationThreadService; }
    StoryIllustrationService storyIllustrationService() { return storyIllustrationService; }
    TurnPlanner turnPlanner() { return turnPlanner; }
}
