package com.minikun.personalloop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@ConditionalOnProperty(name = "minikun.personal-loop.automation.enabled", havingValue = "true", matchIfMissing = true)
public final class AutomationScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AutomationScheduler.class);
    private final SafeAutomationService service;
    private final String ownerId;

    public AutomationScheduler(SafeAutomationService service,
            @Value("${minikun.personal-loop.owner-id:default}") String ownerId) {
        this.service = service; this.ownerId = ownerId;
    }

    @Scheduled(fixedDelayString = "${minikun.personal-loop.automation.poll-interval-ms:60000}")
    public void evaluate() {
        try { service.evaluate(ownerId); }
        catch (RuntimeException exception) { LOG.warn("process=personal_automation event=evaluation_failed reason={}", exception.getMessage()); }
    }
}
