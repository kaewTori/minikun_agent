package com.minikun.personality.learning;

/** One bounded response-style observation extracted from explicit wording or stable usage. */
public record AdaptationObservation(String dimension, String value, double weight, boolean explicit) {
    public AdaptationObservation {
        if (dimension == null || dimension.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException("adaptation dimension and value are required");
        }
        if (!Double.isFinite(weight) || weight <= 0 || weight > 2) {
            throw new IllegalArgumentException("adaptation weight must be between 0 and 2");
        }
        dimension = dimension.trim();
        value = value.trim();
    }
}
