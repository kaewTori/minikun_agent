package com.minikun.memory.reflection;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryException;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryCandidate;

public final class ReflectionParser {
    private static final List<String> MEMORY_FIELDS = List.of("category", "content", "confidence", "reason");
    private final ObjectMapper objectMapper;

    public ReflectionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MemoryCandidate> parse(String response, String conversationId) {
        try {
            JsonNode root = objectMapper.readTree(response);
            requireObject(root, "response");
            requireExactFields(root, List.of("memories"), "response");
            JsonNode memories = required(root, "memories");
            if (!memories.isArray()) {
                throw invalid("memories must be an array");
            }
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
                        result.add(new MemoryCandidate(conversationId,
                            MemoryCategory.valueOf(category), content, confidenceValue, reason));
                } catch (IllegalArgumentException exception) {
                    throw invalid("invalid memory category", exception);
                }
            }
            return List.copyOf(result);
        } catch (MemoryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MemoryException("reflection response is not valid JSON", exception);
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