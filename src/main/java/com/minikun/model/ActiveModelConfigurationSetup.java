package com.minikun.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ActiveModelConfigurationSetup {
    @Bean
    ActiveModelConfiguration activeModelConfiguration(
            @Value("${minikun.model.active:existing}") String activeModel) {
        return ActiveModelConfiguration.parse(activeModel);
    }
}