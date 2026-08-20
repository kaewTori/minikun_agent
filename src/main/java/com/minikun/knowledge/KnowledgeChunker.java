package com.minikun.knowledge;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Heading-aware bounded text chunking with a small overlap between chunks. */
final class KnowledgeChunker {
    private final int maximumCharacters;
    private final int overlapCharacters;

    KnowledgeChunker(int maximumCharacters, int overlapCharacters) {
        if (maximumCharacters < 256 || overlapCharacters < 0 || overlapCharacters >= maximumCharacters / 2) {
            throw new IllegalArgumentException("invalid knowledge chunk limits");
        }
        this.maximumCharacters = maximumCharacters;
        this.overlapCharacters = overlapCharacters;
    }

    List<ChunkText> chunk(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<ChunkText> result = new ArrayList<>();
        String heading = "";
        StringBuilder current = new StringBuilder();
        for (String rawLine : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.matches("^#{1,6}\\s+.+")) heading = line.replaceFirst("^#{1,6}\\s+", "").trim();
            appendLine(result, current, heading, line);
        }
        flush(result, current, heading);
        return List.copyOf(result);
    }

    private void appendLine(List<ChunkText> result, StringBuilder current, String heading, String line) {
        String remaining = line;
        do {
            int separator = current.isEmpty() ? 0 : 1;
            int available = maximumCharacters - current.length() - separator;
            if (available <= 0) {
                flush(result, current, heading);
                continue;
            }
            int take = Math.min(available, remaining.length());
            if (!current.isEmpty()) current.append('\n');
            current.append(remaining, 0, take);
            remaining = remaining.substring(take);
            if (!remaining.isEmpty()) flush(result, current, heading);
        } while (!remaining.isEmpty());
    }

    private void flush(List<ChunkText> result, StringBuilder current, String heading) {
        String value = current.toString().trim();
        if (!value.isBlank()) result.add(new ChunkText(heading == null ? "" : heading, value, sha256(value)));
        String overlap = value.length() <= overlapCharacters ? value
                : value.substring(value.length() - overlapCharacters);
        current.setLength(0);
        if (!overlap.isBlank()) current.append(overlap.stripLeading());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record ChunkText(String heading, String content, String hash) {}
}
