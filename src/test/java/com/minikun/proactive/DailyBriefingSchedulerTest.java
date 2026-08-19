package com.minikun.proactive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import com.minikun.calendar.ExternalCalendarEvent;
import com.minikun.calendar.ExternalCalendarService;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.planner.PlannerEvent;
import com.minikun.planner.PlannerRecurrence;
import com.minikun.planner.PlannerService;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskKind;
import com.minikun.task.TaskService;
import com.minikun.task.TaskStatus;
import com.minikun.weather.WeatherProvider;
import com.minikun.weather.WeatherReport;

class DailyBriefingSchedulerTest {
    @Test
    void formatsWeatherCalendarsActionsAndWaitingItems() {
        DailyBriefingScheduler scheduler = new DailyBriefingScheduler(
                mock(TaskService.class), mock(PlannerService.class), externalProvider(), mock(WeatherProvider.class),
                mock(NotificationDispatcher.class), mock(NotificationSchedulerMonitor.class), mock(JdbcTemplate.class),
                mock(ProactiveNotificationPolicy.class), Clock.systemUTC(), "default", "Asia/Bangkok", "08:00",
                "Bangkok", "TH");
        Instant now = Instant.parse("2026-08-20T01:00:00Z");
        PersonalTask actionable = task("เขียนรายงาน", "เปิดเอกสารและร่างหัวข้อ", "");
        PersonalTask waiting = task("ยืนยันงบ", "", "ฝ่ายบัญชี");
        PlannerEvent local = new PlannerEvent(UUID.randomUUID(), "conversation", "โทรหาทีม", "", now.plusSeconds(3600),
                ZoneId.of("Asia/Bangkok"), 0, PlannerRecurrence.NONE, "ACTIVE", now.plusSeconds(3600), now, now);
        ExternalCalendarEvent external = new ExternalCalendarEvent("meeting", "ประชุมลูกค้า", "", "ห้อง A",
                now.plusSeconds(7200), now.plusSeconds(10800), false, "personal");
        WeatherReport weather = new WeatherReport("Bangkok", "Thailand", 0, 0, "Asia/Bangkok", "2026-08-20",
                30.0, 33.0, 0.0, 5.0, 1, "มีเมฆบางส่วน", 27.0, 34.0, 60, 1.0,
                "06:00", "18:30", now, "Open-Meteo");

        String message = scheduler.message(List.of(actionable, waiting), List.of(local), List.of(external),
                weather, LocalDate.of(2026, 8, 20));

        assertTrue(message.contains("🌤 อากาศ Bangkok"));
        assertTrue(message.contains("📅 นัดหมาย"));
        assertTrue(message.contains("ประชุมลูกค้า — ห้อง A"));
        assertTrue(message.contains("✅ งานและขั้นตอนถัดไป"));
        assertTrue(message.contains("ถัดไป: เปิดเอกสารและร่างหัวข้อ"));
        assertTrue(message.contains("⏳ สิ่งที่กำลังรอ"));
        assertTrue(message.contains("รอ: ฝ่ายบัญชี"));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ExternalCalendarService> externalProvider() {
        return mock(ObjectProvider.class);
    }

    private PersonalTask task(String title, String nextAction, String waitingFor) {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        return new PersonalTask(UUID.randomUUID(), "default", "conversation", TaskKind.TASK, title, "",
                TaskStatus.OPEN, null, null, ZoneId.of("Asia/Bangkok"), nextAction, waitingFor,
                null, null, now, now, null);
    }
}
