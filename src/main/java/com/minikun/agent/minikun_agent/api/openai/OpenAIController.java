package com.minikun.agent.minikun_agent.api.openai;

import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.EmbeddingResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class OpenAIController {

    private final ChatService chatService;

    @PostMapping("/chat/completions")
    public ResponseEntity<?> chatCompletion(
            @RequestBody ChatCompletionRequest request) {

        if (Boolean.TRUE.equals(request.stream())) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(chatService.chatCompletionStream(request));
        }

        ChatCompletionResponse response =
                chatService.chatCompletion(request);

        return ResponseEntity.ok(response);
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
