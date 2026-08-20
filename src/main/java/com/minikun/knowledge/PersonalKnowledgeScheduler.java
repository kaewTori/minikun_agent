package com.minikun.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@ConditionalOnProperty(name = "minikun.personal-knowledge.monitor.enabled",
        havingValue = "true", matchIfMissing = true)
public final class PersonalKnowledgeScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(PersonalKnowledgeScheduler.class);
    private final PersonalKnowledgeService knowledge;

    @Value("${minikun.personal-knowledge.owner-id:default}")
    private String ownerId;

    public PersonalKnowledgeScheduler(PersonalKnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Scheduled(initialDelayString = "${minikun.personal-knowledge.monitor.initial-delay-ms:120000}",
            fixedDelayString = "${minikun.personal-knowledge.monitor.poll-interval-ms:300000}")
    public void refresh() {
        try {
            KnowledgeIndexReport report = knowledge.reindex(ownerId, false);
            if (report.indexed() > 0 || report.failed() > 0) {
                LOG.info("process=personal_knowledge event=monitor_completed indexed={} unchanged={} failed={}",
                        report.indexed(), report.unchanged(), report.failed());
            }
        } catch (RuntimeException exception) {
            LOG.warn("process=personal_knowledge event=monitor_failed");
        }
    }
}
