package com.minikun.search;

public class SearchTimeoutException extends SearchException {
    public SearchTimeoutException(String message) {
        super(message);
    }

    public SearchTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}