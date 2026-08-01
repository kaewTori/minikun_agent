package com.minikun.memory.internal;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.MemoryException;

final class MemoryResponseParser {
    private final ObjectMapper objectMapper;

    MemoryResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<RawCandidate> parse(String response) {
        if (response == null || response.isBlank()) {
            throw new MemoryException("memory extraction response is empty");
        }
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode memories = root == null ? null : root.get("memories");
            if (memories == null || !memories.isArray()) {
                throw new MemoryException("memory extraction response must contain a memories array");
            }
            List<RawCandidate> result = new ArrayList<>();
            for (JsonNode memory : memories) {
                result.add(new RawCandidate(
                        text(memory, "category"),
                        text(memory, "content"),
                        number(memory, "confidence"),
                        text(memory, "reason")));
            }
            return List.copyOf(result);
        } catch (MemoryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MemoryException("memory extraction response is not valid JSON", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : Double.NaN;
    }

    record RawCandidate(String category, String content, double confidence, String reason) {
    }
}
