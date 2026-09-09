package com.minikun.visual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.CooperationRouter;
import com.minikun.model.task.OllamaTaskModelProvider;
import com.minikun.model.task.TaskModelProvider;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.visual.generation.enabled", havingValue = "true")
public class StoryIllustrationConfiguration {
    @Bean
    StoryIllustrationProvider tinyGradStoryIllustrationProvider(
            ObjectMapper objectMapper,
            @Value("${minikun.visual.generation.tinygrad.base-url:http://127.0.0.1:8002}") String baseUrl,
            @Value("${minikun.visual.generation.tinygrad.token:}") String token,
            @Value("${minikun.visual.generation.tinygrad.model:mala-anime-mix-nsfw-ponyxl}") String model,
            @Value("${minikun.visual.generation.tinygrad.negative-prompt:}") String negativePrompt,
            @Value("${minikun.visual.generation.tinygrad.width:768}") int width,
            @Value("${minikun.visual.generation.tinygrad.height:1280}") int height,
            @Value("${minikun.visual.generation.tinygrad.steps:40}") int steps,
            @Value("${minikun.visual.generation.tinygrad.guidance:6.0}") double guidance,
            @Value("${minikun.visual.generation.tinygrad.scheduler:dpmpp2m}") String scheduler,
            @Value("${minikun.visual.generation.tinygrad.schedule:karras}") String schedule,
            @Value("${minikun.visual.generation.tinygrad.timeout:PT10M}") Duration timeout,
            @Value("${minikun.visual.generation.max-image-bytes:15728640}") int maximumImageBytes,
            TinyGradRuntimeAccessCoordinator runtimeAccess) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(requestFactory);
        if (token != null && !token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.strip());
        }
        return new TinyGradImageGenerationProvider(
                builder.build(), objectMapper, stripTrailingSlash(baseUrl), model, negativePrompt,
                width, height, steps, guidance,
                scheduler, schedule, maximumImageBytes, runtimeAccess);
    }

    @Bean
    GeneratedImageStore generatedImageStore(
            @Value("${minikun.visual.generation.output-directory:${user.home}/.minikun/generated-images}") Path root,
            @Value("${minikun.visual.generation.max-image-bytes:15728640}") int maximumImageBytes,
            Clock clock) {
        return new GeneratedImageStore(root, maximumImageBytes, clock);
    }

    @Bean
    ImageGenerationHistoryStore imageGenerationHistoryStore(ObjectProvider<JdbcTemplate> jdbcTemplate) {
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        return jdbc == null ? new InMemoryImageGenerationHistoryStore()
                : new JdbcImageGenerationHistoryStore(jdbc);
    }

    @Bean
    ImageGenerationTool imageGenerationTool(
            StoryIllustrationProvider provider,
            GeneratedImageStore store,
            ImageGenerationHistoryStore historyStore,
            @Value("${minikun.visual.generation.max-prompt-characters:8000}") int maximumPromptCharacters,
            @Value("${minikun.visual.generation.max-pixels:4194304}") long maximumPixels) {
        return new ImageGenerationTool(provider, store, historyStore, maximumPromptCharacters, maximumPixels);
    }

    @Bean
    StoryIllustrationIntentDetector storyIllustrationIntentDetector(
            ObjectProvider<CooperationRouter> cooperationRouter) {
        CooperationRouter router = cooperationRouter.getIfAvailable();
        return router == null ? new StoryIllustrationIntentDetector()
                : new StoryIllustrationIntentDetector(router);
    }

    @Bean
    StoryVisualPlanGenerator storyVisualPlanGenerator(
            ObjectMapper objectMapper,
            @Value("${minikun.visual.generation.pony-prompt.ollama.base-url:http://127.0.0.1:11434}")
                    String baseUrl,
            @Value("${minikun.visual.generation.pony-prompt.ollama.model:hf.co/llmfan46/gemma-4-E2B-it-ultra-uncensored-heretic-GGUF:Q4_K_M}")
                    String model,
            @Value("${minikun.visual.generation.pony-prompt.ollama.timeout:PT120S}") Duration timeout) {
        return new TaskModelStoryVisualPlanGenerator(
                visualPonyTaskModelProvider(objectMapper, baseUrl, model, timeout), objectMapper);
    }

    @Bean
    PonyPromptTransformer ponyPromptTransformer(
            ObjectMapper objectMapper,
            @Value("${minikun.visual.generation.pony-prompt.ollama.base-url:http://127.0.0.1:11434}")
                    String baseUrl,
            @Value("${minikun.visual.generation.pony-prompt.ollama.model:hf.co/llmfan46/gemma-4-E2B-it-ultra-uncensored-heretic-GGUF:Q4_K_M}")
                    String model,
            @Value("${minikun.visual.generation.pony-prompt.ollama.timeout:PT120S}") Duration timeout) {
        return new MainModelPonyPromptTransformer(
                visualPonyTaskModelProvider(objectMapper, baseUrl, model, timeout), objectMapper);
    }

    @Bean
    CharacterVisualMemory characterVisualMemory(
            ObjectProvider<JdbcTemplate> jdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        JdbcTemplate jdbc = jdbcTemplate.getIfAvailable();
        return jdbc == null ? new InMemoryCharacterVisualMemory()
                : new JdbcCharacterVisualMemory(jdbc, objectMapper, clock);
    }

    @Bean
    StoryIllustrationService storyIllustrationService(
            ImageGenerationTool imageGenerationTool,
            StoryVisualPlanGenerator visualPlanGenerator,
            CharacterVisualMemory characterVisualMemory,
            PonyPromptTransformer promptTransformer,
            StoryIllustrationIntentDetector intentDetector,
            @Value("${minikun.visual.generation.auto-illustrate-stories:true}") boolean autoIllustrateStories,
            @Value("${minikun.visual.generation.max-prompt-characters:8000}") int maximumPromptCharacters,
            @Value("${minikun.visual.generation.storyboard.max-scenes:3}") int maximumStoryboardScenes) {
        return new StoryIllustrationService(
                imageGenerationTool, autoIllustrateStories, maximumPromptCharacters,
                visualPlanGenerator, characterVisualMemory, maximumStoryboardScenes, promptTransformer,
                intentDetector);
    }

    @Bean
    GeneratedImageController generatedImageController(GeneratedImageStore store) {
        return new GeneratedImageController(store);
    }

    @Bean
    ImageStudioController imageStudioController(
            ImageGenerationTool imageGenerationTool,
            TinyGradRuntimeStatusReader runtimeStatus,
            PonyPromptTransformer promptTransformer,
            @Value("${minikun.visual.management.token:${minikun.memory.management.token:}}") String token,
            @Value("${minikun.visual.generation.max-prompt-characters:8000}") int maximumPromptCharacters) {
        return new ImageStudioController(
                imageGenerationTool, runtimeStatus, promptTransformer, token, maximumPromptCharacters);
    }

    @Bean
    TinyGradRuntimeStatusReader tinyGradRuntimeStatusReader(
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${minikun.visual.generation.tinygrad.base-url:http://127.0.0.1:8002}") String baseUrl,
            @Value("${minikun.visual.generation.tinygrad.health-timeout:PT2S}") Duration timeout,
            TinyGradRuntimeAccessCoordinator runtimeAccess) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        return new TinyGradRuntimeStatusClient(httpClient, objectMapper, clock, baseUrl, timeout, runtimeAccess);
    }

    @Bean
    TinyGradRuntimeAccessCoordinator tinyGradRuntimeAccessCoordinator() {
        return new TinyGradRuntimeAccessCoordinator();
    }

    @Bean
    ImageStudioExceptionHandler imageStudioExceptionHandler() {
        return new ImageStudioExceptionHandler();
    }

    private String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private TaskModelProvider visualPonyTaskModelProvider(
            ObjectMapper objectMapper, String baseUrl, String model, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl) + "/api/chat")
                .requestFactory(requestFactory)
                .build();
        return new OllamaTaskModelProvider(restClient, objectMapper, model, timeout, true);
    }
}
