package com.minikun.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class IcsCalendarParserTest {
    private final IcsCalendarParser parser = new IcsCalendarParser();

    @Test
    void expandsRecurringEventsInTheirDeclaredTimezone() {
        String feed = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Mini-kun Test//EN
                BEGIN:VEVENT
                UID:standup@example.test
                DTSTAMP:20260819T000000Z
                DTSTART;TZID=Asia/Bangkok:20260820T090000
                DTEND;TZID=Asia/Bangkok:20260820T093000
                RRULE:FREQ=DAILY;COUNT=3
                SUMMARY:Morning standup
                LOCATION:Video room
                END:VEVENT
                END:VCALENDAR
                """;

        var events = parser.parse(feed, Instant.parse("2026-08-19T00:00:00Z"),
                Instant.parse("2026-08-24T00:00:00Z"), ZoneId.of("Asia/Bangkok"), "personal");

        assertEquals(3, events.size());
        assertEquals(Instant.parse("2026-08-20T02:00:00Z"), events.getFirst().startsAt());
        assertEquals(Instant.parse("2026-08-20T02:30:00Z"), events.getFirst().endsAt());
        assertEquals("Video room", events.getFirst().location());
        assertEquals(Instant.parse("2026-08-22T02:00:00Z"), events.getLast().startsAt());
    }

    @Test
    void mapsAllDayEventsAndSkipsCancelledEntries() {
        String feed = """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Mini-kun Test//EN
                BEGIN:VEVENT
                UID:holiday@example.test
                DTSTAMP:20260819T000000Z
                DTSTART;VALUE=DATE:20260821
                DTEND;VALUE=DATE:20260822
                SUMMARY:Holiday
                END:VEVENT
                BEGIN:VEVENT
                UID:cancelled@example.test
                DTSTAMP:20260819T000000Z
                DTSTART:20260821T030000Z
                DTEND:20260821T040000Z
                STATUS:CANCELLED
                SUMMARY:Cancelled
                END:VEVENT
                END:VCALENDAR
                """;

        var events = parser.parse(feed, Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-23T00:00:00Z"), ZoneId.of("Asia/Bangkok"), "personal");

        assertEquals(1, events.size());
        assertTrue(events.getFirst().allDay());
        assertEquals("Holiday", events.getFirst().title());
    }

    @Test
    void rejectsNonCalendarPayload() {
        try {
            parser.parse("not a calendar", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                    ZoneId.of("UTC"), "personal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not valid"));
            return;
        }
        throw new AssertionError("invalid calendar should be rejected");
    }
}
