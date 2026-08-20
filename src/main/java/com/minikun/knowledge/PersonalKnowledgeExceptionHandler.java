package com.minikun.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PersonalKnowledgeController.class)
public final class PersonalKnowledgeExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PersonalKnowledgeExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> unavailable(DataAccessException exception) {
        LOG.warn("process=personal_knowledge event=database_unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("knowledge_unavailable", "personal knowledge storage is unavailable"));
    }

    record ErrorResponse(String code, String message) {}
}
