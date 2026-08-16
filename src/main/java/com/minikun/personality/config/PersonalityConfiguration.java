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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PersonalityConfiguration {
    @Bean
    AdaptivePersonaRuntime adaptivePersonaRuntime() { return new AdaptivePersonaRuntime(); }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    UserProfileStore userProfileStore() { return new InMemoryUserProfileStore(); }

    @Bean
    @ConditionalOnMissingBean(JdbcTemplate.class)
    PreferenceStore preferenceStore() { return new InMemoryPreferenceStore(); }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    UserProfileStore jdbcUserProfileStore(JdbcTemplate jdbc) { return new JdbcUserProfileStore(jdbc); }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    PreferenceStore jdbcPreferenceStore(JdbcTemplate jdbc) { return new JdbcPreferenceStore(jdbc); }
}
