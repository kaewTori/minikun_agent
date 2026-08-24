package com.minikun.systemhealth;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SystemHealthConfiguration {
    @Bean
    SystemHealthReader systemHealthReader(
            ObjectMapper objectMapper,
            @Value("${minikun.system.health.disk-path:/Volumes/minikun}") String diskPath,
            @Value("${minikun.system.health.dependencies:application=127.0.0.1:8080,postgres=127.0.0.1:5432,redis=127.0.0.1:6379,ollama=127.0.0.1:11434,searxng=127.0.0.1:8888,browser=127.0.0.1:3001}") String dependencies,
            @Value("${minikun.system.health.connect-timeout:500ms}") Duration connectTimeout,
            @Value("${minikun.system.health.memory-warning-percent:85}") double memoryWarningPercent,
            @Value("${minikun.system.health.disk-warning-percent:90}") double diskWarningPercent,
            @Value("${minikun.model.tinygrad.base-url:http://127.0.0.1:8001/v1}") String tinyGradBaseUrl,
            @Value("${minikun.model.tinygrad.health.timeout:PT2S}") Duration tinyGradHealthTimeout) {
        HttpClient allocatorHttpClient = HttpClient.newBuilder()
                .connectTimeout(tinyGradHealthTimeout)
                .build();
        return new DefaultSystemHealthReader(
                Path.of(diskPath),
                SystemHealthDependency.parseList(dependencies),
                connectTimeout,
                memoryWarningPercent,
                diskWarningPercent,
                new HttpNvAllocatorMemoryProbe(
                        allocatorHttpClient, objectMapper, tinyGradBaseUrl, tinyGradHealthTimeout));
    }
}
