package com.minikun.agent.minikun_agent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;

import org.springframework.boot.SpringApplication;

/** Selects the existing database-offline profile before Spring creates JDBC beans. */
final class DatabaseStartup {
    private static final String OFFLINE_PROFILE = "database-offline";

    private DatabaseStartup() {
    }

    static void enableOfflineProfileWhenUnavailable(SpringApplication application, String[] args) {
        if (offlineAlreadySelected(application, args)
                || !Boolean.parseBoolean(setting(args, "minikun.database.auto-degrade",
                        "MINIKUN_DATABASE_AUTO_DEGRADE", "true"))) {
            return;
        }

        String url = setting(args, "spring.datasource.url", "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://127.0.0.1:5432/minikun");
        String username = setting(args, "spring.datasource.username", "SPRING_DATASOURCE_USERNAME", "minikun");
        String password = setting(args, "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD", "");
        int timeoutSeconds = Integer.parseInt(setting(args, "minikun.database.probe-timeout-seconds",
                "MINIKUN_DATABASE_PROBE_TIMEOUT_SECONDS", "2"));

        if (!canConnect(url, username, password, timeoutSeconds)) {
            application.setAdditionalProfiles(OFFLINE_PROFILE);
            System.err.println("Minikun: PostgreSQL unavailable; starting automatically in database-offline mode.");
        }
    }

    static boolean canConnect(String url, String username, String password, int timeoutSeconds) {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", Integer.toString(Math.max(1, timeoutSeconds)));
        try (Connection ignored = DriverManager.getConnection(url, properties)) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private static boolean offlineAlreadySelected(SpringApplication application, String[] args) {
        if (application.getAdditionalProfiles().contains(OFFLINE_PROFILE)) return true;
        String profiles = setting(args, "spring.profiles.active", "SPRING_PROFILES_ACTIVE", "");
        return Arrays.stream(profiles.split(","))
                .map(String::trim)
                .anyMatch(OFFLINE_PROFILE::equals);
    }

    private static String setting(String[] args, String property, String environment, String fallback) {
        String prefix = "--" + property + "=";
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith(prefix))
                .map(argument -> argument.substring(prefix.length()))
                .findFirst()
                .orElseGet(() -> System.getProperty(property,
                        System.getenv().getOrDefault(environment, fallback)));
    }
}
