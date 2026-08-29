package com.minikun.knowledge.acquisition;

import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = KnowledgeAcquisitionController.class)
@ConditionalOnProperty(name = "minikun.knowledge-acquisition.enabled", havingValue = "true", matchIfMissing = true)
final class KnowledgeAcquisitionExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ErrorResponse> invalid(RuntimeException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> unavailable(DataAccessException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("knowledge_acquisition_unavailable", "knowledge storage is unavailable"));
    }

    record ErrorResponse(String code, String message) { }
}
