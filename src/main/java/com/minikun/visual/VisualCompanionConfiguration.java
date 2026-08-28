package com.minikun.visual;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class VisualCompanionConfiguration {
    @Bean ImageProxyService imageProxyService(
            @Value("${minikun.visual.proxy.connect-timeout:PT5S}") Duration connect,
            @Value("${minikun.visual.proxy.read-timeout:PT15S}") Duration read,
            @Value("${minikun.visual.proxy.cache-ttl:PT6H}") Duration ttl,
            @Value("${minikun.visual.proxy.max-image-bytes:8388608}") int maxBytes,
            @Value("${minikun.visual.proxy.max-cache-entries:128}") int maxEntries,
            Clock clock) {
        return new ImageProxyService(connect, read, ttl, maxBytes, maxEntries, clock);
    }
    @Bean ImageProxyController imageProxyController(ImageProxyService service) {
        return new ImageProxyController(service);
    }
    @Bean InspirationBoardStore inspirationBoardStore(ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate template = jdbc.getIfAvailable();
        return template == null ? new InMemoryInspirationBoardStore() : new JdbcInspirationBoardStore(template);
    }
    @Bean InspirationBoardService inspirationBoardService(InspirationBoardStore store, Clock clock) {
        return new InspirationBoardService(store, clock);
    }
    @Bean InspirationBoardController inspirationBoardController(InspirationBoardService service,
            @Value("${minikun.visual.management.token:${minikun.memory.management.token:}}") String token) {
        return new InspirationBoardController(service, token);
    }
}
