package com.minikun.ups;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import com.minikun.notification.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public final class UpsStatusController {
    private final NutUpsClient client;
    private final ObjectProvider<UpsMonitor> monitor;
    private final ObjectProvider<NotificationDispatcher> notifications;
    @Value("${minikun.system.health.management-token:${minikun.memory.management.token:}}")
    private String managementToken;

    public UpsStatusController(NutUpsClient client, ObjectProvider<UpsMonitor> monitor, ObjectProvider<NotificationDispatcher> notifications) {
        this.client = client;
        this.monitor = monitor;
        this.notifications = notifications;
    }

    @GetMapping("/v1/ups/status")
    public NutUpsClient.Snapshot status(@RequestHeader(value = "X-Minikun-System-Token", required = false) String token) {
        authorize(token);
        return client.read();
    }

    @GetMapping("/v1/ups/history")
    public List<UpsHistory.Entry> history(@RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean samples,
            @RequestHeader(value = "X-Minikun-System-Token", required = false) String token) throws IOException {
        authorize(token);
        if (limit < 1 || limit > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 500");
        return enabledMonitor().recent(limit, samples);
    }

    @GetMapping("/v1/ups/monitor")
    public Map<String, Object> monitor(@RequestHeader(value = "X-Minikun-System-Token", required = false) String token) {
        authorize(token);
        UpsMonitor value = monitor.getIfAvailable();
        return value == null ? Map.of("enabled", false) : value.status();
    }

    @PostMapping("/v1/ups/notifications/test")
    public Map<String, Object> testNotification(@RequestHeader(value = "X-Minikun-System-Token", required = false) String token) {
        authorize(token);
        enabledMonitor();
        var dispatcher = notifications.getIfAvailable();
        if (dispatcher == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "notification service unavailable");
        dispatcher.publish(new NotificationRequest("UPS_TEST", java.util.UUID.randomUUID().toString(), NotificationChannel.REMINDER,
                "Mini-kun UPS notification test", "ทดสอบการแจ้งเตือน UPS ของมินิคุงเท่านั้น ไม่ใช่เหตุไฟดับจริง", 3, "electric_plug"));
        return Map.of("accepted", true, "simulation", true);
    }

    private UpsMonitor enabledMonitor() {
        UpsMonitor value = monitor.getIfAvailable();
        if (value == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "UPS monitor disabled");
        return value;
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "system health management token is invalid");
    }
}
