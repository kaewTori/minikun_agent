package com.minikun.proactive;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.task.PersonalTask;
import com.minikun.task.TaskService;
import com.minikun.calendar.ExternalCalendarEvent;
import com.minikun.calendar.ExternalCalendarService;
import com.minikun.planner.PlannerEvent;
import com.minikun.planner.PlannerService;
import com.minikun.weather.WeatherProvider;
import com.minikun.weather.WeatherReport;
import com.minikun.weather.WeatherRequest;
import com.minikun.goal.GoalService;
import com.minikun.goal.PersonalGoal;

/** Sends one concise daily view of open work after the configured local time. */
@Component
@ConditionalOnProperty(
        name = {"minikun.proactive.briefing.enabled", "minikun.task.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public final class DailyBriefingScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DailyBriefingScheduler.class);
    private static final String STATE_KEY = "daily-task-briefing";
    private static final String SCHEDULER = "daily-briefing";

    private final TaskService tasks;
    private final PlannerService planner;
    private final ObjectProvider<ExternalCalendarService> externalCalendar;
    private final WeatherProvider weather;
    private final NotificationDispatcher notifications;
    private final NotificationSchedulerMonitor monitor;
    private final JdbcTemplate jdbc;
    private final ProactiveNotificationPolicy policy;
    private final Clock clock;
    private final String ownerId;
    private final ZoneId zone;
    private final LocalTime sendAt;
    private final String weatherLocation;
    private final String weatherCountryCode;
    private final GoalService goals;

    @Autowired
    public DailyBriefingScheduler(
            TaskService tasks,
            PlannerService planner,
            ObjectProvider<ExternalCalendarService> externalCalendar,
            WeatherProvider weather,
            NotificationDispatcher notifications,
            NotificationSchedulerMonitor monitor,
            JdbcTemplate jdbc,
            ProactiveNotificationPolicy policy,
            Clock clock,
            @Value("${minikun.proactive.briefing.owner-id:default}") String ownerId,
            @Value("${minikun.proactive.briefing.zone:Asia/Bangkok}") String zone,
            @Value("${minikun.proactive.briefing.time:08:00}") String sendAt,
            @Value("${minikun.weather.alert.location:Bangkok}") String weatherLocation,
            @Value("${minikun.weather.alert.country-code:TH}") String weatherCountryCode,
            ObjectProvider<GoalService> goals) {
        this.tasks = Objects.requireNonNull(tasks, "task service must not be null");
        this.planner = Objects.requireNonNull(planner, "planner service must not be null");
        this.externalCalendar = Objects.requireNonNull(externalCalendar, "external calendar provider must not be null");
        this.weather = Objects.requireNonNull(weather, "weather provider must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification service must not be null");
        this.monitor = Objects.requireNonNull(monitor, "scheduler monitor must not be null");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
        this.policy = Objects.requireNonNull(policy, "proactive policy must not be null");
        this.clock = Objects.requireNonNull(clock, "briefing clock must not be null");
        this.ownerId = requireOwner(ownerId);
        this.weatherLocation = Objects.requireNonNullElse(weatherLocation, "Bangkok").trim();
        this.weatherCountryCode = Objects.requireNonNullElse(weatherCountryCode, "TH").trim();
        this.goals = goals == null ? null : goals.getIfAvailable();
        try {
            this.zone = ZoneId.of(Objects.requireNonNullElse(zone, "Asia/Bangkok").trim());
            this.sendAt = LocalTime.parse(Objects.requireNonNullElse(sendAt, "08:00").trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid daily briefing timezone or time", exception);
        }
        this.monitor.register(SCHEDULER);
    }

    /** Backward-compatible constructor for focused tests and lightweight callers. */
    public DailyBriefingScheduler(
            TaskService tasks, PlannerService planner, ObjectProvider<ExternalCalendarService> externalCalendar,
            WeatherProvider weather, NotificationDispatcher notifications, NotificationSchedulerMonitor monitor,
            JdbcTemplate jdbc, ProactiveNotificationPolicy policy, Clock clock, String ownerId, String zone,
            String sendAt, String weatherLocation, String weatherCountryCode) {
        this(tasks, planner, externalCalendar, weather, notifications, monitor, jdbc, policy, clock, ownerId, zone,
                sendAt, weatherLocation, weatherCountryCode, null);
    }

    @Scheduled(fixedDelayString = "${minikun.proactive.briefing.poll-interval-ms:30000}")
    public void deliverDailyBriefing() {
        Instant now = clock.instant();
        monitor.polled(SCHEDULER, now);
        if (!policy.allows(now)) {
            return;
        }
        ZonedDateTime localNow = now.atZone(zone);
        LocalDate today = localNow.toLocalDate();
        if (localNow.toLocalTime().isBefore(sendAt) || alreadySent(today)) {
            return;
        }

        List<PersonalTask> openTasks = tasks.list(ownerId, null).stream()
                .filter(PersonalTask::active)
                .sorted(java.util.Comparator.comparing(PersonalTask::dueAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .limit(12)
                .toList();
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        List<PlannerEvent> localEvents = planner.upcoming(dayStart, dayEnd).stream().limit(12).toList();
        List<ExternalCalendarEvent> externalEvents = externalEvents(dayStart, dayEnd);
        List<PersonalGoal> openGoals = goals == null ? List.of() : goals.syncOpenProgress(ownerId).stream()
                .filter(PersonalGoal::open).sorted(java.util.Comparator.comparing(PersonalGoal::nextReviewAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).limit(8).toList();
        WeatherReport weatherReport = weather();
        if (openTasks.isEmpty() && openGoals.isEmpty() && localEvents.isEmpty() && externalEvents.isEmpty() && weatherReport == null) {
            markSent(today, now);
            return;
        }
        try {
            notifications.publish(new NotificationRequest(
                    "BRIEFING", ownerId + ":" + today, NotificationChannel.REMINDER,
                    "Mini-kun daily briefing",
                    message(openTasks, openGoals, localEvents, externalEvents, weatherReport, today),
                    3, "sunrise,calendar,checklist"));
            markSent(today, now);
            monitor.delivered(SCHEDULER, clock.instant());
            LOGGER.info("process=daily_briefing event=delivered owner_id={} date={} task_count={} event_count={}",
                    ownerId, today, openTasks.size(), localEvents.size() + externalEvents.size());
        } catch (RuntimeException exception) {
            monitor.failed(SCHEDULER, clock.instant());
            LOGGER.warn("process=daily_briefing event=delivery_failed owner_id={} reason={}",
                    ownerId, exception.getMessage());
        }
    }

    String message(
            List<PersonalTask> tasks,
            List<PlannerEvent> localEvents,
            List<ExternalCalendarEvent> externalEvents,
            WeatherReport weatherReport,
            LocalDate date) {
        return message(tasks, List.of(), localEvents, externalEvents, weatherReport, date);
    }

    String message(
            List<PersonalTask> tasks,
            List<PersonalGoal> goals,
            List<PlannerEvent> localEvents,
            List<ExternalCalendarEvent> externalEvents,
            WeatherReport weatherReport,
            LocalDate date) {
        StringBuilder message = new StringBuilder("พี่สาวครับ สรุปวันนี้ ").append(date).append(" นะครับ");
        if (weatherReport != null) {
            message.append("\n\n🌤 อากาศ ").append(weatherReport.location()).append(": ")
                    .append(Objects.requireNonNullElse(weatherReport.currentWeatherDescription(), "ไม่ทราบ"));
            if (weatherReport.dailyTemperatureMinCelsius() != null) {
                message.append(" ").append(weatherReport.dailyTemperatureMinCelsius()).append("–")
                        .append(weatherReport.dailyTemperatureMaxCelsius()).append("°C");
            }
            if (weatherReport.dailyPrecipitationProbabilityPercent() != null) {
                message.append(" ฝน ").append(weatherReport.dailyPrecipitationProbabilityPercent()).append("%");
            }
        }
        if (!localEvents.isEmpty() || !externalEvents.isEmpty()) {
            message.append("\n\n📅 นัดหมาย");
            for (PlannerEvent event : localEvents) {
                message.append("\n• ").append(time(event.startsAt())).append(" ").append(event.title());
            }
            for (ExternalCalendarEvent event : externalEvents) {
                message.append("\n• ").append(event.allDay() ? "ทั้งวัน" : time(event.startsAt()))
                        .append(" ").append(event.title());
                if (!event.location().isBlank()) message.append(" — ").append(event.location());
            }
        }
        List<PersonalTask> waiting = tasks.stream().filter(task -> !task.waitingFor().isBlank()).toList();
        List<PersonalTask> actionable = tasks.stream().filter(task -> task.waitingFor().isBlank()).toList();
        if (!actionable.isEmpty()) {
            message.append("\n\n✅ งานและขั้นตอนถัดไป");
            appendTasks(message, actionable);
        }
        if (!waiting.isEmpty()) {
            message.append("\n\n⏳ สิ่งที่กำลังรอ");
            appendTasks(message, waiting);
        }
        if (!goals.isEmpty()) {
            message.append("\n\n🎯 เป้าหมายที่กำลังดูแล");
            for (PersonalGoal goal : goals) {
                message.append("\n• ").append(goal.title()).append(" — ")
                        .append(goal.progressPercent()).append("%");
                if (!goal.metric().isBlank()) {
                    message.append(" (").append(goal.currentValue()).append("/").append(goal.targetValue())
                            .append(" ").append(goal.metric()).append(")");
                }
            }
        }
        return message.toString();
    }

    private void appendTasks(StringBuilder message, List<PersonalTask> tasks) {
        for (PersonalTask task : tasks) {
            message.append("\n• ").append(task.title());
            if (task.dueAt() != null) message.append(" — กำหนด ").append(task.dueAt().atZone(zone).toLocalDateTime());
            if (!task.nextAction().isBlank()) message.append("\n  ถัดไป: ").append(task.nextAction());
            if (!task.waitingFor().isBlank()) message.append("\n  รอ: ").append(task.waitingFor());
        }
    }

    private String time(Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone));
    }

    private List<ExternalCalendarEvent> externalEvents(Instant from, Instant to) {
        ExternalCalendarService service = externalCalendar.getIfAvailable();
        if (service == null) return List.of();
        try {
            return service.events(from, to).stream().limit(12).toList();
        } catch (RuntimeException exception) {
            LOGGER.warn("process=daily_briefing event=external_calendar_unavailable reason={}", exception.getMessage());
            return List.of();
        }
    }

    private WeatherReport weather() {
        try {
            return weather.forecast(new WeatherRequest(weatherLocation, "วันนี้", weatherCountryCode));
        } catch (RuntimeException exception) {
            LOGGER.warn("process=daily_briefing event=weather_unavailable reason={}", exception.getMessage());
            return null;
        }
    }

    private boolean alreadySent(LocalDate date) {
        List<LocalDate> dates = jdbc.query(
                "SELECT last_sent_date FROM minikun_proactive_briefing_state WHERE briefing_key = ?",
                (resultSet, rowNum) -> {
                    java.sql.Date value = resultSet.getDate(1);
                    return value == null ? null : value.toLocalDate();
                }, STATE_KEY);
        return dates.stream().anyMatch(date::equals);
    }

    private void markSent(LocalDate date, Instant sentAt) {
        jdbc.update("""
                INSERT INTO minikun_proactive_briefing_state (briefing_key, last_sent_date, last_sent_at)
                VALUES (?, ?, ?)
                ON CONFLICT (briefing_key) DO UPDATE SET last_sent_date = EXCLUDED.last_sent_date,
                    last_sent_at = EXCLUDED.last_sent_at
                """, STATE_KEY, java.sql.Date.valueOf(date), Timestamp.from(sentAt));
    }

    private String requireOwner(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank() || "*".equals(normalized)) {
            throw new IllegalArgumentException("briefing owner id must not be blank or wildcard");
        }
        return normalized;
    }
}
