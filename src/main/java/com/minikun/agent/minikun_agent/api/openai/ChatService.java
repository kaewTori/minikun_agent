package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingResponse;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.agent.minikun_agent.conversation.ConversationSummaryService;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.memory.MemoryRecallService;
import com.minikun.memory.DeferredReflectionService;
import com.minikun.memory.event.ObservationPublisher;
import com.minikun.memory.ReflectionService;
import com.minikun.personality.runtime.AdaptivePersonaService;
import com.minikun.personality.companion.CompanionModeService;
import com.minikun.personality.companion.CompanionModeContext;
import com.minikun.relationship.ConversationThreadService;
import com.minikun.personality.profile.UserModelService;
import com.minikun.knowledge.PersonalKnowledgeService;
import com.minikun.knowledge.acquisition.AcquiredKnowledgeIndex;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ModelUsage;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.model.task.title.TitleGenerationService;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.browser.BrowserContentException;
import com.minikun.browser.BrowserContentService;
import com.minikun.context.runtime.PersonalContextRuntime;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.KnowledgeConsolidationService;
import com.minikun.pcs.KnowledgeSelectionService;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.SearchService;
import com.minikun.search.SearchSelectionSignalMapper;
import com.minikun.research.AutonomousResearchService;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import com.minikun.tools.ToolEvidence;
import com.minikun.tools.ToolRequestRouter;
import com.minikun.tokenbudget.runtime.DynamicGenerationOptionsFactory;
import com.minikun.tokenbudget.config.TokenBudgetProperties;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;
import com.minikun.vision.VisionInput;
import com.minikun.vision.VisionInputException;
import com.minikun.vision.VisionInputService;
import com.minikun.visual.StoryIllustrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import org.slf4j.MDC;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Slf4j
public class ChatService {
    private static final String DEFAULT_CHAT_MODEL = "hf.co/HauhauCS/Gemma4-12B-QAT-Uncensored-HauhauCS-Balanced:Q4_K_M";
    private static final String BLANK_MODEL_RESPONSE = "ขออภัยครับ โมเดลยังไม่ได้ส่งคำตอบที่สมบูรณ์ กรุณาลองสั่งอีกครั้งครับ";
    private final ActiveChatModelProvider activeChatModelProvider;
    private final EmbeddingModel embeddingModel;
    private final ChatTransactionLogger transactionLogger;
    private final ConversationMemoryService conversationMemoryService;
    private final ObjectProvider<MemoryRecallService> memoryRecallService;
    private final CharacterSpecification characterSpecification;
    private final PromptComposer promptComposer;
    private final SearchService searchService;
    private final SearchDecisionService searchDecisionService;
    private final SearchQueryPlanningService searchQueryPlanningService;
    private final SearchContextAwarenessService searchContextAwarenessService;
    private final KnowledgeSelectionService knowledgeSelectionService;
    private final KnowledgeConsolidationService knowledgeConsolidationService;
    private final SearchSelectionSignalMapper searchSelectionSignalMapper;
    private final DiagnosticsService diagnosticsService;
    private final DiagnosticsFormatter diagnosticsFormatter;
    private final DiagnosticsPromptBuilder diagnosticsPromptBuilder;
    private final CommandCatalog commandCatalog;
    private final CommandFormatter commandFormatter;
    private final VersionService versionService;
    private final VersionFormatter versionFormatter;
    private final ModelsService modelsService;
    private final ModelsFormatter modelsFormatter;
    private final CacheService cacheService;
    private final CacheFormatter cacheFormatter;
    private final ObjectProvider<ReflectionService> reflectionService;
    private final TitleGenerationService titleGenerationService;
    private final OpenAiChatResponseFactory responseFactory = new OpenAiChatResponseFactory();
    private final SpringAiPromptAdapter promptAdapter = new SpringAiPromptAdapter();
    private final ChatCapabilityFactory capabilityFactory = new ChatCapabilityFactory();
    private final ChatGenerationOptionsResolver generationOptionsResolver = new ChatGenerationOptionsResolver();
    private final ChatRequestInspector requestInspector = new ChatRequestInspector();
    private SpringAiToolCallingRuntime toolCallingRuntime;
    private List<ToolRequestRouter> toolRequestRouters = List.of();
    private DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory;
    private ModelCapabilityRegistry modelCapabilityRegistry;
    private TokenBudgetProperties tokenBudgetProperties;

    private PersonalContextRuntime personalContextRuntime;

    private AdaptivePersonaService adaptivePersonaService;

    private CompanionModeService companionModeService;

    private UserModelService userModelService;

    private DeferredReflectionService deferredReflectionService;

    private ObservationPublisher observationPublisher;

    private ChatPerformanceMetrics performanceMetrics;

    private ChatGenerationProfileSelector generationProfileSelector;

    private BrowserContentService browserContentService;

    private VisionInputService visionInputService;

    private PersonalKnowledgeService personalKnowledgeService;

    private AcquiredKnowledgeIndex acquiredKnowledgeIndex;

    private ConversationSummaryService conversationSummaryService;

    private ConversationThreadService conversationThreadService;

    private AutonomousResearchService autonomousResearchService;

    private StoryIllustrationService storyIllustrationService;

    private ChatExplainabilityRecorder explainabilityRecorder = new ChatExplainabilityRecorder(null);

