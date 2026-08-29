package com.minikun.knowledge.acquisition;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls a bounded number of due topics; overlapping acquisition batches are skipped. */
@Slf4j
final class KnowledgeAcquisitionScheduler {
    private final KnowledgeAcquisitionService service;
    private final String ownerId;
    private final int topicsPerRun;
    private final AtomicBoolean running = new AtomicBoolean();

    KnowledgeAcquisitionScheduler(KnowledgeAcquisitionService service, String ownerId, int topicsPerRun) {
        this.service = Objects.requireNonNull(service);
        this.ownerId = Objects.requireNonNullElse(ownerId, "default").strip();
        if (this.ownerId.isBlank() || topicsPerRun < 1 || topicsPerRun > 10) {
            throw new IllegalArgumentException("invalid knowledge acquisition scheduler configuration");
        }
        this.topicsPerRun = topicsPerRun;
    }

    @Scheduled(initialDelayString = "${minikun.knowledge-acquisition.scheduler.initial-delay-ms:180000}",
            fixedDelayString = "${minikun.knowledge-acquisition.scheduler.poll-interval-ms:300000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) return;
        try {
            int expired = service.expireStaleClaims();
            var runs = service.runDue(ownerId, topicsPerRun);
            if (expired > 0 || !runs.isEmpty()) {
                log.info("process=knowledge_acquisition_scheduler event=completed runs={} expired={}",
                        runs.size(), expired);
            }
        } catch (RuntimeException exception) {
            log.warn("process=knowledge_acquisition_scheduler event=failed reason={}", exception.getMessage());
        } finally {
            running.set(false);
        }
    }
}
