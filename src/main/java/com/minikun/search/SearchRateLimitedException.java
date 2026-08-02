package com.minikun.search;

public class SearchRateLimitedException extends SearchException {
    public SearchRateLimitedException(String message) {
        super(message);
    }

    public SearchRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}