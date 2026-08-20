package com.minikun.communication;

public final class CommunicationUnavailableException extends RuntimeException {
    public CommunicationUnavailableException(String message) {
        super(message);
    }

    public CommunicationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
