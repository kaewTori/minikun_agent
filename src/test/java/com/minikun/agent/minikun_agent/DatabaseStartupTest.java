package com.minikun.agent.minikun_agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class DatabaseStartupTest {

    @Test
    void selectsOfflineProfileWhenDatabaseIsUnavailable() {
        SpringApplication application = new SpringApplication(MinikunAgentApplication.class);

        DatabaseStartup.enableOfflineProfileWhenUnavailable(application,
                new String[] { "--spring.datasource.url=jdbc:missing:minikun" });

        assertThat(application.getAdditionalProfiles()).contains("database-offline");
    }

    @Test
    void allowsAutomaticFallbackToBeDisabled() {
        SpringApplication application = new SpringApplication(MinikunAgentApplication.class);

        DatabaseStartup.enableOfflineProfileWhenUnavailable(application, new String[] {
                "--spring.datasource.url=jdbc:missing:minikun",
                "--minikun.database.auto-degrade=false"
        });

        assertThat(application.getAdditionalProfiles()).doesNotContain("database-offline");
    }
}
