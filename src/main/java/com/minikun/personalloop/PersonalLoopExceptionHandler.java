package com.minikun.personalloop;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PersonalLoopController.class)
public final class PersonalLoopExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", "invalid_request", "message", message(exception))));
    }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String,Object>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", Map.of("code", "invalid_state", "message", message(exception))));
    }
    private String message(RuntimeException exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
}
