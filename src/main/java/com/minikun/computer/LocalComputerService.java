package com.minikun.computer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Sandboxed local file operations addressed only through configured logical roots. */
public final class LocalComputerService {
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "yaml", "yml", "csv", "tsv", "properties",
            "java", "kt", "kts", "xml", "html", "css", "js", "ts", "sql", "log", "ics", "sh");
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "pem", "key", "p12", "pfx", "keystore", "jks");
    private static final String MISSING = "MISSING";

    private final Map<String, ComputerRoot> roots;
    private final ComputerAuditStore audit;
    private final ComputerCommandGateway commands;
    private final ComputerContentRedactor redactor = new ComputerContentRedactor();
    private final Clock clock;
    private final int maxReadBytes;
    private final int maxWriteBytes;
    private final int maxDepth;
    private final int maxScannedFiles;

    public LocalComputerService(
            List<ComputerRoot> roots,
            ComputerAuditStore audit,
            ComputerCommandGateway commands,
            Clock clock,
            int maxReadBytes,
            int maxWriteBytes,
            int maxDepth,
            int maxScannedFiles) {
        Map<String, ComputerRoot> configured = new LinkedHashMap<>();
        roots.forEach(value -> configured.put(value.name(), value));
        this.roots = Map.copyOf(configured);
        this.audit = Objects.requireNonNull(audit, "computer audit store must not be null");
        this.commands = Objects.requireNonNull(commands, "computer command gateway must not be null");
        this.clock = Objects.requireNonNull(clock, "computer clock must not be null");
        if (maxReadBytes < 1024 || maxWriteBytes < 1 || maxDepth < 1 || maxScannedFiles < 1) {
            throw new IllegalArgumentException("computer limits must be positive");
        }
        this.maxReadBytes = maxReadBytes;
        this.maxWriteBytes = maxWriteBytes;
        this.maxDepth = maxDepth;
        this.maxScannedFiles = maxScannedFiles;
    }

    public List<Map<String, Object>> roots() {
        return roots.values().stream().map(value -> Map.<String, Object>of(
                "name", value.name(), "available", Files.isDirectory(value.path()))).toList();
    }

    public List<ComputerEntry> list(String rootName, String relativePath, int limit) {
        Path directory = existing(rootName, relativePath, true);
        try (Stream<Path> values = Files.list(directory)) {
            return values.filter(this::visible).map(path -> entry(rootName, path)).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ComputerEntry::type).thenComparing(ComputerEntry::path))
                    .limit(safeLimit(limit)).toList();
        } catch (IOException exception) {
            throw new IllegalArgumentException("directory could not be listed");
        }
    }

    public List<ComputerSearchMatch> search(String rootName, String relativePath, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 200) {
            throw new IllegalArgumentException("search query must contain 1 to 200 characters");
        }
        Path directory = existing(rootName, relativePath, true);
        String needle = query.toLowerCase(Locale.ROOT);
        List<ComputerSearchMatch> result = new ArrayList<>();
        int safeLimit = safeLimit(limit);
        try (Stream<Path> paths = Files.walk(directory, maxDepth)) {
            var iterator = paths.filter(this::readableTextFile).limit(maxScannedFiles).iterator();
            while (iterator.hasNext() && result.size() < safeLimit) {
                Path path = iterator.next();
                String relative = relative(rootName, path);
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                    result.add(new ComputerSearchMatch(relative, "filename", ""));
                    continue;
                }
                byte[] bytes = readBounded(path, Math.min(maxReadBytes, 32_768));
                String content = new String(bytes, StandardCharsets.UTF_8);
                int index = content.toLowerCase(Locale.ROOT).indexOf(needle);
                if (index >= 0) {
                    int start = Math.max(0, index - 80);
                    int end = Math.min(content.length(), index + query.length() + 120);
                    result.add(new ComputerSearchMatch(relative, "content",
                            redactor.redact(content.substring(start, end).replaceAll("\\s+", " ").trim())));
                }
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalArgumentException("files could not be searched");
        }
    }

    public ComputerFileContent read(String rootName, String relativePath) {
        Path path = existing(rootName, relativePath, false);
        requireText(path);
        try {
            long size = Files.size(path);
            byte[] bytes = readBounded(path, maxReadBytes);
            rejectBinary(bytes);
            return new ComputerFileContent(rootName, relative(rootName, path), size, sha256(path),
                    size > bytes.length, redactor.redact(new String(bytes, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("file could not be read");
        }
    }

    public ComputerFolderSnapshot inspectFolder(String rootName, String relativePath) {
        Path directory = existing(rootName, relativePath, true);
        int files = 0;
        int directories = 0;
        long bytes = 0;
        boolean truncated = false;
        List<ComputerEntry> recent = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(directory, maxDepth)) {
            var iterator = paths.filter(path -> !path.equals(directory)).filter(this::visible).iterator();
            int scanned = 0;
            while (iterator.hasNext()) {
                if (++scanned > maxScannedFiles) {
                    truncated = true;
                    break;
                }
                Path path = iterator.next();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    directories++;
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files++;
                    bytes += Files.size(path);
                    ComputerEntry entry = entry(rootName, path);
                    if (entry != null) recent.add(entry);
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("folder could not be inspected");
        }
        recent.sort(Comparator.comparing(ComputerEntry::modifiedAt).reversed());
        return new ComputerFolderSnapshot(rootName, relative(rootName, directory), files, directories, bytes,
                truncated, recent.stream().limit(20).toList());
    }

    public String clipboard(String ownerId, String conversationId) {
        String content = redactor.redact(commands.clipboard());
        if (content.length() > maxReadBytes) content = content.substring(0, maxReadBytes);
        record(ownerId, conversationId, "clipboard", "clipboard", "READ", "content not stored");
        return content;
    }

    public ComputerOperationPreview preview(Map<String, Object> arguments) {
        String action = text(arguments, "action");
        return switch (action) {
            case "write" -> previewWrite(arguments);
            case "move" -> previewMove(arguments);
            case "trash" -> previewTrash(arguments);
            case "open_path" -> previewOpenPath(arguments);
            case "open_url" -> previewOpenUrl(arguments);
            case "open_app" -> previewOpenApp(arguments);
            case "workflow" -> previewWorkflow(arguments);
            default -> throw new IllegalArgumentException("operation does not support preview");
        };
    }

    public Map<String, Object> execute(
            Map<String, Object> arguments, String ownerId, String conversationId) {
        String action = text(arguments, "action");
        String target = safeTarget(arguments);
        try {
            Map<String, Object> result = switch (action) {
                case "write" -> executeWrite(arguments);
                case "move" -> executeMove(arguments);
                case "trash" -> executeTrash(arguments);
                case "open_path" -> executeOpenPath(arguments);
                case "open_url" -> executeOpenUrl(arguments);
                case "open_app" -> executeOpenApp(arguments);
                case "workflow" -> executeWorkflow(arguments);
                default -> throw new IllegalArgumentException("unsupported computer operation");
            };
            record(ownerId, conversationId, action, target, "COMPLETED", "operation completed");
            return result;
        } catch (RuntimeException exception) {
            record(ownerId, conversationId, action, target, "FAILED", safeDetail(exception.getMessage()));
            throw exception;
        }
    }

    public List<Map<String, String>> workflows() { return commands.workflows(); }
    public List<String> applications() { return commands.applications(); }
    public List<ComputerAudit> audit(String ownerId, int limit) { return audit.list(ownerId, limit); }
    public void recordRead(String ownerId, String conversationId, String operation, String root, String path) {
        String target = (root == null ? "" : root) + ":" + (path == null || path.isBlank() ? "." : path);
        record(ownerId, conversationId, operation, target, "READ", "content not stored");
    }
    public void recordRequested(String ownerId, String conversationId, ComputerOperationPreview preview) {
        record(ownerId, conversationId, preview.operation(), safeTarget(preview.executionArguments()),
                "REQUESTED", "previewed; content not stored");
    }

    private ComputerOperationPreview previewWrite(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        String path = text(arguments, "path");
        String content = value(arguments, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxWriteBytes) throw new IllegalArgumentException("content exceeds write limit");
        Path target = destination(root, path);
        requireText(target);
        String expected = Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? sha256(target) : MISSING;
        Map<String, Object> execution = copy(arguments);
        execution.put("expected_sha256", expected);
        return new ComputerOperationPreview("write",
                (MISSING.equals(expected) ? "Create " : "Replace ") + root + ":" + relative(root, target)
                        + " (" + bytes.length + " bytes)", execution);
    }

    private ComputerOperationPreview previewMove(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        Path source = existing(root, text(arguments, "path"), false);
        Path destination = destination(root, text(arguments, "target"));
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("move target already exists");
        }
        Map<String, Object> execution = copy(arguments);
        execution.put("expected_sha256", sha256(source));
        return new ComputerOperationPreview("move",
                "Move " + root + ":" + relative(root, source) + " to " + relative(root, destination), execution);
    }

    private ComputerOperationPreview previewTrash(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        Path source = existing(root, text(arguments, "path"), false);
        Map<String, Object> execution = copy(arguments);
        execution.put("expected_sha256", sha256(source));
        return new ComputerOperationPreview("trash",
                "Move " + root + ":" + relative(root, source) + " to Mini-kun's recoverable trash", execution);
    }

    private ComputerOperationPreview previewOpenPath(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        Path path = existing(root, text(arguments, "path"), null);
        return new ComputerOperationPreview("open_path", "Open " + root + ":" + relative(root, path), copy(arguments));
    }

    private ComputerOperationPreview previewOpenUrl(Map<String, Object> arguments) {
        commands.validateUrl(text(arguments, "url"));
        return new ComputerOperationPreview("open_url", "Open URL " + text(arguments, "url"), copy(arguments));
    }

    private ComputerOperationPreview previewOpenApp(Map<String, Object> arguments) {
        String app = text(arguments, "application");
        if (commands.applications().stream().noneMatch(value -> value.equalsIgnoreCase(app))) {
            throw new IllegalArgumentException("application is not allowlisted");
        }
        return new ComputerOperationPreview("open_app", "Open application " + app, copy(arguments));
    }

    private ComputerOperationPreview previewWorkflow(Map<String, Object> arguments) {
        String id = text(arguments, "workflow_id");
        Map<String, String> workflow = commands.workflows().stream()
                .filter(value -> id.equals(value.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("workflow is not allowlisted"));
        return new ComputerOperationPreview("workflow",
                "Run workflow " + id + ": " + workflow.get("description"), copy(arguments));
    }

    private Map<String, Object> executeWrite(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        Path target = destination(root, text(arguments, "path"));
        requireExpected(target, text(arguments, "expected_sha256"));
        String content = value(arguments, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxWriteBytes) throw new IllegalArgumentException("content exceeds write limit");
        requireText(target);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".minikun-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Map.of("operation", "write", "root", root, "path", relative(root, target),
                    "size_bytes", bytes.length, "sha256", sha256(target));
        } catch (IOException exception) {
            throw new IllegalArgumentException("file could not be written");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private Map<String, Object> executeMove(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        Path source = existing(root, text(arguments, "path"), false);
        requireExpected(source, text(arguments, "expected_sha256"));
        Path destination = destination(root, text(arguments, "target"));
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("move target exists");
        try {
            Files.move(source, destination);
            return Map.of("operation", "move", "root", root,
                    "from", relative(root, source), "to", relative(root, destination));
        } catch (IOException exception) {
            throw new IllegalArgumentException("file could not be moved");
        }
    }

    private Map<String, Object> executeTrash(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        Path source = existing(root, text(arguments, "path"), false);
        requireExpected(source, text(arguments, "expected_sha256"));
        Path trash = root(root).path().resolve(".minikun-trash");
        try {
            Files.createDirectories(trash);
            String name = clock.instant().toEpochMilli() + "-" + UUID.randomUUID() + "-" + source.getFileName();
            Path destination = trash.resolve(name);
            Files.move(source, destination);
            return Map.of("operation", "trash", "root", root, "path", relative(root, source),
                    "recoverable", true, "trash_id", name);
        } catch (IOException exception) {
            throw new IllegalArgumentException("file could not be moved to recoverable trash");
        }
    }

    private Map<String, Object> executeOpenPath(Map<String, Object> arguments) {
        String root = text(arguments, "root");
        Path path = existing(root, text(arguments, "path"), null);
        commands.openPath(path);
        return Map.of("operation", "open_path", "root", root, "path", relative(root, path));
    }

    private Map<String, Object> executeOpenUrl(Map<String, Object> arguments) {
        String url = text(arguments, "url");
        commands.openUrl(url);
        return Map.of("operation", "open_url", "url", url);
    }

    private Map<String, Object> executeOpenApp(Map<String, Object> arguments) {
        String app = text(arguments, "application");
        commands.openApplication(app);
        return Map.of("operation", "open_app", "application", app);
    }

    private Map<String, Object> executeWorkflow(Map<String, Object> arguments) {
        String id = text(arguments, "workflow_id");
        commands.runWorkflow(id);
        return Map.of("operation", "workflow", "workflow_id", id);
    }

    private Path existing(String rootName, String relativePath, Boolean directory) {
        Path candidate = bounded(rootName, relativePath);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException("path does not exist or is a symbolic link");
        }
        ensureRealPath(rootName, candidate);
        if (Boolean.TRUE.equals(directory) && !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path must be a directory");
        }
        if (Boolean.FALSE.equals(directory) && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path must be a regular file");
        }
        return candidate;
    }

    private Path destination(String rootName, String relativePath) {
        Path candidate = bounded(rootName, relativePath);
        Path parent = candidate.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("destination parent directory must already exist");
        }
        ensureRealPath(rootName, parent);
        if (Files.isSymbolicLink(candidate)) throw new IllegalArgumentException("symbolic links are not allowed");
        return candidate;
    }

    private Path bounded(String rootName, String relativePath) {
        ComputerRoot root = root(rootName);
        String value = relativePath == null ? "" : relativePath.trim();
        Path relative = value.isBlank() ? Path.of("") : Path.of(value);
        if (relative.isAbsolute()) throw new IllegalArgumentException("absolute paths are not allowed");
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || ".env".equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("hidden paths and environment files are not allowed");
            }
        }
        Path candidate = root.path().resolve(relative).normalize();
        if (!candidate.startsWith(root.path())) throw new IllegalArgumentException("path escapes the configured root");
        return candidate;
    }

    private void ensureRealPath(String rootName, Path path) {
        try {
            Path realRoot = root(rootName).path().toRealPath();
            if (!path.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException("path escapes the configured root");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("configured root is unavailable");
        }
    }

    private ComputerRoot root(String name) {
        ComputerRoot result = roots.get(name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
        if (result == null) throw new IllegalArgumentException("unknown computer root");
        return result;
    }

    private boolean visible(Path path) {
        return !Files.isSymbolicLink(path) && path.getFileName() != null
                && !path.getFileName().toString().startsWith(".");
    }

    private boolean readableTextFile(Path path) {
        if (!visible(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            requireText(path);
            return Files.size(path) <= Math.max(maxReadBytes * 4L, maxReadBytes);
        } catch (RuntimeException | IOException exception) {
            return false;
        }
    }

    private void requireText(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals(".env") || name.startsWith(".env.")) {
            throw new IllegalArgumentException("environment files are not readable");
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        if (BLOCKED_EXTENSIONS.contains(extension)) throw new IllegalArgumentException("sensitive key files are blocked");
        if (!TEXT_EXTENSIONS.contains(extension)) throw new IllegalArgumentException("file type is not supported as text");
    }

    private ComputerEntry entry(String rootName, Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            String type = attributes.isDirectory() ? "directory" : attributes.isRegularFile() ? "file" : "other";
            return new ComputerEntry(relative(rootName, path), type,
                    attributes.isRegularFile() ? attributes.size() : 0, attributes.lastModifiedTime().toInstant());
        } catch (IOException exception) {
            return null;
        }
    }

    private String relative(String rootName, Path path) {
        String value = root(rootName).path().relativize(path).toString();
        return value.isBlank() ? "." : value;
    }

    private byte[] readBounded(Path path, int limit) throws IOException {
        try (var input = Files.newInputStream(path)) { return input.readNBytes(limit); }
    }

    private void rejectBinary(byte[] value) {
        for (byte current : value) if (current == 0) throw new IllegalArgumentException("binary files are not readable");
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("file fingerprint could not be calculated");
        }
    }

    private void requireExpected(Path path, String expected) {
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (MISSING.equals(expected)) {
            if (exists) throw new IllegalArgumentException("file changed after preview; preview again");
        } else if (!exists || expected.isBlank() || !expected.equals(sha256(path))) {
            throw new IllegalArgumentException("file changed after preview; preview again");
        }
    }

    private int safeLimit(int value) { return Math.max(1, Math.min(value, 100)); }
    private String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : value.toString().trim();
    }
    private String value(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value.toString();
    }
    private Map<String, Object> copy(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> { if (key != null && value != null && !"confirmed".equals(key)) result.put(key, value); });
        return result;
    }
    private String safeTarget(Map<String, Object> values) {
        String path = text(values, "path");
        if (!path.isBlank()) return text(values, "root") + ":" + boundedText(path);
        String url = text(values, "url");
        if (!url.isBlank()) {
            try {
                var parsed = java.net.URI.create(url);
                return parsed.getScheme() + "://" + parsed.getHost();
            } catch (RuntimeException ignored) {
                return "invalid-url";
            }
        }
        for (String key : List.of("application", "workflow_id")) {
            String value = text(values, key);
            if (!value.isBlank()) return boundedText(value);
        }
        return "";
    }
    private String boundedText(String value) { return value.length() > 500 ? value.substring(0, 500) : value; }
    private String safeDetail(String value) {
        if (value == null || value.isBlank()) return "operation failed";
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
    private void record(String ownerId, String conversationId, String operation,
            String target, String status, String detail) {
        audit.save(new ComputerAudit(UUID.randomUUID(), ownerId, conversationId, operation,
                target, status, clock.instant(), detail));
    }
}
