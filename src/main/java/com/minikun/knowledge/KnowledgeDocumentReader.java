package com.minikun.knowledge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Reads bounded UTF-8 knowledge documents from configured roots without following symlinks. */
final class KnowledgeDocumentReader {
    private static final Set<String> EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "yaml", "yml", "csv", "tsv", "properties",
            "java", "kt", "kts", "xml", "html", "css", "js", "ts", "sql", "log", "ics");
    private static final Set<String> BLOCKED = Set.of("pem", "key", "p12", "pfx", "keystore", "jks");

    private final Map<String, KnowledgeRoot> roots;
    private final long maxFileBytes;
    private final int maxFiles;
    private final int maxDepth;

    KnowledgeDocumentReader(List<KnowledgeRoot> roots, long maxFileBytes, int maxFiles, int maxDepth) {
        Map<String, KnowledgeRoot> configured = new LinkedHashMap<>();
        roots.forEach(root -> configured.put(root.name(), root));
        this.roots = Map.copyOf(configured);
        if (maxFileBytes < 1024 || maxFiles < 1 || maxDepth < 1) {
            throw new IllegalArgumentException("knowledge ingestion limits must be positive");
        }
        this.maxFileBytes = maxFileBytes;
        this.maxFiles = maxFiles;
        this.maxDepth = maxDepth;
    }

    List<KnowledgeStatus.RootStatus> rootStatus() {
        return roots.values().stream()
                .map(root -> new KnowledgeStatus.RootStatus(root.name(), Files.isDirectory(root.path())))
                .toList();
    }

    List<KnowledgeDocument> read(String rootName, String relativePath, boolean recursive) {
        KnowledgeRoot root = root(rootName);
        Path target = resolve(root, relativePath);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return List.of(read(root, target));
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("knowledge path does not exist or is not a regular file/directory");
        }
        int depth = recursive ? maxDepth : 1;
        try (Stream<Path> paths = Files.walk(target, depth)) {
            List<Path> files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::supported).sorted(Comparator.comparing(Path::toString))
                    .limit((long) maxFiles + 1L).toList();
            if (files.size() > maxFiles) throw new IllegalArgumentException("knowledge path exceeds file limit");
            List<KnowledgeDocument> result = new ArrayList<>(files.size());
            for (Path path : files) result.add(read(root, path));
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalArgumentException("knowledge directory could not be read", exception);
        }
    }

    KnowledgeDocument readRegistered(KnowledgeSourceRecord source) {
        return read(source.root(), source.path(), false).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("registered knowledge source is missing"));
    }

    private KnowledgeDocument read(KnowledgeRoot root, Path path) {
        requireSupported(path);
        try {
            long size = Files.size(path);
            if (size > maxFileBytes) throw new IllegalArgumentException("knowledge file exceeds size limit");
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > maxFileBytes) throw new IllegalArgumentException("knowledge file exceeds size limit");
            String content = decode(bytes).replace("\u0000", "").trim();
            if (content.isBlank()) throw new IllegalArgumentException("knowledge file is empty");
            String relative = root.path().toRealPath().relativize(path.toRealPath()).toString()
                    .replace(java.io.File.separatorChar, '/');
            return new KnowledgeDocument(root.name(), relative, path.getFileName().toString(), path,
                    content, sha256(bytes), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
        } catch (IOException exception) {
            throw new IllegalArgumentException("knowledge file could not be read", exception);
        }
    }

    private Path resolve(KnowledgeRoot root, String relativePath) {
        if (!Files.isDirectory(root.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("knowledge root is unavailable");
        }
        String value = relativePath == null ? "" : relativePath.trim();
        if (value.startsWith("/") || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("knowledge path must be relative");
        }
        Path target = root.path().resolve(value).normalize();
        if (!target.startsWith(root.path()) || Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("knowledge path escapes its configured root or is a symlink");
        }
        try {
            Path realRoot = root.path().toRealPath();
            // The final path was already rejected when it is a symlink. Resolve parent aliases
            // here so macOS /var -> /private/var and configured symlinked roots compare correctly.
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                throw new IllegalArgumentException("knowledge path escapes its configured root or is a symlink");
            }
            return realTarget;
        } catch (IOException exception) {
            throw new IllegalArgumentException("knowledge path does not exist", exception);
        }
    }

    private KnowledgeRoot root(String name) {
        KnowledgeRoot root = roots.get(name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
        if (root == null) throw new IllegalArgumentException("unknown knowledge root");
        return root;
    }

    private boolean supported(Path path) {
        try {
            requireSupported(path);
            return !Files.isHidden(path) && java.util.stream.StreamSupport.stream(path.spliterator(), false)
                    .noneMatch(part -> part.toString().startsWith("."));
        } catch (IllegalArgumentException | IOException exception) {
            return false;
        }
    }

    private void requireSupported(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".") || ".env".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("hidden files and .env are not knowledge sources");
        }
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(extension) || !EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("unsupported knowledge file type");
        }
    }

    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("knowledge file must be valid UTF-8 text");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
