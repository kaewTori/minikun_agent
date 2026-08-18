package com.minikun.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Open-Meteo geocoding adapter shared by location-aware capabilities. */
public final class OpenMeteoLocationResolver implements LocationResolver {
    private static final String SOURCE = "Open-Meteo Geocoding (CC BY 4.0)";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpenMeteoLocationResolver(RestClient client, ObjectMapper objectMapper, Clock clock) {
        this.client = Objects.requireNonNull(client, "geocoding client must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public LocationResult resolve(LocationRequest request) {
        Objects.requireNonNull(request, "location request must not be null");
        try {
            String body = client.get().uri(uri(request)).retrieve().body(String.class);
            JsonNode results = objectMapper.readTree(body).path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new IllegalArgumentException("location could not be resolved: " + request.query());
            }
            return parse(results.get(0));
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("location provider returned HTTP "
                    + exception.getStatusCode().value(), exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("location provider response is invalid", exception);
        }
    }

    private java.net.URI uri(LocationRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/v1/search")
                .queryParam("name", normalize(request.query()))
                .queryParam("count", 5)
                .queryParam("language", language(request.query()))
                .queryParam("format", "json");
        if (!request.countryCode().isBlank()) {
            builder.queryParam("countryCode", request.countryCode());
        }
        return builder.build().toUri();
    }

    private LocationResult parse(JsonNode result) {
        return new LocationResult(
                text(result, "name", "Unknown"),
                text(result, "country", ""),
                text(result, "country_code", "").toUpperCase(Locale.ROOT),
                text(result, "admin1", ""),
                text(result, "admin2", ""),
                number(result, "latitude"),
                number(result, "longitude"),
                text(result, "timezone", "UTC"),
                clock.instant(),
                SOURCE);
    }

    private String normalize(String value) {
        return value.trim().replace('ฯ', ' ').replaceAll("\\s+", " ").trim();
    }

    private String language(String value) {
        boolean containsThai = value.codePoints()
                .anyMatch(codePoint -> codePoint >= 0x0E00 && codePoint <= 0x0E7F);
        return containsThai ? "th" : "en";
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("location response is missing " + field);
        }
        return value.asDouble();
    }
}
