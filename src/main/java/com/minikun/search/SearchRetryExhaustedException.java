package com.minikun.search;

public class SearchRetryExhaustedException extends SearchException {
    public SearchRetryExhaustedException(String message) {
        super(message);
    }

    public SearchRetryExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}