package com.minikun.search;

public class InvalidSearchRequestException extends SearchException {
    public InvalidSearchRequestException(String message) {
        super(message);
    }
}