package com.minikun.guardian;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Reads only configured log files, with bounded tails and credential redaction. */
public final class GuardianLogReader {
    private static final int MAX_LINES = 200;
    private static final int MAX_TAIL_BYTES = 131_072;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|bearer|token|api[-_]?key|password|passwd|secret)(\\s*[:=]\\s*|\\s+)([^\\s,;]+)");
    private static final Pattern URL_CREDENTIAL = Pattern.compile("(?i)(https?://)([^/@\\s]+)@([A-Za-z0-9.-]+)");

    private final Map<String, GuardianLogSource> sources;

    public GuardianLogReader(List<GuardianLogSource> sources) {
        Map<String, GuardianLogSource> configured = new LinkedHashMap<>();
        for (GuardianLogSource source : sources == null ? List.<GuardianLogSource>of() : sources) {
            if (configured.putIfAbsent(source.name(), source) != null) {
                throw new IllegalArgumentException("duplicate guardian log source: " + source.name());
            }
        }
        this.sources = Map.copyOf(configured);
    }

    public List<String> sourceNames() {
        return sources.keySet().stream().sorted().toList();
    }

    public GuardianLogSnapshot read(String sourceName, int requestedLines) {
        GuardianLogSource source = sources.get(normalize(sourceName));
        if (source == null) {
            throw new IllegalArgumentException("unknown guardian log source; allowed sources: " + sourceNames());
        }
        int limit = Math.max(1, Math.min(requestedLines, MAX_LINES));
        if (!Files.isRegularFile(source.path()) || !Files.isReadable(source.path())) {
            return new GuardianLogSnapshot(source.name(), "UNAVAILABLE", List.of(), 0, 0);
        }
        try {
            List<String> lines = tail(source, limit);
            int warnings = 0;
            int errors = 0;
            for (String line : lines) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (upper.contains("WARN")) warnings++;
                if (upper.contains("ERROR") || upper.contains("EXCEPTION") || upper.contains("FATAL")) errors++;
            }
            return new GuardianLogSnapshot(source.name(), "UP", lines, warnings, errors);
        } catch (IOException exception) {
            return new GuardianLogSnapshot(source.name(), "UNAVAILABLE", List.of(), 0, 0);
        }
    }

    public List<GuardianLogSnapshot> inspectAll(int lines) {
        return sourceNames().stream().map(name -> read(name, lines)).toList();
    }

    private List<String> tail(GuardianLogSource source, int limit) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(source.path().toFile(), "r")) {
            long length = file.length();
            int bytesToRead = (int) Math.min(length, MAX_TAIL_BYTES);
            byte[] bytes = new byte[bytesToRead];
            file.seek(length - bytesToRead);
            file.readFully(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] split = content.split("\\R");
            List<String> result = new ArrayList<>();
            int start = Math.max(bytesToRead < length ? 1 : 0, split.length - limit);
            for (int index = start; index < split.length; index++) {
                String redacted = redact(split[index]);
                if (!redacted.isBlank()) result.add(redacted);
            }
            if (result.size() > limit) result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
            return Collections.unmodifiableList(result);
        }
    }

    private String redact(String line) {
        String redacted = SECRET_ASSIGNMENT.matcher(line).replaceAll("$1$2[REDACTED]");
        return URL_CREDENTIAL.matcher(redacted).replaceAll("$1[REDACTED]@$3");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
