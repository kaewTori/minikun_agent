package com.minikun.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;

/** Private, bounded local storage; only opaque artifact IDs are exposed to callers. */
final class PresentationStore {
    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    private final Path root;
    private final Path operations;
    private final int maximumBytes;
    private final Duration retention;
    private final ObjectMapper mapper;
    private final Clock clock;

    PresentationStore(Path root, int maximumBytes, Duration retention, ObjectMapper mapper, Clock clock) {
        this.root = Objects.requireNonNull(root, "presentation directory must not be null")
                .toAbsolutePath().normalize();
        this.operations = this.root.resolve(".operations");
        if (maximumBytes < 1 || retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("presentation storage limits must be positive");
        }
        this.maximumBytes = maximumBytes;
        this.retention = retention;
        this.mapper = Objects.requireNonNull(mapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ponytail: in-process commit lock; use a database lock if multiple app instances share this directory.
    synchronized Stored save(byte[] bytes, PresentationSpec spec, String ownerId,
            String conversationId, String requestId) {
        return save(bytes, spec, ownerId, conversationId, requestId, "");
    }

    synchronized Stored save(byte[] bytes, PresentationSpec spec, String ownerId,
            String conversationId, String requestId, String parentArtifactId) {
        Objects.requireNonNull(bytes, "presentation bytes must not be null");
        Objects.requireNonNull(spec, "presentation spec must not be null");
        String owner = required(ownerId, "owner id");
        String conversation = required(conversationId, "conversation id");
        String request = requestId == null ? "" : requestId.strip();
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw new IllegalArgumentException("generated presentation exceeds the file size limit");
        }
        try {
            createPrivateDirectories();
            String specJson = mapper.writeValueAsString(spec);
            String operationKey = request.isEmpty() ? "" : hash(owner + "\u0000" + conversation
                    + "\u0000" + request + "\u0000" + parentArtifactId + "\u0000" + specJson);
            if (!operationKey.isBlank()) {
                Metadata existing = readMetadata(operations.resolve(operationKey + ".json"));
                if (existing != null && existing.ownerId().equals(owner)
                        && Files.isRegularFile(file(existing.artifactId()), LinkOption.NOFOLLOW_LINKS)) {
                    return existing.toStored(this);
                }
            }

            String artifactId = UUID.randomUUID().toString();
            Instant createdAt = clock.instant();
            Metadata metadata = new Metadata(artifactId, owner, conversation, operationKey, parentArtifactId,
                    spec.title(), spec.theme(), spec.slides().size(), bytes.length, createdAt.toString(),
                    specJson);
            Path target = file(artifactId);
            Path sidecar = sidecar(artifactId);
            Path temporary = Files.createTempFile(root, ".presentation-", ".tmp");
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                move(temporary, target);
                writeAtomic(sidecar, mapper.writeValueAsBytes(metadata));
                if (!operationKey.isBlank()) {
                    writeAtomic(operations.resolve(operationKey + ".json"), mapper.writeValueAsBytes(metadata));
                }
                return metadata.toStored(this);
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                Files.deleteIfExists(target);
                Files.deleteIfExists(sidecar);
                throw exception;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("generated presentation could not be stored", exception);
        }
    }

    Stored read(String artifactId, String ownerId) {
        if (artifactId == null || !ID.matcher(artifactId).matches()) {
            throw new IllegalArgumentException("presentation was not found");
        }
        Metadata metadata = readMetadata(sidecar(artifactId));
        if (metadata == null || !metadata.artifactId().equals(artifactId)
                || !metadata.ownerId().equals(ownerId == null ? "" : ownerId.strip())) {
            throw new IllegalArgumentException("presentation was not found");
        }
        return metadata.toStored(this);
    }

    // ponytail: scan the bounded-retention artifact directory; add an index if revision lookup becomes hot.
    synchronized Stored latest(String ownerId, String conversationId) {
        String owner = required(ownerId, "owner id");
        String conversation = required(conversationId, "conversation id");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("presentation was not found");
        }
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
                    .map(this::readMetadata)
                    .filter(Objects::nonNull)
                    .filter(metadata -> metadata.ownerId().equals(owner)
                            && metadata.conversationId().equals(conversation)
                            && Files.isRegularFile(file(metadata.artifactId()), LinkOption.NOFOLLOW_LINKS))
                    .max(Comparator.comparing(Metadata::createdAt).thenComparing(Metadata::artifactId))
                    .map(metadata -> metadata.toStored(this))
                    .orElseThrow(() -> new IllegalArgumentException("presentation was not found"));
        } catch (IOException exception) {
            throw new IllegalStateException("presentation could not be read", exception);
        }
    }

    byte[] bytes(Stored stored) {
        Path file = file(stored.artifactId());
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("presentation was not found");
            }
            long size = Files.size(file);
            if (size < 1 || size > maximumBytes || size != stored.bytes()) {
                throw new IllegalArgumentException("stored presentation has an invalid size");
            }
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new IllegalArgumentException("presentation was not found", exception);
        }
    }

    @Scheduled(fixedDelayString = "${minikun.presentation.cleanup-interval:PT6H}")
    void removeExpired() {
        Instant cutoff = clock.instant().minus(retention);
        synchronized (this) {
            try {
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
                try (var files = Files.list(root)) {
                    for (Path path : files.filter(item -> item.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
                            .toList()) {
                        Metadata metadata = readMetadata(path);
                        if (metadata == null || Instant.parse(metadata.createdAt()).isAfter(cutoff)) continue;
                        Files.deleteIfExists(file(metadata.artifactId()));
                        Files.deleteIfExists(path);
                        if (metadata.operationKey() != null && metadata.operationKey().matches("[0-9a-f]{64}")) {
                            Files.deleteIfExists(operations.resolve(metadata.operationKey() + ".json"));
                        }
                    }
                }
            } catch (IOException exception) {
                // A later scheduled pass retries cleanup; it never touches files outside this directory.
            }
        }
    }

    private Metadata readMetadata(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 128_000) return null;
            Metadata metadata = mapper.readValue(Files.readAllBytes(path), Metadata.class);
            return metadata != null && ID.matcher(metadata.artifactId()).matches() ? metadata : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private void createPrivateDirectories() throws IOException {
        var privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("presentation storage must be a real directory");
        }
        try {
            Files.createDirectories(root, privateDirectory);
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            Files.createDirectories(operations, privateDirectory);
            Files.setPosixFilePermissions(operations, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            Files.createDirectories(root);
            Files.createDirectories(operations);
        }
    }

    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target);
        Path temporary = Files.createTempFile(target.getParent(), ".metadata-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("presentation was not found");
        Path path = root.resolve(id + ".pptx").normalize();
        if (!path.getParent().equals(root)) throw new IllegalArgumentException("presentation path is invalid");
        return path;
    }

    private Path sidecar(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("presentation was not found");
        return root.resolve(id + ".json").normalize();
    }

    private static String required(String value, String name) {
        String result = value == null ? "" : value.strip();
        if (result.isBlank() || result.length() > 256) throw new IllegalArgumentException(name + " is required");
        return result;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Metadata(String artifactId, String ownerId, String conversationId, String operationKey,
            String parentArtifactId,
            String title, String theme, int slideCount, long bytes, String createdAt, String specJson) {
        Stored toStored(PresentationStore store) {
            return new Stored(artifactId, ownerId, conversationId, title, theme, slideCount, bytes,
                    Instant.parse(createdAt), "/v1/presentations/" + artifactId + "/download", specJson,
                    parentArtifactId == null ? "" : parentArtifactId);
        }
    }

    record Stored(String artifactId, String ownerId, String conversationId, String title, String theme,
            int slideCount, long bytes, Instant createdAt, String url, String specJson, String parentArtifactId) {
        String filename() { return artifactId + ".pptx"; }
    }
}
