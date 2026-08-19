package com.minikun.guardian;

import com.minikun.systemhealth.SystemHealthReader;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class GuardianConfiguration {
    @Bean
    GuardianLogReader guardianLogReader(
            @Value("${minikun.guardian.logs.sources:application=logs/application.log}") String sources) {
        return new GuardianLogReader(GuardianLogSource.parseList(sources));
    }

    @Bean
    GuardianBackupChecker guardianBackupChecker(
            @Value("${minikun.guardian.backups.targets:}") String targets,
            @Value("${minikun.guardian.backups.maximum-age:36h}") Duration maximumAge,
            Clock clock) {
        return new GuardianBackupChecker(GuardianBackupTarget.parseList(targets), maximumAge, clock);
    }

    @Bean
    HomelabGuardianService homelabGuardianService(
            SystemHealthReader health,
            GuardianLogReader logs,
            GuardianBackupChecker backups,
            Clock clock) {
        return new HomelabGuardianService(health, logs, backups, clock);
    }

    @Bean
    GuardianAuditStore guardianAuditStore(ObjectProvider<JdbcTemplate> jdbc) {
        return new ResilientGuardianAuditStore(jdbc);
    }

    @Bean
    GuardianAlertStateStore guardianAlertStateStore(ObjectProvider<JdbcTemplate> jdbc) {
        return new GuardianAlertStateStore(jdbc);
    }

    @Bean
    GuardianCommandRunner guardianCommandRunner() {
        return new ProcessGuardianCommandRunner();
    }

    @Bean
    GuardianActionService guardianActionService(
            @Value("${minikun.guardian.actions:}") String actions,
            @Value("${minikun.guardian.action-timeout:30s}") Duration timeout,
            GuardianCommandRunner runner,
            GuardianAuditStore audit,
            Clock clock) {
        return new GuardianActionService(GuardianActionDefinition.parseList(actions), runner, audit, clock, timeout);
    }
}
