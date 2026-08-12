package com.minikun.model.task.title;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.minikun.model.task.TaskModelProvider;

@Configuration(proxyBeanMethods = false)
public class TitleGenerationConfiguration {
    @Bean
    TitlePromptBuilder titlePromptBuilder() {
        return new TitlePromptBuilder();
    }

    @Bean
    TitleGenerationProvider titleGenerationProvider(
            @Value("${minikun.model.title.provider:ollama}") String provider,
            TitlePromptBuilder promptBuilder,
            TaskModelProvider taskModelProvider) {
        if (!"ollama".equalsIgnoreCase(provider)) {
            return messages -> {
                throw new IllegalStateException("title provider is disabled");
            };
        }
        return new OllamaTitleGenerationProvider(taskModelProvider, promptBuilder);
    }
}
