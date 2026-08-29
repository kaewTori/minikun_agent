package com.minikun.agent.minikun_agent.api.openai;

import java.time.LocalDate;
import java.util.List;

import org.springframework.ai.chat.prompt.Prompt;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.commands.CommandCatalog;
import com.minikun.context.runtime.PersonalContextRuntime;
import com.minikun.context.runtime.PersonalContextRuntimeResult;
import com.minikun.conversation.continuity.ConversationContinuity;
import com.minikun.conversation.continuity.ConversationContinuityResolver;
import com.minikun.conversation.repair.ConversationRepairAdvice;
import com.minikun.conversation.repair.ConversationRepairAdvisor;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.ContextBudgetSection;
import com.minikun.pcs.DefaultContextBudgetPolicy;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptRequest;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.pcs.model.PromptRole;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.personality.companion.CompanionModeContext;
import com.minikun.personality.model.PersonalUserModel;
import com.minikun.personality.profile.UserModelService;
import com.minikun.personality.runtime.AdaptivePersonaService;
import com.minikun.personality.runtime.ConversationStyleAdvisor;
import com.minikun.tokenbudget.config.TokenBudgetProperties;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import com.minikun.tools.ToolEvidence;
import com.minikun.vision.VisionInput;
import com.minikun.relationship.ConversationThreadService;
import java.util.ArrayList;

import lombok.extern.slf4j.Slf4j;

/** Composes all prompt inputs and applies the optional dynamic context budget. */
@Slf4j
final class ChatPromptFactory {
    private final CharacterSpecification characterSpecification;
    private final PromptComposer promptComposer;
    private final CommandCatalog commandCatalog;
    private final ActiveChatModelProvider activeChatModelProvider;
    private final SpringAiPromptAdapter promptAdapter;
    private final ChatCapabilityFactory capabilityFactory;
    private final ChatGenerationOptionsResolver generationOptionsResolver;
    private final PersonalContextRuntime personalContextRuntime;
    private final DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory;
    private final ModelCapabilityRegistry modelCapabilityRegistry;
    private final TokenBudgetProperties tokenBudgetProperties;
    private final AdaptivePersonaService adaptivePersonaService;
    private final UserModelService userModelService;
    private final ConversationThreadService conversationThreadService;
    private final ChatGenerationProfileSelector generationProfileSelector;
    private final ChatPerformanceMetrics performanceMetrics;
    private final Configuration configuration;
    private final ConversationStyleAdvisor conversationStyleAdvisor = new ConversationStyleAdvisor();
    private final ConversationHistoryWindow conversationHistoryWindow = new ConversationHistoryWindow();
    private final ConversationContinuityResolver continuityResolver = new ConversationContinuityResolver();
    private final ConversationRepairAdvisor conversationRepairAdvisor = new ConversationRepairAdvisor();

    ChatPromptFactory(
            CharacterSpecification characterSpecification,
            PromptComposer promptComposer,
            CommandCatalog commandCatalog,
            ActiveChatModelProvider activeChatModelProvider,
            SpringAiPromptAdapter promptAdapter,
            ChatCapabilityFactory capabilityFactory,
            ChatGenerationOptionsResolver generationOptionsResolver,
            PersonalContextRuntime personalContextRuntime,
            DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory,
            ModelCapabilityRegistry modelCapabilityRegistry,
            TokenBudgetProperties tokenBudgetProperties,
            AdaptivePersonaService adaptivePersonaService,
            UserModelService userModelService,
            ConversationThreadService conversationThreadService,
            ChatGenerationProfileSelector generationProfileSelector,
            ChatPerformanceMetrics performanceMetrics,
            Configuration configuration) {
        this.characterSpecification = characterSpecification;
        this.promptComposer = promptComposer;
        this.commandCatalog = commandCatalog;
        this.activeChatModelProvider = activeChatModelProvider;
        this.promptAdapter = promptAdapter;
        this.capabilityFactory = capabilityFactory;
        this.generationOptionsResolver = generationOptionsResolver;
        this.personalContextRuntime = personalContextRuntime;
        this.dynamicGenerationOptionsFactory = dynamicGenerationOptionsFactory;
        this.modelCapabilityRegistry = modelCapabilityRegistry;
        this.tokenBudgetProperties = tokenBudgetProperties;
        this.adaptivePersonaService = adaptivePersonaService;
        this.userModelService = userModelService;
        this.conversationThreadService = conversationThreadService;
        this.generationProfileSelector = generationProfileSelector;
        this.performanceMetrics = performanceMetrics;
        this.configuration = configuration;
    }

