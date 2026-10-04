package com.minikun.search.internal;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;
import com.minikun.search.SearchDecisionClientException;
import com.minikun.search.SearchDecisionProvider;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionPrompt;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchPlanHints;

final class TaskModelSearchDecisionProvider implements SearchDecisionProvider {
    private static final Set<SearchDecisionReason> MODEL_REASONS = Set.of(
            SearchDecisionReason.CURRENT_INFORMATION,
            SearchDecisionReason.FACT_LOOKUP,
            SearchDecisionReason.EXTERNAL_RESOURCE,
            SearchDecisionReason.IMAGE_REQUEST,
            SearchDecisionReason.GENERAL_KNOWLEDGE);
    private static final Set<String> FIELDS = Set.of(
            "shouldSearch", "reason", "intent", "confidence", "searchQuery",
            "alternateQueries", "evidenceNeeds", "location");
    private static final Set<String> INTENTS = Set.of(
            "local_discovery", "current_information", "fact_lookup", "research", "comparison", "images", "general");
    private static final Set<String> EVIDENCE_NEEDS = Set.of(
            "opening_hours", "rating", "location", "price", "availability", "transit_access",
            "official_source", "freshness", "atmosphere");
    // ponytail: split common conjunctions only; use semantic segmentation if wider phrasing proves necessary.
    private static final Pattern REQUEST_SEPARATOR = Pattern.compile(
            "(?iu)(?:\\s*(?:แล้ว|และ|;|\\n)\\s*|\\s+(?:and|then)\\s+)");
    private static final String PART_INSTRUCTIONS = """
            Classify ONE request into reason and searchQuery, output JSON only.
            Reasons: EXTERNAL_RESOURCE for recommending real places or services;
            CURRENT_INFORMATION for time-varying facts; FACT_LOOKUP for external source;
            IMAGE_REQUEST for finding images; GENERAL_KNOWLEDGE for stable explanations or conversation.
            searchQuery must be a concise nonempty query in the user language if search is needed,
            empty otherwise. Never invent locations or constraints. No examples.
            """;
    private final TaskModelProvider taskModelProvider;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    TaskModelSearchDecisionProvider(TaskModelProvider taskModelProvider, ObjectMapper objectMapper) {
        this(taskModelProvider, objectMapper, Duration.ofSeconds(5));
    }

