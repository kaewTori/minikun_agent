package com.minikun.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "minikun.calendar.external.enabled", havingValue = "true")
@RequestMapping("/v1/calendar/events")
public final class ExternalCalendarController {
    private final ExternalCalendarService calendar;
    private final Clock clock;

    @Value("${minikun.calendar.management.token:${minikun.task.management.token:${minikun.memory.management.token:}}}")
    private String managementToken;

    public ExternalCalendarController(ExternalCalendarService calendar, Clock clock) {
        this.calendar = Objects.requireNonNull(calendar, "external calendar must not be null");
        this.clock = Objects.requireNonNull(clock, "calendar clock must not be null");
    }

    @GetMapping
    public List<ExternalCalendarEvent> list(
            @RequestParam(defaultValue = "14") int days,
            @RequestHeader(value = "X-Minikun-Calendar-Token", required = false) String token) {
        authorize(token);
        if (days < 1 || days > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between 1 and 90");
        }
        Instant now = clock.instant();
        return calendar.events(now, now.plus(Duration.ofDays(days)));
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "calendar management token is invalid");
        }
    }
}
