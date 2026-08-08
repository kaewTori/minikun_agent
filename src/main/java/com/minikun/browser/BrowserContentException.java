package com.minikun.browser;

public final class BrowserContentException extends RuntimeException {
    public BrowserContentException(String message) {
        super(message);
    }

    public BrowserContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
