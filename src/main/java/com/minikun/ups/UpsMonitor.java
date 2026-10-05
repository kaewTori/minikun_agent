package com.minikun.ups;

import com.minikun.notification.*;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observes UPS changes only; notification I/O never blocks the polling thread. */
public final class UpsMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(UpsMonitor.class);
    private final Supplier<NutUpsClient.Snapshot> reader;
    private final NotificationDispatcher notifications;
    private final UpsHistory history;
    private final Clock clock;
    private final UpsHistory.State state;
    private NutUpsClient.Snapshot latest;
    private boolean started;
    private Instant nextDelivery = Instant.EPOCH;
    private String deliveryError = "";
    private String storageError = "";
    @Value("${minikun.ups.monitor.poll-interval-ms:5000}")
    private long pollIntervalMillis = 5000;
    private ScheduledExecutorService scheduler;

    public UpsMonitor(Supplier<NutUpsClient.Snapshot> reader, NotificationDispatcher notifications,
            UpsHistory history, Clock clock) throws IOException {
        this.reader = reader;
        this.notifications = notifications;
        this.history = history;
        this.clock = clock;
        this.state = history.load();
        // A stopped agent cannot observe changes during the gap.
        if (state.onBatterySince != null) state.timingGap = true;
    }

    @PostConstruct
    public void start() {
        if (pollIntervalMillis < 1000) throw new IllegalArgumentException("UPS poll interval must be at least 1000ms");
        scheduler = Executors.newScheduledThreadPool(2,
                task -> Thread.ofPlatform().daemon(true).name("ups-monitor").unstarted(task));
        scheduler.scheduleWithFixedDelay(this::poll, 5000, pollIntervalMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::deliverPending, 15000, 5000, TimeUnit.MILLISECONDS);
    }

    public void poll() {
        try { observe(reader.get()); }
        catch (Exception exception) {
            synchronized (this) { storageError = "UPS_HISTORY_WRITE_FAILED"; }
            LOG.error("process=ups_monitor event=poll_failed reason={}", exception.toString());
        }
    }

    synchronized void observe(NutUpsClient.Snapshot snapshot) throws IOException {
        latest = snapshot;
        Instant now = snapshot.queriedAt();
        if (!started) {
            event(snapshot, "MONITOR_STARTED", "เริ่มเฝ้าดู UPS; ช่วงที่มินิคุงหยุดไม่มีข้อมูล", null, true, false);
            started = true;
        }
        if (!snapshot.available()) {
            state.failures = Math.min(2, state.failures + 1);
            state.timingGap = true;
            if (state.failures >= 2 && state.connected) {
                event(snapshot, "UNAVAILABLE", "อ่าน UPS ไม่ได้สองครั้งติดต่อกัน (" + snapshot.error()
                        + "); ยังยืนยันโหมดไฟหรือระดับแบตไม่ได้", null, true, true);
                state.connected = false;
            }
        } else {
            if (!state.connected) event(snapshot, "COMM_RECOVERED", "กลับมาอ่านข้อมูล UPS ได้แล้ว", null, true, true);
            state.connected = true;
            state.failures = 0;
            Set<String> flags = new HashSet<>(Arrays.asList(snapshot.variables().get("ups.status").split("\\s+")));
            String mode = flags.contains("OB") ? "OB" : flags.contains("OL") ? "OL" : "UNKNOWN";
            if (mode.equals("OB") && state.onBatterySince == null) {
                state.timingGap |= state.mode.isEmpty() || state.mode.equals("UNKNOWN");
                event(snapshot, "ONBATT", "พบ UPS กำลังใช้แบตเตอรี่; เริ่มจับเวลาจากครั้งที่ตรวจพบนี้", null, state.timingGap, true);
                state.onBatterySince = now;
            } else if (mode.equals("OL") && state.onBatterySince != null) {
                long seconds = Math.max(0, Duration.between(state.onBatterySince, now).getSeconds());
                event(snapshot, "ONLINE", "UPS กลับมาใช้ไฟบ้านแล้ว; ห่างจากครั้งแรกที่พบใช้แบตประมาณ " + seconds
                        + " วินาที" + (state.timingGap ? " (มีช่วงไม่มีข้อมูล จึงยืนยันระยะเวลาต่อเนื่องไม่ได้)" : ""),
                        seconds, state.timingGap, true);
                state.onBatterySince = null;
                state.timingGap = false;
            } else if (mode.equals("UNKNOWN")) state.timingGap = true;
            else if (mode.equals("OL")) state.timingGap = false;
            if (flags.contains("LB") && !state.lowBattery)
                event(snapshot, "LOWBATT", "UPS แจ้งแบตเตอรี่ต่ำ; ยังไม่ทราบเวลาสำรองที่เหลือ", null, state.timingGap, true);
            if (!flags.contains("LB") && state.lowBattery)
                event(snapshot, "LOWBATT_CLEARED", "UPS ไม่รายงานสถานะแบตต่ำแล้ว", null, state.timingGap, false);
            state.lowBattery = flags.contains("LB");
            state.mode = mode;
        }
        if (state.lastSample == null || !now.isBefore(state.lastSample.plusSeconds(60))) {
            event(snapshot, "SAMPLE", "ค่าที่อ่านจาก NUT; battery.charge อาจเป็นค่าประเมิน", null, state.timingGap, false);
            state.lastSample = now;
        }
        history.save(state);
        storageError = "";
    }

    private void event(NutUpsClient.Snapshot snapshot, String type, String message, Long seconds,
            boolean gap, boolean notify) throws IOException {
        var entry = new UpsHistory.Entry(UUID.randomUUID().toString(), snapshot.queriedAt(), type, message,
                snapshot.available(), snapshot.error(), snapshot.variables(), seconds, gap);
        history.append(entry);
        if (notify) state.pending.add(entry);
        LOG.info("process=ups_monitor event={} available={} status={}", type, snapshot.available(),
                snapshot.variables().getOrDefault("ups.status", "UNKNOWN"));
    }

    public void deliverPending() {
        UpsHistory.Entry entry;
        synchronized (this) {
            if (state.pending.isEmpty() || clock.instant().isBefore(nextDelivery)) return;
            entry = state.pending.getFirst();
        }
        try {
            notifications.publish(new NotificationRequest("UPS", entry.id(), NotificationChannel.REMINDER,
                    "Mini-kun UPS: " + entry.type(), "เหตุการณ์ UPS เมื่อ "
                    + entry.at().atZone(ZoneId.of("Asia/Bangkok")) + "\n" + entry.message(),
                    entry.type().equals("LOWBATT") ? 5 : entry.type().equals("ONBATT") || entry.type().equals("UNAVAILABLE") ? 4 : 3,
                    "electric_plug,battery"));
            synchronized (this) {
                state.pending.removeIf(pending -> pending.id().equals(entry.id()));
                history.save(state);
                deliveryError = "";
            }
        } catch (Exception exception) {
            synchronized (this) {
                nextDelivery = clock.instant().plusSeconds(30);
                deliveryError = "UPS_NOTIFICATION_DELIVERY_FAILED";
            }
            LOG.warn("process=ups_monitor event=notification_failed event_id={} reason={}", entry.id(), exception.toString());
        }
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("lastCheck", latest == null ? "" : latest.queriedAt());
        result.put("available", latest != null && latest.available());
        result.put("pendingNotifications", state.pending.size());
        result.put("deliveryError", deliveryError);
        result.put("storageError", storageError);
        result.put("onBatterySince", state.onBatterySince == null ? "" : state.onBatterySince);
        result.put("timingGap", state.timingGap);
        return result;
    }

    public List<UpsHistory.Entry> recent(int limit, boolean samples) throws IOException { return history.recent(limit, samples); }

    @PreDestroy
    public void close() throws IOException, InterruptedException {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        }
        synchronized (this) { history.save(state); }
    }
}
