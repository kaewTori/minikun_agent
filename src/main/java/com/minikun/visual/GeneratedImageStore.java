package com.minikun.visual;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Stores generated media outside conversation history and exposes opaque local URLs. */
public final class GeneratedImageStore {
    private final Path root;
    private final int maximumImageBytes;
    private final Clock clock;

    public GeneratedImageStore(Path root, int maximumImageBytes, Clock clock) {
        this.root = Objects.requireNonNull(root, "image directory must not be null").toAbsolutePath().normalize();
        if (maximumImageBytes < 1) {
            throw new IllegalArgumentException("maximum image bytes must be positive");
        }
        this.maximumImageBytes = maximumImageBytes;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public StoredImage save(byte[] source) {
        byte[] bytes = Objects.requireNonNull(source, "image bytes must not be null").clone();
        if (bytes.length == 0 || bytes.length > maximumImageBytes) {
            throw new ImageGenerationException("generated image has an invalid size");
        }
        ImageType type = ImageType.detect(bytes);
        String filename = UUID.randomUUID() + "." + type.extension;
        Path target = root.resolve(filename).normalize();
        if (!target.getParent().equals(root)) {
            throw new ImageGenerationException("generated image path is invalid");
        }
        try {
            Files.createDirectories(root);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new ImageGenerationException("generated image could not be stored", exception);
        }
        return new StoredImage(filename, "/v1/images/generated/" + filename,
                type.contentType, bytes.length, clock.instant());
    }

    public StoredImageContent read(String filename) {
        String safeName = filename == null ? "" : filename.strip().toLowerCase(Locale.ROOT);
        if (!safeName.matches("[0-9a-f-]{36}\\.(?:png|jpg|webp)")) {
            throw new ImageGenerationException("generated image name is invalid");
        }
        Path target = root.resolve(safeName).normalize();
        if (!target.getParent().equals(root) || !Files.isRegularFile(target)) {
            throw new ImageGenerationException("generated image was not found");
        }
        try {
            long length = Files.size(target);
            if (length < 1 || length > maximumImageBytes) {
                throw new ImageGenerationException("stored image has an invalid size");
            }
            byte[] bytes = Files.readAllBytes(target);
            ImageType type = ImageType.detect(bytes);
            return new StoredImageContent(bytes, type.contentType);
        } catch (IOException exception) {
            throw new ImageGenerationException("generated image could not be read", exception);
        }
    }

    public record StoredImage(String filename, String url, String contentType, int bytes, Instant createdAt) { }

    public record StoredImageContent(byte[] bytes, String contentType) {
        public StoredImageContent {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private enum ImageType {
        PNG("png", "image/png"),
        JPEG("jpg", "image/jpeg"),
        WEBP("webp", "image/webp");

        private final String extension;
        private final String contentType;

        ImageType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        private static ImageType detect(byte[] bytes) {
            if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4e && bytes[3] == 0x47 && bytes[4] == 0x0d
                    && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) {
                return PNG;
            }
            if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
                return JPEG;
            }
            if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                    && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W'
                    && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return WEBP;
            }
            throw new ImageGenerationException("generated image format is unsupported");
        }
    }
}
