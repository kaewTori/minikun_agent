package com.minikun.runtime;

final class RuntimeFormatter {
    private RuntimeFormatter() {
    }

    static String value(RuntimeValue value) {
        if (value == null) {
            return "Unavailable";
        }
        return switch (value.state()) {
            case CONFIGURED -> value.value() == null ? "Unavailable" : value.value();
            case NOT_CONFIGURED -> "Not configured";
            case UNAVAILABLE -> "Unavailable";
        };
    }
}