    Result prepare(Request input) {
        ChatCompletionRequest request = input.request();
        ChatMessage userMessage = input.userMessage();
        boolean creativeRequest = isCreativeConversation(input);
        ChatGenerationOptionsResolver.Result generation = generationOptionsResolver.resolve(
                request,
                userMessage.content(),
                input.interactionMode() == null ? null : input.interactionMode().mode(),
                input.verifiedToolResult() != null
                        || input.visionInput() != null && input.visionInput().hasImages(),
                creativeRequest,
                configuration.generationMaxTokens(),
                configuration.generationTemperature(),
                generationProfileSelector,
                performanceMetrics);
        long effectiveContextBudgetCharacters = creativeRequest
                ? Math.max(configuration.contextBudgetCharacters(),
                        configuration.creativeContextBudgetCharacters())
                : configuration.contextBudgetCharacters();
        String runtime = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("Current date: " + LocalDate.now());
        long conversationBudget = new DefaultContextBudgetPolicy()
                .allocate(effectiveContextBudgetCharacters)
                .allocation(ContextBudgetSection.CONVERSATION);
        String summarySection = summarySection(input.conversationSummary(), conversationBudget);
        long recentConversationBudget = Math.max(0L, conversationBudget
                - summarySection.length()
                - (summarySection.isBlank() ? 0 : "\n\nRecent turns:\n".length()));
        ConversationHistoryWindow.Result conversationWindow = conversationHistoryWindow.build(
                request, input.history(), this::isCommandMessage, recentConversationBudget,
                input.recentMessageLimit());
        String conversation = combineConversation(summarySection, conversationWindow.content());
        String conversationSystemContent = conversationSystemContent(
                summarySection, conversationWindow.omittedMessages());
        List<PromptMessage> conversationMessages = conversationWindow.messages().stream()
                .map(message -> new PromptMessage(
                        "assistant".equalsIgnoreCase(message.role())
                                ? PromptRole.ASSISTANT : PromptRole.USER,
                        message.content()))
                .toList();
        log.debug("process=conversation_window event=selected source={} input_messages={} "
                        + "selected_messages={} omitted_messages={} summary_chars={} "
                        + "selected_chars={} budget_chars={}",
                conversationWindow.source(), conversationWindow.inputMessages(),
                conversationWindow.selectedMessages(), conversationWindow.omittedMessages(),
                summarySection.length(), conversation.length(), conversationBudget);

        boolean factFirstToolTurn = isVerifiedToolResult(input.verifiedToolResult());
        String conversationContent = conversation;
        SearchSelectionSignals promptSearchSignals = factFirstToolTurn
                ? SearchSelectionSignals.EMPTY : input.knowledgeSelection().searchSignals();
        SearchContext promptSearchContext = factFirstToolTurn
                ? SearchContext.EMPTY : input.knowledgeSelection().searchContext();
        KnowledgeSelection promptKnowledgeSelection = factFirstToolTurn
                ? KnowledgeSelection.EMPTY : input.knowledgeSelection().selection();
        KnowledgeConsolidation promptKnowledgeConsolidation = factFirstToolTurn
                ? KnowledgeConsolidation.EMPTY : input.knowledgeSelection().consolidation();
        KnowledgeContext promptKnowledge = factFirstToolTurn
                ? KnowledgeContext.empty() : input.knowledgeSelection().selection().knowledgeContext();
        var conversationStyle = conversationStyleAdvisor.advise(
                userMessage.content(), conversationWindow.messages());
        var personaSignals = adaptivePersonaService == null
                ? com.minikun.personality.signal.PersonaSelectionSignals.EMPTY
                : adaptivePersonaService.evaluate(
                        input.ownerId(), userMessage.content(), promptSearchSignals.searchRequested(),
                        configuration.nativeToolsAvailable() || input.verifiedToolResult() != null,
                        false, conversationStyle.mood()).signals();
        PersonalUserModel userModel = userModelService == null
                ? PersonalUserModel.EMPTY
                : safeUserModel(input.ownerId());
        PromptRequest promptRequest = new PromptRequest(
                characterSpecification,
                new RuntimeContext(runtime),
                conversationContent.isBlank() && conversationMessages.isEmpty()
                        ? null
                        : new ConversationContext(
                                conversationContent, conversationSystemContent, conversationMessages),
                promptKnowledge,
                capabilities(input, conversationStyle.instruction(), creativeRequest),
                new com.minikun.pcs.model.UserMessage(userMessage.content()),
                promptSearchSignals,
                promptSearchContext,
                promptKnowledgeSelection,
                promptKnowledgeConsolidation,
                null,
                personaSignals,
                userModel);
        PersonalContextRuntime contextRuntime = personalContextRuntime;
        if (contextRuntime == null
                && configuration.dynamicTokenBudgetEnabled()
                && dynamicGenerationOptionsFactory != null) {
            contextRuntime = new PersonalContextRuntime(promptComposer, dynamicGenerationOptionsFactory);
        }
        if (contextRuntime == null) {
            return new Result(
                    adapt(promptComposer.compose(promptRequest), generation.options(), input.visionInput()),
                    generation.profile());
        }
        ModelCapability capability = configuration.dynamicTokenBudgetEnabled()
                && modelCapabilityRegistry != null
                        ? modelCapabilityRegistry.get(activeChatModelProvider.get().id())
                        : null;
        long configuredReservedOutputTokens = tokenBudgetProperties == null
                ? configuration.reservedOutputTokens()
                : tokenBudgetProperties.reservedOutputTokens();
        PersonalContextRuntimeResult result = contextRuntime.prepare(
                promptRequest,
                generation.options(),
                capability,
                configuration.dynamicTokenBudgetEnabled(),
                effectiveContextBudgetCharacters,
                configuredReservedOutputTokens,
                configuration.generationMaxTokens());
        log.debug("process=context_runtime event=prepared requested_context_chars={} final_prompt_chars={} "
                        + "recovery_attempts={} estimated_input_tokens={} allocated_output_tokens={}",
                result.snapshot().requestedContextCharacters(),
                result.snapshot().finalPromptCharacters(),
                result.snapshot().recoveryAttempts(),
                result.snapshot().estimatedInputTokens(),
                result.snapshot().allocatedOutputTokens());
        return new Result(adapt(result.prompt(), result.generationOptions(), input.visionInput()), generation.profile());
    }

