package com.minikun.visual;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ImageStudioController.class)
public final class ImageStudioExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ImageStudioExceptionHandler.class);

    @ExceptionHandler(ImageGenerationException.class)
    ResponseEntity<ErrorEnvelope> generationFailure(ImageGenerationException exception) {
        HttpStatus status = switch (exception.code()) {
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case UPSTREAM_FAILURE, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        LOG.warn("process=image_studio event=generation_failed code={} status={} message={}",
                exception.code(), status.value(), exception.getMessage());
        return ResponseEntity.status(status).body(new ErrorEnvelope(
                new ApiError(exception.getMessage(), "tinygrad_error",
                        exception.code().name().toLowerCase(java.util.Locale.ROOT))));
    }

    record ErrorEnvelope(ApiError error) { }
    record ApiError(String message, String type, String code) { }
}
