package com.minikun.character;

import java.util.List;
import java.util.Objects;

public class CharacterException extends RuntimeException {
    private final List<Error> errors;

    public CharacterException(String message, List<Error> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public CharacterException(String message, Throwable cause, List<Error> errors) {
        super(message, cause);
        this.errors = List.copyOf(errors);
    }

    public List<Error> errors() {
        return errors;
    }

    public record Error(String code, String file, String message) {
        public Error {
            code = Objects.requireNonNullElse(code, "UNKNOWN");
            file = Objects.requireNonNullElse(file, "");
            message = Objects.requireNonNullElse(message, "");
        }
    }
}
