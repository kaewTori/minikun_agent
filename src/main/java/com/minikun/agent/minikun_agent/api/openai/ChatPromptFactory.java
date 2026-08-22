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
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.GenerationOptions;
import com.minikun.model.capability.ModelCapability;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptException;
import com.minikun.pcs.PromptRequest;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.personality.companion.CompanionModeContext;
import com.minikun.personality.model.MoodSnapshot;
import com.minikun.personality.model.PersonalUserModel;
import com.minikun.personality.profile.UserModelService;
import com.minikun.personality.runtime.AdaptivePersonaService;
import com.minikun.tokenbudget.config.TokenBudgetProperties;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import com.minikun.tools.ToolEvidence;
import com.minikun.vision.VisionInput;

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
    private final ChatGenerationProfileSelector generationProfileSelector;
    private final ChatPerformanceMetrics performanceMetrics;
    private final Configuration configuration;

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
        this.generationProfileSelector = generationProfileSelector;
        this.performanceMetrics = performanceMetrics;
        this.configuration = configuration;
    }

    Result prepare(Request input) {
        ChatCompletionRequest request = input.request();
        ChatMessage userMessage = input.userMessage();
        String runtime = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("Current date: " + LocalDate.now());
        String conversation = input.history().stream()
                .filter(message -> !"system".equals(message.role()))
                .filter(message -> !isCommandMessage(message.content()))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");

        boolean factFirstToolTurn = isVerifiedToolResult(input.verifiedToolResult());
        String conversationContent = factFirstToolTurn
                ? ""
                : conversation.isBlank() ? requestConversation(request) : conversation;
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
        var personaSignals = adaptivePersonaService == null
                ? com.minikun.personality.signal.PersonaSelectionSignals.EMPTY
                : adaptivePersonaService.evaluate(
                        input.ownerId(), userMessage.content(), promptSearchSignals.searchRequested(),
                        configuration.nativeToolsAvailable() || input.verifiedToolResult() != null,
                        false, MoodSnapshot.DEFAULT).signals();
        PersonalUserModel userModel = userModelService == null
                ? PersonalUserModel.EMPTY
                : safeUserModel(input.ownerId());
        PromptRequest promptRequest = new PromptRequest(
                characterSpecification,
                new RuntimeContext(runtime),
                conversationContent.isBlank() ? null : new ConversationContext(conversationContent),
                promptKnowledge,
                capabilityFactory.create(
                        input.knowledgeSelection(), input.imageAwareness(), input.verifiedToolResult(),
                        input.visionInput(), input.interactionMode(), configuration.nativeToolsAvailable()),
                new com.minikun.pcs.model.UserMessage(userMessage.content()),
                promptSearchSignals,
                promptSearchContext,
                promptKnowledgeSelection,
                promptKnowledgeConsolidation,
                null,
                personaSignals,
                userModel);
        ChatGenerationOptionsResolver.Result generation = generationOptionsResolver.resolve(
                request,
                userMessage.content(),
                input.interactionMode() == null ? null : input.interactionMode().mode(),
                input.verifiedToolResult() != null
                        || input.visionInput() != null && input.visionInput().hasImages(),
                configuration.generationMaxTokens(),
                configuration.generationTemperature(),
                generationProfileSelector,
                performanceMetrics);

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
                configuration.contextBudgetCharacters(),
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

    private String requestConversation(ChatCompletionRequest request) {
        int currentUserIndex = lastUserMessageIndex(request);
        return java.util.stream.IntStream.range(0, currentUserIndex)
                .mapToObj(request.messages()::get)
                .filter(message -> !"system".equals(message.role()))
                .filter(message -> hasText(message.content()))
                .filter(message -> !isCommandMessage(message.content()))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private int lastUserMessageIndex(ChatCompletionRequest request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            var message = request.messages().get(index);
            if ("user".equals(message.role()) && (hasText(message.content()) || message.hasImageContent())) {
                return index;
            }
        }
        throw new PromptException("chat request must contain user text or an image");
    }

    private boolean isVerifiedToolResult(ToolEvidence evidence) {
        return evidence != null && evidence.success() && hasText(evidence.content());
    }

    private boolean isCommandMessage(String content) {
        return hasText(content) && commandCatalog.findExact(content.trim()).isPresent();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    record Request(
            ChatCompletionRequest request,
            ChatMessage userMessage,
            List<ChatMessage> history,
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
            int generationMaxTokens,
            double generationTemperature,
            int ollamaContextSize,
            String configuredModel) {
    }
}