    private TurnPlanner turnPlanner = new TurnPlanner(
            new com.minikun.model.CooperationRouter(), (TurnAmbiguityResolver) null);

    @Autowired
    void configureCollaborators(ChatCollaborators collaborators) {
        toolCallingRuntime = collaborators.toolCallingRuntime();
        toolRequestRouters = collaborators.toolRequestRouters();
        dynamicGenerationOptionsFactory = collaborators.dynamicGenerationOptionsFactory();
        modelCapabilityRegistry = collaborators.modelCapabilityRegistry();
        tokenBudgetProperties = collaborators.tokenBudgetProperties();
        personalContextRuntime = collaborators.personalContextRuntime();
        adaptivePersonaService = collaborators.adaptivePersonaService();
        companionModeService = collaborators.companionModeService();
        userModelService = collaborators.userModelService();
        deferredReflectionService = collaborators.deferredReflectionService();
        observationPublisher = collaborators.observationPublisher();
        performanceMetrics = collaborators.performanceMetrics();
        generationProfileSelector = collaborators.generationProfileSelector();
        browserContentService = collaborators.browserContentService();
        visionInputService = collaborators.visionInputService();
        personalKnowledgeService = collaborators.personalKnowledgeService();
        acquiredKnowledgeIndex = collaborators.acquiredKnowledgeIndex();
        conversationSummaryService = collaborators.conversationSummaryService();
        conversationThreadService = collaborators.conversationThreadService();
        autonomousResearchService = collaborators.autonomousResearchService();
        storyIllustrationService = collaborators.storyIllustrationService();
        if (collaborators.turnPlanner() != null) turnPlanner = collaborators.turnPlanner();
        explainabilityRecorder = new ChatExplainabilityRecorder(collaborators.explainabilitySink());
    }

    public ChatService(
            ActiveChatModelProvider activeChatModelProvider,
            EmbeddingModel embeddingModel,
            ChatTransactionLogger transactionLogger,
            ConversationMemoryService conversationMemoryService,
            ObjectProvider<MemoryRecallService> memoryRecallService,
            CharacterSpecification characterSpecification,
            PromptComposer promptComposer,
            SearchService searchService,
            SearchDecisionService searchDecisionService,
            SearchSelectionSignalMapper searchSelectionSignalMapper,
            DiagnosticsService diagnosticsService,
            DiagnosticsFormatter diagnosticsFormatter,
            DiagnosticsPromptBuilder diagnosticsPromptBuilder,
            CommandCatalog commandCatalog,
            CommandFormatter commandFormatter,
            VersionService versionService,
            VersionFormatter versionFormatter,
            ModelsService modelsService,
            ModelsFormatter modelsFormatter,
            CacheService cacheService,
            CacheFormatter cacheFormatter,
            ObjectProvider<ReflectionService> reflectionService) {
        this(
                activeChatModelProvider,
                embeddingModel,
                transactionLogger,
                conversationMemoryService,
                memoryRecallService,
                characterSpecification,
                promptComposer,
                searchService,
                searchDecisionService,
                new com.minikun.search.internal.DefaultSearchQueryPlanningService(),
                new com.minikun.search.internal.DefaultSearchContextAwarenessService(),
                new com.minikun.pcs.DefaultKnowledgeSelectionService(),
                new com.minikun.pcs.DefaultKnowledgeConsolidationService(),
                searchSelectionSignalMapper,
                diagnosticsService,
                diagnosticsFormatter,
                diagnosticsPromptBuilder,
                commandCatalog,
                commandFormatter,
                versionService,
                versionFormatter,
                modelsService,
                modelsFormatter,
                cacheService,
                cacheFormatter,
                reflectionService,
                new TitleGenerationService(messages -> TitleGenerationService.FALLBACK_TITLE));
    }

        public ChatService(
                ActiveChatModelProvider activeChatModelProvider,
                EmbeddingModel embeddingModel,
                ChatTransactionLogger transactionLogger,
                ConversationMemoryService conversationMemoryService,
                ObjectProvider<MemoryRecallService> memoryRecallService,
                CharacterSpecification characterSpecification,
                PromptComposer promptComposer,
                SearchService searchService,
                SearchDecisionService searchDecisionService,
                SearchSelectionSignalMapper searchSelectionSignalMapper,
                DiagnosticsService diagnosticsService,
                DiagnosticsFormatter diagnosticsFormatter,
                DiagnosticsPromptBuilder diagnosticsPromptBuilder,
                CommandCatalog commandCatalog,
                CommandFormatter commandFormatter,
                VersionService versionService,
                VersionFormatter versionFormatter,
                ModelsService modelsService,
                ModelsFormatter modelsFormatter,
                CacheService cacheService,
                CacheFormatter cacheFormatter,
                ObjectProvider<ReflectionService> reflectionService,
                TitleGenerationService titleGenerationService) {
            this(
                    activeChatModelProvider,
                    embeddingModel,
                    transactionLogger,
                    conversationMemoryService,
                    memoryRecallService,
                    characterSpecification,
                    promptComposer,
                    searchService,
                    searchDecisionService,
                    new com.minikun.search.internal.DefaultSearchQueryPlanningService(),
                    new com.minikun.search.internal.DefaultSearchContextAwarenessService(),
                    new com.minikun.pcs.DefaultKnowledgeSelectionService(),
                    new com.minikun.pcs.DefaultKnowledgeConsolidationService(),
                    searchSelectionSignalMapper,
                    diagnosticsService,
                    diagnosticsFormatter,
                    diagnosticsPromptBuilder,
                    commandCatalog,
                    commandFormatter,
                    versionService,
                    versionFormatter,
                    modelsService,
                    modelsFormatter,
                    cacheService,
                    cacheFormatter,
                    reflectionService,
                    titleGenerationService);
        }

