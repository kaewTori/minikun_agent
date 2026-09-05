package com.minikun.agent.minikun_agent.api.openai;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final BackgroundChatService backgroundChatService;
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

    @PostMapping("/chat/background")
    public ResponseEntity<Map<String, Object>> startBackgroundChat(
            @RequestBody ChatCompletionRequest request,
            HttpServletRequest httpRequest) {
        ConversationIdResolver.Resolution resolution =
                conversationIdResolver.resolveDetails(request, httpRequest);
        UUID jobId = backgroundChatService.submit(request, resolution.conversationId());
        return ResponseEntity.accepted()
                .header(CONVERSATION_HEADER, resolution.conversationId().value())
                .header(CONVERSATION_SOURCE_HEADER, resolution.source().name().toLowerCase(Locale.ROOT))
                .header("Access-Control-Expose-Headers", EXPOSED_HEADERS)
                .body(Map.of("id", jobId, "status", "running"));
    }

    @GetMapping("/chat/background/{jobId}")
    public ResponseEntity<BackgroundChatService.View> backgroundChat(@PathVariable UUID jobId) {
        return backgroundChatService.find(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/chat/background/{jobId}")
    public Map<String, Object> cancelBackgroundChat(@PathVariable UUID jobId) {
        return Map.of("id", jobId, "cancelled", backgroundChatService.cancel(jobId));
    }

    @PostMapping("/chat/background/{jobId}/resume")
    public Map<String, Object> resumeBackgroundChat(@PathVariable UUID jobId) {
        return Map.of("id", jobId, "resumed", backgroundChatService.resume(jobId));
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
