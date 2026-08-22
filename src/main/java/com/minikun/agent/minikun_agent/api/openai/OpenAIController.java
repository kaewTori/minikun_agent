package com.minikun.agent.minikun_agent.api.openai;

import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minikun.agent.minikun_agent.api.ConversationIdResolver;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingResponse;
import com.minikun.agent.minikun_agent.conversation.ConversationId;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class OpenAIController {
    private static final String CONVERSATION_HEADER = "X-Conversation-Id";
    private static final String CONVERSATION_SOURCE_HEADER = "X-Conversation-Id-Source";
    private static final String EXPOSED_HEADERS = CONVERSATION_HEADER + ", " + CONVERSATION_SOURCE_HEADER;

    private final ChatService chatService;
    private final ConversationIdResolver conversationIdResolver;

    @PostMapping("/chat/completions")
    public ResponseEntity<?> chatCompletion(
            @RequestBody ChatCompletionRequest request,
            HttpServletRequest httpRequest) {
        ConversationIdResolver.Resolution resolution =
                conversationIdResolver.resolveDetails(request, httpRequest);
        ConversationId conversationId = resolution.conversationId();

        if (Boolean.TRUE.equals(request.stream())) {
            return ResponseEntity.ok()
                .header(CONVERSATION_HEADER, conversationId.value())
                .header(CONVERSATION_SOURCE_HEADER, resolution.source().name().toLowerCase(Locale.ROOT))
                .header("Access-Control-Expose-Headers", EXPOSED_HEADERS)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(chatService.chatCompletionStream(request, conversationId));
        }

        ChatCompletionResponse response =
            chatService.chatCompletion(request, conversationId);

        return ResponseEntity.ok()
            .header(CONVERSATION_HEADER, conversationId.value())
            .header(CONVERSATION_SOURCE_HEADER, resolution.source().name().toLowerCase(Locale.ROOT))
            .header("Access-Control-Expose-Headers", EXPOSED_HEADERS)
            .body(response);
    }

    @GetMapping("/models")
    public ModelsResponse models() {
        return chatService.listModels();
    }

    @PostMapping("/embeddings")
    public EmbeddingResponse embeddings(
            @RequestBody EmbeddingRequest request) {

        return chatService.embeddings(request);
    }
}
