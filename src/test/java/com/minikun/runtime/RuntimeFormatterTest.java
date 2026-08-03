package com.minikun.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RuntimeFormatterTest {
    @Test
    void rendersAllRuntimeStatesDeterministically() {
        VersionFormatter formatter = new VersionFormatter();
        String output = formatter.format(new VersionInfo(
                RuntimeValue.configured("1.0"),
                RuntimeValue.notConfigured(),
                RuntimeValue.unavailable(),
                RuntimeValue.configured("25"),
                RuntimeValue.configured("4.1.0")));

        assertEquals("Version\n"
                + "Application: 1.0\n"
                + "Build: Not configured\n"
                + "Revision: Unavailable\n"
                + "Java: 25\n"
                + "Spring Boot: 4.1.0\n", output);
    }

    @Test
    void keepsModelsFieldOrderStable() {
        String output = new ModelsFormatter().format(new ModelsInfo(
                RuntimeValue.configured("chat"), RuntimeValue.notConfigured(),
                RuntimeValue.configured("memory"), RuntimeValue.unavailable(),
                RuntimeValue.notConfigured()));

        assertEquals("Models\n"
                + "Chat: chat\n"
                + "Embedding: Not configured\n"
                + "Memory: memory\n"
                + "Memory llama.cpp: Unavailable\n"
                + "Search decision: Not configured\n", output);
    }

    @Test
    void rendersCacheConfigurationWithoutAccessingBackend() {
        String output = new CacheFormatter().format(new CacheInfo(
                RuntimeValue.configured("true"),
                RuntimeValue.configured("valkey"),
                RuntimeValue.configured(Duration.ofMinutes(5).toString())));

        assertEquals("Cache\n"
                + "Enabled: true\n"
                + "Backend: valkey\n"
                + "TTL: PT5M\n", output);
    }
}
