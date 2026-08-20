package com.minikun.voice;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates bounded audio uploads before any decoder or model sees them. */
public final class VoiceAudioPolicy {
    private static final Map<String, Set<String>> TYPES = Map.of(
            "wav", Set.of("audio/wav", "audio/x-wav", "audio/wave"),
            "aiff", Set.of("audio/aiff", "audio/x-aiff"),
            "aif", Set.of("audio/aiff", "audio/x-aiff"),
            "mp3", Set.of("audio/mpeg", "audio/mp3"),
            "m4a", Set.of("audio/mp4", "audio/x-m4a", "video/mp4"),
            "mp4", Set.of("audio/mp4", "video/mp4"),
            "caf", Set.of("audio/x-caf", "audio/caf", "application/octet-stream"));

    private final long maxBytes;

    public VoiceAudioPolicy(long maxBytes) {
        if (maxBytes < 1024) throw new IllegalArgumentException("voice max bytes must be at least 1024");
        this.maxBytes = maxBytes;
    }

    public String validate(String filename, String contentType, byte[] data) {
        if (data == null || data.length == 0) throw invalid("audio file must not be empty");
        if (data.length > maxBytes) throw invalid("audio file exceeds the configured size limit");
        String safeName = filename == null ? "" : filename.trim();
        int dot = safeName.lastIndexOf('.');
        String extension = dot < 0 ? "" : safeName.substring(dot + 1).toLowerCase(Locale.ROOT);
        Set<String> allowedTypes = TYPES.get(extension);
        if (allowedTypes == null) throw invalid("unsupported audio file type");
        String normalizedType = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!normalizedType.isBlank() && !allowedTypes.contains(normalizedType)) {
            throw invalid("audio content type does not match the file extension");
        }
        if (!hasExpectedSignature(extension, data)) throw invalid("audio file signature is invalid");
        return extension;
    }

    public long maxBytes() { return maxBytes; }
    public Set<String> formats() { return Set.copyOf(TYPES.keySet()); }

    private boolean hasExpectedSignature(String extension, byte[] value) {
        return switch (extension) {
            case "wav" -> ascii(value, 0, "RIFF") && ascii(value, 8, "WAVE");
            case "aif", "aiff" -> ascii(value, 0, "FORM")
                    && (ascii(value, 8, "AIFF") || ascii(value, 8, "AIFC"));
            case "mp3" -> ascii(value, 0, "ID3") || (value.length > 1
                    && (value[0] & 0xff) == 0xff && ((value[1] & 0xe0) == 0xe0));
            case "m4a", "mp4" -> ascii(value, 4, "ftyp");
            case "caf" -> ascii(value, 0, "caff");
            default -> false;
        };
    }

    private boolean ascii(byte[] value, int offset, String expected) {
        if (value.length < offset + expected.length()) return false;
        for (int index = 0; index < expected.length(); index++) {
            if ((char) value[offset + index] != expected.charAt(index)) return false;
        }
        return true;
    }

    private VoiceException invalid(String message) {
        return new VoiceException(VoiceErrorCode.INVALID_AUDIO, message);
    }
}
