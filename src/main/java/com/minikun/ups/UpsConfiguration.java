package com.minikun.ups;

import java.time.Duration;
import java.time.Clock;
import java.nio.file.Path;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.notification.NotificationDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.ups.enabled", havingValue = "true", matchIfMissing = true)
@Import(UpsStatusController.class)
public class UpsConfiguration {
    @Bean
    NutUpsClient nutUpsClient(@Value("${minikun.ups.host:127.0.0.1}") String host,
            @Value("${minikun.ups.port:3493}") int port,
            @Value("${minikun.ups.name:cleanline}") String name,
            @Value("${minikun.ups.timeout:2s}") Duration timeout) {
        return new NutUpsClient(host, port, name, timeout);
    }

    @Bean UpsStatusTool upsStatusTool(NutUpsClient client) { return new UpsStatusTool(client); }

    @Bean
    @ConditionalOnProperty(name = "minikun.ups.monitor.enabled", havingValue = "true", matchIfMissing = true)
    UpsHistory upsHistory(ObjectMapper json,
            @Value("${minikun.ups.monitor.directory:${user.home}/.minikun/ups}") String directory) throws IOException {
        return new UpsHistory(Path.of(directory), json);
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.ups.monitor.enabled", havingValue = "true", matchIfMissing = true)
    UpsMonitor upsMonitor(NutUpsClient client, NotificationDispatcher notifications, UpsHistory history, Clock clock) throws IOException {
        return new UpsMonitor(client::read, notifications, history, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "minikun.ups.monitor.enabled", havingValue = "true", matchIfMissing = true)
    UpsHistoryTool upsHistoryTool(UpsMonitor monitor) { return new UpsHistoryTool(monitor); }
}
