package com.minikun.vision;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class VisionInputException extends RuntimeException {
    public VisionInputException(String message) {
        super(message);
    }

    public VisionInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
