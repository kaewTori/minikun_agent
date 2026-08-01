package com.minikun.character;

import java.util.Arrays;
import java.util.List;

final class MarkdownSectionParser {
    List<String> parse(String content) {
        return Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .filter(line -> !line.matches("-{3,}"))
                .map(this::normalize)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String normalize(String line) {
        String normalized = line.replaceFirst("^[-*+]\\s+", "");
        normalized = normalized.replaceFirst("^\\d+[.)]\\s+", "");
        return normalized.replace("**", "").trim();
    }
}