    @Value("${minikun.search.enabled:false}")
    private boolean searchEnabled;

    @Value("${minikun.search.timeout:10s}")
    private java.time.Duration searchTimeout;

    @Value("${minikun.diagnostics.conversational.enabled:false}")
    private boolean diagnosticsConversationalEnabled;

    @Value("${minikun.memory.reflection.enabled:false}")
    private boolean reflectionEnabled;

    @Value("${minikun.tools.enabled:false}")
    private boolean toolsEnabled;

    @Value("${minikun.memory.retrieval.max-candidates:10}")
    private int configuredMemoryRetrievalLimit;

    @Value("${minikun.personal-knowledge.auto-recall-limit:5}")
    private int configuredPersonalKnowledgeLimit;

    @Value("${minikun.search.safesearch:true}")
    private boolean searchSafeSearch;

    @Value("${minikun.search.query-planning.enabled:true}")
    private boolean searchQueryPlanningEnabled;

    @Value("${minikun.search.result-limit:8}")
    private int configuredSearchResultLimit;

    @Value("${minikun.research.source-read-limit:3}")
    private int configuredResearchSourceReadLimit;

    @Value("${minikun.research.autonomous.timeout:PT60S}")
    private java.time.Duration configuredAutonomousResearchTimeout = java.time.Duration.ofSeconds(60);

    @Value("${minikun.model.generation.temperature:0.5}")
    private double configuredGenerationTemperature;

    @Value("${minikun.model.generation.max-tokens:4096}")
    private int configuredGenerationMaxTokens;

    @Value("${spring.ai.ollama.chat.options.num-ctx:16384}")
    private int configuredOllamaContextSize = 16_384;

    @Value("${minikun.token-budget.dynamic-enabled:false}")
    private boolean dynamicTokenBudgetEnabled;

    @Value("${minikun.token-budget.reserved-output-tokens:256}")
    private long reservedOutputTokens;

    @Value("${minikun.context-budget.characters:24000}")
    private long contextBudgetCharacters = 24_000L;

    @Value("${minikun.context-budget.creative-characters:40000}")
    private long creativeContextBudgetCharacters = 40_000L;

    @Value("${minikun.model.generation.continuation.enabled:true}")
    private boolean continuationEnabled = true;

    @Value("${minikun.model.generation.continuation.tail-characters:12000}")
    private int continuationTailCharacters = 12_000;

