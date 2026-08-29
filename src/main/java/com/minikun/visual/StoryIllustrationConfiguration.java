package com.minikun.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.visual.generation.enabled", havingValue = "true")
public class StoryIllustrationConfiguration {
    @Bean
    StoryIllustrationProvider storyIllustrationProvider(
            ObjectMapper objectMapper,
            @Value("${minikun.visual.generation.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${minikun.visual.generation.api-key:}") String apiKey,
            @Value("${minikun.visual.generation.model:gpt-image-2}") String model,
            @Value("${minikun.visual.generation.size:1536x1024}") String size,
            @Value("${minikun.visual.generation.quality:medium}") String quality,
            @Value("${minikun.visual.generation.timeout:PT120S}") Duration timeout,
            @Value("${minikun.visual.generation.max-image-bytes:15728640}") int maximumImageBytes) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(requestFactory);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.strip());
        }
        return new OpenAiCompatibleStoryIllustrationProvider(
                builder.build(), objectMapper, model, size, quality, maximumImageBytes);
    }

    @Bean
    GeneratedImageStore generatedImageStore(
            @Value("${minikun.visual.generation.output-directory:${user.home}/.minikun/generated-images}") Path root,
            @Value("${minikun.visual.generation.max-image-bytes:15728640}") int maximumImageBytes,
            Clock clock) {
        return new GeneratedImageStore(root, maximumImageBytes, clock);
    }

    @Bean
    StoryIllustrationService storyIllustrationService(
            StoryIllustrationProvider provider,
            GeneratedImageStore store,
            @Value("${minikun.visual.generation.auto-illustrate-stories:true}") boolean autoIllustrateStories,
            @Value("${minikun.visual.generation.max-prompt-characters:8000}") int maximumPromptCharacters) {
        return new StoryIllustrationService(provider, store, autoIllustrateStories, maximumPromptCharacters);
    }

    @Bean
    GeneratedImageController generatedImageController(GeneratedImageStore store) {
        return new GeneratedImageController(store);
    }

    private String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
