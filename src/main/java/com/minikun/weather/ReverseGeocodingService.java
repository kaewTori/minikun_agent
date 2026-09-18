package com.minikun.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Bounded, non-persistent reverse geocoding for nearby-place requests. */
public final class ReverseGeocodingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReverseGeocodingService.class);
    private static final String SOURCE = "OpenStreetMap Nominatim";
    private static final Duration FUTURE_SKEW = Duration.ofMinutes(2);

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration maximumAge;
    private final double maximumAccuracy;
    private final String userAgent;
    private final boolean enabled;

    public ReverseGeocodingService(RestClient client, ObjectMapper objectMapper, Clock clock,
            Duration maximumAge, double maximumAccuracy, String userAgent, boolean enabled) {
        this.client = Objects.requireNonNull(client, "reverse geocoding client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maximumAge = Objects.requireNonNull(maximumAge, "maximum age must not be null");
        if (maximumAge.isZero() || maximumAge.isNegative()) {
            throw new IllegalArgumentException("maximum age must be positive");
        }
        if (!Double.isFinite(maximumAccuracy) || maximumAccuracy <= 0) {
            throw new IllegalArgumentException("maximum accuracy must be positive");
        }
        this.maximumAccuracy = maximumAccuracy;
        this.userAgent = Objects.requireNonNull(userAgent, "user agent must not be null").strip();
        if (this.userAgent.isBlank()) {
            throw new IllegalArgumentException("user agent must not be blank");
        }
        this.enabled = enabled;
    }

    public Optional<LocationResult> resolve(DeviceLocation location) {
        if (!enabled || location == null || !fresh(location) || location.accuracy() > maximumAccuracy) {
            return Optional.empty();
        }
        try {
            String body = client.get().uri(uri(location))
                    .header("Accept-Language", "th")
                    .header("User-Agent", userAgent)
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode address = root.path("address");
            String name = area(address);
            if (name.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new LocationResult(
                    name,
                    text(address, "country"),
                    text(address, "country_code").toUpperCase(Locale.ROOT),
                    text(address, "state"),
                    text(address, "county", "city_district"),
                    location.latitude(),
                    location.longitude(),
                    "unknown",
                    clock.instant(),
                    SOURCE));
        } catch (RestClientResponseException exception) {
            LOGGER.warn("process=location event=reverse_geocode_failed status={} provider={}",
                    exception.getStatusCode().value(), SOURCE);
            return Optional.empty();
        } catch (Exception exception) {
            LOGGER.warn("process=location event=reverse_geocode_failed reason={} provider={}",
                    exception.getClass().getSimpleName(), SOURCE);
            return Optional.empty();
        }
    }

    private boolean fresh(DeviceLocation location) {
        if (location.capturedAt() == null) {
            return false;
        }
        long now = clock.millis();
        long captured = location.capturedAt();
        long age = maximumAge.toMillis();
        return captured <= now + FUTURE_SKEW.toMillis() && captured >= now - age;
    }

    private java.net.URI uri(DeviceLocation location) {
        return UriComponentsBuilder.fromPath("/reverse")
                .queryParam("lat", coordinate(location.latitude()))
                .queryParam("lon", coordinate(location.longitude()))
                .queryParam("format", "jsonv2")
                .queryParam("zoom", 14)
                .queryParam("addressdetails", 1)
                .queryParam("accept-language", "th")
                .build().toUri();
    }

    private String area(JsonNode address) {
        String local = first(address, "neighbourhood", "suburb", "quarter", "city_district",
                "town", "city", "municipality");
        String city = first(address, "city_district", "city", "town", "municipality", "state_district", "state");
        if (local.isBlank()) return city;
        if (city.isBlank() || local.equalsIgnoreCase(city)) return local;
        return local + ", " + city;
    }

    private String first(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node == null ? null : node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return "";
    }

    private String coordinate(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
