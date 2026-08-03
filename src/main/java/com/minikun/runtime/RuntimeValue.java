package com.minikun.runtime;

import java.util.Objects;

public record RuntimeValue(RuntimeValueState state, String value) {

    public RuntimeValue {
        Objects.requireNonNull(state, "state must not be null");
        value = value == null || value.isBlank() ? null : value;
    }

    public static RuntimeValue configured(String value) {
        return new RuntimeValue(RuntimeValueState.CONFIGURED, value);
    }

    public static RuntimeValue notConfigured() {
        return new RuntimeValue(RuntimeValueState.NOT_CONFIGURED, null);
    }

    public static RuntimeValue unavailable() {
        return new RuntimeValue(RuntimeValueState.UNAVAILABLE, null);
    }
}
