package com.minikun.calendar;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

import org.springframework.web.client.RestClient;

/** Downloads a private iCalendar feed without exposing its URL in logs. */
public final class IcsCalendarClient {
    private final RestClient restClient;
    private final URI feedUri;
    private final IcsCalendarParser parser;
    private final ZoneId defaultZone;
    private final String source;

    public IcsCalendarClient(
            RestClient restClient,
            URI feedUri,
            IcsCalendarParser parser,
            ZoneId defaultZone,
            String source,
            boolean allowHttp) {
        this.restClient = Objects.requireNonNull(restClient, "calendar rest client must not be null");
        this.feedUri = validate(feedUri, allowHttp);
        this.parser = Objects.requireNonNull(parser, "calendar parser must not be null");
        this.defaultZone = Objects.requireNonNull(defaultZone, "calendar zone must not be null");
        this.source = Objects.requireNonNullElse(source, "external").trim();
    }

    public List<ExternalCalendarEvent> events(Instant from, Instant to) {
        String body = restClient.get().uri(feedUri).retrieve().body(String.class);
        if (body == null || body.isBlank()) throw new IllegalStateException("external calendar returned an empty feed");
        return parser.parse(body, from, to, defaultZone, source);
    }

    private URI validate(URI uri, boolean allowHttp) {
        Objects.requireNonNull(uri, "calendar feed URI must not be null");
        String scheme = Objects.requireNonNullElse(uri.getScheme(), "").toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(scheme) || allowHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException("external calendar feed must use HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("external calendar feed must have a host");
        }
        return uri;
    }
}
