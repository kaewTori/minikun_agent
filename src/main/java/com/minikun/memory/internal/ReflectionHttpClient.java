package com.minikun.memory.internal;

import java.util.List;
import java.util.ArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.minikun.memory.MemoryException;
import com.minikun.memory.reflection.ReflectionClient;
import com.minikun.memory.reflection.ReflectionPrompt;

final class ReflectionHttpClient implements ReflectionClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    ReflectionHttpClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String reflect(ReflectionPrompt prompt) {
        Response response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(List.of(new Message("user", prompt.content())), false,
                384, 0.0, new ResponseFormat("json_object"), "none",
                new ChatTemplateKwargs(false)))
                .retrieve()
                .body(Response.class);
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new MemoryException("reflection client returned an empty response");
        }
        String content = normalizeContent(response.choices().getFirst().message().content());
        if (content.isBlank()) {
            throw new MemoryException("reflection client returned an empty response");
        }
        return content;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.strip();
        if (normalized.startsWith("<think>")) {
            int closingTag = normalized.indexOf("</think>", "<think>".length());
            if (closingTag >= 0) {
                normalized = normalized.substring(closingTag + "</think>".length()).strip();
            }
        }
        return extractJsonDocument(normalized);
    }

    private String extractJsonDocument(String content) {
        if (isJsonDocument(content)) {
            return content;
        }
        List<JsonNode> objects = new ArrayList<>();
        for (int index = 0; index < content.length();) {
            int openingIndex = nextJsonObject(content, index);
            if (openingIndex < 0) {
                break;
            }
            int closingIndex = matchingJsonEnd(content, openingIndex);
            if (closingIndex < 0) {
                break;
            }
            String candidate = content.substring(openingIndex, closingIndex + 1);
            try {
                JsonNode object = objectMapper.readTree(candidate);
                if (object != null && object.isObject()) {
                    objects.add(object);
                }
            } catch (Exception exception) {
                return content;
            }
            index = closingIndex + 1;
        }
        if (!objects.isEmpty()) {
            if (objects.size() == 1 && objects.getFirst().has("memories")) {
                return objects.getFirst().toString();
            }
            return objectMapper.createObjectNode().set("memories",
                    objectMapper.createArrayNode().addAll(objects)).toString();
        }
        return content;
    }

    private int nextJsonObject(String content, int start) {
        int openingIndex = content.indexOf('{', start);
        return openingIndex;
    }

    private int matchingJsonEnd(String content, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < content.length(); index++) {
            char current = content.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private boolean isJsonDocument(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            return root != null && (root.isObject() || root.isArray());
        } catch (Exception exception) {
            return false;
        }
    }

        private record Request(List<Message> messages, boolean stream, int max_tokens,
            double temperature, ResponseFormat response_format, String reasoning_format,
            ChatTemplateKwargs chat_template_kwargs) {
    }

    private record Message(String role, String content) {
    }

    private record ResponseFormat(String type) {
    }

    private record ChatTemplateKwargs(boolean enable_thinking) {
    }

    private record Response(List<Choice> choices) {
    }

    private record Choice(MessageResponse message) {
    }

    private record MessageResponse(String role, String content) {
    }
}