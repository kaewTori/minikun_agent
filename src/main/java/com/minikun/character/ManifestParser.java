package com.minikun.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;

final class ManifestParser {
    private final YAMLMapper mapper = new YAMLMapper();

    JsonNode parse(String content) throws IOException {
        return mapper.readTree(content);
    }

    static String text(JsonNode node, String path) {
        JsonNode value = node.at(path);
        return value.isTextual() ? value.asText().trim() : "";
    }
}