    @Value("${minikun.model.generation.continuation.max-tokens:1024}")
    private int continuationMaxTokens = 1_024;

    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request, ConversationId conversationId) {
        VisionInput visionInput = visionInput(request);
        ChatMessage userMessage = userMessage(request, visionInput);
        var commandResponse = commandHandler().blocking(
                userMessage, requestInspector.publicModelName(),
                diagnosticsConversationalEnabled, modelGateway());
        if (commandResponse != null) {
            log.info("process=command event=completed");
            return commandResponse;
        }
        if (requestInspector.isInternalTitleRequest(request)) {
            return responseForContent(request, titleGenerationService.generateTitle(requestInspector.titleMessages(request)));
        }
        String model = requestInspector.publicModelName();
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
                "chatcmpl-" + UUID.randomUUID(), model, false, request.messages().size());
        long requestStarted = System.nanoTime();
        String requestResult = "success";
        try {
            Optional<ToolEvidence> verifiedToolResult = routeTool(
                    userMessage, conversationId, memoryOwnerId(request, conversationId));
            if (verifiedToolResult.map(ToolEvidence::finalResponse).orElse(false)) {
                String content = verifiedToolResult.get().content();
                String ownerId = memoryOwnerId(request, conversationId);
                turnFinalizer().persistDeterministic(
                        conversationId, userMessage, content, ownerId,
                        transaction.requestId(), requestInspector.shouldPersist(request), false);
                recordExplainability(ownerId, conversationId, transaction.requestId(),
                        ChatExplainabilityRecorder.forTool(verifiedToolResult.get()));
                transaction.success();
                return responseForContent(request, content);
            }
            ChatExecutionContext context;
            try {
                context = prepareChatExecution(
                        request, conversationId, userMessage, transaction, false, verifiedToolResult.orElse(null),
                        visionInput);
            } catch (BrowserContentException exception) {
                String content = ChatBrowserFailureFormatter.format(
                        userMessage.content(), exception, browserContentService);
                turnFinalizer().persistDeterministic(
                        conversationId, userMessage, content, memoryOwnerId(request, conversationId),
                        transaction.requestId(), requestInspector.shouldPersist(request), false);
                recordExplainability(memoryOwnerId(request, conversationId), conversationId, transaction.requestId(),
                        ChatExplainabilityRecorder.browserFailure());
                transaction.success();
                return responseForContent(request, content);
            }
            if (verifiedToolResult.map(ToolEvidence::requiresConfirmation).orElse(false)) {
                String content = verifiedToolResult.get().content();
                turnFinalizer().complete(
                        context.conversationId(), userMessage, context.ownerId(), transaction.requestId(), content,
                        context.persistConversation(), false);
                recordExplainability(context.ownerId(), context.conversationId(), transaction.requestId(),
                        context.explainability());
                transaction.success();
                return responseForContent(request, content);
            }
            // The weather route has already executed the tool. Generate the final
            // answer from the MCS/PCS prompt, without invoking that tool twice.
            long generationStarted = System.nanoTime();
            boolean toolRuntimeRequired = verifiedToolResult.isEmpty() && context.turnPlan().needsTools();
            var response = verifiedToolResult.isPresent() || !toolRuntimeRequired
                    ? modelGateway().chat(
                            context.prompt(), "chat_model", transaction.requestId(), context.conversationId())
                    : modelGateway().chatWithTools(
                            context.prompt(), context.conversationId(), context.ownerId(), transaction.requestId());
            ModelUsage usage = modelUsage(response);
            recordPromptTokenEstimate(context.estimatedInputTokens(), usage.promptTokens());
            String content = response.getResult().getOutput().getText();
            var continued = responseContinuationCoordinator().complete(
                    response, content, context.prompt(), context.generationProfile(), transaction.requestId(),
                    prompt -> modelGateway().chat(prompt, "response_continuation",
                            transaction.requestId(), context.conversationId()));
            content = continued.content();
            if (continued.continuationResponse() != null) {
                usage = addModelUsage(usage, modelUsage(continued.continuationResponse()));
            }
            recordModelUsage(usage, generationStarted);
            recordGenerationOutcome(
                    context.generationProfile(), continued.finishReason(), continued.continuationCount());
            if (!requestInspector.hasText(content)) {
                log.warn("process=model_response event=blank request_id={}", transaction.requestId());
                content = BLANK_MODEL_RESPONSE;
            }
            content = CitationLinker.normalize(content, context.citations());
            StoryIllustrationService.IllustrationResult illustration = illustrate(
                    context, userMessage.content(), content);
            content = illustration.appendNoticeTo(content);
            List<ChatAttachment> attachments = combineAttachments(
                    context.attachments(), illustration.attachments());
            turnFinalizer().complete(
                    context.conversationId(), userMessage, context.ownerId(), transaction.requestId(), content,
                    context.persistConversation(), false);
            recordExplainability(context.ownerId(), context.conversationId(), transaction.requestId(),
                    context.explainability());
            ChatCompletionResponse result = responseFactory.completion(
                    transaction.requestId(), Instant.now().getEpochSecond(), model, content, usage,
                    attachments, continued.finishReason());
            transaction.success();
            return result;
        } catch (RuntimeException exception) {
            requestResult = "error";
            transaction.failed(exception);
            throw exception;
        } finally {
            recordStage("total", requestStarted, requestResult);
        }
    }

    public Flux<String> chatCompletionStream(ChatCompletionRequest request, ConversationId conversationId) {
        VisionInput visionInput = visionInput(request);
        ChatMessage userMessage = userMessage(request, visionInput);
        String command = commandHandler().content(userMessage);
        if (command != null) {
            return commandStream(command, request);
        }
        if (requestInspector.isInternalTitleRequest(request)) {
            return commandStream(titleGenerationService.generateTitle(requestInspector.titleMessages(request)), request);
        }
        String model = requestInspector.publicModelName();
        String requestId = "chatcmpl-" + UUID.randomUUID();
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
                requestId, model, true, request.messages().size());
        long requestStarted = System.nanoTime();
        String id = requestId;
        long created = Instant.now().getEpochSecond();
        StringBuilder assistantContent = new StringBuilder();
        AtomicReference<ModelUsage> modelUsage = new AtomicReference<>(ModelUsage.empty());
        AtomicBoolean firstTokenRecorded = new AtomicBoolean();
        AtomicBoolean promptEstimateRecorded = new AtomicBoolean();

        Optional<ToolEvidence> verifiedToolResult = routeTool(
                userMessage, conversationId, memoryOwnerId(request, conversationId));
        if (verifiedToolResult.map(ToolEvidence::finalResponse).orElse(false)) {
            String content = verifiedToolResult.get().content();
            String ownerId = memoryOwnerId(request, conversationId);
            turnFinalizer().persistDeterministic(
                    conversationId, userMessage, content, ownerId,
                    transaction.requestId(), requestInspector.shouldPersist(request), true);
            recordExplainability(ownerId, conversationId, transaction.requestId(),
                    ChatExplainabilityRecorder.forTool(verifiedToolResult.get()));
            transaction.success();
            return commandStream(content, request);
        }

        ChatExecutionContext context;
        try {
            context = prepareChatExecution(
                request, conversationId, userMessage, transaction, true, verifiedToolResult.orElse(null),
                visionInput);
        } catch (BrowserContentException exception) {
            String content = ChatBrowserFailureFormatter.format(
                    userMessage.content(), exception, browserContentService);
            turnFinalizer().persistDeterministic(
                    conversationId, userMessage, content, memoryOwnerId(request, conversationId),
                    transaction.requestId(), requestInspector.shouldPersist(request), true);
            recordExplainability(memoryOwnerId(request, conversationId), conversationId, transaction.requestId(),
                    ChatExplainabilityRecorder.browserFailure());
            transaction.success();
            return commandStream(content, request);
        }
        if (verifiedToolResult.map(ToolEvidence::requiresConfirmation).orElse(false)) {
            String content = verifiedToolResult.get().content();
            turnFinalizer().complete(
                    context.conversationId(), userMessage, context.ownerId(), transaction.requestId(), content,
                    context.persistConversation(), true);
            recordExplainability(context.ownerId(), context.conversationId(), transaction.requestId(),
                    context.explainability());
            transaction.success();
            return commandStream(content, request);
        }
        long modelStarted = System.nanoTime();
        String traceId = MDC.get("trace_id");
        CitationLinker.Stream citationStream = CitationLinker.stream(context.citations());
        boolean toolRuntimeRequired = verifiedToolResult.isEmpty() && context.turnPlan().needsTools();
        Flux<ChatResponse> primaryResponses = verifiedToolResult.isPresent()
            ? modelGateway().stream(context.prompt(), context.conversationId())
            : toolsEnabled && toolCallingRuntime != null && toolRuntimeRequired
            ? Flux.defer(() -> Flux.just(modelGateway().reviewToolRuntimeDraft(
                    context.prompt(), context.conversationId(), context.ownerId())))
            : modelGateway().stream(context.prompt(), context.conversationId());
        var continued = responseContinuationCoordinator().stream(
                primaryResponses, assistantContent, context.prompt(), context.generationProfile(), requestId,
                prompt -> modelGateway().stream(prompt, context.conversationId()),
                response -> captureModelUsage(modelUsage, response),
                response -> modelUsage.set(addModelUsage(modelUsage.get(), modelUsage(response))));
        Flux<ChatResponse> modelResponses = continued.responses();
        Flux<String> modelChunks = modelResponses
                .doOnNext(response -> recordPromptTokenEstimate(
                        context.estimatedInputTokens(), modelUsage(response).promptTokens(), promptEstimateRecorded))
                .doOnNext(response -> recordFirstToken(
                        response, firstTokenRecorded, requestStarted, modelStarted))
                .doOnNext(response -> appendAssistantText(assistantContent, response))
                .map(response -> responseFactory.contentChunk(
                        citationStream.accept(response.getResult().getOutput().getText()), id, created, model))
                .filter(chunk -> !chunk.isBlank());
        Flux<String> chunks = Flux.concat(modelChunks, Flux.defer(() -> {
                    String tail = citationStream.finish();
                    String chunk = responseFactory.contentChunk(tail, id, created, model);
                    return chunk.isBlank() ? Flux.empty() : Flux.just(chunk);
                }))
                .doFinally(signal -> ChatTraceScope.run(traceId, () -> {
                    logModelDuration("chat_model_stream", modelStarted, requestId);
                    recordStage("model", modelStarted, signalResult(signal));
                    recordModelUsage(modelUsage.get(), modelStarted);
                }));
        return Flux.concat(
                Flux.just(responseFactory.initialChunk(id, created, model, context.attachments())),
                chunks,
                Flux.defer(() -> {
                    if (requestInspector.hasText(assistantContent.toString())) return Flux.empty();
                    assistantContent.append(BLANK_MODEL_RESPONSE);
                    return Flux.just(responseFactory.contentChunk(BLANK_MODEL_RESPONSE, id, created, model));
                }),
                Flux.defer(() -> {
                    var illustration = illustrate(context, userMessage.content(), assistantContent.toString());
                    assistantContent.append(illustration.notice());
                    return Flux.fromIterable(responseFactory.illustrationChunks(
                            id, created, model, illustration));
                }).subscribeOn(Schedulers.boundedElastic()),
                Flux.defer(() -> {
                    String usageChunk = responseFactory.usageChunk(modelUsage.get(), id, created, model);
                    return usageChunk.isEmpty() ? Flux.empty() : Flux.just(usageChunk);
                }),
                Flux.defer(() -> Flux.just(responseFactory.stopChunk(
                        id, created, model, continued.finishReason().get()))),
                Flux.just("[DONE]"))
                .doOnComplete(() -> {
                    recordGenerationOutcome(context.generationProfile(),
                            continued.finishReason().get(), continued.continuationCount().get());
                    if (!assistantContent.isEmpty()) {
                        String finalizedContent = CitationLinker.normalize(
                                assistantContent.toString(), context.citations());
                        turnFinalizer().complete(
                                context.conversationId(), userMessage, context.ownerId(), transaction.requestId(),
                                finalizedContent, context.persistConversation(), true);
                        recordExplainability(context.ownerId(), context.conversationId(), transaction.requestId(),
                                context.explainability());
                    }
                    ChatTraceScope.run(traceId, transaction::success);
                })
                .doOnError(exception -> ChatTraceScope.run(traceId, () -> transaction.failed(exception)))
                .doOnCancel(() -> ChatTraceScope.run(traceId, transaction::cancelled))
                .doFinally(signal -> recordStage("total", requestStarted, signalResult(signal)));
    }

    private ChatExecutionContext prepareChatExecution(
            ChatCompletionRequest request,
            ConversationId conversationId,
            ChatMessage userMessage,
            ChatTransactionLogger.Transaction transaction,
            boolean streaming,
            ToolEvidence verifiedToolResult,
            VisionInput visionInput) {
        boolean persistConversation = requestInspector.shouldPersist(request);
        String ownerId = memoryOwnerId(request, conversationId);
        CompletableFuture<CompanionModeContext> interactionModeFuture = CompletableFuture.supplyAsync(
                () -> companionModeFor(ownerId, conversationId, userMessage.content()));
        CompletableFuture<String> summaryFuture = CompletableFuture.supplyAsync(
                () -> conversationSummary(ownerId, conversationId));
        long conversationStarted = System.nanoTime();
        List<ChatMessage> history;
        String conversationResult = "success";
        try {
            history = conversationMemoryService.load(conversationId);
            log.info("process=conversation_history event=loaded messages={}{}", history.size(),
                    streaming ? " stream=true" : "");
            log.debug("process=conversation event=turn_persistence_deferred{}",
                    streaming ? " stream=true" : "");
        } catch (RuntimeException exception) {
            conversationResult = "error";
            throw exception;
        } finally {
            recordStage("conversation", conversationStarted, conversationResult);
        }
        CompanionModeContext interactionMode = interactionModeFuture.join();
        String conversationSummary = summaryFuture.join();
        String classifierContext = classifierContext(history);
        TurnPlan turnPlan = turnPlanner.plan(userMessage.content(), classifierContext, interactionMode,
                visionInput != null && visionInput.hasImages(), verifiedToolResult,
                toolsEnabled && toolCallingRuntime != null);
        int recentMessageLimit = conversationSummary.isBlank() || conversationSummaryService == null
                ? Integer.MAX_VALUE : conversationSummaryService.recentMessageLimit();
        ChatKnowledgeSelection knowledgeSelection = knowledgeResolver().resolve(
                new ChatKnowledgeResolver.Request(
                        userMessage.content(), transaction.requestId(), conversationId, ownerId,
                        hasConversationContext(history, request), classifierContext, turnPlan));
        turnPlan = turnPlan.refine(knowledgeSelection);
        if (performanceMetrics != null) performanceMetrics.turnPlan(turnPlan);
        ChatImagePreparer.Result preparedImages = ChatImagePreparer.prepare(knowledgeSelection);
        long promptStarted = System.nanoTime();
        ChatPromptFactory.Result preparedPrompt;
        String promptResult = "success";
        try {
            preparedPrompt = promptFactory().prepare(new ChatPromptFactory.Request(
                    request,
                    userMessage,
                    history,
                    conversationSummary,
                    recentMessageLimit,
                    knowledgeSelection,
                    preparedImages.awareness(),
                    verifiedToolResult,
                    ownerId,
                    conversationId.value(),
                    interactionMode,
                    visionInput,
                    shouldIllustrate(turnPlan),
                    turnPlan));
        } catch (RuntimeException exception) {
            promptResult = "error";
            throw exception;
        } finally {
            recordStage("prompt", promptStarted, promptResult);
        }
        log.info("process=prompt event=composed{}", streaming ? " stream=true" : "");
        return new ChatExecutionContext(
                preparedPrompt.prompt(), conversationId, persistConversation, ownerId,
                preparedImages.attachments(), preparedPrompt.generationProfile(),
                preparedPrompt.estimatedInputTokens(),
                explainabilityRecorder.context(
                        knowledgeSelection, verifiedToolResult, preparedPrompt.generationProfile(), turnPlan),
                turnPlan,
                CitationLinker.from(knowledgeSelection.selection()));
    }

    private ChatCommandHandler commandHandler() {
        return new ChatCommandHandler(
                commandCatalog,
                commandFormatter,
                diagnosticsService,
                diagnosticsFormatter,
                diagnosticsPromptBuilder,
                versionService,
                versionFormatter,
                modelsService,
                modelsFormatter,
                cacheService,
                cacheFormatter,
                promptAdapter,
                responseFactory);
    }

    private Optional<ToolEvidence> routeTool(
            ChatMessage userMessage, ConversationId conversationId, String ownerId) {
        if (!toolsEnabled || toolRequestRouters == null || toolRequestRouters.isEmpty()) {
            return Optional.empty();
        }
        for (ToolRequestRouter router : toolRequestRouters) {
            Optional<ToolEvidence> result = router.route(userMessage.content(), conversationId, ownerId);
            if (result.isPresent()) {
                ToolEvidence evidence = result.get();
                log.info("process=tool_route event=completed tool={} success={}",
                        evidence.toolName(), evidence.success());
                return result;
            }
        }
        return Optional.empty();
    }

    private ChatTurnFinalizer turnFinalizer() {
        return new ChatTurnFinalizer(
                conversationMemoryService,
                reflectionService,
                deferredReflectionService,
                observationPublisher,
                conversationSummaryService,
                conversationThreadService,
                reflectionEnabled);
    }

    private ChatModelGateway modelGateway() {
        return new ChatModelGateway(
                activeChatModelProvider,
                toolCallingRuntime,
                performanceMetrics,
                toolsEnabled);
    }

    private ChatCompletionResponse responseForContent(ChatCompletionRequest request, String content) {
        String model = requestInspector.publicModelName();
        return responseFactory.contentCompletion(model, content);
    }

    private Flux<String> commandStream(String content, ChatCompletionRequest request) {
        String model = requestInspector.publicModelName();
        return Flux.fromIterable(responseFactory.contentStream(model, content));
    }

    private ModelUsage modelUsage(ChatResponse response) {
        return responseFactory.modelUsage(response);
    }

    private ModelUsage addModelUsage(ModelUsage first, ModelUsage second) {
        ModelUsage left = first == null ? ModelUsage.empty() : first;
        ModelUsage right = second == null ? ModelUsage.empty() : second;
        return new ModelUsage(
                Math.addExact(left.promptTokens(), right.promptTokens()),
                Math.addExact(left.completionTokens(), right.completionTokens()));
    }

    private ChatResponseContinuationCoordinator responseContinuationCoordinator() {
        return new ChatResponseContinuationCoordinator(
                new ResponseContinuation(
                        continuationEnabled, continuationTailCharacters, continuationMaxTokens),
                performanceMetrics);
    }

    private void recordGenerationOutcome(String profile, String finishReason, int continuationCount) {
        if (performanceMetrics != null) {
            performanceMetrics.generationOutcome(profile, finishReason, continuationCount);
        }
    }

    private void captureModelUsage(AtomicReference<ModelUsage> target, ChatResponse response) {
        ModelUsage usage = modelUsage(response);
        if (!usage.equals(ModelUsage.empty())) {
            target.set(usage);
        }
    }

    public ModelsResponse listModels() {
        return new ModelsResponse("list", List.of(
                new ModelsResponse.Model(
                        requestInspector.publicModelName(), "model", Instant.now().getEpochSecond(), "minikun")));
    }

    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        long started = System.nanoTime();
        List<EmbeddingResponse.Data> data = java.util.stream.IntStream.range(0, request.texts().size())
                .mapToObj(index -> new EmbeddingResponse.Data(
                        "embedding", toFloatList(embeddingModel.embed(request.texts().get(index))), index))
                .toList();
        EmbeddingResponse result = new EmbeddingResponse("list", data,
                requestInspector.publicModelName(),
                new EmbeddingResponse.Usage(0, 0));
        logModelDuration("embedding_model", started, null);
        return result;
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new java.util.ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private String memoryOwnerId(ChatCompletionRequest request, ConversationId conversationId) {
        String requestedOwnerId = request.owner_id();
        if (requestedOwnerId != null && !requestedOwnerId.isBlank()) {
            return requestedOwnerId;
        }
        return "default";
    }

    private ChatKnowledgeResolver knowledgeResolver() {
        return new ChatKnowledgeResolver(
                memoryRecallService,
                personalKnowledgeService,
                acquiredKnowledgeIndex,
                searchService,
                searchDecisionService,
                searchQueryPlanningService,
                searchContextAwarenessService,
                knowledgeSelectionService,
                knowledgeConsolidationService,
                searchSelectionSignalMapper,
                browserContentService,
                autonomousResearchService,
                performanceMetrics,
                new ChatKnowledgeResolver.Configuration(
                        searchEnabled,
                        searchTimeout,
                        searchSafeSearch,
                        searchQueryPlanningEnabled,
                        configuredSearchResultLimit,
                        configuredMemoryRetrievalLimit,
                        configuredPersonalKnowledgeLimit,
                        configuredResearchSourceReadLimit,
                        configuredAutonomousResearchTimeout));
    }

    private ChatPromptFactory promptFactory() {
        return new ChatPromptFactory(
                characterSpecification,
                promptComposer,
                commandCatalog,
                activeChatModelProvider,
                promptAdapter,
                capabilityFactory,
                generationOptionsResolver,
                personalContextRuntime,
                dynamicGenerationOptionsFactory,
                modelCapabilityRegistry,
                tokenBudgetProperties,
                adaptivePersonaService,
                userModelService,
                conversationThreadService,
                generationProfileSelector,
                performanceMetrics,
                new ChatPromptFactory.Configuration(
                        toolsEnabled && toolCallingRuntime != null,
                        dynamicTokenBudgetEnabled,
                        reservedOutputTokens,
                        contextBudgetCharacters,
                        creativeContextBudgetCharacters,
                        configuredGenerationMaxTokens,
                        configuredGenerationTemperature,
                        configuredOllamaContextSize,
                        effectiveConfiguredChatModel()));
    }

    private CompanionModeContext companionModeFor(String ownerId, ConversationId conversationId, String message) {
        if (companionModeService == null || conversationId == null) return null;
        try {
            return companionModeService.evaluate(ownerId, conversationId.value(), message).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("Companion mode lookup failed; continuing in balanced mode", exception);
            return null;
        }
    }
    private String conversationSummary(String ownerId, ConversationId conversationId) {
        if (conversationSummaryService == null) return "";
        try {
            return conversationSummaryService.summary(ownerId, conversationId).orElse("");
        } catch (RuntimeException exception) {
            log.warn("Conversation summary lookup failed; continuing with recent turns", exception);
            return "";
        }
    }
    private void recordExplainability(String ownerId, ConversationId conversationId, String responseId,
            ChatExplainabilityRecorder.Context context) {
        explainabilityRecorder.record(ownerId, conversationId, responseId, context);
    }
    private record ChatExecutionContext(Prompt prompt, ConversationId conversationId, boolean persistConversation,
            String ownerId, List<ChatAttachment> attachments, String generationProfile, Long estimatedInputTokens,
            ChatExplainabilityRecorder.Context explainability, TurnPlan turnPlan, CitationLinker.Context citations) { }

    private boolean hasConversationContext(
            List<ChatMessage> history, ChatCompletionRequest request) {
        boolean hasHistory = history.stream()
                .anyMatch(message -> !requestInspector.isCommand(message.content(), commandCatalog));
        long requestConversationMessages = request.messages().stream()
                .filter(message -> !"system".equals(message.role()))
                .filter(message -> requestInspector.hasText(message.content()))
                .count();
        return hasHistory || requestConversationMessages > 1;
    }

    private String classifierContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        return history.stream()
                .filter(message -> message != null && !requestInspector.isSystemMessage(message)
                        && requestInspector.hasText(message.content())
                        && !requestInspector.isCommand(message.content(), commandCatalog))
                .skip(Math.max(0, history.size() - 6L))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String effectiveConfiguredChatModel() {
        String chatModel = modelsService.chatModel();
        return chatModel == null || chatModel.isBlank()
                ? DEFAULT_CHAT_MODEL
            : chatModel;
    }

    private void appendAssistantText(StringBuilder content, ChatResponse response) {
        String text = response.getResult().getOutput().getText();
        if (text != null) content.append(text);
    }

    private boolean shouldIllustrate(TurnPlan turnPlan) {
        return storyIllustrationService != null && turnPlan != null && turnPlan.imageOutput();
    }

    private StoryIllustrationService.IllustrationResult illustrate(
            ChatExecutionContext context, String userMessage, String assistantContent) {
        if (storyIllustrationService == null) return new StoryIllustrationService.IllustrationResult(List.of(), "");
        return storyIllustrationService.illustrate(context.ownerId(), context.conversationId().value(),
                userMessage, assistantContent,
                context.turnPlan() != null && context.turnPlan().imageOutput());
    }
    private List<ChatAttachment> combineAttachments(
            List<ChatAttachment> existing, List<ChatAttachment> generated) {
        if (generated == null || generated.isEmpty()) {
            return existing == null ? List.of() : existing;
        }
        java.util.ArrayList<ChatAttachment> combined = new java.util.ArrayList<>();
        if (existing != null) {
            combined.addAll(existing);
        }
        combined.addAll(generated);
        return List.copyOf(combined);
    }

    private void recordFirstToken(
            ChatResponse response,
            AtomicBoolean recorded,
            long requestStarted,
            long modelStarted) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || !requestInspector.hasText(response.getResult().getOutput().getText())
                || !recorded.compareAndSet(false, true)) {
            return;
        }
        recordStage("ttft", requestStarted, "success");
        recordStage("model_ttft", modelStarted, "success");
    }

    private VisionInput visionInput(ChatCompletionRequest request) {
        int userMessageIndex = requestInspector.lastUserMessageIndex(request.messages());
        var message = request.messages().get(userMessageIndex);
        if (!message.hasImageContent()) {
            return VisionInput.EMPTY;
        }
        if (!activeChatModelProvider.get().capabilities().vision()) {
            throw new VisionInputException("the active model does not support image input");
        }
        if (visionInputService == null) {
            throw new VisionInputException("image input is not configured");
        }
        return visionInputService.resolve(message);
    }

    private ChatMessage userMessage(ChatCompletionRequest request, VisionInput visionInput) {
        int userMessageIndex = requestInspector.lastUserMessageIndex(request.messages());
        String content = request.messages().get(userMessageIndex).content();
        if (!requestInspector.hasText(content) && visionInput != null && visionInput.hasImages()) {
            content = "ช่วยวิเคราะห์รูปภาพที่แนบมา";
        }
        return new ChatMessage("user", content);
    }

    private void logModelDuration(String process, long started, String requestId) {
        log.info("model_call={} request_id={} duration_ms={}", process,
                requestId == null ? "-" : requestId,
                (System.nanoTime() - started) / 1_000_000);
    }

    private void recordStage(String stage, long startedNanos, String result) {
        if (performanceMetrics != null) {
            performanceMetrics.record(stage, startedNanos, result);
        }
    }

    private void recordModelUsage(ModelUsage usage, long startedNanos) {
        if (performanceMetrics != null && usage != null) {
            performanceMetrics.modelUsage(
                    usage.promptTokens(), usage.completionTokens(), System.nanoTime() - startedNanos);
        }
    }

    private void recordPromptTokenEstimate(Long estimatedTokens, int actualTokens) {
        if (performanceMetrics != null && estimatedTokens != null) {
            performanceMetrics.promptTokenEstimate(estimatedTokens, actualTokens);
        }
    }

    private void recordPromptTokenEstimate(
            Long estimatedTokens, int actualTokens, AtomicBoolean recorded) {
        if (actualTokens > 0 && recorded.compareAndSet(false, true)) {
            recordPromptTokenEstimate(estimatedTokens, actualTokens);
        }
    }

    private String signalResult(reactor.core.publisher.SignalType signal) {
        if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
            return "success";
        }
        if (signal == reactor.core.publisher.SignalType.CANCEL) {
            return "cancelled";
        }
        return "error";
    }
}
