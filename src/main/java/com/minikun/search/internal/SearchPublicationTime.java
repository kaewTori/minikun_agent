package com.minikun.search.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Accepts provider timestamps without substituting retrieval time for publication time. */
final class SearchPublicationTime {
    private SearchPublicationTime() { }

    static Instant parse(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").strip();
            if (value.isBlank()) continue;
            try { return Instant.parse(value); } catch (RuntimeException ignored) { }
            try { return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant(); }
            catch (RuntimeException ignored) { }
            try { return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            catch (RuntimeException ignored) { }
            try { return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant(); }
            catch (RuntimeException ignored) { }
        }
        return null;
    }
}
