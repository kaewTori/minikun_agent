package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.weather.DeviceLocation;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class BackgroundChatStoreTest {
    @Test
    void keepsBackgroundJobsUsableWhenDatabaseFailsAfterStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(jdbc).update(anyString(), any(Object[].class));
        BackgroundChatStore store = new BackgroundChatStore(jdbc, new ObjectMapper());
        UUID id = UUID.randomUUID();
        ChatCompletionRequest request = new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ช่วยทำงานนี้")),
                "offline-conversation", false, null, null, null);

        store.create(id, request, "offline-conversation");
        store.update(id, "COMPLETED", null, "");

        BackgroundChatStore.SavedJob job = store.find(id).orElseThrow();
        assertEquals("COMPLETED", job.status());
        assertEquals(request, job.request());
    }

    @Test
    void removesDeviceCoordinatesFromDurableBackgroundRequestJson() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BackgroundChatStore store = new BackgroundChatStore(jdbc, new ObjectMapper());
        ChatCompletionRequest request = new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", "ร้านอาหารแถวนี้")),
                "location-conversation", false, null, null, null, null, "owner", null, null,
                new DeviceLocation(13.732639, 100.529052, 5.0, System.currentTimeMillis()));

        store.create(UUID.randomUUID(), request, "location-conversation");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        String requestJson = (String) arguments.getValue()[2];
        org.junit.jupiter.api.Assertions.assertFalse(requestJson.contains("device_location"));
        org.junit.jupiter.api.Assertions.assertFalse(requestJson.contains("13.732639"));
    }
}