    TaskModelSearchDecisionProvider(
            TaskModelProvider taskModelProvider, ObjectMapper objectMapper, Duration timeout) {
        this.taskModelProvider = Objects.requireNonNull(taskModelProvider, "taskModelProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    @Override
    public SearchDecision classify(SearchDecisionPrompt prompt) {
        String message = prompt.userMessage();
        int current = message.lastIndexOf("Current user message:\n");
        int start = current < 0 ? 0 : current + "Current user message:\n".length();
        String prefix = message.substring(0, start);
        List<String> parts = java.util.Arrays.stream(REQUEST_SEPARATOR.split(message.substring(start)))
                .map(String::trim).filter(part -> !part.isBlank()).toList();
        if (parts.size() < 2 || parts.size() > 4) return classifySingle(prompt);
        List<SearchDecision> decisions = new ArrayList<>();
        for (String part : parts) {
            try {
                decisions.add(classifySingle(new SearchDecisionPrompt(
                        PART_INSTRUCTIONS, prompt.currentDate(), prefix + part)));
            } catch (SearchDecisionClientException exception) {
                decisions.add(classifySingle(new SearchDecisionPrompt(
                        prompt.instructions(), prompt.currentDate(), prefix + part)));
            }
        }
        SearchDecision selected = decisions.stream()
                .filter(decision -> decision.reason() == SearchDecisionReason.EXTERNAL_RESOURCE)
                .findFirst().orElseGet(() -> decisions.stream().filter(SearchDecision::shouldSearch)
                        .findFirst().orElse(decisions.getFirst()));
        if (!selected.shouldSearch()) {
            return new SearchDecision(false, prompt.userMessage(), SearchDecisionReason.GENERAL_KNOWLEDGE);
        }
        int selectedIndex = decisions.indexOf(selected);
        String primary = selected.planHints().primaryQuery().isBlank()
                ? parts.get(selectedIndex) : selected.planHints().primaryQuery();
        List<String> alternates = java.util.stream.IntStream.range(0, decisions.size())
                .filter(index -> index != selectedIndex && decisions.get(index).shouldSearch())
                .mapToObj(index -> decisions.get(index).planHints().primaryQuery().isBlank()
                        ? parts.get(index) : decisions.get(index).planHints().primaryQuery())
                .filter(query -> !query.isBlank() && !query.equalsIgnoreCase(primary))
                .limit(2).toList();
        String intent = switch (selected.reason()) {
            case EXTERNAL_RESOURCE -> "local_discovery";
            case CURRENT_INFORMATION -> "current_information";
            case FACT_LOOKUP -> "fact_lookup";
            case IMAGE_REQUEST -> "images";
            default -> "general";
        };
        SearchPlanHints hints = new SearchPlanHints(intent, selected.planHints().confidence(),
                primary, alternates, selected.planHints().evidenceNeeds(), selected.planHints().location());
        return new SearchDecision(true, prompt.userMessage(), selected.reason(), hints);
    }

    private SearchDecision classifySingle(SearchDecisionPrompt prompt) {
        try {
            TaskModelRequest request = new TaskModelRequest(
                    List.of(new TaskModelMessage("system", prompt.instructions()),
                            new TaskModelMessage("user", "/no_think\ncurrentDate: %s\nuserMessage: %s"
                                    .formatted(prompt.currentDate(), prompt.userMessage()))),
                    256, 0.0, TaskModelRequest.ResponseFormat.JSON_OBJECT);
            String response;
            try {
                response = CompletableFuture.supplyAsync(() -> taskModelProvider.generate(request))
                        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .get();
            } catch (ExecutionException exception) {
                throw new SearchDecisionClientException("search decision timed out", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SearchDecisionClientException("search decision interrupted", exception);
            }
            JsonNode root = objectMapper.readTree(response);
            validateSchema(root);
            SearchDecisionReason reason = SearchDecisionReason.valueOf(root.get("reason").textValue());
            boolean reasonRequiresSearch = reason != SearchDecisionReason.GENERAL_KNOWLEDGE;
            SearchPlanHints hints = new SearchPlanHints(
                    text(root, "intent"), number(root, "confidence"), text(root, "searchQuery"),
                    stringList(root, "alternateQueries", 2), stringList(root, "evidenceNeeds", 6),
                    text(root, "location"));
            return new SearchDecision(reasonRequiresSearch, prompt.userMessage(), reason, hints);
        } catch (SearchDecisionClientException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new SearchDecisionClientException("search decision provider failed", exception);
        }
    }

    private void validateSchema(JsonNode root) {
        if (root == null || !root.isObject()
                || !root.has("reason") || !root.get("reason").isTextual()
                || root.has("shouldSearch") && !root.get("shouldSearch").isBoolean()) {
            throw new SearchDecisionClientException("search decision response has invalid fields", null);
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new SearchDecisionClientException("search decision response has an unknown field", null);
            }
        });
        SearchDecisionReason reason;
        try {
            reason = SearchDecisionReason.valueOf(root.get("reason").textValue());
        } catch (IllegalArgumentException exception) {
            throw new SearchDecisionClientException("search decision response has an invalid reason", exception);
        }
        if (!MODEL_REASONS.contains(reason)) {
            throw new SearchDecisionClientException("search decision response contains a disallowed reason", null);
        }
        if (root.has("intent") && (!root.get("intent").isTextual()
                || !INTENTS.contains(root.get("intent").textValue()))) {
            throw new SearchDecisionClientException("search decision response has an invalid intent", null);
        }
        if (root.has("confidence") && (!root.get("confidence").isNumber()
                || root.get("confidence").doubleValue() < 0.0
                || root.get("confidence").doubleValue() > 1.0)) {
            throw new SearchDecisionClientException("search decision response has invalid confidence", null);
        }
        validateText(root, "searchQuery", 300);
        validateText(root, "location", 160);
        validateList(root, "alternateQueries", 2, null);
        validateList(root, "evidenceNeeds", 6, EVIDENCE_NEEDS);
    }

    private String text(JsonNode root, String field) {
        return root.has(field) ? root.get(field).textValue() : "";
    }

    private double number(JsonNode root, String field) {
        return root.has(field) ? root.get(field).doubleValue() : 0.0;
    }

    private List<String> stringList(JsonNode root, String field, int limit) {
        if (!root.has(field)) return List.of();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        root.get(field).forEach(node -> values.add(node.textValue()));
        return values.stream().limit(limit).toList();
    }

    private void validateText(JsonNode root, String field, int maxLength) {
        if (root.has(field) && (!root.get(field).isTextual()
                || root.get(field).textValue().length() > maxLength)) {
            throw new SearchDecisionClientException("search decision response has invalid " + field, null);
        }
    }

    private void validateList(JsonNode root, String field, int maxSize, Set<String> allowedValues) {
        if (!root.has(field)) return;
        JsonNode values = root.get(field);
        if (!values.isArray() || values.size() > maxSize) {
            throw new SearchDecisionClientException("search decision response has invalid " + field, null);
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 300
                    || allowedValues != null && !allowedValues.contains(value.textValue())) {
                throw new SearchDecisionClientException("search decision response has invalid " + field, null);
            }
        }
    }
}
