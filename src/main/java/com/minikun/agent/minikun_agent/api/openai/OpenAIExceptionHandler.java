package com.minikun.agent.minikun_agent.api.openai;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.minikun.vision.VisionInputException;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice(assignableTypes = OpenAIController.class)
@Slf4j
public final class OpenAIExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorEnvelope> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorEnvelope(new ApiError(exception.getMessage(), "invalid_request_error", null, "invalid_input")));
    }

    @ExceptionHandler(VisionInputException.class)
    ResponseEntity<ErrorEnvelope> invalidVisionInput(VisionInputException exception) {
        log.warn("process=vision_input event=rejected reason={}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorEnvelope(new ApiError(
                        exception.getMessage(), "invalid_request_error", "messages.content", "invalid_image")));
    }

    record ErrorEnvelope(ApiError error) {
    }

    record ApiError(String message, String type, String param, String code) {
    }
}
