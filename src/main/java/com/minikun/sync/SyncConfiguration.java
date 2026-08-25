package com.minikun.sync;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.sync.enabled", havingValue = "true", matchIfMissing = true)
@Import({SyncController.class, SyncExceptionHandler.class})
public class SyncConfiguration {
    @Bean
    PairedDeviceRepository pairedDeviceRepository(ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate available = jdbc.getIfAvailable();
        return available == null ? new InMemoryPairedDeviceRepository() : new JdbcPairedDeviceRepository(available);
    }

    @Bean
    ChatSyncRepository chatSyncRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper objectMapper) {
        JdbcTemplate available = jdbc.getIfAvailable();
        return available == null ? new InMemoryChatSyncRepository() : new JdbcChatSyncRepository(available, objectMapper);
    }

    @Bean
    SyncEventBroker syncEventBroker() {
        return new SyncEventBroker();
    }

    @Bean
    DevicePairingService devicePairingService(PairedDeviceRepository devices, Clock clock,
            @Value("${minikun.sync.owner-id:default}") String ownerId,
            @Value("${minikun.sync.canonical-origin:https://mini-kun:8443}") String canonicalOrigin,
            @Value("${minikun.sync.pairing-ttl:PT2M}") Duration pairingTtl,
            @Value("${minikun.sync.session-ttl:P180D}") Duration sessionTtl) {
        return new DevicePairingService(devices, clock, new SecureRandom(), ownerId,
                canonicalOrigin, pairingTtl, sessionTtl);
    }

    @Bean
    ChatSyncService chatSyncService(ChatSyncRepository repository, SyncEventBroker events, Clock clock) {
        return new ChatSyncService(repository, events, clock);
    }
}
