package com.minikun.agent.minikun_agent.api.openai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
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
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.PromptException;
import com.minikun.pcs.PromptRequest;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.RuntimeContext;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ChatTransactionLogger transactionLogger;
    private final ConversationMemoryService conversationMemoryService;
    private final CharacterSpecification characterSpecification;
    private final PromptComposer promptComposer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.ai.ollama.chat.options.model:hf.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF:Q6_K}")
    private String configuredChatModel;

    @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}")
    private String configuredEmbeddingModel;

    public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
        String model = modelName(request.model(), configuredChatModel);
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
            "chatcmpl-" + UUID.randomUUID(), model, false, request.messages().size());
        ConversationId conversationId = conversationId(request);
        ChatMessage userMessage = userMessage(request);
        List<ChatMessage> history = conversationMemoryService.load(conversationId);
        try {
        conversationMemoryService.append(conversationId, userMessage);
        var response = chatModel.call(promptFor(request, history));
        String content = response.getResult().getOutput().getText();
        conversationMemoryService.append(conversationId, new ChatMessage("assistant", content));
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

    public Flux<String> chatCompletionStream(ChatCompletionRequest request) {
        String model = modelName(request.model(), configuredChatModel);
        String requestId = "chatcmpl-" + UUID.randomUUID();
        ChatTransactionLogger.Transaction transaction = transactionLogger.start(
            requestId, model, true, request.messages().size());
        String id = requestId;
        long created = Instant.now().getEpochSecond();
        ConversationId conversationId = conversationId(request);
        ChatMessage userMessage = userMessage(request);
        List<ChatMessage> history = conversationMemoryService.load(conversationId);
        conversationMemoryService.append(conversationId, userMessage);
        StringBuilder assistantContent = new StringBuilder();

        Flux<String> chunks = chatModel.stream(promptFor(request, history))
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
                    if (!assistantContent.isEmpty()) {
                        conversationMemoryService.append(
                                conversationId, new ChatMessage("assistant", assistantContent.toString()));
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

    private Prompt promptFor(ChatCompletionRequest request, List<ChatMessage> history) {
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

        PromptRequest promptRequest = new PromptRequest(
                characterSpecification,
                new RuntimeContext(runtime),
                conversation.isBlank() ? null : new ConversationContext(conversation),
                null,
                List.of(),
                new com.minikun.pcs.model.UserMessage(userMessage.content()));
        return new Prompt(promptComposer.compose(promptRequest));
    }

    private void appendAssistantText(StringBuilder content, ChatResponse response) {
        String text = response.getResult().getOutput().getText();
        if (text != null) {
            content.append(text);
        }
    }

    private ConversationId conversationId(ChatCompletionRequest request) {
        if (!hasText(request.conversation_id())) {
            throw new PromptException("conversation_id must not be blank");
        }
        return new ConversationId(request.conversation_id());
    }

    private ChatMessage userMessage(ChatCompletionRequest request) {
        int userMessageIndex = lastUserMessageIndex(request.messages());
        return new ChatMessage("user", request.messages().get(userMessageIndex).content());
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

    private String modelName(String requestedModel, String configuredModel) {
        return requestedModel == null || requestedModel.isBlank() ? configuredModel : requestedModel;
    }
}