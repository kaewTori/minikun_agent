package com.minikun.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

class RuntimeServiceTest {
    @Test
    void modelsSnapshotIsImmutableAndConfigurationOnly() {
        ModelsService service = new ModelsService("chat", "embedding", "memory", "");

        ModelsInfo first = service.snapshot();
        ModelsInfo second = service.snapshot();

        assertEquals(first, second);
        assertEquals(RuntimeValueState.NOT_CONFIGURED, first.searchDecisionModel().state());
    }

    @Test
    void cacheSnapshotUsesConfiguredValuesOnly() {
        CacheInfo snapshot = new CacheService("false", "valkey", Duration.ofMinutes(5)).snapshot();

        assertEquals(RuntimeValueState.CONFIGURED, snapshot.enabled().state());
        assertEquals("valkey", snapshot.backend().value());
        assertEquals("PT5M", snapshot.ttl().value());
    }

    @Test
    void versionSnapshotAllowsMissingBuildMetadata() {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        VersionInfo snapshot = new VersionService(provider, "").snapshot();

        assertEquals(RuntimeValueState.NOT_CONFIGURED, snapshot.applicationVersion().state());
        assertEquals(RuntimeValueState.NOT_CONFIGURED, snapshot.buildVersion().state());
        assertEquals(RuntimeValueState.NOT_CONFIGURED, snapshot.revision().state());
        assertEquals(RuntimeValueState.CONFIGURED, snapshot.javaVersion().state());
    }
}
