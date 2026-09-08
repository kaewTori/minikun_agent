package com.minikun.model;

import java.util.Locale;
import java.util.Objects;

public record ActiveModelConfiguration(ChatModelId active) {
    public ActiveModelConfiguration {
        Objects.requireNonNull(active, "active model id must not be null");
    }

    public static ActiveModelConfiguration parse(String value) {
        Objects.requireNonNull(value, "minikun.model.active must not be null");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            return new ActiveModelConfiguration(ChatModelId.valueOf(normalized.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid minikun.model.active value '" + value + "'; expected existing",
                    exception);
        }
    }
}
