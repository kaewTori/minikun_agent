package com.minikun.search.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.SearchDecisionClient;
import com.minikun.search.SearchDecisionClientException;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionPrompt;
import com.minikun.search.model.SearchDecisionReason;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

final class HttpSearchDecisionClient implements SearchDecisionClient {
    private static final Set<String> RESPONSE_FIELDS = Set.of("shouldSearch", "reason");
    private static final Set<SearchDecisionReason> REMOTE_REASONS = Set.of(
            SearchDecisionReason.CURRENT_INFORMATION,
            SearchDecisionReason.FACT_LOOKUP,
            SearchDecisionReason.EXTERNAL_RESOURCE,
            SearchDecisionReason.GENERAL_KNOWLEDGE);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    HttpSearchDecisionClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public SearchDecision classify(SearchDecisionPrompt prompt) {
        try {
            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(
                        List.of(
                                new Message("system", prompt.instructions()),
                            new Message("user", "/no_think\ncurrentDate: %s\nuserMessage: %s"
                                .formatted(prompt.currentDate(), prompt.userMessage()))),
                        false,
                        256,
                        0.0,
                        new ResponseFormat("json_schema", new JsonSchema(
                            "search_decision",
                            new DecisionSchema(
                                "object",
                                Map.of(
                                    "shouldSearch", new PropertySchema("boolean"),
                                    "reason", new PropertySchema("string")),
                                List.of("shouldSearch", "reason"),
                                false),
                            true)),
                        "none",
                        new ChatTemplateKwargs(false)))
                    .retrieve()
                    .body(String.class);
                JsonNode responseRoot = objectMapper.readTree(response);
                JsonNode content = responseRoot.path("choices").path(0).path("message").path("content");
                if (!content.isTextual()) {
                throw new SearchDecisionClientException("llama.cpp response has no message content", null);
                }
                JsonNode root = objectMapper.readTree(content.textValue());
                validateSchema(root);
                boolean shouldSearch = root.get("shouldSearch").booleanValue();
                SearchDecisionReason reason = SearchDecisionReason.valueOf(root.get("reason").textValue());
            return new SearchDecision(shouldSearch, prompt.userMessage(), reason);
        } catch (SearchDecisionClientException exception) {
            throw exception;
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new SearchDecisionClientException("search decision client failed", exception);
        }
    }

    private void validateSchema(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != RESPONSE_FIELDS.size()) {
            throw new SearchDecisionClientException("search decision response must be an object with exactly two fields", null);
        }
        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            if (!RESPONSE_FIELDS.contains(fields.next())) {
                throw new SearchDecisionClientException("search decision response contains an unknown field", null);
            }
        }
        JsonNode shouldSearch = root.get("shouldSearch");
        JsonNode reason = root.get("reason");
        if (shouldSearch == null || reason == null || shouldSearch.isNull() || reason.isNull()
                || !shouldSearch.isBoolean() || !reason.isTextual()) {
            throw new SearchDecisionClientException("search decision response has invalid fields", null);
        }
        SearchDecisionReason parsedReason;
        try {
            parsedReason = SearchDecisionReason.valueOf(reason.textValue());
        } catch (IllegalArgumentException exception) {
            throw new SearchDecisionClientException("search decision response has an invalid reason", exception);
        }
        if (!REMOTE_REASONS.contains(parsedReason)) {
            throw new SearchDecisionClientException("search decision response contains an internal reason", null);
        }
    }

    private record Request(List<Message> messages, boolean stream, int max_tokens,
            double temperature, ResponseFormat response_format, String reasoning_format,
            ChatTemplateKwargs chat_template_kwargs) {
    }

    private record Message(String role, String content) {
    }

    private record ResponseFormat(String type, JsonSchema json_schema) {
    }

        private record JsonSchema(String name, DecisionSchema schema, boolean strict) {
        }

        private record DecisionSchema(String type, Map<String, PropertySchema> properties,
            List<String> required, boolean additionalProperties) {
        }

        private record PropertySchema(String type) {
        }

    private record ChatTemplateKwargs(boolean enable_thinking) {
    }

}