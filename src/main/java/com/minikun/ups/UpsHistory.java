package com.minikun.ups;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/** Local daily JSONL history and an atomic checkpoint; works without PostgreSQL. */
public final class UpsHistory {
    private final Path directory;
    private final ObjectMapper json;
    private LocalDate lastDay;

    public UpsHistory(Path directory, ObjectMapper json) throws IOException {
        this.directory = directory;
        this.json = json.copy().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Files.createDirectories(directory);
        privateFile(directory, "rwx------");
    }

    synchronized void append(Entry entry) throws IOException {
        LocalDate day = entry.at().atZone(ZoneOffset.UTC).toLocalDate();
        Path file = directory.resolve(day + ".jsonl");
        if (!Files.exists(file)) Files.createFile(file);
        privateFile(file, "rw-------");
        try (var output = new RandomAccessFile(file.toFile(), "rw")) {
            long length = output.length();
            if (length > 0) {
                output.seek(length - 1);
                boolean incomplete = output.read() != '\n';
                output.seek(length);
                if (incomplete) output.write('\n');
            }
            output.write((json.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!day.equals(lastDay)) {
            for (Path old : files()) {
                LocalDate date = LocalDate.parse(old.getFileName().toString().substring(0, 10));
                if (date.isBefore(day.minusDays(29))) Files.delete(old);
            }
            lastDay = day;
        }
    }

    synchronized State load() throws IOException {
        Path file = directory.resolve("state.json");
        return Files.exists(file) ? json.readValue(file.toFile(), State.class) : new State();
    }

    synchronized void save(State state) throws IOException {
        Path temporary = directory.resolve("state.json.tmp");
        Files.writeString(temporary, json.writeValueAsString(state));
        privateFile(temporary, "rw-------");
        Files.move(temporary, directory.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public synchronized List<Entry> recent(int limit, boolean samples) throws IOException {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("history limit must be between 1 and 500");
        List<Entry> result = new ArrayList<>();
        for (Path file : files()) {
            ArrayDeque<Entry> tail = new ArrayDeque<>();
            try (var lines = Files.newBufferedReader(file)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Entry entry;
                    try { entry = json.readValue(line, Entry.class); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                        LoggerFactory.getLogger(UpsHistory.class).warn("UPS history has an unreadable row in {}", file.getFileName());
                        continue;
                    }
                    if (!samples && entry.type().equals("SAMPLE")) continue;
                    tail.addLast(entry);
                    if (tail.size() > limit - result.size()) tail.removeFirst();
                }
            }
            while (!tail.isEmpty()) result.add(tail.removeLast());
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }

    private List<Path> files() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
                    .sorted(Comparator.reverseOrder()).toList();
        }
    }

    private void privateFile(Path path, String permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    public record Entry(String id, Instant at, String type, String message, boolean available,
            String error, Map<String, String> variables, Long batterySeconds, boolean timingGap) { }

    /** Pending notification IDs survive restart; event history is independent of delivery. */
    public static final class State {
        public String mode = "";
        public boolean lowBattery;
        public boolean connected = true;
        public int failures;
        public Instant onBatterySince;
        public boolean timingGap;
        public Instant lastSample;
        public List<Entry> pending = new ArrayList<>();
    }
}
