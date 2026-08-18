package com.minikun.systemhealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class SystemHealthDependencyTest {
    @Test
    void parsesConfiguredDependencyAllowlist() {
        List<SystemHealthDependency> dependencies = SystemHealthDependency.parseList(
                "postgres=127.0.0.1:5432,redis=[::1]:6379");

        assertEquals(List.of(
                new SystemHealthDependency("postgres", "127.0.0.1", 5432),
                new SystemHealthDependency("redis", "::1", 6379)), dependencies);
    }

    @Test
    void rejectsUnstructuredDependency() {
        assertThrows(IllegalArgumentException.class,
                () -> SystemHealthDependency.parseList("postgres"));
    }
}
