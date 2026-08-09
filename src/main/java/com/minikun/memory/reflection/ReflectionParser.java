package com.minikun.memory.reflection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.minikun.memory.MemoryException;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryCandidate;

public final class ReflectionParser {
    private static final Logger log = LoggerFactory.getLogger(ReflectionParser.class);
    private static final List<String> MEMORY_FIELDS = List.of("category", "content", "confidence", "reason");
    private static final String PARSED = "minikun.memory.reflection.candidates.parsed";
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ReflectionParser(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public ReflectionParser(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public List<MemoryCandidate> parse(String response, CompletedConversation conversation) {
        try {

            log.info("response data is {}", response);

            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(response);
            JsonNode memories = memoriesNode(root);
            List<MemoryCandidate> result = new ArrayList<>();
            for (JsonNode memory : memories) {
                requireObject(memory, "memory");
                requireExactFields(memory, MEMORY_FIELDS, "memory");
                String category = requiredText(memory, "category");
                String content = requiredText(memory, "content");
                String reason = requiredText(memory, "reason");
                JsonNode confidence = required(memory, "confidence");
                if (!confidence.isNumber() || !Double.isFinite(confidence.asDouble())) {
                    throw invalid("confidence must be a finite number");
                }
                double confidenceValue = confidence.asDouble();
                if (confidenceValue < 0.0 || confidenceValue > 1.0) {
                    throw invalid("confidence must be between 0 and 1");
                }
                try {
                    result.add(new MemoryCandidate(conversation.conversationId(),
                            MemoryCategory.valueOf(category), content, confidenceValue, reason));
                } catch (IllegalArgumentException exception) {
                    throw invalid("invalid memory category", exception);
                }
            }
            List<MemoryCandidate> parsed = List.copyOf(result);
            incrementParsed(parsed.size());
            return parsed;
        } catch (MemoryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MemoryException("reflection response is not valid JSON", exception);
        }
    }

    private JsonNode memoriesNode(JsonNode root) {
        if (root != null && root.isArray()) {
            return root;
        }
        if (root != null && root.isObject() && root.size() == 1
                && root.get("memories") != null && root.get("memories").isArray()) {
            return root.get("memories");
        }
        throw invalid("response must be an array or an object containing a memories array");
    }

    private void incrementParsed(int count) {
        try {
            Counter.builder(PARSED).register(meterRegistry).increment(count);
        } catch (RuntimeException ignored) {
        }
    }

    private JsonNode required(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw invalid(field + " is required and must not be null");
        }
        return value;
    }

    private String requiredText(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be a non-blank string");
        }
        return value.textValue();
    }

    private void requireObject(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw invalid(name + " must be an object");
        }
    }

    private void requireExactFields(JsonNode object, List<String> expected, String name) {
        var fields = new java.util.HashSet<String>();
        object.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(new java.util.HashSet<>(expected))) {
            throw invalid(name + " contains missing or additional fields");
        }
    }

    private MemoryException invalid(String message) {
        return new MemoryException(message);
    }

    private MemoryException invalid(String message, Throwable cause) {
        return new MemoryException(message, cause);
    }

}