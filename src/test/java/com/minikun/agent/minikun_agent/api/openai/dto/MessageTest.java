package com.minikun.agent.minikun_agent.api.openai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MessageTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsAndWritesLegacyStringContent() throws Exception {
        Message message = objectMapper.readValue(
                "{\"role\":\"user\",\"content\":\"hello\"}", Message.class);

        assertEquals("user", message.role());
        assertEquals("hello", message.content());
        assertTrue(message.contentParts().isEmpty());
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(message));
        assertEquals("user", json.path("role").asText());
        assertEquals("hello", json.path("content").asText());
    }

    @Test
    void readsOpenAiTextAndImageContentParts() throws Exception {
        Message message = objectMapper.readValue("""
                {"role":"user","content":[
                  {"type":"text","text":"อ่านข้อความนี้"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo=","detail":"auto"}}
                ]}
                """, Message.class);

        assertEquals("อ่านข้อความนี้", message.content());
        assertEquals(2, message.contentParts().size());
        assertTrue(message.hasImageContent());
        assertEquals("data:image/png;base64,iVBORw0KGgo=",
                message.contentParts().get(1).imageUrl().url());
    }

    @Test
    void readsMultimodalContentWithTheSpringBootJacksonRuntime() throws Exception {
        Message message = new tools.jackson.databind.ObjectMapper().readValue("""
                {"role":"user","content":[
                  {"type":"text","text":"describe"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}
                ]}
                """, Message.class);

        assertEquals("describe", message.content());
        assertTrue(message.hasImageContent());
    }

    @Test
    void rejectsUnknownContentPartType() {
        assertThrows(Exception.class, () -> objectMapper.readValue("""
                {"role":"user","content":[{"type":"input_audio","input_audio":{}}]}
                """, Message.class));
    }
}
