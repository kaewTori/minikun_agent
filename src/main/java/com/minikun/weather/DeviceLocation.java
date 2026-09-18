package com.minikun.weather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Ephemeral device location supplied by the browser for one chat turn. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceLocation(
        Double latitude,
        Double longitude,
        Double accuracy,
        @JsonProperty("captured_at") Long capturedAt) {
    public DeviceLocation {
        if (latitude == null || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("device latitude must be between -90 and 90");
        }
        if (longitude == null || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("device longitude must be between -180 and 180");
        }
        if (accuracy == null || !Double.isFinite(accuracy) || accuracy < 0) {
            throw new IllegalArgumentException("device accuracy must be non-negative");
        }
        if (capturedAt != null && capturedAt < 0) {
            throw new IllegalArgumentException("device capture time must be non-negative");
        }
    }
}
