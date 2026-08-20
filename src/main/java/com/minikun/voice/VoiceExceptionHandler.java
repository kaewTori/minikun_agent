package com.minikun.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice(assignableTypes = VoiceController.class)
public final class VoiceExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceExceptionHandler.class);

    @ExceptionHandler(VoiceException.class)
    ResponseEntity<ErrorEnvelope> voiceFailure(VoiceException exception) {
        HttpStatus status = switch (exception.code()) {
            case INVALID_AUDIO, INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case PROCESSING_FAILED -> HttpStatus.BAD_GATEWAY;
        };
        LOG.warn("process=voice event=rejected code={} status={}", exception.code(), status.value());
        return ResponseEntity.status(status).body(new ErrorEnvelope(
                new ApiError(exception.getMessage(), "voice_error", null,
                        exception.code().name().toLowerCase(java.util.Locale.ROOT))));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorEnvelope> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.badRequest().body(new ErrorEnvelope(
                new ApiError("audio file exceeds the configured size limit", "voice_error", "file", "invalid_audio")));
    }

    record ErrorEnvelope(ApiError error) {}
    record ApiError(String message, String type, String param, String code) {}
}