    private Prompt adapt(
            com.minikun.pcs.model.Prompt prompt,
            GenerationOptions generationOptions,
            VisionInput visionInput) {
        Prompt adapted = promptAdapter.adapt(
                prompt,
                generationOptions,
                activeChatModelProvider.get().id(),
                configuration.configuredModel(),
                configuration.ollamaContextSize());
        return promptAdapter.withVisionMedia(adapted, visionInput);
    }

    private PersonalUserModel safeUserModel(String ownerId) {
        try {
            return userModelService.snapshot(ownerId);
        } catch (RuntimeException exception) {
            log.warn("User model snapshot failed; continuing without personal context owner_id={}",
                    ownerId, exception);
            return PersonalUserModel.EMPTY;
        }
    }

    private List<CapabilityInstruction> capabilities(
            Request input, String conversationStyleInstruction, boolean creativeRequest) {
        List<CapabilityInstruction> values = new ArrayList<>(capabilityFactory.create(
                input.userMessage().content(), input.knowledgeSelection(), input.imageAwareness(),
                input.verifiedToolResult(), input.visionInput(), input.interactionMode(),
                conversationStyleInstruction, configuration.nativeToolsAvailable(), creativeRequest));
        ConversationContinuity continuity = continuityResolver.resolve(
                input.userMessage().content(), continuityContext(input.history()));
        if (continuity.followUp()) {
            values.add(new CapabilityInstruction(
                    "Turn continuity", continuity.promptInstruction(), true));
            ConversationRepairAdvice repair = conversationRepairAdvisor.advise(
                    continuity,
                    input.knowledgeSelection().selection(),
                    input.knowledgeSelection().searchContext());
            if (repair.required()) {
                values.add(new CapabilityInstruction(
                        "Conversation repair", repair.instruction(), true));
            }
            log.debug("process=conversation_continuity event=resolved confidence={} repair_reason={}",
                    continuity.confidence(), repair.reason());
        }
        if (conversationThreadService != null) {
            try {
                String context = conversationThreadService.promptContext(
                        input.ownerId(), java.util.Objects.requireNonNullElse(
                                input.request().conversation_id(), ""), input.userMessage().content());
                if (hasText(context)) {
                    values.add(new CapabilityInstruction("Relationship continuity", context, true));
                }
            } catch (RuntimeException exception) {
                log.warn("Conversation thread context failed; continuing without it", exception);
            }
        }
        return List.copyOf(values);
    }

