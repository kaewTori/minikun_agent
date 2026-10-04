package com.minikun.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.visual.GeneratedImageStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.presentation.enabled", havingValue = "true", matchIfMissing = true)
public class PresentationConfiguration {
    @Bean
    PresentationStore presentationStore(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${minikun.presentation.output-directory:${user.home}/.minikun/presentations}") Path root,
            @Value("${minikun.presentation.max-file-bytes:20971520}") int maximumBytes,
            @Value("${minikun.presentation.retention:P30D}") Duration retention) {
        return new PresentationStore(root, maximumBytes, retention, objectMapper, clock);
    }

    @Bean
    PresentationService presentationService(
            ObjectMapper objectMapper,
            ObjectProvider<GeneratedImageStore> generatedImages,
            PresentationStore store) {
        return new PresentationService(objectMapper, generatedImages.getIfAvailable(), store);
    }

    @Bean
    PresentationTool presentationTool(PresentationService service) {
        return new PresentationTool(service);
    }

    @Bean
    PresentationReadTool presentationReadTool(PresentationService service) {
        return new PresentationReadTool(service);
    }

    @Bean
    PresentationRevisionTool presentationRevisionTool(PresentationService service) {
        return new PresentationRevisionTool(service);
    }

}
