package com.minikun.vision;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import com.minikun.agent.minikun_agent.api.openai.dto.Message;

/** Resolves untrusted OpenAI image_url parts into bounded in-memory Spring AI media. */
@Service
public final class VisionInputService {
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp");

    private final boolean enabled;
    private final int maxImages;
    private final int maxImageBytes;
    private final boolean remoteUrlsEnabled;
    private final boolean allowHttp;
    private final Duration readTimeout;
    private final HttpClient httpClient;

    public VisionInputService(
            @Value("${minikun.vision.enabled:true}") boolean enabled,
            @Value("${minikun.vision.max-images:3}") int maxImages,
            @Value("${minikun.vision.max-image-bytes:5242880}") int maxImageBytes,
            @Value("${minikun.vision.remote-url.enabled:true}") boolean remoteUrlsEnabled,
            @Value("${minikun.vision.remote-url.allow-http:false}") boolean allowHttp,
            @Value("${minikun.vision.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${minikun.vision.read-timeout:PT15S}") Duration readTimeout) {
        if (maxImages < 1 || maxImageBytes < 1) {
            throw new IllegalArgumentException("vision image limits must be positive");
        }
        this.enabled = enabled;
        this.maxImages = maxImages;
        this.maxImageBytes = maxImageBytes;
        this.remoteUrlsEnabled = remoteUrlsEnabled;
        this.allowHttp = allowHttp;
        this.readTimeout = positive(readTimeout, Duration.ofSeconds(15));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(positive(connectTimeout, Duration.ofSeconds(5)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public VisionInput resolve(Message message) {
        if (message == null || !message.hasImageContent()) {
            return VisionInput.EMPTY;
        }
        if (!enabled) {
            throw new VisionInputException("image input is disabled");
        }
        for (Message.ContentPart part : message.contentParts()) {
            if (!"text".equals(part.type()) && !"image_url".equals(part.type())) {
                throw new VisionInputException("unsupported message content part type: " + part.type());
            }
            if ("text".equals(part.type()) && part.text() == null) {
                throw new VisionInputException("text content parts must contain text");
            }
        }
        List<Message.ContentPart> imageParts = message.contentParts().stream()
                .filter(part -> "image_url".equals(part.type()))
                .toList();
        if (imageParts.size() > maxImages) {
            throw new VisionInputException("a request may contain at most " + maxImages + " images");
        }

        List<Media> media = new ArrayList<>(imageParts.size());
        for (Message.ContentPart part : imageParts) {
            if (part.imageUrl() == null || blank(part.imageUrl().url())) {
                throw new VisionInputException("image_url content parts must contain a non-blank URL");
            }
            ResolvedImage image = resolve(part.imageUrl().url());
            media.add(new Media(MimeTypeUtils.parseMimeType(image.mimeType()),
                    new ByteArrayResource(image.bytes())));
        }
        return new VisionInput(media);
    }

    private ResolvedImage resolve(String source) {
        if (source.startsWith("data:")) {
            return resolveDataUrl(source);
        }
        if (!remoteUrlsEnabled) {
            throw new VisionInputException("remote image URLs are disabled");
        }
        return resolveRemoteUrl(source);
    }

    private ResolvedImage resolveDataUrl(String source) {
        int comma = source.indexOf(',');
        if (comma < 0) {
            throw new VisionInputException("invalid image data URL");
        }
        String metadata = source.substring(5, comma);
        String encoded = source.substring(comma + 1);
        String[] parameters = metadata.split(";");
        String mimeType = normalizeMimeType(parameters.length == 0 ? "" : parameters[0]);
        if (parameters.length != 2 || !"base64".equalsIgnoreCase(parameters[1])) {
            throw new VisionInputException("image data URLs must use base64 encoding");
        }
        validateMimeType(mimeType);
        long maximumEncodedLength = ((long) maxImageBytes + 2L) / 3L * 4L;
        if (encoded.length() > maximumEncodedLength) {
            throw new VisionInputException("image exceeds the maximum size of " + maxImageBytes + " bytes");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new VisionInputException("image data URL contains invalid base64", exception);
        }
        validateBytes(mimeType, bytes);
        return new ResolvedImage(mimeType, bytes);
    }

    private ResolvedImage resolveRemoteUrl(String source) {
        URI uri;
        try {
            uri = new URI(source);
        } catch (URISyntaxException exception) {
            throw new VisionInputException("invalid remote image URL", exception);
        }
        validateRemoteUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Accept", "image/jpeg,image/png,image/webp")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                throw new VisionInputException("remote image returned HTTP " + response.statusCode());
            }
            String mimeType = normalizeMimeType(response.headers().firstValue("Content-Type").orElse(""));
            validateMimeType(mimeType);
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxImageBytes) {
                close(response.body());
                throw new VisionInputException("image exceeds the maximum size of " + maxImageBytes + " bytes");
            }
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = readBounded(body);
            }
            validateBytes(mimeType, bytes);
            return new ResolvedImage(mimeType, bytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VisionInputException("remote image download was interrupted", exception);
        } catch (IOException exception) {
            throw new VisionInputException("remote image could not be downloaded", exception);
        }
    }

    private void validateRemoteUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new VisionInputException("remote images must use HTTPS");
        }
        if (blank(uri.getHost()) || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new VisionInputException("remote image URL is not allowed");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isBlockedAddress(address)) {
                    throw new VisionInputException("remote image URL resolves to a private or local address");
                }
            }
        } catch (IOException exception) {
            throw new VisionInputException("remote image host could not be resolved", exception);
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first == 127 || first >= 224
                    || first == 100 && second >= 64 && second <= 127
                    || first == 169 && second == 254;
        }
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxImageBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxImageBytes) {
                throw new VisionInputException("image exceeds the maximum size of " + maxImageBytes + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void validateBytes(String mimeType, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > maxImageBytes) {
            throw new VisionInputException("image must contain between 1 and " + maxImageBytes + " bytes");
        }
        boolean signatureMatches = switch (mimeType) {
            case "image/jpeg" -> bytes.length >= 3
                    && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8 && unsigned(bytes[2]) == 0xff;
            case "image/png" -> bytes.length >= 8
                    && unsigned(bytes[0]) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                    && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "image/webp" -> bytes.length >= 12
                    && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP");
            default -> false;
        };
        if (!signatureMatches) {
            throw new VisionInputException("image bytes do not match declared media type " + mimeType);
        }
    }

    private void validateMimeType(String mimeType) {
        if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new VisionInputException("unsupported image media type; use JPEG, PNG, or WebP");
        }
    }

    private String normalizeMimeType(String value) {
        try {
            MimeType mimeType = MimeTypeUtils.parseMimeType(value);
            return mimeType.getType().toLowerCase(Locale.ROOT) + "/"
                    + mimeType.getSubtype().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private boolean ascii(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (bytes[offset + index] != (byte) value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private void close(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The response has already failed validation.
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private record ResolvedImage(String mimeType, byte[] bytes) {
    }
}
