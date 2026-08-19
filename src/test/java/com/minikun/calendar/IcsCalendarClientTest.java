package com.minikun.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IcsCalendarClientTest {
    @Test
    void downloadsAndParsesPrivateFeed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String body = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Mini-kun Test//EN
                BEGIN:VEVENT
                UID:meeting
                DTSTAMP:20260819T000000Z
                DTSTART:20260820T030000Z
                DTEND:20260820T040000Z
                SUMMARY:Planning
                END:VEVENT
                END:VCALENDAR
                """;
        server.expect(requestTo("http://calendar.test/private.ics")).andRespond(withSuccess(body, null));
        IcsCalendarClient client = new IcsCalendarClient(builder.build(), URI.create("http://calendar.test/private.ics"),
                new IcsCalendarParser(), ZoneId.of("Asia/Bangkok"), "personal", true);

        var events = client.events(Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"));

        assertEquals(1, events.size());
        assertEquals("Planning", events.getFirst().title());
        server.verify();
    }

    @Test
    void requiresHttpsByDefault() {
        assertThrows(IllegalArgumentException.class, () -> new IcsCalendarClient(
                RestClient.create(), URI.create("http://calendar.test/private.ics"), new IcsCalendarParser(),
                ZoneId.of("UTC"), "personal", false));
    }
}
