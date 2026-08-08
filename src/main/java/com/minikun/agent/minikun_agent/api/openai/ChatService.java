package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingResponse;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.agent.minikun_agent.conversation.ConversationMemoryService;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPrompt;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.diagnostics.DiagnosticsSummary;
import com.minikun.memory.MemoryRecallService;
import com.minikun.memory.MemoryScope;
import com.minikun.memory.ReflectionService;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptException;
import com.minikun.pcs.PromptRequest;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeConsolidation;
import com.minikun.pcs.KnowledgeConsolidationService;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSelectionService;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.SearchContext;
import com.minikun.pcs.SearchSelectionSignals;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.SearchContextAwarenessService;
import com.minikun.search.SearchService;
import com.minikun.search.SearchSelectionSignalMapper;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchRequest;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchQueryPlan;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import org.slf4j.MDC;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@Slf4j
public class ChatService {

    private final ChatModel chatModel;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatService(
            ChatModel chatModel,
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
                chatModel,
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
                reflectionService);
    }

    @Value("${spring.ai.ollama.chat.options.model:hf.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF:Q5_K_M}")
    private String configuredChatModel;

    @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}")
    private String configuredEmbeddingModel;

    @Value("${minikun.search.enabled:false}")
    private boolean searchEnabled;

    @Value("${minikun.search.timeout:10s}")
    private java.time.Duration searchTimeout;

    @Value("${minikun.diagnostics.conversational.enabled:false}")
    private boolean diagnosticsConversationalEnabled;

    @Value("${minikun.memory.reflection.enabled:false}")
    private boolean reflectionEnabled;

    @Value("${minikun.memory.retrieval.max-candidates:10}")
    private int configuredMemoryRetrievalLimit;

    @Value("${minikun.search.safesearch:true}")
    private boolean searchSafeSearch;

    @Value("${minikun.search.query-planning.enabled:true}")
    private boolean searchQueryPlanningEnabled;

    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request, ConversationId conversationId) {
        ChatMessage userMessage = userMessage(request);
        var commandResponse = commandResponse(request, userMessage);
        if (commandResponse != null) {
            return commandResponse;
        }
        String model = modelName(request.model(), configuredChatModel);
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
            "chatcmpl-" + UUID.randomUUID(), model, false, request.messages().size());
        boolean persistConversation = shouldPersistConversation(request);
        List<ChatMessage> history = conversationMemoryService.load(conversationId);
        try {
        if (persistConversation) {
            conversationMemoryService.append(conversationId, userMessage);
        }
        KnowledgePipelineSelection knowledgeSelection = knowledgeFor(
            userMessage.content(), transaction.requestId(), conversationId, request.owner_id(),
            hasConversationContext(history, request));
        Prompt prompt = promptFor(request, history, knowledgeSelection);
        var response = callChatModel(prompt, "chat_model", transaction.requestId());
        String content = response.getResult().getOutput().getText();
        if (persistConversation) {
            conversationMemoryService.append(conversationId, new ChatMessage("assistant", content));
            reflectOnCompletedConversation(request.owner_id(), conversationId);
        }
        var choice = new ChatCompletionResponse.Choice(
                0,
                new com.minikun.agent.minikun_agent.api.openai.dto.Message("assistant", content),
                "stop");
        ChatCompletionResponse result = new ChatCompletionResponse(
            transaction.requestId(), "chat.completion", Instant.now().getEpochSecond(),
                model, List.of(choice), new ChatCompletionResponse.Usage(0, 0, 0));
        transaction.success();
        return result;
        } catch (RuntimeException exception) {
            transaction.failed(exception);
            throw exception;
        }
    }

    public Flux<String> chatCompletionStream(ChatCompletionRequest request, ConversationId conversationId) {
        ChatMessage userMessage = userMessage(request);
        String command = commandOutput(userMessage);
        if (command != null) {
            return commandStream(command, request);
        }
        String model = modelName(request.model(), configuredChatModel);
        String requestId = "chatcmpl-" + UUID.randomUUID();
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
            requestId, model, true, request.messages().size());
        String id = requestId;
        long created = Instant.now().getEpochSecond();
        boolean persistConversation = shouldPersistConversation(request);
        List<ChatMessage> history = conversationMemoryService.load(conversationId);
        if (persistConversation) {
            conversationMemoryService.append(conversationId, userMessage);
        }
        StringBuilder assistantContent = new StringBuilder();

        KnowledgePipelineSelection knowledgeSelection = knowledgeFor(
            userMessage.content(), transaction.requestId(), conversationId, request.owner_id(),
            hasConversationContext(history, request));
        long modelStarted = System.nanoTime();
        Flux<String> chunks = chatModel.stream(promptFor(request, history, knowledgeSelection))
            .doOnNext(response -> appendAssistantText(assistantContent, response))
                .map(response -> streamChunk(response, id, created, model))
            .filter(chunk -> !chunk.isBlank())
            .doOnComplete(() -> logModelDuration("chat_model_stream", modelStarted, requestId))
            .doOnError(exception -> logModelDuration("chat_model_stream", modelStarted, requestId))
            .doOnCancel(() -> logModelDuration("chat_model_stream", modelStarted, requestId));

        return Flux.concat(
                Flux.just(data(new ChatCompletionResponse.StreamChunk(
                        id, "chat.completion.chunk", created, model,
                        List.of(new ChatCompletionResponse.StreamChoice(
                                0, new ChatCompletionResponse.Delta("assistant", ""), null))))),
                chunks,
                Flux.just(data(new ChatCompletionResponse.StreamChunk(
                        id, "chat.completion.chunk", created, model,
                        List.of(new ChatCompletionResponse.StreamChoice(0,
                                new ChatCompletionResponse.Delta(null, null), "stop"))))),
                Flux.just("[DONE]"))
                .doOnComplete(() -> {
                    if (persistConversation && !assistantContent.isEmpty()) {
                        conversationMemoryService.append(
                                conversationId, new ChatMessage("assistant", assistantContent.toString()));
                        reflectOnCompletedConversation(request.owner_id(), conversationId);
                    }
                    transaction.success();
                })
                .doOnError(transaction::failed)
                .doOnCancel(transaction::cancelled);
    }

            private ChatCompletionResponse commandResponse(ChatCompletionRequest request, ChatMessage userMessage) {
            var command = commandCatalog.findExact(userMessage.content());
            if (command.isEmpty()) {
                return null;
            }
            if (command.get().type() == com.minikun.commands.CommandType.DIAGNOSTICS) {
                return diagnosticsResponse(request, userMessage);
            }
            String content = commandOutput(userMessage);
            if (content == null) {
                return null;
            }
            String model = modelName(request.model(), configuredChatModel);
            return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(), "chat.completion", Instant.now().getEpochSecond(),
                model,
                List.of(new ChatCompletionResponse.Choice(
                    0,
                    new com.minikun.agent.minikun_agent.api.openai.dto.Message("assistant", content),
                    "stop")),
                new ChatCompletionResponse.Usage(0, 0, 0));
            }

            private ChatCompletionResponse diagnosticsResponse(
                    ChatCompletionRequest request, ChatMessage userMessage) {
            DiagnosticsSummary summary = diagnosticsService.summarize();
            if (!diagnosticsConversationalEnabled) {
                return responseForContent(request, diagnosticsFormatter.format(summary));
            }
            try {
                DiagnosticsPrompt diagnosticsPrompt = diagnosticsPromptBuilder.build(summary, userMessage.content());
                String content = callChatModel(
                    toSpringPrompt(diagnosticsPrompt), "chat_model", null)
                    .getResult().getOutput().getText();
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("Diagnostics LLM returned empty content");
                }
                return responseForContent(request, content);
            } catch (RuntimeException exception) {
                return responseForContent(request, diagnosticsFormatter.format(summary));
            }
            }

            private String commandOutput(ChatMessage userMessage) {
            return commandCatalog.findExact(userMessage.content())
                .map(command -> switch (command.type()) {
                    case DIAGNOSTICS -> diagnosticsFormatter.format(diagnosticsService.summarize());
                    case HELP -> commandFormatter.format(commandCatalog);
                    case VERSION -> versionFormatter.format(versionService.snapshot());
                    case MODELS -> modelsFormatter.format(modelsService.snapshot());
                    case CACHE -> cacheFormatter.format(cacheService.snapshot());
                })
                .orElse(null);
            }

            private ChatCompletionResponse responseForContent(ChatCompletionRequest request, String content) {
            String model = modelName(request.model(), configuredChatModel);
            return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(), "chat.completion", Instant.now().getEpochSecond(),
                model,
                List.of(new ChatCompletionResponse.Choice(
                    0,
                    new com.minikun.agent.minikun_agent.api.openai.dto.Message("assistant", content),
                    "stop")),
                new ChatCompletionResponse.Usage(0, 0, 0));
            }

            private Flux<String> commandStream(String content, ChatCompletionRequest request) {
            String id = "chatcmpl-" + UUID.randomUUID();
            long created = Instant.now().getEpochSecond();
            String model = modelName(request.model(), configuredChatModel);
            return Flux.just(
                data(new ChatCompletionResponse.StreamChunk(
                    id, "chat.completion.chunk", created, model,
                    List.of(new ChatCompletionResponse.StreamChoice(
                        0, new ChatCompletionResponse.Delta("assistant", content), null)))),
                data(new ChatCompletionResponse.StreamChunk(
                    id, "chat.completion.chunk", created, model,
                    List.of(new ChatCompletionResponse.StreamChoice(
                        0, new ChatCompletionResponse.Delta(null, null), "stop")))),
                "[DONE]");
            }

    private String streamChunk(ChatResponse response, String id, long created, String model) {
        String content = response.getResult().getOutput().getText();
        if (content == null || content.isEmpty()) {
            return "";
        }
        return data(new ChatCompletionResponse.StreamChunk(
                id, "chat.completion.chunk", created, model,
                List.of(new ChatCompletionResponse.StreamChoice(
                        0, new ChatCompletionResponse.Delta(null, content), null))));
    }

    private String data(ChatCompletionResponse.StreamChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize streaming response", exception);
        }
    }

    public ModelsResponse listModels() {
        String model = configuredChatModel;
        return new ModelsResponse("list", List.of(
                new ModelsResponse.Model(model, "model", Instant.now().getEpochSecond(), "minikun")));
    }

    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        long started = System.nanoTime();
        List<EmbeddingResponse.Data> data = java.util.stream.IntStream.range(0, request.texts().size())
            .mapToObj(index -> new EmbeddingResponse.Data(
                "embedding", toFloatList(embeddingModel.embed(request.texts().get(index))), index))
                .toList();
        EmbeddingResponse result = new EmbeddingResponse("list", data, modelName(request.model(), configuredEmbeddingModel),
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

        private Prompt promptFor(
            ChatCompletionRequest request,
            List<ChatMessage> history,
            KnowledgePipelineSelection knowledgeSelection) {
        var userMessage = userMessage(request);
        String runtime = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("Current date: " + LocalDate.now());
        String conversation = history.stream()
            .filter(message -> !"system".equals(message.role()))
            .filter(message -> !isCommandMessage(message.content()))
            .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");

        String conversationContent = conversation.isBlank()
            ? requestConversation(request)
            : conversation;
        PromptRequest promptRequest = new PromptRequest(
                characterSpecification,
                new RuntimeContext(runtime),
            conversationContent.isBlank() ? null : new ConversationContext(conversationContent),
                knowledgeSelection.selection().knowledgeContext(),
                List.of(),
                new com.minikun.pcs.model.UserMessage(userMessage.content()),
                knowledgeSelection.searchSignals(),
                knowledgeSelection.searchContext(),
                knowledgeSelection.selection(),
                knowledgeSelection.consolidation());
        return toSpringPrompt(promptComposer.compose(promptRequest));
    }

    private String requestConversation(ChatCompletionRequest request) {
        int currentUserIndex = lastUserMessageIndex(request.messages());
        return java.util.stream.IntStream.range(0, currentUserIndex)
                .mapToObj(request.messages()::get)
                .filter(message -> !"system".equals(message.role()))
                .filter(message -> hasText(message.content()))
                .filter(message -> !isCommandMessage(message.content()))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private KnowledgeContext recallKnowledge(ConversationId conversationId, String ownerId) {
        if (conversationId == null || ownerId == null || ownerId.isBlank()) {
            return new KnowledgeContext("");
        }
        MemoryRecallService service = memoryRecallService.getIfAvailable();
        if (service == null) {
            return new KnowledgeContext("");
        }
        try {
                KnowledgeContext knowledge = service.recall(new MemoryScope(ownerId, conversationId),
                    configuredMemoryRetrievalLimit);
            return knowledge == null ? new KnowledgeContext("") : knowledge;
        } catch (RuntimeException exception) {
            log.warn("Long-term memory recall failed; continuing without knowledge", exception);
            return new KnowledgeContext("");
        }
    }

    private KnowledgePipelineSelection knowledgeFor(
            String query, String requestId, ConversationId conversationId, String ownerId,
            boolean conversationContextAvailable) {
        Map<String, String> previous = null;
        boolean scoped = false;
        try {
            previous = MDC.getCopyOfContextMap();
            if (requestId != null) {
                MDC.put("request_id", requestId);
            }
            if (conversationId != null) {
                MDC.put("conversation_id", conversationId.value());
            }
            scoped = true;
        } catch (RuntimeException ignored) {
            restoreMdc(previous);
        }
        try {
            return knowledgeFor(query, conversationContextAvailable, conversationId, ownerId);
        } finally {
            if (scoped) {
                restoreMdc(previous);
            }
        }
    }

    private KnowledgePipelineSelection knowledgeFor(String query, boolean conversationContextAvailable,
            ConversationId conversationId, String ownerId) {
        KnowledgeContext memoryKnowledge = recallKnowledge(conversationId, ownerId);
        if (!searchEnabled || isInternalTitleRequest(query)) {
            return selectionWithContext(query, conversationContextAvailable, memoryKnowledge,
                    null, false, null, SearchSelectionSignals.EMPTY);
        }

        SearchDecision decision = searchDecisionService.decide(query);
        SearchQueryPlan plan = searchQueryPlanningEnabled
                ? searchQueryPlanningService.plan(query, decision)
                : new SearchQueryPlan(decision.shouldSearch(), query,
                        decision.shouldSearch() ? decision.query() : "", List.of(), List.of(),
                        "all", "general", "", 1.0, "query_planning_disabled");
        SearchDecision plannedDecision = plan.shouldSearch()
                ? new SearchDecision(true, plan.primaryQuery(), decision.reason())
                : decision;
        SearchSelectionSignals searchSignals = searchSelectionSignalMapper.map(decision);
        if (!plan.shouldSearch()) {
            return selectionWithContext(query, conversationContextAvailable, memoryKnowledge,
                    plannedDecision, false, null, searchSignals);
        }

        KnowledgeContext searchKnowledge = null;
        boolean searchAttempted = false;
        try {
            searchAttempted = true;
            SearchRequest searchRequest = new SearchRequest(
                    UUID.randomUUID(),
                    plan.primaryQuery(),
                    10,
                    Instant.now().plus(searchTimeout),
                    new SearchOptions(plan.language(), categoryFor(plan.intent()), plan.timeRange(), searchSafeSearch),
                    plan.alternateQueries());
            searchKnowledge = searchService.search(searchRequest);
                log.info("Search completed knowledgeCharacters={}",
                    searchKnowledge == null ? 0 : searchKnowledge.content().length());
                return pipelineSelection(query, conversationContextAvailable, memoryKnowledge,
                    plannedDecision, searchAttempted, searchKnowledge, searchSignals);
        } catch (RuntimeException exception) {
            log.warn("Search failed; continuing without search knowledge", exception);
                return pipelineSelection(query, conversationContextAvailable, memoryKnowledge,
                    plannedDecision, searchAttempted, null, searchSignals);
        }
    }

    private String categoryFor(String intent) {
        return "current_information".equals(intent) ? "news" : "";
    }

            private KnowledgePipelineSelection selectionWithContext(
                String query,
                boolean conversationContextAvailable,
                KnowledgeContext memoryKnowledge,
                SearchDecision decision,
                boolean searchAttempted,
                KnowledgeContext searchKnowledge,
                SearchSelectionSignals searchSignals) {
            KnowledgeSelection selection = selectKnowledge(query, memoryKnowledge, searchKnowledge);
            return new KnowledgePipelineSelection(
                selection,
                consolidate(selection),
                searchSignals,
                searchContextAwarenessService.observe(
                    query, conversationContextAvailable, memoryKnowledge, decision,
                    searchAttempted, searchKnowledge));
            }

            private KnowledgeSelection selectKnowledge(
                String query,
                KnowledgeContext memoryKnowledge,
                KnowledgeContext searchKnowledge) {
            return knowledgeSelectionService.select(
                query,
                candidatesFor(memoryKnowledge, KnowledgeSource.MEMORY),
                candidatesFor(searchKnowledge, KnowledgeSource.SEARCH));
            }

            private KnowledgePipelineSelection pipelineSelection(
                String query,
                boolean conversationContextAvailable,
                KnowledgeContext memoryKnowledge,
                SearchDecision decision,
                boolean searchAttempted,
                KnowledgeContext searchKnowledge,
                SearchSelectionSignals searchSignals) {
            KnowledgeSelection selection = selectKnowledge(query, memoryKnowledge, searchKnowledge);
            return new KnowledgePipelineSelection(
                selection,
                consolidate(selection),
                searchSignals,
                searchContextAwarenessService.observe(
                    query, conversationContextAvailable, memoryKnowledge, decision,
                    searchAttempted, searchKnowledge));
            }

            private KnowledgeConsolidation consolidate(KnowledgeSelection selection) {
            try {
                return knowledgeConsolidationService.consolidate(selection.selectedCandidates());
            } catch (RuntimeException exception) {
                log.warn("Knowledge consolidation failed; continuing without metadata", exception);
                return KnowledgeConsolidation.EMPTY;
            }
            }

            private List<KnowledgeCandidate> candidatesFor(
                KnowledgeContext knowledge,
                KnowledgeSource source) {
            if (knowledge == null || knowledge.content().isBlank()) {
                return List.of();
            }
            if (!knowledge.candidates().isEmpty()) {
                return knowledge.candidates();
            }
            return List.of(new KnowledgeCandidate(
                source.name().toLowerCase() + "-legacy", source, knowledge.content(), 0));
            }

            private record KnowledgePipelineSelection(
                KnowledgeSelection selection,
                KnowledgeConsolidation consolidation,
                SearchSelectionSignals searchSignals,
                SearchContext searchContext) {
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

    private KnowledgeContext combineKnowledge(KnowledgeContext memory, KnowledgeContext search) {
        String memoryContent = memory == null ? "" : memory.content();
        String searchContent = search == null ? "" : search.content();
        if (memoryContent.isBlank()) {
            return new KnowledgeContext(searchContent);
        }
        if (searchContent.isBlank()) {
            return new KnowledgeContext(memoryContent);
        }
        return new KnowledgeContext(memoryContent + "\n" + searchContent);
    }

    private Prompt toSpringPrompt(com.minikun.pcs.model.Prompt prompt) {
        List<Message> messages = prompt.messages().stream()
                .map(this::toSpringMessage)
                .toList();
        return new Prompt(messages);
    }

        private Prompt toSpringPrompt(DiagnosticsPrompt diagnosticsPrompt) {
        String system = diagnosticsPrompt.persona().content()
            + "\n\n[Diagnostics instructions]\n" + diagnosticsPrompt.instructions()
            + "\n\n[DiagnosticsSummary]\n" + diagnosticsFormatter.format(diagnosticsPrompt.summary());
        return new Prompt(List.of(
            new SystemMessage(system),
            new UserMessage(diagnosticsPrompt.userRequest())));
        }

    private Message toSpringMessage(PromptMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
        };
    }

    private void appendAssistantText(StringBuilder content, ChatResponse response) {
        String text = response.getResult().getOutput().getText();
        if (text != null) {
            content.append(text);
        }
    }

    private ChatMessage userMessage(ChatCompletionRequest request) {
        int userMessageIndex = lastUserMessageIndex(request.messages());
        return new ChatMessage("user", request.messages().get(userMessageIndex).content());
    }

    private CompletedConversation completedConversation(String ownerId, ConversationId conversationId) {
        return new CompletedConversation(
                ownerId, conversationId.value(),
                conversationMemoryService.load(conversationId).stream()
                        .map(message -> new CompletedConversation.Message(message.role(), message.content()))
                        .toList());
    }

    private void reflectOnCompletedConversation(String ownerId, ConversationId conversationId) {
        if (!reflectionEnabled) {
            return;
        }
        try {
            CompletedConversation conversation = completedConversation(ownerId, conversationId);
            var service = reflectionService.getIfAvailable();
            if (service != null) {
                service.reflect(conversation);
            }
        } catch (RuntimeException exception) {
            log.warn("memory_reflection conversation_id={} success=false", conversationId.value(), exception);
        }
    }

    private int lastUserMessageIndex(List<com.minikun.agent.minikun_agent.api.openai.dto.Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("user".equals(messages.get(index).role()) && hasText(messages.get(index).content())) {
                return index;
            }
        }
        throw new PromptException("chat request must contain a non-blank user message");
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

    private boolean isInternalTitleRequest(String content) {
        if (!hasText(content)) {
            return false;
        }
        String normalized = content.toLowerCase();
        return normalized.contains("generate a concise title summarizing the chat history")
                || normalized.contains("your entire response must consist solely of the json object")
                || normalized.contains("### task:\n") && normalized.contains("### chat history:");
    }

    private ChatResponse callChatModel(Prompt prompt, String process, String requestId) {
        long started = System.nanoTime();
        try {
            return chatModel.call(prompt);
        } finally {
            logModelDuration(process, started, requestId);
        }
    }

    private void logModelDuration(String process, long started, String requestId) {
        log.info("model_call={} request_id={} duration_ms={}", process,
                requestId == null ? "-" : requestId,
                (System.nanoTime() - started) / 1_000_000);
    }

    private String modelName(String requestedModel, String configuredModel) {
        return requestedModel == null || requestedModel.isBlank() ? configuredModel : requestedModel;
    }
}
