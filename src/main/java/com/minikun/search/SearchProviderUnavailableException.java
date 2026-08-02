package com.minikun.search;

public class SearchProviderUnavailableException extends SearchException {
    public SearchProviderUnavailableException(String message) {
        super(message);
    }

    public SearchProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}