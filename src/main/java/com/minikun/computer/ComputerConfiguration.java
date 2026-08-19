package com.minikun.computer;

import com.minikun.guardian.GuardianCommandRunner;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.computer.enabled", havingValue = "true", matchIfMissing = true)
public class ComputerConfiguration {
    @Bean
    ComputerAuditStore computerAuditStore(ObjectProvider<JdbcTemplate> jdbc) {
        return new ResilientComputerAuditStore(jdbc);
    }

    @Bean
    ComputerCommandGateway computerCommandGateway(
            GuardianCommandRunner runner,
            @Value("${minikun.computer.command-timeout:15s}") Duration timeout,
            @Value("${minikun.computer.workflows:}") String workflows,
            @Value("${minikun.computer.applications:Finder,Safari,TextEdit,Preview}") String applications) {
        Set<String> allowedApplications = Arrays.stream(applications.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
        return new ComputerCommandGateway(runner, timeout,
                ComputerWorkflowDefinition.parseList(workflows), allowedApplications);
    }

    @Bean
    LocalComputerService localComputerService(
            ComputerAuditStore audit,
            ComputerCommandGateway commands,
            Clock clock,
            @Value("${minikun.computer.roots:}") String roots,
            @Value("${minikun.computer.max-read-bytes:65536}") int maxReadBytes,
            @Value("${minikun.computer.max-write-bytes:65536}") int maxWriteBytes,
            @Value("${minikun.computer.max-depth:6}") int maxDepth,
            @Value("${minikun.computer.max-scanned-files:1000}") int maxScannedFiles) {
        return new LocalComputerService(ComputerRoot.parseList(roots), audit, commands, clock,
                maxReadBytes, maxWriteBytes, maxDepth, maxScannedFiles);
    }
}
