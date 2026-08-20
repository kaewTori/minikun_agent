package com.minikun.communication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CommunicationController.class)
public final class CommunicationExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CommunicationExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(CommunicationUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(CommunicationUnavailableException exception) {
        LOG.warn("process=communication_assistant event=unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("communication_unavailable", exception.getMessage()));
    }

    record ErrorResponse(String code, String message) {
    }
}
