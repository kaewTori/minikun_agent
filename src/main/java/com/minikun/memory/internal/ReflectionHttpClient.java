package com.minikun.memory.internal;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.minikun.memory.MemoryException;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionPrompt;

final class ReflectionHttpClient implements ReflectionClient {
    private final RestClient restClient;

    ReflectionHttpClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String reflect(ReflectionPrompt prompt) {
        Response response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(List.of(new Message("user", prompt.content())), false,
                        384, 0.0, new ResponseFormat("json_object")))
                .retrieve()
                .body(Response.class);
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null
                || response.choices().getFirst().message().content() == null) {
            throw new MemoryException("reflection client returned an empty response");
        }
        return response.choices().getFirst().message().content();
    }

    private record Request(List<Message> messages, boolean stream, int max_tokens,
            double temperature, ResponseFormat response_format) {
    }

    private record Message(String role, String content) {
    }

    private record ResponseFormat(String type) {
    }

    private record Response(List<Choice> choices) {
    }

    private record Choice(MessageResponse message) {
    }

    private record MessageResponse(String role, String content) {
    }
}