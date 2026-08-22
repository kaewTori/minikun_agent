package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import com.minikun.personality.profile.UserModelService;
import com.minikun.knowledge.PersonalKnowledgeService;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ModelUsage;
import com.minikun.model.CooperativeChatModelService;
import com.minikun.model.capability.ModelCapabilityRegistry;
import com.minikun.model.task.title.TitleGenerationService;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.browser.BrowserContentException;
import com.minikun.browser.BrowserContentService;
import com.minikun.context.runtime.PersonalContextRuntime;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptException;
import com.minikun.pcs.KnowledgeConsolidationService;
import com.minikun.pcs.KnowledgeSelectionService;
import com.minikun.pcs.model.ImageSource;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.SearchService;
import com.minikun.search.SearchSelectionSignalMapper;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import org.slf4j.MDC;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Slf4j
public class ChatService {
    private static final String PUBLIC_MODEL_NAME = "mini-kun";
    private static final String DEFAULT_CHAT_MODEL = "hf.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF:Q5_K_M";


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

    private SpringAiToolCallingRuntime toolCallingRuntime;

    private List<ToolRequestRouter> toolRequestRouters = List.of();

    private DynamicGenerationOptionsFactory dynamicGenerationOptionsFactory;

    private ModelCapabilityRegistry modelCapabilityRegistry;

    private TokenBudgetProperties tokenBudgetProperties;

    private PersonalContextRuntime personalContextRuntime;

    private AdaptivePersonaService adaptivePersonaService;

    private CompanionModeService companionModeService;

    private UserModelService userModelService;

    private CooperativeChatModelService cooperativeChatModelService;

    private DeferredReflectionService deferredReflectionService;

    private ObservationPublisher observationPublisher;

    private ChatPerformanceMetrics performanceMetrics;

    private ChatGenerationProfileSelector generationProfileSelector;

    private BrowserContentService browserContentService;

    private VisionInputService visionInputService;

    private PersonalKnowledgeService personalKnowledgeService;

    private ConversationSummaryService conversationSummaryService;

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
        cooperativeChatModelService = collaborators.cooperativeChatModelService();
        deferredReflectionService = collaborators.deferredReflectionService();
        observationPublisher = collaborators.observationPublisher();
        performanceMetrics = collaborators.performanceMetrics();
        generationProfileSelector = collaborators.generationProfileSelector();
        browserContentService = collaborators.browserContentService();
        visionInputService = collaborators.visionInputService();
        personalKnowledgeService = collaborators.personalKnowledgeService();
        conversationSummaryService = collaborators.conversationSummaryService();
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

    @Value("${spring.ai.ollama.embedding.options.model:qwen3-embedding:0.6b}")
    private String configuredEmbeddingModel;

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

    @Value("${minikun.model.generation.temperature:0.5}")
    private double configuredGenerationTemperature;

    @Value("${minikun.model.generation.max-tokens:2048}")
    private int configuredGenerationMaxTokens;

    @Value("${spring.ai.ollama.chat.options.num-ctx:16384}")
    private int configuredOllamaContextSize = 16_384;

    @Value("${minikun.token-budget.dynamic-enabled:false}")
    private boolean dynamicTokenBudgetEnabled;

    @Value("${minikun.token-budget.reserved-output-tokens:256}")
    private long reservedOutputTokens;

