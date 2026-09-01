package com.minikun.visual;

public final class ImageGenerationException extends RuntimeException {
    public enum Code {
        UNAVAILABLE,
        TIMEOUT,
        UPSTREAM_FAILURE,
        INVALID_RESPONSE
    }

    private final Code code;

    public ImageGenerationException(String message) {
        this(Code.UPSTREAM_FAILURE, message, null);
    }

    public ImageGenerationException(String message, Throwable cause) {
        this(Code.UPSTREAM_FAILURE, message, cause);
    }

    public ImageGenerationException(Code code, String message) {
        this(code, message, null);
    }

    public ImageGenerationException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? Code.UPSTREAM_FAILURE : code;
    }

    public Code code() {
        return code;
    }
}
