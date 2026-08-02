package com.minikun.search;

public class SearchExecutionException extends SearchException {
    public SearchExecutionException(String message) {
        super(message);
    }

    public SearchExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}