    @Value("${minikun.context-budget.characters:24000}")
    private long contextBudgetCharacters = 24_000L;

    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request, ConversationId conversationId) {
        VisionInput visionInput = visionInput(request);
        ChatMessage userMessage = userMessage(request, visionInput);
        var commandResponse = commandHandler().blocking(
                userMessage, modelName(request.model(), effectiveConfiguredChatModel()),
                diagnosticsConversationalEnabled, modelGateway());
        if (commandResponse != null) {
            log.info("process=command event=completed");
            return commandResponse;
        }
        if (isInternalTitleRequest(request)) {
            return responseForContent(request, titleGenerationService.generateTitle(titleMessages(request)));
        }
        String model = modelName(request.model(), effectiveConfiguredChatModel());
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
                "chatcmpl-" + UUID.randomUUID(), model, false, request.messages().size());
        long requestStarted = System.nanoTime();
        String requestResult = "success";
        try {
            Optional<ToolEvidence> verifiedToolResult = routeTool(
                    userMessage, conversationId, memoryOwnerId(request, conversationId));
            if (verifiedToolResult.map(ToolEvidence::finalResponse).orElse(false)) {
                String content = verifiedToolResult.get().content();
                turnFinalizer().persistDeterministic(
                        conversationId, userMessage, content, memoryOwnerId(request, conversationId),
                        transaction.requestId(), shouldPersistConversation(request), false);
                transaction.success();
                return responseForContent(request, content);
            }
            ChatExecutionContext context;
            try {
                context = prepareChatExecution(
                        request, conversationId, userMessage, transaction, false, verifiedToolResult.orElse(null),
                        visionInput);
            } catch (BrowserContentException exception) {
                String content = browserFailureMessage(userMessage.content(), exception);
                turnFinalizer().persistDeterministic(
                        conversationId, userMessage, content, memoryOwnerId(request, conversationId),
                        transaction.requestId(), shouldPersistConversation(request), false);
                transaction.success();
                return responseForContent(request, content);
            }
            if (verifiedToolResult.map(ToolEvidence::requiresConfirmation).orElse(false)) {
                String content = verifiedToolResult.get().content();
                turnFinalizer().complete(
                        context.conversationId(), userMessage, context.ownerId(), transaction.requestId(), content,
                        context.persistConversation(), false);
                transaction.success();
                return responseForContent(request, content);
            }
            // The weather route has already executed the tool. Generate the final
            // answer from the MCS/PCS prompt, without invoking that tool twice.
            long generationStarted = System.nanoTime();
            var response = verifiedToolResult.isPresent()
                    ? modelGateway().chat(
                            context.prompt(), "chat_model", transaction.requestId(), context.conversationId())
                    : modelGateway().chatWithTools(
                            context.prompt(), context.conversationId(), context.ownerId(), transaction.requestId());
            ModelUsage usage = modelUsage(response);
            recordModelUsage(usage, generationStarted);
            String content = response.getResult().getOutput().getText();
            if (!hasText(content)) {
                log.warn("process=model_response event=blank request_id={}", transaction.requestId());
                content = "ขออภัยครับ โมเดลยังไม่ได้ส่งคำตอบที่สมบูรณ์ กรุณาลองสั่งอีกครั้งครับ";
            }
            turnFinalizer().complete(
                    context.conversationId(), userMessage, context.ownerId(), transaction.requestId(), content,
                    context.persistConversation(), false);
            ChatCompletionResponse result = responseFactory.completion(
                    transaction.requestId(), Instant.now().getEpochSecond(), model, content, usage,
                    context.attachments());
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
        if (isInternalTitleRequest(request)) {
            return commandStream(titleGenerationService.generateTitle(titleMessages(request)), request);
        }
        String model = modelName(request.model(), effectiveConfiguredChatModel());
        String requestId = "chatcmpl-" + UUID.randomUUID();
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
                requestId, model, true, request.messages().size());
        long requestStarted = System.nanoTime();
        String id = requestId;
        long created = Instant.now().getEpochSecond();
        StringBuilder assistantContent = new StringBuilder();
        AtomicReference<ModelUsage> modelUsage = new AtomicReference<>(ModelUsage.empty());
        AtomicBoolean firstTokenRecorded = new AtomicBoolean();

        Optional<ToolEvidence> verifiedToolResult = routeTool(
                userMessage, conversationId, memoryOwnerId(request, conversationId));
        if (verifiedToolResult.map(ToolEvidence::finalResponse).orElse(false)) {
            String content = verifiedToolResult.get().content();
            turnFinalizer().persistDeterministic(
                    conversationId, userMessage, content, memoryOwnerId(request, conversationId),
                    transaction.requestId(), shouldPersistConversation(request), true);
            transaction.success();
            return commandStream(content, request);
        }

        ChatExecutionContext context;
        try {
            context = prepareChatExecution(
                request, conversationId, userMessage, transaction, true, verifiedToolResult.orElse(null),
                visionInput);
        } catch (BrowserContentException exception) {
            String content = browserFailureMessage(userMessage.content(), exception);
            turnFinalizer().persistDeterministic(
                    conversationId, userMessage, content, memoryOwnerId(request, conversationId),
                    transaction.requestId(), shouldPersistConversation(request), true);
            transaction.success();
            return commandStream(content, request);
        }
        if (verifiedToolResult.map(ToolEvidence::requiresConfirmation).orElse(false)) {
            String content = verifiedToolResult.get().content();
            turnFinalizer().complete(
                    context.conversationId(), userMessage, context.ownerId(), transaction.requestId(), content,
                    context.persistConversation(), true);
            transaction.success();
            return commandStream(content, request);
        }
        long modelStarted = System.nanoTime();
        String traceId = MDC.get("trace_id");
        Flux<ChatResponse> modelResponses = verifiedToolResult.isPresent()
            ? modelGateway().stream(context.prompt(), context.conversationId())
            : toolsEnabled && toolCallingRuntime != null
                    && !directStreamingProfile(context.generationProfile())
            ? Flux.defer(() -> Flux.just(modelGateway().reviewToolRuntimeDraft(
                    context.prompt(), context.conversationId(), context.ownerId())))
            : modelGateway().stream(context.prompt(), context.conversationId());
        Flux<String> chunks = modelResponses
                .doOnNext(response -> recordFirstToken(
                        response, firstTokenRecorded, requestStarted, modelStarted))
                .doOnNext(response -> appendAssistantText(assistantContent, response))
                .doOnNext(response -> captureModelUsage(modelUsage, response))
                .map(response -> responseFactory.contentChunk(response, id, created, model))
                .filter(chunk -> !chunk.isBlank())
                .doFinally(signal -> withTrace(traceId, () -> {
                    logModelDuration("chat_model_stream", modelStarted, requestId);
                    recordStage("model", modelStarted, signalResult(signal));
                    recordModelUsage(modelUsage.get(), modelStarted);
                }));

        return Flux.concat(
                Flux.just(responseFactory.initialChunk(id, created, model, context.attachments())),
                chunks,
                Flux.defer(() -> {
                    String usageChunk = responseFactory.usageChunk(modelUsage.get(), id, created, model);
                    return usageChunk.isEmpty() ? Flux.empty() : Flux.just(usageChunk);
                }),
                Flux.just(responseFactory.stopChunk(id, created, model)),
                Flux.just("[DONE]"))
                .doOnComplete(() -> {
                    if (!assistantContent.isEmpty()) {
                        turnFinalizer().complete(
                                context.conversationId(), userMessage, context.ownerId(), transaction.requestId(),
                                assistantContent.toString(), context.persistConversation(), true);
                    }
                    withTrace(traceId, transaction::success);
                })
                .doOnError(exception -> withTrace(traceId, () -> transaction.failed(exception)))
                .doOnCancel(() -> withTrace(traceId, transaction::cancelled))
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
        boolean persistConversation = shouldPersistConversation(request);
        String ownerId = memoryOwnerId(request, conversationId);
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
        CompanionModeContext interactionMode = companionModeFor(ownerId, conversationId, userMessage.content());
        String conversationSummary = conversationSummary(ownerId, conversationId);
        int recentMessageLimit = conversationSummary.isBlank() || conversationSummaryService == null
                ? Integer.MAX_VALUE : conversationSummaryService.recentMessageLimit();
        ChatKnowledgeSelection knowledgeSelection = knowledgeResolver().resolve(
                new ChatKnowledgeResolver.Request(
                        userMessage.content(), transaction.requestId(), conversationId, ownerId,
                        hasConversationContext(history, request), classifierContext(history)));
        PreparedImages preparedImages = prepareImages(knowledgeSelection);
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
                    interactionMode,
                    visionInput));
        } catch (RuntimeException exception) {
            promptResult = "error";
            throw exception;
        } finally {
            recordStage("prompt", promptStarted, promptResult);
        }
        log.info("process=prompt event=composed{}", streaming ? " stream=true" : "");
        return new ChatExecutionContext(
                preparedPrompt.prompt(), conversationId, persistConversation, ownerId,
                preparedImages.attachments(), preparedPrompt.generationProfile());
    }

    private PreparedImages prepareImages(ChatKnowledgeSelection knowledgeSelection) {
        try {
            List<ImageSource> selectedImages = ImageAttachmentSelector.select(
                    knowledgeSelection.selection().knowledgeContext().images());
            List<ChatAttachment> attachments = ImageAttachmentMapper.map(selectedImages);
            if (attachments.size() != selectedImages.size()) {
                throw new IllegalStateException("image attachment count does not match selected image count");
            }
            ImageAwareness awareness = selectedImages.isEmpty()
                    ? null : new ImageAwareness(selectedImages.size());
            return new PreparedImages(awareness, attachments);
        } catch (RuntimeException exception) {
            log.warn("Image attachment selection failed; continuing without attachments", exception);
            return PreparedImages.EMPTY;
        }
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
                reflectionEnabled);
    }

    private ChatModelGateway modelGateway() {
        return new ChatModelGateway(
                activeChatModelProvider,
                cooperativeChatModelService,
                toolCallingRuntime,
                performanceMetrics,
                toolsEnabled);
    }

    private ChatCompletionResponse responseForContent(ChatCompletionRequest request, String content) {
        String model = modelName(request.model(), effectiveConfiguredChatModel());
        return responseFactory.contentCompletion(model, content);
    }

    private Flux<String> commandStream(String content, ChatCompletionRequest request) {
        String model = modelName(request.model(), effectiveConfiguredChatModel());
        return Flux.fromIterable(responseFactory.contentStream(model, content));
    }

    private ModelUsage modelUsage(ChatResponse response) {
        return responseFactory.modelUsage(response);
    }

    private void captureModelUsage(AtomicReference<ModelUsage> target, ChatResponse response) {
        ModelUsage usage = modelUsage(response);
        if (!usage.equals(ModelUsage.empty())) {
            target.set(usage);
        }
    }

    public ModelsResponse listModels() {
        return new ModelsResponse("list", List.of(
                new ModelsResponse.Model(PUBLIC_MODEL_NAME, "model", Instant.now().getEpochSecond(), "minikun")));
    }

    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        long started = System.nanoTime();
        List<EmbeddingResponse.Data> data = java.util.stream.IntStream.range(0, request.texts().size())
                .mapToObj(index -> new EmbeddingResponse.Data(
                        "embedding", toFloatList(embeddingModel.embed(request.texts().get(index))), index))
                .toList();
        EmbeddingResponse result = new EmbeddingResponse("list", data,
                modelName(request.model(), configuredEmbeddingModel),
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
                searchService,
                searchDecisionService,
                searchQueryPlanningService,
                searchContextAwarenessService,
                knowledgeSelectionService,
                knowledgeConsolidationService,
                searchSelectionSignalMapper,
                browserContentService,
                performanceMetrics,
                new ChatKnowledgeResolver.Configuration(
                        searchEnabled,
                        searchTimeout,
                        searchSafeSearch,
                        searchQueryPlanningEnabled,
                        configuredSearchResultLimit,
                        configuredMemoryRetrievalLimit,
                        configuredPersonalKnowledgeLimit));
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
                generationProfileSelector,
                performanceMetrics,
                new ChatPromptFactory.Configuration(
                        toolsEnabled && toolCallingRuntime != null,
                        dynamicTokenBudgetEnabled,
                        reservedOutputTokens,
                        contextBudgetCharacters,
                        configuredGenerationMaxTokens,
                        configuredGenerationTemperature,
                        configuredOllamaContextSize,
                        effectiveConfiguredChatModel()));
    }

    private CompanionModeContext companionModeFor(
            String ownerId, ConversationId conversationId, String latestUserMessage) {
        if (companionModeService == null || conversationId == null) {
            return null;
        }
        return companionModeService.evaluate(ownerId, conversationId.value(), latestUserMessage).orElse(null);
    }

    private String conversationSummary(String ownerId, ConversationId conversationId) {
        if (conversationSummaryService == null) {
            return "";
        }
        return conversationSummaryService.summary(ownerId, conversationId).orElse("");
    }

    private String browserFailureMessage(String userMessage, BrowserContentException exception) {
        String url = "the supplied link";
        if (browserContentService != null) {
            try {
                url = browserContentService.urlsIn(userMessage).stream()
                        .findFirst().orElse(url);
            } catch (BrowserContentException ignored) {
                // The validation error itself is enough to explain the failure.
            }
        }
        log.warn("Browser render failed url={} reason={}", url, exception.getMessage());
        return "ไม่สามารถอ่านลิงก์ได้: " + url + " (" + exception.getMessage() + ")";
    }

    private record ChatExecutionContext(
            Prompt prompt,
            ConversationId conversationId,
            boolean persistConversation,
            String ownerId,
            List<ChatAttachment> attachments,
            String generationProfile) {
    }

    private record PreparedImages(ImageAwareness awareness, List<ChatAttachment> attachments) {
        private static final PreparedImages EMPTY = new PreparedImages(null, List.of());

        private PreparedImages {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    private boolean hasConversationContext(
            List<ChatMessage> history, ChatCompletionRequest request) {
        boolean hasHistory = history.stream()
                .anyMatch(message -> !isCommandMessage(message.content()));
        long requestConversationMessages = request.messages().stream()
                .filter(message -> !"system".equals(message.role()))
                .filter(message -> hasText(message.content()))
                .count();
        return hasHistory || requestConversationMessages > 1;
    }

    private String classifierContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        return history.stream()
                .filter(message -> message != null && !isSystemMessage(message)
                        && hasText(message.content()) && !isCommandMessage(message.content()))
                .skip(Math.max(0, history.size() - 6L))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private void restoreMdc(Map<String, String> previous) {
        try {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        } catch (RuntimeException ignored) {
            // MDC must not affect search execution.
        }
    }

    private String effectiveConfiguredChatModel() {
        String chatModel = modelsService.chatModel();
        return chatModel == null || chatModel.isBlank()
                ? DEFAULT_CHAT_MODEL
            : chatModel;
    }

    private void appendAssistantText(StringBuilder content, ChatResponse response) {
        String text = response.getResult().getOutput().getText();
        if (text != null) {
            content.append(text);
        }
    }

    private void recordFirstToken(
            ChatResponse response,
            AtomicBoolean recorded,
            long requestStarted,
            long modelStarted) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || !hasText(response.getResult().getOutput().getText())
                || !recorded.compareAndSet(false, true)) {
            return;
        }
        recordStage("ttft", requestStarted, "success");
        recordStage("model_ttft", modelStarted, "success");
    }

    private VisionInput visionInput(ChatCompletionRequest request) {
        int userMessageIndex = lastUserMessageIndex(request.messages());
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
        int userMessageIndex = lastUserMessageIndex(request.messages());
        String content = request.messages().get(userMessageIndex).content();
        if (!hasText(content) && visionInput != null && visionInput.hasImages()) {
            content = "ช่วยวิเคราะห์รูปภาพที่แนบมา";
        }
        return new ChatMessage("user", content);
    }

    private boolean isSystemMessage(ChatMessage message) {
        return "system".equalsIgnoreCase(message.role());
    }

    private int lastUserMessageIndex(List<com.minikun.agent.minikun_agent.api.openai.dto.Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).role())
                    && (hasText(messages.get(index).content()) || messages.get(index).hasImageContent())) {
                return index;
            }
        }
        throw new PromptException("chat request must contain user text or an image");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isCommandMessage(String content) {
        return hasText(content) && commandCatalog.findExact(content.trim()).isPresent();
    }

    private boolean shouldPersistConversation(ChatCompletionRequest request) {
        return request.messages().stream()
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .noneMatch(this::isInternalTitleRequest);
    }

    private boolean isInternalTitleRequest(ChatCompletionRequest request) {
        return request.messages().stream()
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .anyMatch(this::isInternalTitleRequest);
    }

    private List<ChatMessage> titleMessages(ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> hasText(message.content()))
                .filter(message -> "user".equals(message.role()) || "assistant".equals(message.role()))
                .map(message -> new ChatMessage(message.role(), message.content()))
                .toList();
    }

    private boolean isInternalTitleRequest(String content) {
        if (!hasText(content)) {
            return false;
        }
        String normalized = content.toLowerCase();
        return normalized.contains("generate a concise title summarizing the chat history")
                || normalized.contains("your entire response must consist solely of the json object")
                || normalized.contains("### task:\n") && normalized.contains("### chat history:");
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

    private String signalResult(reactor.core.publisher.SignalType signal) {
        if (signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
            return "success";
        }
        if (signal == reactor.core.publisher.SignalType.CANCEL) {
            return "cancelled";
        }
        return "error";
    }

    private boolean directStreamingProfile(String profile) {
        return "companion".equals(profile)
                || "general".equals(profile)
                || "focus".equals(profile)
                || "creative".equals(profile);
    }

    private void withTrace(String traceId, Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (traceId == null || traceId.isBlank() || "-".equals(traceId)) {
                MDC.remove("trace_id");
            } else {
                MDC.put("trace_id", traceId);
            }
            action.run();
        } finally {
            restoreMdc(previous);
        }
    }

    private String modelName(String requestedModel, String configuredModel) {
        // Backend model identifiers are implementation details. Keep the public API stable
        // even when Ollama/TinyGrad is switched or its configured model changes.
        return PUBLIC_MODEL_NAME;
    }
}
