package com.minikun.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

class DeviceLocationJsonTest {
    @Test
    void readsStructuredSnakeCaseLocationAndCanRedactIt() throws Exception {
        ChatCompletionRequest request = new ObjectMapper().readValue("""
                {"model":"mini-kun","messages":[{"role":"user","content":"ร้านอาหารแถวนี้"}],
                "device_location":{"latitude":13.732639,"longitude":100.529052,"accuracy":5,
                "captured_at":1789430400000}}
                """, ChatCompletionRequest.class);

        assertEquals(13.732639, request.deviceLocation().latitude());
        assertEquals(1789430400000L, request.deviceLocation().capturedAt());
        assertFalse(new ObjectMapper().writeValueAsString(request.withoutDeviceLocation())
                .contains("device_location"));
    }
}
