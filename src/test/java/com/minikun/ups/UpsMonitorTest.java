package com.minikun.ups;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.notification.*;
import com.minikun.tools.Tool;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UpsMonitorTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void recordsChangesRetriesDeliveryAndKeepsOutageAndAlertsAcrossRestart() throws Exception {
        var clock = new MutableClock();
        var current = new AtomicReference<>(snapshot(clock, "OL"));
        List<NotificationRequest> delivered = new ArrayList<>();
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        NotificationDispatcher dispatcher = request -> {
            if (failing.get()) throw new IllegalStateException("offline");
            delivered.add(request);
        };
        var history = new UpsHistory(directory, json);
        var monitor = new UpsMonitor(current::get, dispatcher, history, clock);
        monitor.poll();
        assertEquals(0, monitor.status().get("pendingNotifications"));
        clock.advance(5);
        current.set(snapshot(clock, "OB"));
        monitor.poll();
        monitor.deliverPending();
        assertEquals(1, monitor.status().get("pendingNotifications"));
        assertFalse(monitor.status().get("deliveryError").toString().isBlank());
        clock.advance(31);
        failing.set(false);
        monitor.deliverPending();
        assertEquals(1, delivered.size());
        current.set(snapshot(clock, "OB"));
        monitor.poll();
        assertEquals(0, monitor.status().get("pendingNotifications"));
        clock.advance(5);
        current.set(snapshot(clock, "OB LB"));
        monitor.poll();
        monitor.close();

        var resumed = new UpsMonitor(current::get, dispatcher, new UpsHistory(directory, json), clock);
        resumed.poll();
        assertEquals(1, resumed.status().get("pendingNotifications"), "No duplicate battery/low-battery alert on restart");
        resumed.deliverPending();
        assertEquals(5, delivered.getLast().priority());
        clock.advance(5);
        current.set(new NutUpsClient.Snapshot(clock.instant(), "cleanline", false, "DATA-STALE", Map.of()));
        resumed.poll();
        assertEquals(0, resumed.status().get("pendingNotifications"));
        clock.advance(5);
        current.set(new NutUpsClient.Snapshot(clock.instant(), "cleanline", false, "DATA-STALE", Map.of()));
        resumed.poll();
        resumed.poll();
        assertEquals(1, resumed.status().get("pendingNotifications"));
        clock.advance(5);
        current.set(snapshot(clock, "OL"));
        resumed.poll();
        assertEquals(3, resumed.status().get("pendingNotifications"));
        for (int i = 0; i < 3; i++) resumed.deliverPending();
        assertEquals(List.of("ONBATT", "LOWBATT", "UNAVAILABLE", "COMM_RECOVERED", "ONLINE"),
                delivered.stream().map(n -> n.title().substring("Mini-kun UPS: ".length())).toList());
        var events = resumed.recent(100, false);
        var restored = events.stream().filter(e -> e.type().equals("ONLINE")).findFirst().orElseThrow();
        assertTrue(restored.timingGap());
        assertEquals(51L, restored.batterySeconds());
        assertTrue(events.stream().filter(e -> !e.available()).allMatch(e -> e.variables().isEmpty()));
        assertEquals(1, events.stream().filter(e -> e.type().equals("UNAVAILABLE")).count());
        resumed.close();
        var restarted = new UpsMonitor(current::get, dispatcher, new UpsHistory(directory, json), clock);
        restarted.poll();
        assertEquals(0, restarted.status().get("pendingNotifications"));
        assertTrue(new UpsHistoryTool(restarted).route("ดูประวัติ UPS ย้อนหลัง", new ConversationId("ups-history-test"))
                .orElseThrow().content().contains("ยืนยันระยะเวลาต่อเนื่องไม่ได้"));
        restarted.close();
    }

    @Test
    void unknownStatusDoesNotRestartTheOutageTimerOrGenerateAnotherBatteryAlert() throws Exception {
        var clock = new MutableClock();
        var current = new AtomicReference<>(snapshot(clock, "OB"));
        var history = new UpsHistory(directory, json);
        var monitor = new UpsMonitor(current::get, request -> {}, history, clock);
        monitor.poll();
        clock.advance(10);
        current.set(snapshot(clock, "CAL"));
        monitor.poll();
        clock.advance(10);
        current.set(snapshot(clock, "OB"));
        monitor.poll();
        clock.advance(10);
        current.set(snapshot(clock, "OL"));
        monitor.poll();
        var events = monitor.recent(100, false);
        assertEquals(1, events.stream().filter(e -> e.type().equals("ONBATT")).count());
        var restored = events.stream().filter(e -> e.type().equals("ONLINE")).findFirst().orElseThrow();
        assertEquals(30L, restored.batterySeconds());
        assertTrue(restored.timingGap());
        monitor.close();
    }

    @Test
    void boundsHistoryRetainsThirtyDaysAndRecoversFromPartialRows() throws Exception {
        var history = new UpsHistory(directory, json);
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        history.append(entry(now.minus(Duration.ofDays(31)), "OLD"));
        for (int i = 0; i < 3; i++) history.append(entry(now.plusSeconds(i), "EVENT" + i));
        Path today = directory.resolve("2026-10-05.jsonl");
        Files.writeString(today, "{\"incomplete\":", StandardOpenOption.APPEND);
        history.append(entry(now.plusSeconds(4), "LAST"));
        assertEquals(List.of("LAST", "EVENT2"), history.recent(2, false).stream().map(UpsHistory.Entry::type).toList());
        assertFalse(Files.exists(directory.resolve("2026-09-04.jsonl")));
        assertThrows(IllegalArgumentException.class, () -> history.recent(501, false));
        assertEquals(4, history.recent(100, false).size());
    }

    @Test
    void wiresMonitorAndProtectsHistoryAndTestNotificationWithExistingSystemToken() {
        List<NotificationRequest> delivered = new ArrayList<>();
        new ApplicationContextRunner().withUserConfiguration(UpsConfiguration.class)
                .withBean(Clock.class, () -> Clock.systemUTC())
                .withBean(ObjectMapper.class, () -> json)
                .withBean(NotificationDispatcher.class, () -> delivered::add)
                .withPropertyValues("minikun.ups.monitor.directory=" + directory,
                        "minikun.system.health.management-token=ups-test-token")
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .run(context -> {
                    assertNotNull(context.getBean(UpsMonitor.class));
                    assertEquals(2, context.getBeansOfType(Tool.class).size());
                    var mvc = MockMvcBuilders.standaloneSetup(context.getBean(UpsStatusController.class)).build();
                    mvc.perform(get("/v1/ups/history")).andExpect(status().isForbidden());
                    mvc.perform(get("/v1/ups/history").header("X-Minikun-System-Token", "ups-test-token"))
                            .andExpect(status().isOk()).andExpect(content().json("[]"));
                    mvc.perform(get("/v1/ups/history?limit=501").header("X-Minikun-System-Token", "ups-test-token"))
                            .andExpect(status().isBadRequest());
                    mvc.perform(post("/v1/ups/notifications/test")).andExpect(status().isForbidden());
                    mvc.perform(post("/v1/ups/notifications/test").header("X-Minikun-System-Token", "ups-test-token"))
                            .andExpect(status().isOk());
                    assertEquals("UPS_TEST", delivered.getFirst().sourceType());
                    assertTrue(delivered.getFirst().message().contains("ไม่ใช่เหตุไฟดับจริง"));
                });
    }

    private NutUpsClient.Snapshot snapshot(Clock clock, String status) {
        return new NutUpsClient.Snapshot(clock.instant(), "cleanline", true, "",
                Map.of("ups.status", status, "input.voltage", "228.5", "ups.load", "0"));
    }
    private UpsHistory.Entry entry(Instant at, String type) {
        return new UpsHistory.Entry(UUID.randomUUID().toString(), at, type, type, true, "", Map.of(), null, false);
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
