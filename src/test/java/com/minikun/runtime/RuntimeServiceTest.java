package com.minikun.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
    void switchesOnlyToAnInstalledOllamaModel() {
        ModelsService service = new ModelsService("old", "embedding", "memory", "");

        ModelsService.Catalog catalog = service.activate("new:latest", List.of("old", "new:latest"));

        assertEquals("new:latest", catalog.active());
        assertEquals("new:latest", service.chatModel());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.activate("missing", List.of("old", "new:latest")));
    }

    @Test
    void readsInstalledModelsFromOllamaTags() {
        RestClient.Builder client = RestClient.builder().baseUrl("http://ollama");
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("http://ollama/api/tags")).andRespond(withSuccess(
                "{\"models\":[{\"name\":\"qwen3:8b\"},{\"name\":\"gemma3:4b\"}]}",
                MediaType.APPLICATION_JSON));
        ModelsService service = new ModelsService("qwen3:8b", "embedding", "memory", "", client.build());

        assertEquals(List.of("qwen3:8b", "gemma3:4b"), service.catalog().models());
        server.verify();
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
