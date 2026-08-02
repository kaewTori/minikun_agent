package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
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
import com.minikun.memory.MemoryAnalyzer;
import com.minikun.memory.MemoryRecallService;
import com.minikun.memory.MemoryService;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptException;
import com.minikun.pcs.PromptRequest;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.PromptMessage;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ChatTransactionLogger transactionLogger;
    private final ConversationMemoryService conversationMemoryService;
    private final MemoryAnalyzer memoryAnalyzer;
    private final ObjectProvider<MemoryService> memoryService;
    private final ObjectProvider<MemoryRecallService> memoryRecallService;
    private final CharacterSpecification characterSpecification;
    private final PromptComposer promptComposer;
    private final SearchService searchService;
    private final SearchDecisionService searchDecisionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.ollama.chat.options.model:hf.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF:Q5_K_M}")
    private String configuredChatModel;

    @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}")
    private String configuredEmbeddingModel;

    @Value("${minikun.search.enabled:false}")
    private boolean searchEnabled;

    @Value("${minikun.search.timeout:10s}")
    private java.time.Duration searchTimeout;

    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request, ConversationId conversationId) {
        String model = modelName(request.model(), configuredChatModel);
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
            "chatcmpl-" + UUID.randomUUID(), model, false, request.messages().size());
        ChatMessage userMessage = userMessage(request);
        boolean persistConversation = shouldPersistConversation(request);
        List<ChatMessage> history = conversationMemoryService.load(conversationId);
        try {
        if (persistConversation) {
            conversationMemoryService.append(conversationId, userMessage);
        }
        KnowledgeContext knowledge = knowledgeFor(userMessage.content());
        var response = chatModel.call(promptFor(request, history, knowledge));
        String content = response.getResult().getOutput().getText();
        if (persistConversation) {
            conversationMemoryService.append(conversationId, new ChatMessage("assistant", content));
            extractMemories(conversationId);
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
        String model = modelName(request.model(), configuredChatModel);
        String requestId = "chatcmpl-" + UUID.randomUUID();
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
            requestId, model, true, request.messages().size());
        String id = requestId;
        long created = Instant.now().getEpochSecond();
        ChatMessage userMessage = userMessage(request);
        boolean persistConversation = shouldPersistConversation(request);
        List<ChatMessage> history = conversationMemoryService.load(conversationId);
        if (persistConversation) {
            conversationMemoryService.append(conversationId, userMessage);
        }
        StringBuilder assistantContent = new StringBuilder();

        KnowledgeContext knowledge = knowledgeFor(userMessage.content());
        Flux<String> chunks = chatModel.stream(promptFor(request, history, knowledge))
            .doOnNext(response -> appendAssistantText(assistantContent, response))
                .map(response -> streamChunk(response, id, created, model))
                .filter(chunk -> !chunk.isBlank());

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
                        extractMemories(conversationId);
                    }
                    transaction.success();
                })
                .doOnError(transaction::failed)
                .doOnCancel(transaction::cancelled);
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
        List<EmbeddingResponse.Data> data = java.util.stream.IntStream.range(0, request.texts().size())
            .mapToObj(index -> new EmbeddingResponse.Data(
                "embedding", toFloatList(embeddingModel.embed(request.texts().get(index))), index))
                .toList();
        return new EmbeddingResponse("list", data, modelName(request.model(), configuredEmbeddingModel),
                new EmbeddingResponse.Usage(0, 0));
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
            KnowledgeContext knowledge) {
        var userMessage = userMessage(request);
        String runtime = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("Current date: " + LocalDate.now());
        String conversation = history.stream()
            .filter(message -> !"system".equals(message.role()))
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
                knowledge,
                List.of(),
                new com.minikun.pcs.model.UserMessage(userMessage.content()));
        return toSpringPrompt(promptComposer.compose(promptRequest));
    }

    private String requestConversation(ChatCompletionRequest request) {
        int currentUserIndex = lastUserMessageIndex(request.messages());
        return java.util.stream.IntStream.range(0, currentUserIndex)
                .mapToObj(request.messages()::get)
                .filter(message -> !"system".equals(message.role()))
                .filter(message -> hasText(message.content()))
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private KnowledgeContext recallKnowledge() {
        MemoryRecallService service = memoryRecallService.getIfAvailable();
        if (service == null) {
            return new KnowledgeContext("");
        }
        try {
            KnowledgeContext knowledge = service.recall();
            return knowledge == null ? new KnowledgeContext("") : knowledge;
        } catch (RuntimeException exception) {
            log.warn("Long-term memory recall failed; continuing without knowledge", exception);
            return new KnowledgeContext("");
        }
    }

    private KnowledgeContext knowledgeFor(String query) {
        KnowledgeContext memoryKnowledge = recallKnowledge();
        if (!searchEnabled) {
            return memoryKnowledge;
        }

        var decision = searchDecisionService.decide(query);
        log.info("Search decision shouldSearch={} query={}", decision.shouldSearch(), decision.query());
        if (!decision.shouldSearch()) {
            return memoryKnowledge;
        }

        try {
            SearchRequest searchRequest = new SearchRequest(
                    UUID.randomUUID(),
                    decision.query(),
                    10,
                    Instant.now().plus(searchTimeout));
            KnowledgeContext searchKnowledge = searchService.search(searchRequest);
                    log.info("Search completed query={} knowledgeCharacters={}",
                        decision.query(), searchKnowledge == null ? 0 : searchKnowledge.content().length());
            return combineKnowledge(memoryKnowledge, searchKnowledge);
        } catch (RuntimeException exception) {
            log.warn("Search failed; continuing without search knowledge", exception);
            return memoryKnowledge;
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

    private void extractMemories(ConversationId conversationId) {
        try {
            CompletedConversation conversation = new CompletedConversation(
                    conversationId.value(),
                    conversationMemoryService.load(conversationId).stream()
                            .map(message -> new CompletedConversation.Message(message.role(), message.content()))
                            .toList());
            var candidates = memoryAnalyzer.analyze(conversation);
                var service = memoryService.getIfAvailable();
                if (service == null) {
                    log.warn("memory_persistence conversation_id={} skipped=true reason=memory_service_unavailable candidate_count={}",
                            conversationId.value(), candidates.size());
                    return;
                }
                    var persisted = service.persist(conversation, candidates);
                    log.info("memory_persistence conversation_id={} candidate_count={} persisted_count={} skipped_count={}",
                        conversationId.value(), candidates.size(), persisted.size(), candidates.size() - persisted.size());
        } catch (RuntimeException exception) {
            log.warn("Long-term memory extraction failed for conversation {}", conversationId.value(), exception);
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

    private boolean shouldPersistConversation(ChatCompletionRequest request) {
        return request.messages().stream()
                .map(com.minikun.agent.minikun_agent.api.openai.dto.Message::content)
                .filter(this::hasText)
                .map(String::toLowerCase)
                .noneMatch(content -> content.contains("generate a concise title summarizing the chat history")
                        || content.contains("your entire response must consist solely of the json object")
                        || content.contains("### task:\n") && content.contains("### chat history:"));
    }

    private String modelName(String requestedModel, String configuredModel) {
        return requestedModel == null || requestedModel.isBlank() ? configuredModel : requestedModel;
    }
}