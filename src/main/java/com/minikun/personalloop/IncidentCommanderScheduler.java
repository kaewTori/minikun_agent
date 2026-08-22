package com.minikun.personalloop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@ConditionalOnProperty(name = "minikun.personal-loop.incident.enabled", havingValue = "true", matchIfMissing = true)
public final class IncidentCommanderScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(IncidentCommanderScheduler.class);
    private final IncidentCommanderService service;
    private final String ownerId;

    public IncidentCommanderScheduler(IncidentCommanderService service,
            @Value("${minikun.personal-loop.owner-id:default}") String ownerId) {
        this.service = service; this.ownerId = ownerId;
    }

    @Scheduled(fixedDelayString = "${minikun.personal-loop.incident.poll-interval-ms:60000}")
    public void inspect() {
        try { service.inspect(ownerId); }
        catch (RuntimeException exception) { LOG.warn("process=incident_commander event=inspection_failed reason={}", exception.getMessage()); }
    }
}
