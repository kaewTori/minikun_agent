package com.minikun.personality.config;

import com.minikun.personality.preference.InMemoryPreferenceStore;
import com.minikun.personality.preference.PreferenceStore;
import com.minikun.personality.preference.JdbcPreferenceStore;
import com.minikun.personality.profile.InMemoryUserProfileStore;
import com.minikun.personality.profile.UserProfileStore;
import com.minikun.personality.profile.JdbcUserProfileStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.minikun.personality.runtime.AdaptivePersonaRuntime;
import com.minikun.personality.runtime.AdaptivePersonaService;
import com.minikun.personality.learning.AdaptationSignalStore;
import com.minikun.personality.learning.AdaptivePreferenceLearningService;
import com.minikun.personality.learning.InMemoryAdaptationSignalStore;
import com.minikun.personality.learning.JdbcAdaptationSignalStore;
import com.minikun.personality.learning.ResponsePreferenceDetector;
import com.minikun.personality.management.AdaptationController;
import com.minikun.personality.management.AdaptationExceptionHandler;
import com.minikun.personality.management.UserModelController;
import com.minikun.personality.management.PersonaManagementController;
import com.minikun.personality.management.PersonaManagementService;
import com.minikun.personality.profile.UserModelService;
import com.minikun.personality.companion.CompanionModeService;
import com.minikun.personality.companion.CompanionModeStore;
import com.minikun.personality.companion.InMemoryCompanionModeStore;
import com.minikun.personality.companion.JdbcCompanionModeStore;
import com.minikun.personality.companion.CompanionModeController;
import com.minikun.memory.MemoryRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({AdaptivePreferenceLearningService.class,
        AdaptationController.class, AdaptationExceptionHandler.class,
        PersonaManagementService.class, PersonaManagementController.class})
public class PersonalityConfiguration {
    @Bean
    CompanionModeService companionModeService(
            @Value("${minikun.companion-mode.enabled:true}") boolean enabled,
            CompanionModeStore store) {
        return new CompanionModeService(enabled, store);
    }

    @Bean
    CompanionModeStore companionModeStore(ObjectProvider<JdbcTemplate> jdbc,
            @Value("${minikun.companion-mode.maximum-sessions:1000}") int maximumSessions) {
        JdbcTemplate template = jdbc.getIfAvailable();
        return template == null ? new InMemoryCompanionModeStore(maximumSessions) : new JdbcCompanionModeStore(template);
    }

    @Bean CompanionModeController companionModeController(CompanionModeService service) {
        return new CompanionModeController(service);
    }

    @Bean
    AdaptivePersonaRuntime adaptivePersonaRuntime() { return new AdaptivePersonaRuntime(); }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    UserProfileStore userProfileStore() { return new InMemoryUserProfileStore(); }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    PreferenceStore preferenceStore() { return new InMemoryPreferenceStore(); }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    AdaptationSignalStore adaptationSignalStore() { return new InMemoryAdaptationSignalStore(); }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    UserProfileStore jdbcUserProfileStore(JdbcTemplate jdbc) { return new JdbcUserProfileStore(jdbc); }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    PreferenceStore jdbcPreferenceStore(JdbcTemplate jdbc) { return new JdbcPreferenceStore(jdbc); }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    AdaptationSignalStore jdbcAdaptationSignalStore(JdbcTemplate jdbc) {
        return new JdbcAdaptationSignalStore(jdbc);
    }

    @Bean
    ResponsePreferenceDetector responsePreferenceDetector(
            org.springframework.beans.factory.ObjectProvider<com.minikun.model.task.TaskModelProvider> model,
            com.fasterxml.jackson.databind.ObjectMapper json) { return new ResponsePreferenceDetector(model.getIfAvailable(), json); }

    @Bean
    @ConditionalOnBean(MemoryRepository.class)
    UserModelService userModelService(
            MemoryRepository memories,
            UserProfileStore profiles,
            PreferenceStore preferences,
            Clock clock,
            @Value("${minikun.user-model.maximum-memories:20}") int maximumMemories,
            @Value("${minikun.user-model.memory-max-age:365d}") Duration memoryMaxAge,
            @Value("${minikun.user-model.preference-max-age:365d}") Duration preferenceMaxAge,
            @Value("${minikun.user-model.minimum-confidence:0.5}") double minimumConfidence) {
        return new UserModelService(memories, profiles, preferences, clock, maximumMemories,
                memoryMaxAge, preferenceMaxAge, minimumConfidence);
    }

    @Bean
    @ConditionalOnBean(UserModelService.class)
    AdaptivePersonaService adaptivePersonaService(
            AdaptivePersonaRuntime runtime,
            UserModelService userModelService,
            AdaptivePreferenceLearningService learningService) {
        return new AdaptivePersonaService(runtime, userModelService, learningService);
    }

    @Bean
    @ConditionalOnBean(UserModelService.class)
    UserModelController userModelController(UserModelService userModelService) {
        return new UserModelController(userModelService);
    }
}