    private String continuityContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        return history.stream()
                .filter(java.util.Objects::nonNull)
                .filter(message -> hasText(message.content()))
                .filter(message -> !isCommandMessage(message.content()))
                .skip(Math.max(0, history.size() - 6L))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private boolean isVerifiedToolResult(ToolEvidence evidence) {
        return evidence != null && evidence.success() && hasText(evidence.content());
    }

    private String summarySection(String summary, long conversationBudget) {
        if (!hasText(summary) || conversationBudget <= 0) {
            return "";
        }
        String label = "Rolling summary (older context; recent turns below take precedence):\n";
        int maximumSectionCharacters = (int) Math.min(
                conversationBudget * 40L / 100L, Integer.MAX_VALUE);
        int maximumContentCharacters = maximumSectionCharacters - label.length();
        if (maximumContentCharacters <= 0) {
            return "";
        }
        String normalized = summary.strip();
        String bounded = normalized.length() <= maximumContentCharacters
                ? normalized
                : normalized.substring(0, maximumContentCharacters).stripTrailing();
        return label + bounded;
    }

    private String combineConversation(String summarySection, String recentConversation) {
        if (summarySection.isBlank()) {
            return recentConversation;
        }
        if (recentConversation.isBlank()) {
            return summarySection;
        }
        return summarySection + "\n\nRecent turns:\n" + recentConversation;
    }

    private String conversationSystemContent(String summarySection, int omittedMessages) {
        if (omittedMessages <= 0) {
            return summarySection;
        }
        String omission = "[Earlier conversation omitted]\n"
                + "Some earlier verbatim conversation messages were omitted; "
                + "use the rolling summary when available and treat recent role messages as authoritative.";
        return summarySection.isBlank() ? omission : summarySection + "\n\n" + omission;
    }

    private boolean isCommandMessage(String content) {
        return hasText(content) && commandCatalog.findExact(content.trim()).isPresent();
    }

    private boolean isCreativeConversation(Request input) {
        if (generationProfileSelector == null) {
            return false;
        }
        if (generationProfileSelector.isCreativeRequest(input.userMessage().content())
                || generationProfileSelector.isCreativeRequest(input.conversationSummary())) {
            return true;
        }
        boolean requestCreative = input.request().messages().stream()
                .filter(message -> !"system".equalsIgnoreCase(message.role()))
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .anyMatch(generationProfileSelector::isCreativeRequest);
        if (requestCreative) {
            return true;
        }
        return input.history() != null && input.history().stream()
                .filter(java.util.Objects::nonNull)
                .map(ChatMessage::content)
                .filter(this::hasText)
                .anyMatch(generationProfileSelector::isCreativeRequest);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record Request(
            ChatCompletionRequest request,
            ChatMessage userMessage,
            List<ChatMessage> history,
            String conversationSummary,
            int recentMessageLimit,
            ChatKnowledgeSelection knowledgeSelection,
            ImageAwareness imageAwareness,
            ToolEvidence verifiedToolResult,
            String ownerId,
            CompanionModeContext interactionMode,
            VisionInput visionInput) {
    }

    record Result(Prompt prompt, String generationProfile) {
    }

    record Configuration(
            boolean nativeToolsAvailable,
            boolean dynamicTokenBudgetEnabled,
            long reservedOutputTokens,
            long contextBudgetCharacters,
            long creativeContextBudgetCharacters,
            int generationMaxTokens,
            double generationTemperature,
            int ollamaContextSize,
            String configuredModel) {
    }
}
